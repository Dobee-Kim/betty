package org.example.betty.common.util;

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

    private final JWTService jwtService;

    @Qualifier("redisTemplate")
    private final RedisTemplate<String, Object> redisTemplate;

    public void setSession(String walletAddress, Duration ttl) {
        redisTemplate.opsForValue().set(walletAddress, Boolean.TRUE, ttl);
    }

    public String getSession(String accessToken) {
        if (accessToken == null || accessToken.length() <= 7
                || !accessToken.regionMatches(true, 0, "Bearer ", 0, 7)
                || accessToken.substring(7).isBlank()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ACCESS_TOKEN);
        }

        String accessTokenBody = accessToken.substring(7).trim();
        String walletAddress = jwtService.getSubjectFromToken(accessTokenBody);

        if (!Boolean.TRUE.equals(redisTemplate.hasKey(walletAddress))) {
            throw new BusinessException(ErrorCode.INVALID_SESSION);
        }

        return walletAddress;
    }

    public String getWalletAddress(String accessToken) {
        return getSession(accessToken);
    }

    public void deleteSession(String walletAddress) {
        redisTemplate.delete(walletAddress);
    }
}
