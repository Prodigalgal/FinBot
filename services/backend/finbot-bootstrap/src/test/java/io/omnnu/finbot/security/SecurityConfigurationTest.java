package io.omnnu.finbot.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import io.omnnu.finbot.application.identity.AdminSession;
import io.omnnu.finbot.application.identity.AuthenticationUseCase;
import io.omnnu.finbot.domain.identity.AdminSessionId;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;
import org.springframework.security.web.csrf.CsrfToken;

@SpringJUnitWebConfig(classes = {SecurityConfiguration.class, SecurityConfigurationTest.TestConfiguration.class})
class SecurityConfigurationTest {
    private final WebApplicationContext context;
    private MockMvc mockMvc;

    @Autowired
    SecurityConfigurationTest(WebApplicationContext context) {
        this.context = context;
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void permitsAsyncRedispatchAfterInitialSessionAuthentication() throws Exception {
        var pending = mockMvc.perform(get("/api/v2/test/async")
                        .cookie(new Cookie(SessionAuthenticationFilter.SESSION_COOKIE_NAME, "valid-session-token")))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().string("completed"));
    }

    @Test
    void rejectsAnonymousInitialRequestBeforeAsyncHandlingStarts() throws Exception {
        mockMvc.perform(get("/api/v2/test/async"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(request().asyncNotStarted());
    }

    @Test
    void acceptsRawCookieCsrfTokenFromSpaHeader() throws Exception {
        var sessionCookie = new Cookie(
                SessionAuthenticationFilter.SESSION_COOKIE_NAME,
                "valid-session-token");
        var tokenResponse = mockMvc.perform(get("/api/v2/test/csrf").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        var csrfCookie = tokenResponse.getCookie("XSRF-TOKEN");

        mockMvc.perform(put("/api/v2/test/csrf")
                        .cookie(sessionCookie, csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isOk())
                .andExpect(content().string("updated"));
    }

    @Test
    void identifiesInvalidSpaCsrfSeparatelyFromAuthentication() throws Exception {
        var sessionCookie = new Cookie(
                SessionAuthenticationFilter.SESSION_COOKIE_NAME,
                "valid-session-token");

        mockMvc.perform(put("/api/v2/test/csrf")
                        .cookie(sessionCookie)
                        .header("X-XSRF-TOKEN", "invalid-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED_OR_INVALID_CSRF"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class TestConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        AuthenticationUseCase authenticationUseCase() {
            var useCase = mock(AuthenticationUseCase.class);
            var now = Instant.parse("2026-07-14T08:00:00Z");
            var session = new AdminSession(
                    new AdminSessionId("session_01j0000000001"),
                    "admin",
                    now.plusSeconds(3_600),
                    now,
                    null,
                    now);
            when(useCase.validateSession("valid-session-token")).thenReturn(Optional.of(session));
            return useCase;
        }

        @Bean
        SessionAuthenticationFilter sessionAuthenticationFilter(AuthenticationUseCase authenticationUseCase) {
            return new SessionAuthenticationFilter(authenticationUseCase);
        }

        @Bean
        AsyncTestController asyncTestController() {
            return new AsyncTestController();
        }
    }

    @RestController
    static class AsyncTestController {
        @GetMapping("/api/v2/test/async")
        Callable<String> async() {
            return () -> "completed";
        }

        @GetMapping("/api/v2/test/csrf")
        String csrf(CsrfToken csrfToken) {
            return csrfToken.getToken();
        }

        @PutMapping("/api/v2/test/csrf")
        String update() {
            return "updated";
        }
    }
}
