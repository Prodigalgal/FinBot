package io.omnnu.finbot.security;

import io.omnnu.finbot.application.identity.AuthenticationUseCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class SessionAuthenticationFilter extends OncePerRequestFilter {
    public static final String SESSION_COOKIE_NAME = "FINBOT_SESSION";

    private final AuthenticationUseCase authenticationUseCase;

    public SessionAuthenticationFilter(AuthenticationUseCase authenticationUseCase) {
        this.authenticationUseCase = Objects.requireNonNull(authenticationUseCase, "authenticationUseCase");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        sessionToken(request).flatMap(authenticationUseCase::validateSession).ifPresent(session -> {
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    session.username(),
                    "",
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        });
        filterChain.doFilter(request, response);
    }

    private static java.util.Optional<String> sessionToken(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return java.util.Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> SESSION_COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst();
    }
}
