package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.AgentRoleTemplate;
import io.omnnu.finbot.domain.workflow.AgentRoleTemplateId;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionId;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.util.List;

public interface WorkflowManagementUseCase {
    List<WorkflowDefinitionSummary> definitions();

    WorkflowDefinitionVersion version(WorkflowVersionId versionId);

    WorkflowDefinitionVersion saveDraft(SaveWorkflowDraftCommand command);

    WorkflowDefinitionVersion publish(WorkflowVersionId versionId);

    WorkflowDefinitionVersion rollback(WorkflowDefinitionId definitionId, WorkflowVersionId targetVersionId);

    List<AgentRoleTemplate> roles();

    AgentRoleTemplate saveRole(SaveAgentRoleCommand command);

    void deleteRole(AgentRoleTemplateId roleTemplateId, long expectedVersion);
}
