package io.omnnu.finbot.security;

import io.omnnu.finbot.configuration.InternalServiceTokenProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public final class InternalServiceAuthenticationInterceptor implements HandlerInterceptor {
    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expectedToken;

    public InternalServiceAuthenticationInterceptor(InternalServiceTokenProperties properties) {
        this.expectedToken = Objects.requireNonNull(properties, "properties")
                .serviceToken()
                .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws IOException {
        var authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            var suppliedToken = authorization.substring(BEARER_PREFIX.length())
                    .getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(expectedToken, suppliedToken)) {
                return true;
            }
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"Internal authentication required","status":401,"detail":"A valid internal service token is required"}
                """);
        return false;
    }
}
