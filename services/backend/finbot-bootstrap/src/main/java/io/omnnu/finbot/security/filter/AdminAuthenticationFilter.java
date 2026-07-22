package io.omnnu.finbot.security.filter;

import io.omnnu.finbot.security.principal.AdminApiTokenPrincipal;

import io.omnnu.finbot.application.identity.port.in.AdminApiTokenUseCase;
import io.omnnu.finbot.application.identity.port.in.AuthenticationUseCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class AdminAuthenticationFilter extends OncePerRequestFilter {
    public static final String SESSION_COOKIE_NAME = "FINBOT_SESSION";

    private final AuthenticationUseCase authenticationUseCase;
    private final AdminApiTokenUseCase apiTokenUseCase;

    public AdminAuthenticationFilter(
            AuthenticationUseCase authenticationUseCase,
            AdminApiTokenUseCase apiTokenUseCase) {
        this.authenticationUseCase = Objects.requireNonNull(authenticationUseCase, "authenticationUseCase");
        this.apiTokenUseCase = Objects.requireNonNull(apiTokenUseCase, "apiTokenUseCase");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        var authorizationHeaders = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (!authorizationHeaders.isEmpty()) {
            authenticateBearer(authorizationHeaders);
            filterChain.doFilter(request, response);
            return;
        }
        sessionToken(request)
                .flatMap(authenticationUseCase::validateSession)
                .ifPresent(session -> authenticate(session.username()));
        filterChain.doFilter(request, response);
    }

    public static boolean hasBearerAuthorization(HttpServletRequest request) {
        return Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)).stream()
                .anyMatch(AdminAuthenticationFilter::isBearerAuthorization);
    }

    private void authenticateBearer(List<String> authorizationHeaders) {
        if (authorizationHeaders.size() != 1) {
            return;
        }
        bearerToken(authorizationHeaders.getFirst())
                .flatMap(apiTokenUseCase::authenticate)
                .ifPresent(token -> authenticate(new AdminApiTokenPrincipal(
                        token.tokenId(),
                        token.username(),
                        token.expiresAt())));
    }

    private static Optional<String> bearerToken(String authorization) {
        if (!isBearerAuthorization(authorization)) {
            return Optional.empty();
        }
        var token = authorization.substring(7).strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private static boolean isBearerAuthorization(String authorization) {
        return authorization != null
                && authorization.length() >= 7
                && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    private static void authenticate(Object principal) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                "",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static Optional<String> sessionToken(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> SESSION_COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst();
    }
}
