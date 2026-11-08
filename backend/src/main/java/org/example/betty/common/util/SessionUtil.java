package org.example.betty.common.util;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.example.betty.domain.auth.service.JWTService;
import org.example.betty.exception.BusinessException;
import org.example.betty.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class SessionUtil {

    public record SessionIdentity(String walletAddress, String id, long expiresAt) {}

    private final JWTService jwtService;

    @Qualifier("redisTemplate")
    private final RedisTemplate<String, Object> redisTemplate;

    public void setSession(String accessToken, Duration ttl) {
        Claims claims = jwtService.getClaimsFromToken(accessToken);
        redisTemplate.opsForValue().set(claims.getSubject(), claims.getId(), ttl);
    }

    public String getSession(String accessToken) {
        return getSessionIdentity(accessToken).walletAddress();
    }

    public SessionIdentity getSessionIdentity(String accessToken) {
        if (accessToken == null || accessToken.length() <= 7
                || !accessToken.regionMatches(true, 0, "Bearer ", 0, 7)
                || accessToken.substring(7).isBlank()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ACCESS_TOKEN);
        }

        String accessTokenBody = accessToken.substring(7).trim();
        Claims claims = jwtService.getClaimsFromToken(accessTokenBody);
        SessionIdentity session = new SessionIdentity(claims.getSubject(), claims.getId(),
                claims.getExpiration() == null ? 0 : claims.getExpiration().getTime());
        validateSession(session);
        return session;
    }

    public void validateSession(SessionIdentity session) {
        if (session == null || session.walletAddress() == null || session.id() == null
                || session.expiresAt() <= System.currentTimeMillis()
                || !session.id().equals(redisTemplate.opsForValue().get(session.walletAddress()))) {
            throw new BusinessException(ErrorCode.INVALID_SESSION);
        }
    }

    public String getWalletAddress(String accessToken) {
        return getSession(accessToken);
    }

    public void deleteSession(String walletAddress) {
        redisTemplate.delete(walletAddress);
    }
}
