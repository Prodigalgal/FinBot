package io.omnnu.finbot.api.paper;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import io.omnnu.finbot.api.controller.ApiExceptionHandler;
import io.omnnu.finbot.api.paper.controller.LocalPaperController;
import io.omnnu.finbot.application.paper.port.in.LocalPaperUseCase;
import io.omnnu.finbot.application.paper.exception.LocalPaperConflictException;
import io.omnnu.finbot.application.paper.exception.LocalPaperNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LocalPaperControllerTest {
    @Test void missingFieldsAndNegativeVersionsCannotSilentlyPauseOrMutateTheAccount() throws Exception {
        var paper = mock(LocalPaperUseCase.class);
        var mvc = MockMvcBuilders.standaloneSetup(new LocalPaperController(paper)).setControllerAdvice(new ApiExceptionHandler()).build();
        for (var body : new String[]{"{}", "{\"ordersEnabled\":false}", "{\"expectedVersion\":0}", "{\"ordersEnabled\":true,\"expectedVersion\":-1}"}) {
            mvc.perform(put("/api/v2/trading/local-paper/account").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v2/trading/local-paper/trades/paper_test001/cancel").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(paper);
    }
    @Test void returnsConflictAndNotFoundWithoutChangingVersionSemantics() throws Exception {
        var paper = mock(LocalPaperUseCase.class);
        when(paper.setOrdersEnabled(false, 4)).thenThrow(new LocalPaperConflictException("changed"));
        when(paper.detail("paper_absent001")).thenThrow(new LocalPaperNotFoundException("absent"));
        var mvc = MockMvcBuilders.standaloneSetup(new LocalPaperController(paper)).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(put("/api/v2/trading/local-paper/account").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ordersEnabled\":false,\"expectedVersion\":4}")).andExpect(status().isConflict());
        mvc.perform(get("/api/v2/trading/local-paper/trades/paper_absent001")).andExpect(status().isNotFound());
    }
}
