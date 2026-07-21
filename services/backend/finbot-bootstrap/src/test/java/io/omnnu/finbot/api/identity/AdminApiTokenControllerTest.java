package io.omnnu.finbot.api.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.omnnu.finbot.application.identity.AdminApiTokenUseCase;
import io.omnnu.finbot.application.identity.CreateAdminApiTokenCommand;
import io.omnnu.finbot.application.identity.CreatedAdminApiToken;
import io.omnnu.finbot.domain.identity.AdminApiToken;
import io.omnnu.finbot.domain.identity.AdminApiTokenId;
import io.omnnu.finbot.security.AdminApiTokenPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminApiTokenControllerTest {
    private static final Instant NOW = Instant.parse("2026-07-21T08:00:00Z");

    @Test
    void createsOneTimeTokenWithoutCachingAndUsesAuthenticatedTokenOwner() throws Exception {
        var useCase = mock(AdminApiTokenUseCase.class);
        var token = token();
        var rawToken = "finbot_pat_" + "A".repeat(43);
        when(useCase.createToken(any())).thenReturn(new CreatedAdminApiToken(token, rawToken));
        var controller = new AdminApiTokenController(useCase, Clock.fixed(NOW, ZoneOffset.UTC));
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        var principal = new AdminApiTokenPrincipal(token.tokenId(), "admin", token.expiresAt());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, "", List.of());

        mockMvc.perform(post("/api/v2/api-tokens")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Deployment automation",
                                  "expiresInDays": 90
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.token.tokenId").value(token.tokenId().value()))
                .andExpect(jsonPath("$.rawToken").value(rawToken));

        var command = ArgumentCaptor.forClass(CreateAdminApiTokenCommand.class);
        verify(useCase).createToken(command.capture());
        assertEquals("admin", command.getValue().username());
        assertEquals("Deployment automation", command.getValue().displayName());
        assertEquals(90, command.getValue().expiresInDays());
    }

    private static AdminApiToken token() {
        return new AdminApiToken(
                new AdminApiTokenId("apitoken_controller_test"),
                "Deployment automation",
                "0123456789abcdef",
                "admin",
                NOW.plusSeconds(90L * 86_400L),
                null,
                null,
                NOW,
                NOW,
                0);
    }
}
