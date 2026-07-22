package io.omnnu.finbot.api.workflow.controller;

import io.omnnu.finbot.api.workflow.dto.WorkflowRequests;
import io.omnnu.finbot.api.workflow.dto.WorkflowResponses;

import io.omnnu.finbot.api.workflow.dto.WorkflowRequests.SaveDraftRequest;
import io.omnnu.finbot.api.workflow.dto.WorkflowRequests.SaveRoleRequest;
import io.omnnu.finbot.api.workflow.dto.WorkflowRequests.ActivationRequest;
import io.omnnu.finbot.api.workflow.dto.WorkflowResponses.DefinitionSummaryResponse;
import io.omnnu.finbot.api.workflow.dto.WorkflowResponses.RoleResponse;
import io.omnnu.finbot.api.workflow.dto.WorkflowResponses.SchemaResponse;
import io.omnnu.finbot.api.workflow.dto.WorkflowResponses.VersionResponse;
import io.omnnu.finbot.application.workflow.dto.SaveAgentRoleCommand;
import io.omnnu.finbot.application.workflow.dto.SaveWorkflowDraftCommand;
import io.omnnu.finbot.application.workflow.port.in.WorkflowManagementUseCase;
import io.omnnu.finbot.domain.configuration.AiProviderProfileId;
import io.omnnu.finbot.domain.workflow.AgentRoleTemplateId;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionId;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2")
public final class WorkflowManagementController {
    private final WorkflowManagementUseCase useCase;

    public WorkflowManagementController(WorkflowManagementUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
    }

    @GetMapping("/workflow-schema")
    public SchemaResponse schema() {
        return SchemaResponse.current();
    }

    @GetMapping("/workflow-definitions")
    public List<DefinitionSummaryResponse> definitions() {
        return useCase.definitions().stream().map(DefinitionSummaryResponse::from).toList();
    }

    @GetMapping("/workflow-versions/{versionId}")
    public VersionResponse version(@PathVariable String versionId) {
        return VersionResponse.from(useCase.version(new WorkflowVersionId(versionId)));
    }

    @GetMapping("/workflow-definitions/{definitionId}/versions")
    public List<VersionResponse> versions(@PathVariable String definitionId) {
        return useCase.versions(new WorkflowDefinitionId(definitionId)).stream()
                .map(VersionResponse::from)
                .toList();
    }

    @PutMapping("/workflow-drafts")
    public VersionResponse saveDraft(@Valid @RequestBody SaveDraftRequest request) {
        var command = new SaveWorkflowDraftCommand(
                optionalDefinitionId(request.definitionId()),
                optionalVersionId(request.versionId()),
                request.name(),
                request.description(),
                request.defaultDebateRounds(),
                request.debateProtocol() == null
                        ? null
                        : request.debateProtocol().toDomain(),
                request.maximumSteps(),
                Duration.ofSeconds(request.maximumDurationSeconds()),
                request.maximumTokens(),
                request.maximumCostUsd(),
                request.failurePolicy(),
                request.expectedChecksum(),
                request.nodes().stream().map(WorkflowRequests.NodeRequest::toDomain).toList(),
                request.edges().stream().map(WorkflowRequests.EdgeRequest::toDomain).toList());
        return VersionResponse.from(useCase.saveDraft(command));
    }

    @PostMapping("/workflow-versions/{versionId}/publish")
    public VersionResponse publish(@PathVariable String versionId) {
        return VersionResponse.from(useCase.publish(new WorkflowVersionId(versionId)));
    }

    @PostMapping("/workflow-definitions/{definitionId}/rollback/{targetVersionId}")
    public VersionResponse rollback(
            @PathVariable String definitionId,
            @PathVariable String targetVersionId) {
        return VersionResponse.from(useCase.rollback(
                new WorkflowDefinitionId(definitionId),
                new WorkflowVersionId(targetVersionId)));
    }

    @PutMapping("/workflow-definitions/{definitionId}/activation")
    public DefinitionSummaryResponse setActive(
            @PathVariable String definitionId,
            @Valid @RequestBody ActivationRequest request) {
        return DefinitionSummaryResponse.from(useCase.setActive(
                new WorkflowDefinitionId(definitionId),
                request.active()));
    }

    @GetMapping("/agent-roles")
    public List<RoleResponse> roles() {
        return useCase.roles().stream().map(RoleResponse::from).toList();
    }

    @PostMapping("/agent-roles")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleResponse createRole(@Valid @RequestBody SaveRoleRequest request) {
        return RoleResponse.from(useCase.saveRole(roleCommand(null, request)));
    }

    @PutMapping("/agent-roles/{roleTemplateId}")
    public RoleResponse updateRole(
            @PathVariable String roleTemplateId,
            @Valid @RequestBody SaveRoleRequest request) {
        return RoleResponse.from(useCase.saveRole(roleCommand(
                new AgentRoleTemplateId(roleTemplateId),
                request)));
    }

    @DeleteMapping("/agent-roles/{roleTemplateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRole(
            @PathVariable String roleTemplateId,
            @RequestParam @PositiveOrZero long expectedVersion) {
        useCase.deleteRole(new AgentRoleTemplateId(roleTemplateId), expectedVersion);
    }

    private static SaveAgentRoleCommand roleCommand(
            AgentRoleTemplateId roleTemplateId,
            SaveRoleRequest request) {
        return new SaveAgentRoleCommand(
                roleTemplateId,
                request.displayName(),
                request.objective(),
                request.systemPrompt(),
                request.userPromptTemplate(),
                request.outputContract(),
                new AiProviderProfileId(request.defaultProviderProfileId()),
                request.defaultModelName(),
                request.defaultReasoningEffort(),
                request.expectedVersion());
    }

    private static WorkflowDefinitionId optionalDefinitionId(String value) {
        return value == null || value.isBlank() ? null : new WorkflowDefinitionId(value);
    }

    private static WorkflowVersionId optionalVersionId(String value) {
        return value == null || value.isBlank() ? null : new WorkflowVersionId(value);
    }
}
