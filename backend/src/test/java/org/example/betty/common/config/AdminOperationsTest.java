package org.example.betty.common.config;

import org.example.betty.common.util.SessionUtil;
import org.example.betty.domain.auth.service.JWTService;
import org.example.betty.domain.exchange.controller.ExchangeController;
import org.example.betty.domain.exchange.service.ExchangeService;
import org.example.betty.domain.exchange.service.TransactionQueryService;
import org.example.betty.domain.game.controller.SettleController;
import org.example.betty.domain.game.service.GameSettleService;
import org.example.betty.domain.reward.controller.RewardController;
import org.example.betty.domain.reward.service.RewardService;
import org.example.betty.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = AdminOperationsTest.Config.class)
@TestPropertySource(properties = {
        "ADMIN_OPERATOR_ADDRESS=0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "jwt.secret=test-only-signing-material-not-used-outside-this-test"
})
class AdminOperationsTest {
    private static final String ADMIN = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String USER = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Autowired WebApplicationContext context;
    @Autowired JWTService jwtService;
    @Autowired RedisTemplate<String, Object> redis;
    @Autowired RewardService rewardService;
    @Autowired GameSettleService settleService;
    @Autowired ExchangeService exchangeService;
    private MockMvc mvc;
    private ValueOperations<String, Object> values;

    @BeforeEach
    void setUp() {
        reset(redis, rewardService, settleService, exchangeService);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        mvc = webAppContextSetup(context).build();
    }

    private String bearer(String wallet) {
        return "Bearer " + jwtService.generateAccessToken(wallet, new Date(System.currentTimeMillis() + 60000));
    }

    private String activeBearer(String wallet) {
        String token = bearer(wallet);
        when(values.get(wallet)).thenReturn(jwtService.getClaimsFromToken(token.substring(7)).getId());
        return token;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/reward", "/api/v1/settle/live", "/api/v1/settle/pre/team",
            "/api/v1/settle/pre/team/ready", "/api/v1/settle/pre/mvp", "/api/v1/settle/pre/mvp/ready",
            "/api/v1/exchange/add"})
    void anonymousCannotTriggerPrivilegedOperations(String path) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(rewardService, settleService, exchangeService);
    }

    @Test
    void signedInUserCannotSendRewards() throws Exception {
        mvc.perform(post("/api/v1/reward").header("Authorization", activeBearer(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(rewardService);
    }

    @Test
    void revokedAdminCannotSendRewards() throws Exception {
        mvc.perform(post("/api/v1/reward").header("Authorization", bearer(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(rewardService);
    }

    @Test
    void tamperedAdminTokenCannotSendRewards() throws Exception {
        mvc.perform(post("/api/v1/reward").header("Authorization", bearer(ADMIN) + "invalid")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(rewardService);
    }

    @Test
    void expiredAdminTokenCannotSendRewards() throws Exception {
        String token = jwtService.generateAccessToken(ADMIN, new Date(System.currentTimeMillis() - 60000));
        mvc.perform(post("/api/v1/reward").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(rewardService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad", "Basic test", "Bearer "})
    void malformedAuthorizationIsRejected(String header) throws Exception {
        mvc.perform(post("/api/v1/reward").header("Authorization", header)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(rewardService);
    }

    @Test
    void unsetAdminAddressFailsClosed() throws Exception {
        SessionUtil sessions = mock(SessionUtil.class);
        var interceptor = new AdminOperationsInterceptor(sessions, "");
        var response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(new MockHttpServletRequest("POST", "/api/v1/reward"), response, null));
        assertEquals(403, response.getStatus());
        verifyNoInteractions(sessions);
    }

    @Test
    void walletLookupAlsoRejectsRevokedSessions() {
        assertThrows(BusinessException.class,
                () -> new SessionUtil(jwtService, redis).getWalletAddress(bearer(ADMIN)));
    }

    @Test
    void activeConfiguredAdminCanSendRewards() throws Exception {
        mvc.perform(post("/api/v1/reward").header("Authorization", activeBearer(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(rewardService).sendReward(any());
    }

    @Test
    void publicBalanceLookupRemainsAvailable() throws Exception {
        mvc.perform(get("/api/v1/reward/balance").param("tokenAddress", "token"))
                .andExpect(status().isOk());
        verify(rewardService).getTokenBalance("token");
    }

    @Configuration
    @EnableWebMvc
    @Import({WebConfig.class, AdminOperationsInterceptor.class, RewardController.class,
            SettleController.class, ExchangeController.class})
    static class Config {
        @Bean JWTService jwtService() {
            return new JWTService();
        }
        @Bean RedisTemplate<String, Object> redis() { return mock(RedisTemplate.class); }
        @Bean SessionUtil sessionUtil(JWTService jwt, RedisTemplate<String, Object> redis) {
            return new SessionUtil(jwt, redis);
        }
        @Bean RewardService rewardService() { return mock(RewardService.class); }
        @Bean GameSettleService settleService() { return mock(GameSettleService.class); }
        @Bean ExchangeService exchangeService() { return mock(ExchangeService.class); }
        @Bean TransactionQueryService transactionQueryService() { return mock(TransactionQueryService.class); }
    }
}
