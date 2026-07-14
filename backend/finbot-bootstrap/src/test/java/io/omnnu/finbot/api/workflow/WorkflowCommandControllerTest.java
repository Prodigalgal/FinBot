package io.omnnu.finbot.api.workflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.omnnu.finbot.api.ApiExceptionHandler;
import io.omnnu.finbot.application.workflow.StartWorkflowResult;
import io.omnnu.finbot.application.workflow.StartWorkflowUseCase;
import io.omnnu.finbot.application.workflow.WorkflowRunQuery;
import io.omnnu.finbot.domain.workflow.WorkflowEventId;
import io.omnnu.finbot.domain.workflow.WorkflowRunId;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkflowCommandControllerTest {
    @Test
    void acceptsTypedWorkflowCommandAsynchronously() throws Exception {
        StartWorkflowUseCase useCase = command -> CompletableFuture.completedFuture(new StartWorkflowResult(
                new WorkflowRunId("run_01j0000000001"),
                new WorkflowEventId("event_01j0000000001"),
                Instant.parse("2026-07-13T12:00:00Z")));
        WorkflowRunQuery query = ignored -> Optional.empty();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new WorkflowCommandController(useCase, query))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        var pending = mockMvc.perform(post("/api/v2/workflows")
                        .header("Idempotency-Key", "instant:test-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workflowType": "INSTANT_RESEARCH",
                                  "trigger": "MANUAL",
                                  "workflowVersionId": null,
                                  "requestSummary": "Analyze BTC liquidity"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v2/workflows/run_01j0000000001"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.runId").value("run_01j0000000001"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.eventsUrl")
                        .value("/api/v2/workflows/run_01j0000000001/events"));
    }
}
