package org.example.betty.common.config;

import com.nimbusds.jwt.JWTClaimsSet;
import io.jsonwebtoken.Claims;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.example.betty.common.util.SessionUtil;
import org.example.betty.common.util.Web3AuthUtil;
import org.example.betty.domain.auth.service.AuthServiceImpl;
import org.example.betty.domain.auth.service.JWTService;
import org.example.betty.domain.auth.service.Web3AuthService;
import org.example.betty.domain.display.repository.DisplayAccessRepository;
import org.example.betty.domain.display.controller.DisplaySocketController;
import org.example.betty.domain.display.dto.PixelUpdateMessage;
import org.example.betty.domain.display.service.DisplayService;
import org.example.betty.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionLifecycleTest {
    private final Map<String, Object> storedSessions = new HashMap<>();
    private JWTService jwtService;
    private SessionUtil sessionUtil;
    private AuthServiceImpl authService;
    private String wallet;

    @BeforeEach
    void setUp() throws Exception {
        jwtService = new JWTService();
        ReflectionTestUtils.setField(jwtService, "secret", "test-only-signing-material-not-used-outside-this-test");
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> storedSessions.get(call.getArgument(0)));
        doAnswer(call -> {
            storedSessions.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), any(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(call -> storedSessions.remove(call.getArgument(0)) != null);
        when(redis.hasKey(anyString())).thenAnswer(call -> storedSessions.containsKey(call.getArgument(0)));
        sessionUtil = new SessionUtil(jwtService, redis);
        Web3AuthService web3Auth = mock(Web3AuthService.class);
        String publicKey = HexFormat.of().formatHex(ECNamedCurveTable.getParameterSpec("secp256k1").getG().getEncoded(true));
        wallet = Web3AuthUtil.deriveAddressFromCompressedKey(publicKey);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .expirationTime(new Date(System.currentTimeMillis() + 60000))
                .claim("wallets", List.of(Map.of("curve", "secp256k1", "type", "web3auth_app_key", "public_key", publicKey)))
                .build();
        when(web3Auth.verifyIdToken("test-only-id-token")).thenReturn(claims);
        authService = new AuthServiceImpl(sessionUtil, jwtService, web3Auth);
    }

    @Test
    void logoutThenLoginCannotReviveOldToken() {
        String first = authService.login("test-only-id-token");
        assertEquals(wallet, sessionUtil.getWalletAddress("Bearer " + first));
        authService.logout("Bearer " + first);
        assertThrows(BusinessException.class, () -> sessionUtil.getSession("Bearer " + first));

        String second = authService.login("test-only-id-token");
        assertNotEquals(first, second);
        assertEquals(wallet, sessionUtil.getSession("Bearer " + second));
        assertThrows(BusinessException.class, () -> sessionUtil.getSession("Bearer " + first));
        assertEquals(jwtService.getClaimsFromToken(second).getId(), storedSessions.get(wallet));
        assertNotEquals(second, storedSessions.get(wallet));
    }

    @Test
    void newLoginReplacesPreviousTokenWithoutRequiringLogout() {
        String first = authService.login("test-only-id-token");
        String second = authService.login("test-only-id-token");
        assertEquals(wallet, sessionUtil.getSession("Bearer " + second));
        assertThrows(BusinessException.class, () -> sessionUtil.getSession("Bearer " + first));
    }

    @Test
    void sessionsHaveUniqueIdsEvenWhenIssuedAtClaimsShareASecond() {
        Date expiry = new Date(System.currentTimeMillis() + 60000);
        Set<String> identifiers = new HashSet<>();
        Map<Date, Integer> issuedAtCounts = new HashMap<>();
        for (int n = 0; n < 32; n++) {
            Claims claims = jwtService.getClaimsFromToken(jwtService.generateAccessToken(wallet, expiry));
            assertNotNull(claims.getId());
            assertTrue(identifiers.add(claims.getId()));
            issuedAtCounts.merge(claims.getIssuedAt(), 1, Integer::sum);
        }
        assertTrue(issuedAtCounts.values().stream().anyMatch(count -> count > 1));
    }

    @Test
    void legacyBooleanSessionsFailClosed() {
        String token = jwtService.generateAccessToken(wallet, new Date(System.currentTimeMillis() + 60000));
        storedSessions.put(wallet, Boolean.TRUE);
        assertThrows(BusinessException.class, () -> sessionUtil.getSession("Bearer " + token));
    }

    @Test
    void connectedSessionCannotOutliveJwtExpiryEvenIfRedisStillContainsItsId() {
        String token = authService.login("test-only-id-token");
        SessionUtil.SessionIdentity active = sessionUtil.getSessionIdentity("Bearer " + token);
        SessionUtil.SessionIdentity expired = new SessionUtil.SessionIdentity(active.walletAddress(), active.id(), 0);
        assertThrows(BusinessException.class, () -> sessionUtil.validateSession(expired));
    }

    @Test
    void webSocketHandshakeRejectsOldTokenAfterRelogin() {
        DisplayAccessRepository access = mock(DisplayAccessRepository.class);
        AccessTokenHandshakeInterceptor interceptor = new AccessTokenHandshakeInterceptor(sessionUtil, access);
        String first = authService.login("test-only-id-token");
        assertTrue(handshake(interceptor, first));
        authService.logout("Bearer " + first);
        String second = authService.login("test-only-id-token");
        assertFalse(handshake(interceptor, first));
        assertTrue(handshake(interceptor, second));
        verifyNoInteractions(access);
    }

    @Test
    void connectedDisplaySocketCannotReadOrWriteAfterRelogin() {
        String first = authService.login("test-only-id-token");
        SimpMessageHeaderAccessor headers = displayHeaders(first);
        DisplayService displays = mock(DisplayService.class);
        SimpMessagingTemplate messages = mock(SimpMessagingTemplate.class);
        DisplaySocketController controller = new DisplaySocketController(displays, messages, sessionUtil);
        PixelUpdateMessage pixel = new PixelUpdateMessage(1L, 2L, 0, 0, null, "#ffffff");
        controller.updatePixel(pixel, headers);
        controller.getBoard(1L, 2L, headers);
        verify(displays).updatePixel(eq(1L), eq(2L), eq(0), eq(0), any());
        verify(displays).getDisplay(1L, 2L);
        reset(displays, messages);

        authService.logout("Bearer " + first);
        authService.login("test-only-id-token");
        assertThrows(BusinessException.class, () -> controller.updatePixel(pixel, headers));
        assertThrows(BusinessException.class, () -> controller.getBoard(1L, 2L, headers));
        verifyNoInteractions(displays, messages);
    }

    @Test
    void displayMessagesCannotSelectAnotherRoomOrUseGameOnlyHandshake() {
        String token = authService.login("test-only-id-token");
        SimpMessageHeaderAccessor headers = displayHeaders(token);
        DisplayService displays = mock(DisplayService.class);
        SimpMessagingTemplate messages = mock(SimpMessagingTemplate.class);
        DisplaySocketController controller = new DisplaySocketController(displays, messages, sessionUtil);
        PixelUpdateMessage anotherRoom = new PixelUpdateMessage(3L, 2L, 0, 0, null, "#ffffff");
        assertThrows(BusinessException.class, () -> controller.updatePixel(anotherRoom, headers));
        assertThrows(BusinessException.class, () -> controller.getBoard(3L, 2L, headers));

        AccessTokenHandshakeInterceptor interceptor = new AccessTokenHandshakeInterceptor(sessionUtil, mock(DisplayAccessRepository.class));
        Map<String, Object> attributes = new HashMap<>();
        assertTrue(handshake(interceptor, token, "game&game_id=1", attributes));
        headers.setSessionAttributes(attributes);
        PixelUpdateMessage gameOnly = new PixelUpdateMessage(1L, 2L, 0, 0, null, "#ffffff");
        assertThrows(BusinessException.class, () -> controller.updatePixel(gameOnly, headers));
        verifyNoInteractions(displays, messages);
    }

    private SimpMessageHeaderAccessor displayHeaders(String token) {
        DisplayAccessRepository access = mock(DisplayAccessRepository.class);
        when(access.existsByWalletAddressAndGameIdAndTeamId(wallet, 1L, 2L)).thenReturn(true);
        AccessTokenHandshakeInterceptor interceptor = new AccessTokenHandshakeInterceptor(sessionUtil, access);
        Map<String, Object> attributes = new HashMap<>();
        assertTrue(handshake(interceptor, token, "display&game_id=1&team_id=2", attributes));
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create();
        headers.setSessionAttributes(attributes);
        return headers;
    }

    private boolean handshake(AccessTokenHandshakeInterceptor interceptor, String token) {
        return handshake(interceptor, token, "game&game_id=1", new HashMap<>());
    }

    private boolean handshake(AccessTokenHandshakeInterceptor interceptor, String token, String parameters,
                              Map<String, Object> attributes) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/ws?access_token=" + token + "&type=" + parameters));
        return interceptor.beforeHandshake(request, mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes);
    }
}
