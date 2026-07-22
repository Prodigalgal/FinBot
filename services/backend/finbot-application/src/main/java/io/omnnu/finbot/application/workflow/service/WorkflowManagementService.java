package io.omnnu.finbot.application.workflow.service;

import io.omnnu.finbot.application.workflow.dto.SaveAgentRoleCommand;
import io.omnnu.finbot.application.workflow.dto.SaveWorkflowDraftCommand;
import io.omnnu.finbot.application.workflow.dto.WorkflowDefinitionSummary;
import io.omnnu.finbot.application.workflow.exception.WorkflowManagementConflictException;
import io.omnnu.finbot.application.workflow.exception.WorkflowNotFoundException;
import io.omnnu.finbot.application.workflow.port.in.WorkflowManagementUseCase;
import io.omnnu.finbot.application.workflow.port.out.WorkflowManagementRepository;
import io.omnnu.finbot.application.workflow.validation.WorkflowPublicationValidator;

import io.omnnu.finbot.application.shared.port.out.SortableIdGenerator;
import io.omnnu.finbot.domain.shared.DomainText;
import io.omnnu.finbot.domain.workflow.AgentRoleTemplate;
import io.omnnu.finbot.domain.workflow.AgentRoleTemplateId;
import io.omnnu.finbot.domain.workflow.BooleanConditionOperand;
import io.omnnu.finbot.domain.workflow.DecimalConditionOperand;
import io.omnnu.finbot.domain.workflow.TextConditionOperand;
import io.omnnu.finbot.domain.workflow.TextListConditionOperand;
import io.omnnu.finbot.domain.workflow.WorkflowCondition;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionId;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowEdgeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowNodeDefinition;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import io.omnnu.finbot.domain.workflow.WorkflowVersionStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class WorkflowManagementService implements WorkflowManagementUseCase {
    private final WorkflowManagementRepository repository;
    private final SortableIdGenerator idGenerator;
    private final Clock clock;

    public WorkflowManagementService(
            WorkflowManagementRepository repository,
            SortableIdGenerator idGenerator,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<WorkflowDefinitionSummary> definitions() {
        return repository.listDefinitions();
    }

    @Override
    public WorkflowDefinitionVersion version(WorkflowVersionId versionId) {
        return repository.findVersion(versionId)
                .orElseThrow(() -> new WorkflowNotFoundException("工作流版本不存在"));
    }

    @Override
    public List<WorkflowDefinitionVersion> versions(WorkflowDefinitionId definitionId) {
        Objects.requireNonNull(definitionId, "definitionId");
        return repository.listVersions(definitionId);
    }

    @Override
    public WorkflowDefinitionVersion saveDraft(SaveWorkflowDraftCommand command) {
        Objects.requireNonNull(command, "command");
        var now = clock.instant();
        var definitionId = command.definitionId() == null
                ? new WorkflowDefinitionId(idGenerator.next("workflow_"))
                : command.definitionId();
        var existing = command.versionId() == null
                ? null
                : repository.findVersion(command.versionId()).orElseThrow(
                        () -> new WorkflowManagementConflictException("草稿版本不存在"));
        if (existing != null && existing.status() != WorkflowVersionStatus.DRAFT) {
            throw new WorkflowManagementConflictException("已发布或归档的工作流不可原地修改");
        }
        if (existing != null && !existing.definitionId().equals(definitionId)) {
            throw new IllegalArgumentException("versionId does not belong to definitionId");
        }
        var versionId = existing == null
                ? new WorkflowVersionId(idGenerator.next("workflowversion_"))
                : existing.versionId();
        var versionNumber = existing == null
                ? repository.nextVersionNumber(definitionId)
                : existing.versionNumber();
        var createdAt = existing == null ? now : existing.createdAt();
        var checksum = checksum(command);
        var draft = new WorkflowDefinitionVersion(
                versionId,
                definitionId,
                versionNumber,
                WorkflowVersionStatus.DRAFT,
                command.defaultDebateRounds(),
                command.maximumSteps(),
                command.maximumDuration(),
                command.maximumTokens(),
                command.maximumCostUsd(),
                command.failurePolicy(),
                checksum,
                null,
                createdAt,
                "admin",
                command.nodes(),
                command.edges());
        return repository.saveDraft(
                DomainText.required(command.name(), "name", 160),
                optional(command.description(), 1000),
                false,
                draft,
                command.expectedChecksum(),
                now);
    }

    @Override
    public WorkflowDefinitionVersion publish(WorkflowVersionId versionId) {
        var draft = version(versionId);
        if (draft.status() != WorkflowVersionStatus.DRAFT) {
            throw new WorkflowManagementConflictException("只有草稿版本可以发布");
        }
        WorkflowPublicationValidator.validate(draft);
        return repository.publish(versionId, clock.instant());
    }

    @Override
    public WorkflowDefinitionVersion rollback(
            WorkflowDefinitionId definitionId,
            WorkflowVersionId targetVersionId) {
        var target = version(targetVersionId);
        if (!target.definitionId().equals(definitionId)) {
            throw new IllegalArgumentException("Rollback target does not belong to workflow definition");
        }
        var summary = definitions().stream()
                .filter(value -> value.definitionId().equals(definitionId))
                .findFirst()
                .orElseThrow(() -> new WorkflowNotFoundException("工作流不存在"));
        var now = clock.instant();
        var copy = new WorkflowDefinitionVersion(
                new WorkflowVersionId(idGenerator.next("workflowversion_")),
                definitionId,
                repository.nextVersionNumber(definitionId),
                WorkflowVersionStatus.DRAFT,
                target.defaultDebateRounds(),
                target.maximumSteps(),
                target.maximumDuration(),
                target.maximumTokens(),
                target.maximumCostUsd(),
                target.failurePolicy(),
                target.checksum(),
                null,
                now,
                "admin",
                target.nodes(),
                target.edges());
        WorkflowPublicationValidator.validate(copy);
        var saved = repository.saveDraft(
                summary.name(), summary.description(), summary.builtIn(), copy, null, now);
        return repository.publish(saved.versionId(), now);
    }

    @Override
    public WorkflowDefinitionSummary setActive(WorkflowDefinitionId definitionId, boolean active) {
        Objects.requireNonNull(definitionId, "definitionId");
        var summary = definitions().stream()
                .filter(value -> value.definitionId().equals(definitionId))
                .findFirst()
                .orElseThrow(() -> new WorkflowNotFoundException("工作流不存在"));
        if (active && summary.publishedVersionId() == null) {
            throw new WorkflowManagementConflictException("工作流发布后才能激活");
        }
        if (summary.active() == active) {
            return summary;
        }
        if (!repository.setActive(definitionId, active, clock.instant())) {
            throw new WorkflowManagementConflictException("工作流激活状态更新冲突，请刷新后重试");
        }
        return definitions().stream()
                .filter(value -> value.definitionId().equals(definitionId))
                .findFirst()
                .orElseThrow(() -> new WorkflowNotFoundException("工作流不存在"));
    }

    @Override
    public List<AgentRoleTemplate> roles() {
        return repository.listRoles();
    }

    @Override
    public AgentRoleTemplate saveRole(SaveAgentRoleCommand command) {
        Objects.requireNonNull(command, "command");
        var now = clock.instant();
        if (command.roleTemplateId() == null) {
            var role = role(command, new AgentRoleTemplateId(idGenerator.next("role_")), 0, now, now);
            return repository.createRole(role);
        }
        var existing = repository.findRole(command.roleTemplateId())
                .orElseThrow(() -> new WorkflowNotFoundException("角色不存在"));
        if (existing.builtIn()) {
            throw new WorkflowManagementConflictException("内置角色不可原地修改，请复制后调整");
        }
        if (command.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion is required when updating a role");
        }
        var role = role(
                command,
                existing.roleTemplateId(),
                command.expectedVersion() + 1,
                existing.createdAt(),
                now);
        return repository.updateRole(role, command.expectedVersion())
                .orElseThrow(() -> new WorkflowManagementConflictException("角色已被修改，请刷新后重试"));
    }

    @Override
    public void deleteRole(AgentRoleTemplateId roleTemplateId, long expectedVersion) {
        var existing = repository.findRole(roleTemplateId)
                .orElseThrow(() -> new WorkflowNotFoundException("角色不存在"));
        if (existing.builtIn()) {
            throw new WorkflowManagementConflictException("内置角色不可删除");
        }
        if (!repository.deleteRole(roleTemplateId, expectedVersion)) {
            throw new WorkflowManagementConflictException("角色正在被使用或版本已变化");
        }
    }

    private static AgentRoleTemplate role(
            SaveAgentRoleCommand command,
            AgentRoleTemplateId roleTemplateId,
            long version,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {
        return new AgentRoleTemplate(
                roleTemplateId,
                command.displayName(),
                command.objective(),
                command.systemPrompt(),
                command.userPromptTemplate(),
                command.outputContract(),
                command.defaultProviderProfileId(),
                command.defaultModelName(),
                command.defaultReasoningEffort(),
                false,
                version,
                createdAt,
                updatedAt);
    }

    private static String checksum(SaveWorkflowDraftCommand command) {
        var canonical = new StringBuilder()
                .append(command.defaultDebateRounds()).append('|')
                .append(command.maximumSteps()).append('|')
                .append(command.maximumDuration()).append('|')
                .append(command.maximumTokens()).append('|')
                .append(command.maximumCostUsd().stripTrailingZeros().toPlainString()).append('|')
                .append(command.failurePolicy()).append('\n');
        command.nodes().stream()
                .sorted(Comparator.comparing(node -> node.nodeId().value()))
                .forEach(node -> appendNode(canonical, node));
        command.edges().stream()
                .sorted(Comparator.comparing(edge -> edge.edgeId().value()))
                .forEach(edge -> appendEdge(canonical, edge));
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void appendNode(StringBuilder canonical, WorkflowNodeDefinition node) {
        canonical.append("node|").append(node.nodeId().value()).append('|')
                .append(node.nodeType()).append('|').append(node.displayName()).append('|')
                .append(node.roleName()).append('|').append(node.roleTemplateId()).append('|')
                .append(node.primaryAiBinding()).append('|').append(node.fallbackAiBinding()).append('|')
                .append(node.systemPrompt()).append('|')
                .append(node.userPromptTemplate()).append('|').append(node.outputContract()).append('|')
                .append(node.contextMode()).append('|').append(node.contextHistoryRounds()).append('|')
                .append(node.contextMaximumMessages()).append('|').append(node.maximumOutputTokens()).append('|')
                .append(node.timeoutSeconds()).append('|').append(node.retryPolicy()).append('|')
                .append(node.operation()).append('|').append(node.position()).append('|')
                .append(node.enabled()).append('\n');
    }

    private static void appendEdge(StringBuilder canonical, WorkflowEdgeDefinition edge) {
        canonical.append("edge|").append(edge.edgeId().value()).append('|')
                .append(edge.sourceNodeId().value()).append('|').append(edge.targetNodeId().value()).append('|')
                .append(edge.activationMode()).append('|').append(edge.contextMode()).append('|')
                .append(condition(edge.condition())).append('|').append(edge.loopEdge()).append('|')
                .append(edge.maximumTraversals()).append('\n');
    }

    private static String condition(WorkflowCondition condition) {
        if (condition == null) {
            return "";
        }
        var operand = switch (condition.operand()) {
            case null -> "";
            case TextConditionOperand value -> "text:" + value.value();
            case DecimalConditionOperand value -> "decimal:" + value.value().toPlainString();
            case BooleanConditionOperand value -> "boolean:" + value.value();
            case TextListConditionOperand value -> "list:" + String.join("\u001f", value.values());
        };
        return condition.field() + ':' + condition.operator() + ':' + operand;
    }

    private static String optional(String value, int maximumLength) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException("description is too long");
        }
        return normalized;
    }
}
