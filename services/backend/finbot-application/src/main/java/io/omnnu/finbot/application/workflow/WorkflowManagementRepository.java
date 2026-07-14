package io.omnnu.finbot.application.workflow;

import io.omnnu.finbot.domain.workflow.AgentRoleTemplate;
import io.omnnu.finbot.domain.workflow.AgentRoleTemplateId;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionId;
import io.omnnu.finbot.domain.workflow.WorkflowDefinitionVersion;
import io.omnnu.finbot.domain.workflow.WorkflowVersionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkflowManagementRepository {
    List<WorkflowDefinitionSummary> listDefinitions();

    Optional<WorkflowDefinitionVersion> findVersion(WorkflowVersionId versionId);

    Optional<WorkflowDefinitionVersion> findPublished(WorkflowDefinitionId definitionId);

    int nextVersionNumber(WorkflowDefinitionId definitionId);

    WorkflowDefinitionVersion saveDraft(
            String name,
            String description,
            boolean builtIn,
            WorkflowDefinitionVersion version,
            String expectedChecksum,
            Instant updatedAt);

    WorkflowDefinitionVersion publish(WorkflowVersionId versionId, Instant publishedAt);

    List<AgentRoleTemplate> listRoles();

    Optional<AgentRoleTemplate> findRole(AgentRoleTemplateId roleTemplateId);

    AgentRoleTemplate createRole(AgentRoleTemplate role);

    Optional<AgentRoleTemplate> updateRole(AgentRoleTemplate role, long expectedVersion);

    boolean deleteRole(AgentRoleTemplateId roleTemplateId, long expectedVersion);
}
