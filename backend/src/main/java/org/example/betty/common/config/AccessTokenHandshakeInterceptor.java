package org.example.betty.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.betty.common.util.SessionUtil;
import org.example.betty.domain.display.repository.DisplayAccessRepository;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

@RequiredArgsConstructor
@Slf4j
public class AccessTokenHandshakeInterceptor implements HandshakeInterceptor {

    private final SessionUtil sessionUtil;
    private final DisplayAccessRepository displayAccessRepository;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {

        URI uri = request.getURI();

        MultiValueMap<String, String> queryParams = UriComponentsBuilder.fromUri(uri).build().getQueryParams();

        // access_token 검증
        String token = queryParams.getFirst("access_token");
        if (token == null || token.trim().isEmpty()) {
            log.warn("Missing or empty access_token parameter");
            return false;
        }
        token = "Bearer " + token;

        // 세션 검증 및 등록: 실패 시 바로 종료
        String walletAddress;
        try {
            walletAddress = sessionUtil.getSession(token);
            if (walletAddress == null || walletAddress.trim().isEmpty()) {
                log.warn("Session not found for token");
                return false;
            }
            attributes.put("walletAddress", walletAddress);
        } catch (Exception e) {
            log.error("Failed Session Check", e);
            return false;
        }

        // 요청 파싱: type 파라미터 필수
        String type = queryParams.getFirst("type");
        if (type == null || type.trim().isEmpty()) {
            log.warn("Missing or empty type parameter");
            return false;
        }

        // type 별 분기 처리
        if (type.equals("display")) {
            String strGameId = queryParams.getFirst("game_id");
            String strTeamId = queryParams.getFirst("team_id");

            if (strGameId == null || strTeamId == null) {
                log.warn("Missing game_id or team_id parameters: game_id={}, team_id={}", strGameId, strTeamId);
                return false;
            }

            Long gameId;
            Long teamId;
            try {
                gameId = Long.parseLong(strGameId);
                teamId = Long.parseLong(strTeamId);
            } catch (NumberFormatException e) {
                log.error("Invalid game_id or team_id format", e);
                return false;
            }

            try {
                if (!displayAccessRepository.existsByWalletAddressAndGameIdAndTeamId(walletAddress, gameId, teamId)) {
                    log.warn("Display access not found for walletAddress={}, gameId={}, teamId={}", walletAddress, gameId, teamId);
                    return false;
                }
                log.info("Handshake successful for walletAddress: {}", walletAddress);
                return true;
            } catch (Exception e) {
                log.error("Exception during handshake processing for display", e);
                return false;
            }
        } else if (type.equals("game")) {
            String strGameId = queryParams.getFirst("game_id");
            if (strGameId == null) {
                log.warn("Missing game_id parameter for game type");
                return false;
            }
            // 추가 검증이 필요한 경우 여기에 로직 추가 (예: gameId 파싱 등)
            return true;
        }

        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.error("AfterHandshake encountered an error", exception);
        } else {
            log.info("AfterHandshake completed successfully");
        }
    }
}
