package io.omnnu.finbot.infrastructure.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.omnnu.finbot.application.operations.AccountTaskPayload;
import io.omnnu.finbot.application.operations.BackgroundTaskPayload;
import io.omnnu.finbot.application.operations.IngestionTaskPayload;
import io.omnnu.finbot.application.operations.InstantResearchTaskPayload;
import io.omnnu.finbot.application.operations.MarketDataTaskPayload;
import io.omnnu.finbot.application.operations.ResearchTaskMode;
import io.omnnu.finbot.application.operations.ScheduledResearchTaskPayload;
import io.omnnu.finbot.domain.catalog.InstrumentId;
import io.omnnu.finbot.domain.ledger.ExchangeAccountId;
import io.omnnu.finbot.domain.ingestion.SourceId;
import io.omnnu.finbot.domain.operations.BackgroundTaskType;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class TaskPayloadCodec {
    private final ObjectMapper objectMapper;

    public TaskPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String encode(BackgroundTaskPayload payload) {
        var node = objectMapper.createObjectNode();
        switch (payload) {
            case ScheduledResearchTaskPayload scheduled -> node.put("requestSummary", scheduled.requestSummary());
            case InstantResearchTaskPayload instant -> node
                    .put("requestId", instant.requestId())
                    .put("question", instant.question())
                    .put("workflowType", instant.workflowType().name())
                    .put("trigger", instant.trigger().name())
                    .put("workflowVersionId", instant.workflowVersionId() == null
                            ? null
                            : instant.workflowVersionId().value())
                    .put("workflowIdempotencyKey", instant.workflowIdempotencyKey())
                    .put("taskMode", instant.taskMode().name());
            case AccountTaskPayload account -> node.put("accountId", account.accountId().value());
            case MarketDataTaskPayload marketData -> node.put("instrumentId", marketData.instrumentId().value());
            case IngestionTaskPayload ingestion -> node
                    .put("workflowRunId", ingestion.workflowRunId() == null
                            ? null
                            : ingestion.workflowRunId().value())
                    .put("sourceId", ingestion.sourceId().value())
                    .put("query", ingestion.query());
        }
        return write(node);
    }

    public BackgroundTaskPayload decode(BackgroundTaskType type, String json) {
        var node = readObject(json);
        return switch (type) {
            case SCHEDULED_RESEARCH -> {
                requireOnlyFields(node, Set.of("requestSummary"));
                yield new ScheduledResearchTaskPayload(requiredText(node, "requestSummary"));
            }
            case INSTANT_RESEARCH -> {
                requireOnlyFields(node, Set.of(
                        "requestId", "question", "workflowType", "trigger",
                        "workflowVersionId", "workflowIdempotencyKey", "taskMode"));
                yield new InstantResearchTaskPayload(
                        requiredText(node, "requestId"),
                        requiredText(node, "question"),
                        io.omnnu.finbot.domain.workflow.WorkflowType.valueOf(requiredText(node, "workflowType")),
                        io.omnnu.finbot.domain.workflow.WorkflowTrigger.valueOf(requiredText(node, "trigger")),
                        nullableText(node, "workflowVersionId") == null
                                ? null
                                : new io.omnnu.finbot.domain.workflow.WorkflowVersionId(
                                        nullableText(node, "workflowVersionId")),
                        requiredText(node, "workflowIdempotencyKey"),
                        ResearchTaskMode.valueOf(requiredText(node, "taskMode")));
            }
            case ACCOUNT_SYNC, ORDER_RECONCILIATION -> {
                requireOnlyFields(node, Set.of("accountId"));
                yield new AccountTaskPayload(new ExchangeAccountId(requiredText(node, "accountId")));
            }
            case MARKET_DATA_SYNC -> {
                requireOnlyFields(node, Set.of("instrumentId"));
                yield new MarketDataTaskPayload(new InstrumentId(requiredText(node, "instrumentId")));
            }
            case INGESTION -> {
                requireOnlyFields(node, Set.of("workflowRunId", "sourceId", "query"));
                yield new IngestionTaskPayload(
                        nullableText(node, "workflowRunId") == null
                                ? null
                                : new io.omnnu.finbot.domain.workflow.WorkflowRunId(
                                        nullableText(node, "workflowRunId")),
                        new SourceId(requiredText(node, "sourceId")),
                        requiredText(node, "query"));
            }
        };
    }

    private ObjectNode readObject(String json) {
        try {
            var node = objectMapper.readTree(json);
            if (!(node instanceof ObjectNode objectNode)) {
                throw new IllegalArgumentException("Task payload must be a JSON object");
            }
            return objectNode;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid task payload JSON", exception);
        }
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode task payload", exception);
        }
    }

    private static String requiredText(ObjectNode node, String fieldName) {
        var value = node.get(fieldName);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Task payload field is invalid: " + fieldName);
        }
        return value.textValue();
    }

    private static String nullableText(ObjectNode node, String fieldName) {
        var value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Task payload field is invalid: " + fieldName);
        }
        return value.textValue();
    }

    private static void requireOnlyFields(ObjectNode node, Set<String> allowed) {
        var actual = new HashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!allowed.equals(actual)) {
            throw new IllegalArgumentException("Task payload fields do not match its task type");
        }
    }
}
