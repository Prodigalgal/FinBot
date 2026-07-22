package io.omnnu.finbot.application.workflow.port.in;

import io.omnnu.finbot.application.workflow.dto.SaveAgentRoleCommand;
import io.omnnu.finbot.application.workflow.dto.SaveWorkflowDraftCommand;
import io.omnnu.finbot.application.workflow.dto.WorkflowDefinitionSummary;

import io.omnnu.finbot.domain.workflow.AgentRoleTemplate;
import io.omnnu.finbot.domain.workflow.AgentRoleTemplateId;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionId;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.util.List;

public interface WorkflowManagementUseCase {
    List<WorkflowDefinitionSummary> definitions();

    WorkflowDefinitionVersion version(WorkflowVersionId versionId);

    List<WorkflowDefinitionVersion> versions(WorkflowDefinitionId definitionId);

    WorkflowDefinitionVersion saveDraft(SaveWorkflowDraftCommand command);

    WorkflowDefinitionVersion publish(WorkflowVersionId versionId);

    WorkflowDefinitionVersion rollback(WorkflowDefinitionId definitionId, WorkflowVersionId targetVersionId);

    WorkflowDefinitionSummary setActive(WorkflowDefinitionId definitionId, boolean active);

    List<AgentRoleTemplate> roles();

    AgentRoleTemplate saveRole(SaveAgentRoleCommand command);

    void deleteRole(AgentRoleTemplateId roleTemplateId, long expectedVersion);
}
