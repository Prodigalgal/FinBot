package io.omnnu.finbot.api.research;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.omnnu.finbot.api.controller.ApiExceptionHandler;
import io.omnnu.finbot.api.research.controller.HypothesisController;
import io.omnnu.finbot.application.research.port.in.HypothesisUseCase;
import io.omnnu.finbot.application.research.exception.HypothesisConflictException;
import io.omnnu.finbot.domain.research.HypothesisStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HypothesisControllerTest {
    @Test void requiresTheExpectedVersionTargetStateAndVerificationReason() throws Exception {
        var hypotheses = mock(HypothesisUseCase.class);
        var mvc = MockMvcBuilders.standaloneSetup(new HypothesisController(hypotheses)).setControllerAdvice(new ApiExceptionHandler()).build();
        for (var body : new String[]{"{}", "{\"expectedVersion\":0,\"status\":\"CONFIRMED\",\"reason\":\" \"}"}) {
            mvc.perform(put("/api/v2/research/hypotheses/hypothesis_test001/status")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(hypotheses);
    }
    @Test void rejectsConflictingLifecycleUpdates() throws Exception {
        var hypotheses = mock(HypothesisUseCase.class);
        when(hypotheses.transition("hypothesis_test001", 0, HypothesisStatus.WATCHING, "source checked"))
                .thenThrow(new HypothesisConflictException("changed"));
        var mvc = MockMvcBuilders.standaloneSetup(new HypothesisController(hypotheses)).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(put("/api/v2/research/hypotheses/hypothesis_test001/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"status\":\"WATCHING\",\"reason\":\"source checked\"}")).andExpect(status().isConflict());
    }
}
