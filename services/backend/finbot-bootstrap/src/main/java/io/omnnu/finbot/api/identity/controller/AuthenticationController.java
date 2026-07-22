package io.omnnu.finbot.api.identity.controller;

import io.omnnu.finbot.api.identity.dto.AuthChallengeResponse;
import io.omnnu.finbot.api.identity.dto.AuthStatusResponse;
import io.omnnu.finbot.api.identity.dto.LoginRequest;

import io.omnnu.finbot.application.identity.port.in.AuthenticationUseCase;
import io.omnnu.finbot.application.identity.dto.LoginCommand;
import io.omnnu.finbot.configuration.properties.AuthenticationProperties;
import io.omnnu.finbot.domain.identity.AuthChallengeId;
import io.omnnu.finbot.security.filter.AdminAuthenticationFilter;
import io.omnnu.finbot.security.principal.AdminApiTokenPrincipal;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/auth")
public final class AuthenticationController {
    private final AuthenticationUseCase authenticationUseCase;
    private final AuthenticationProperties properties;

    public AuthenticationController(
            AuthenticationUseCase authenticationUseCase,
            AuthenticationProperties properties) {
        this.authenticationUseCase = Objects.requireNonNull(authenticationUseCase, "authenticationUseCase");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @GetMapping("/challenge")
    public AuthChallengeResponse challenge() {
        return AuthChallengeResponse.from(authenticationUseCase.createChallenge());
    }

    @PostMapping("/login")
    public ResponseEntity<AuthStatusResponse> login(
            @Valid @RequestBody LoginRequest request,
            CsrfToken csrfToken) {
        var result = authenticationUseCase.login(new LoginCommand(
                request.username(),
                request.password(),
                new AuthChallengeId(request.challengeId()),
                request.proofOfWorkSolution(),
                request.mathAnswer()));
        var cookie = sessionCookie(result.rawSessionToken(), properties.sessionTtl());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new AuthStatusResponse(
                        true,
                        result.username(),
                        result.expiresAt(),
                        csrfToken.getToken()));
    }

    @GetMapping("/status")
    public AuthStatusResponse status(Authentication authentication, CsrfToken csrfToken, HttpServletRequest request) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return AuthStatusResponse.anonymous(csrfToken.getToken());
        }
        if (authentication.getPrincipal() instanceof AdminApiTokenPrincipal principal) {
            return new AuthStatusResponse(
                    true,
                    principal.username(),
                    principal.expiresAt(),
                    csrfToken.getToken());
        }
        var session = rawSessionToken(request)
                .flatMap(authenticationUseCase::validateSession)
                .orElse(null);
        if (session == null) {
            return AuthStatusResponse.anonymous(csrfToken.getToken());
        }
        return new AuthStatusResponse(true, session.username(), session.expiresAt(), csrfToken.getToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<AuthStatusResponse> logout(HttpServletRequest request, CsrfToken csrfToken) {
        rawSessionToken(request).ifPresent(authenticationUseCase::logout);
        var expiredCookie = sessionCookie("", Duration.ZERO);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expiredCookie.toString())
                .body(AuthStatusResponse.anonymous(csrfToken.getToken()));
    }

    private ResponseCookie sessionCookie(String value, Duration maximumAge) {
        return ResponseCookie.from(AdminAuthenticationFilter.SESSION_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Strict")
                .path("/")
                .maxAge(maximumAge)
                .build();
    }

    private static java.util.Optional<String> rawSessionToken(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return java.util.Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> AdminAuthenticationFilter.SESSION_COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> !value.isBlank())
                .findFirst();
    }
}
