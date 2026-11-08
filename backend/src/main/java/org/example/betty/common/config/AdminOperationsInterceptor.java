package org.example.betty.common.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.betty.common.util.SessionUtil;
import org.example.betty.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminOperationsInterceptor implements HandlerInterceptor {

    private final SessionUtil sessionUtil;
    private final String adminAddress;

    public AdminOperationsInterceptor(SessionUtil sessionUtil,
                                      @Value("${ADMIN_ADDRESS:}") String adminAddress) {
        this.sessionUtil = sessionUtil;
        this.adminAddress = adminAddress.trim();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equals(request.getMethod())) {
            return true;
        }
        if (!adminAddress.matches("0x[0-9a-fA-F]{40}")) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }

        String walletAddress;
        try {
            walletAddress = sessionUtil.getSession(request.getHeader(HttpHeaders.AUTHORIZATION));
        } catch (BusinessException exception) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (!adminAddress.equalsIgnoreCase(walletAddress)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }
}
