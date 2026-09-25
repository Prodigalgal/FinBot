package io.omnnu.finbot.api.chat.controller;

import io.omnnu.finbot.api.chat.dto.AnalysisChatTurnResponse;
import io.omnnu.finbot.api.chat.dto.CreateAnalysisChatRequest;
import io.omnnu.finbot.api.chat.dto.SendAnalysisChatMessageRequest;
import io.omnnu.finbot.application.chat.dto.AnalysisChatSession;
import io.omnnu.finbot.application.chat.port.in.AnalysisChatUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/analysis-chats")
public final class AnalysisChatController {
    private final AnalysisChatUseCase chats;

    public AnalysisChatController(AnalysisChatUseCase chats) {
        this.chats = Objects.requireNonNull(chats, "chats");
    }

    @GetMapping
    public List<AnalysisChatSession> list(
            @RequestParam(required = false) String beforeChatId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return chats.list(beforeChatId, search, limit);
    }

    @PostMapping
    public ResponseEntity<AnalysisChatSession> create(@Valid @RequestBody CreateAnalysisChatRequest request) {
        var session = chats.create(request.workflowVersionId());
        return ResponseEntity.created(URI.create("/api/v2/analysis-chats/" + session.chatId()))
                .body(session);
    }

    @GetMapping("/{chatId}")
    public AnalysisChatSession find(@PathVariable String chatId) {
        return chats.find(chatId);
    }

    @GetMapping("/{chatId}/turns")
    public List<AnalysisChatTurnResponse> turns(
            @PathVariable String chatId,
            @RequestParam(required = false) Integer beforeTurnNumber,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return chats.turns(chatId, beforeTurnNumber, limit).stream()
                .map(AnalysisChatTurnResponse::from)
                .toList();
    }

    @PostMapping("/{chatId}/turns")
    public CompletionStage<ResponseEntity<AnalysisChatTurnResponse>> send(
            @PathVariable String chatId,
            @RequestHeader("Idempotency-Key") String clientRequestKey,
            @Valid @RequestBody SendAnalysisChatMessageRequest request) {
        return chats.send(chatId, request.message(), clientRequestKey)
                .thenApply(turn -> ResponseEntity.accepted().body(AnalysisChatTurnResponse.from(turn)));
    }
}
