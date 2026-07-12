import { MarkerType, type Edge, type Node } from '@xyflow/react';

import type {
  AIConfigPayload,
  CouncilContextPolicyConfig,
  CouncilRetryPolicyConfig,
  CouncilRoleConfig,
  CouncilTemplateConfig,
  CouncilWorkflowEdgeConfig,
  CouncilWorkflowNodeConfig,
  CouncilWorkflowNodeType,
} from './types';

export type AIConfigForm = Pick<AIConfigPayload, 'sites' | 'task_bindings' | 'prompts' | 'council_templates' | 'experiments'>;

export interface WorkflowNodeData extends Record<string, unknown> {
  label: string;
  nodeType: CouncilWorkflowNodeType;
  provider: string;
  model: string;
  reasoning: string;
  operation: string;
  enabled: boolean;
}

export type WorkflowFlowNode = Node<WorkflowNodeData, 'workflowNode'>;
export type WorkflowFlowEdge = Edge;

const NODE_LABELS: Record<CouncilWorkflowNodeType, string> = {
  input: '研究输入',
  router: '条件路由',
  deterministic: '确定性处理',
  agent: 'AI 角色',
  gate: '质量门禁',
  subflow: '子流程',
  human_review: '人工复核',
  aggregator: '结果聚合',
  chair: '主席裁决',
};

export const DEFAULT_OPERATIONS: Partial<Record<CouncilWorkflowNodeType, string>> = {
  router: 'research_router',
  deterministic: 'market_snapshot',
  gate: 'research_gap_gate',
  subflow: 'evidence_followup',
  human_review: 'investment_committee_review',
  aggregator: 'position_review_merge',
};

export function workflowNodeLabel(node: CouncilWorkflowNodeConfig, template: CouncilTemplateConfig): string {
  if (node.node_type === 'input') return NODE_LABELS.input;
  if (node.node_type === 'chair') return template.chair.display_name || NODE_LABELS.chair;
  if ((node.node_type === 'agent' || node.node_type === 'aggregator') && node.role_id) {
    return template.roles.find((role) => role.role_id === node.role_id)?.display_name || String(node.role_id);
  }
  return node.operation ? `${NODE_LABELS[node.node_type]} · ${operationLabel(node.operation)}` : NODE_LABELS[node.node_type];
}

export function toFlowNodes(template: CouncilTemplateConfig, aiForm: AIConfigForm): WorkflowFlowNode[] {
  return template.workflow.nodes.map((node) => {
    const role = node.role_id ? template.roles.find((item) => item.role_id === node.role_id) : null;
    const binding = node.node_type === 'chair' ? template.chair : role;
    const site = aiForm.sites.find((item) => item.site_id === binding?.site_id);
    const isAiNode = Boolean(binding);
    return {
      id: node.node_id,
      type: 'workflowNode',
      deletable: !['input', 'chair'].includes(node.node_type),
      position: { ...node.position },
      data: {
        label: workflowNodeLabel(node, template),
        nodeType: node.node_type,
        provider: isAiNode ? site?.display_name || binding?.site_id || '未绑定' : '内部执行器',
        model: isAiNode ? binding?.model || '站点默认' : '-',
        reasoning: isAiNode ? reasoningLabel(binding?.reasoning_effort || 'provider_default') : '-',
        operation: node.operation ? operationLabel(node.operation) : '',
        enabled: node.node_type === 'input' || node.node_type === 'chair' || !role || role.enabled,
      },
    };
  });
}

export function toFlowEdges(edges: CouncilWorkflowEdgeConfig[]): WorkflowFlowEdge[] {
  return edges.map((edge) => {
    const condition = edge.condition ? conditionSummary(edge.condition.field, edge.condition.operator, edge.condition.value) : '';
    const label = [edge.loop ? `循环 x${edge.max_traversals || 1}` : '', condition].filter(Boolean).join(' · ');
    return {
      id: edge.edge_id,
      source: edge.source_node_id,
      target: edge.target_node_id,
      type: 'smoothstep',
      label,
      animated: Boolean(edge.loop),
      markerEnd: { type: MarkerType.ArrowClosed },
      style: { strokeWidth: edge.loop ? 2.2 : 1.6, stroke: edge.loop ? '#b26a00' : edge.condition ? '#2f7d5c' : '#64748b' },
      labelStyle: { fontSize: 11, fontWeight: 700, fill: edge.loop ? '#8b5300' : '#40505f' },
      labelBgStyle: { fill: '#ffffff', fillOpacity: 0.92 },
    };
  });
}

export function validateWorkflow(template: CouncilTemplateConfig): string[] {
  const { nodes, edges } = template.workflow;
  const errors: string[] = [];
  const nodeIds = nodes.map((node) => node.node_id);
  const edgeIds = edges.map((edge) => edge.edge_id);
  const nodeSet = new Set(nodeIds);
  const nodesById = new Map(nodes.map((node) => [node.node_id, node]));
  const inputNodes = nodes.filter((node) => node.node_type === 'input');
  const chairNodes = nodes.filter((node) => node.node_type === 'chair');
  const roleNodes = nodes.filter((node) => ['agent', 'aggregator'].includes(node.node_type) && node.role_id);
  if (nodeSet.size !== nodeIds.length) errors.push('节点 ID 不能重复。');
  if (new Set(edgeIds).size !== edgeIds.length) errors.push('连线 ID 不能重复。');
  if (inputNodes.length !== 1) errors.push('必须且只能有一个研究输入节点。');
  if (chairNodes.length !== 1) errors.push('必须且只能有一个主席裁决节点。');
  const expectedRoles = new Set(template.roles.map((role) => role.role_id));
  const mappedRoles = roleNodes.map((node) => String(node.role_id || ''));
  if (mappedRoles.length !== new Set(mappedRoles).size || mappedRoles.some((roleId) => !expectedRoles.has(roleId)) || mappedRoles.length !== expectedRoles.size) {
    errors.push('每个 AI 角色必须且只能映射一个 Agent 或 AI 聚合节点。');
  }
  if (chairNodes[0] && chairNodes[0].role_id !== template.chair.role_id) errors.push('主席节点与 Chair 角色引用不一致。');

  const phaseIds = new Set(template.phases.map((phase) => phase.phase_id));
  for (const node of nodes) {
    if (requiresOperation(node.node_type) && !node.operation) errors.push(`${node.node_id} 必须选择受控操作。`);
    if ((node.phase_ids || []).some((phaseId) => !phaseIds.has(phaseId))) errors.push(`${node.node_id} 引用了不存在的辩论阶段。`);
    if ((node.context_policy?.source_node_ids || []).some((nodeId) => !nodeSet.has(nodeId))) errors.push(`${node.node_id} 的上下文来源已不存在。`);
    if (template.workflow.version === 1 && hasV2NodeSettings(node)) errors.push(`${node.node_id} 使用了 v2 节点能力，请升级工作流。`);
  }

  const pairs = new Set<string>();
  for (const edge of edges) {
    const source = nodesById.get(edge.source_node_id);
    const target = nodesById.get(edge.target_node_id);
    if (!source || !target) {
      errors.push('存在连接到已删除节点的连线。');
      continue;
    }
    if (source.node_id === target.node_id) errors.push('节点不能连接到自身。');
    const pair = `${source.node_id}->${target.node_id}`;
    if (pairs.has(pair)) errors.push('相同节点之间不能重复连线。');
    pairs.add(pair);
    if (source.node_type === 'chair') errors.push('主席节点不能继续连接下游。');
    if (target.node_type === 'input') errors.push('研究输入节点不能接收上游连线。');
    if (edge.loop && (!edge.condition || template.workflow.version < 2)) errors.push(`${edge.edge_id} 的循环必须使用 v2 且设置退出条件。`);
    if (template.workflow.version === 1 && hasV2EdgeSettings(edge)) errors.push(`${edge.edge_id} 使用了 v2 连线能力，请升级工作流。`);
  }

  const forwardEdges = edges.filter((edge) => !edge.loop);
  const cyclic = hasCycle(nodes, forwardEdges);
  if (cyclic) errors.push('非循环连线必须保持有向无环。');
  for (const edge of edges.filter((item) => item.loop)) {
    if (!pathExists(edge.target_node_id, edge.source_node_id, forwardEdges)) errors.push(`${edge.edge_id} 没有闭合一条既有正向路径。`);
  }
  if (inputNodes[0] && chairNodes[0] && !cyclic) {
    for (const node of nodes.filter((item) => !['input', 'chair'].includes(item.node_type))) {
      if (!pathExists(inputNodes[0].node_id, node.node_id, forwardEdges)) errors.push(`${workflowNodeLabel(node, template)} 无法从研究输入到达。`);
      if (!pathExists(node.node_id, chairNodes[0].node_id, forwardEdges)) errors.push(`${workflowNodeLabel(node, template)} 无法到达主席裁决。`);
    }
  }
  const roundPolicy = normalizedRoundPolicy(template);
  if (roundPolicy.min_rounds > roundPolicy.default_rounds || roundPolicy.default_rounds > roundPolicy.max_rounds) errors.push('默认轮次必须位于最小和最大轮次之间。');
  for (const phase of template.phases) {
    if (phase.scheduling_mode === 'moderated' && !phase.moderator_role_id) errors.push(`${phase.label} 使用主持模式但未指定主持角色。`);
  }
  return [...new Set(errors)];
}

export function connectionProblem(template: CouncilTemplateConfig, sourceId: string, targetId: string): string | null {
  const source = template.workflow.nodes.find((node) => node.node_id === sourceId);
  const target = template.workflow.nodes.find((node) => node.node_id === targetId);
  if (!source || !target) return '连线端点不存在。';
  if (sourceId === targetId) return '节点不能连接到自身。';
  if (source.node_type === 'chair') return '主席节点不能继续连接下游。';
  if (target.node_type === 'input') return '研究输入节点不能接收上游连线。';
  if (template.workflow.edges.some((edge) => edge.source_node_id === sourceId && edge.target_node_id === targetId)) return '这两个节点已经连接。';
  if (template.workflow.version === 1 && connectionCreatesLoop(template, sourceId, targetId)) return '该连线会形成循环；先升级到工作流 v2。';
  return null;
}

export function connectionCreatesLoop(template: CouncilTemplateConfig, sourceId: string, targetId: string): boolean {
  return pathExists(targetId, sourceId, template.workflow.edges.filter((edge) => !edge.loop));
}

export function upgradeTemplateToV2(template: CouncilTemplateConfig): CouncilTemplateConfig {
  const copy = cloneTemplate(template);
  return {
    ...copy,
    description: copy.description || '可自由编排角色、上下文、分支、循环与人工门禁的研究工作流。',
    cost_tier: copy.cost_tier || 'standard',
    failure_policy: copy.failure_policy || 'stop',
    builtin: copy.builtin || false,
    template_kind: copy.template_kind || 'custom',
    recommended_for: [...(copy.recommended_for || [])],
    round_policy: normalizedRoundPolicy(copy),
    roles: copy.roles.map((role) => ({ ...role, reasoning_effort: role.reasoning_effort || 'provider_default' })),
    chair: { ...copy.chair, reasoning_effort: copy.chair.reasoning_effort || 'provider_default' },
    workflow: {
      ...copy.workflow,
      version: 2,
      max_steps: copy.workflow.max_steps || 100,
      max_loop_iterations: copy.workflow.max_loop_iterations || 3,
    },
  };
}

export function defaultRole(roleId: string, index: number, siteId: string | null, aiForm: AIConfigForm): CouncilRoleConfig {
  const site = aiForm.sites.find((item) => item.site_id === siteId);
  return {
    role_id: roleId,
    display_name: `分析角色 ${index + 1}`,
    stance: 'neutral',
    objective: '独立审查输入、回应上游观点并保留证据引用。',
    enabled: true,
    order: (index + 1) * 10,
    site_id: siteId,
    protocol: 'chat',
    model: site?.default_chat_model || site?.chat_models[0] || null,
    reasoning_effort: 'medium',
    fallback_site_ids: [],
    system_prompt: '不得虚构事实；必须列出证据、反证和失效条件，不展示隐藏推理过程。',
    user_prompt_template: '{payload_json}',
  };
}

export function defaultControlNode(
  nodeType: Exclude<CouncilWorkflowNodeType, 'input' | 'agent' | 'chair'>,
  nodeId: string,
  position: { x: number; y: number },
): CouncilWorkflowNodeConfig {
  return {
    node_id: nodeId,
    node_type: nodeType,
    role_id: null,
    operation: DEFAULT_OPERATIONS[nodeType],
    position,
    context_policy: defaultContextPolicy(),
    retry_policy: defaultRetryPolicy(),
  };
}

export function cloneTemplate(template: CouncilTemplateConfig): CouncilTemplateConfig {
  return {
    ...template,
    recommended_for: [...(template.recommended_for || [])],
    round_policy: template.round_policy ? {
      ...template.round_policy,
      stop_condition: template.round_policy.stop_condition ? { ...template.round_policy.stop_condition } : undefined,
    } : undefined,
    roles: template.roles.map((role) => ({ ...role, fallback_site_ids: [...role.fallback_site_ids] })),
    phases: template.phases.map((phase) => ({
      ...phase,
      participant_role_ids: [...(phase.participant_role_ids || [])],
    })),
    chair: { ...template.chair, fallback_site_ids: [...template.chair.fallback_site_ids] },
    workflow: {
      ...template.workflow,
      nodes: template.workflow.nodes.map((node) => ({
        ...node,
        position: { ...node.position },
        phase_ids: [...(node.phase_ids || [])],
        config: node.config ? structuredClone(node.config) : undefined,
        context_policy: node.context_policy ? {
          ...node.context_policy,
          source_node_ids: [...node.context_policy.source_node_ids],
          content_fields: [...node.context_policy.content_fields],
        } : undefined,
        retry_policy: node.retry_policy ? { ...node.retry_policy } : undefined,
      })),
      edges: template.workflow.edges.map((edge) => ({
        ...edge,
        condition: edge.condition ? { ...edge.condition } : undefined,
      })),
    },
  };
}

export function normalizedRoundPolicy(template: CouncilTemplateConfig) {
  return template.round_policy || { default_rounds: 3, min_rounds: 1, max_rounds: 8 };
}

export function defaultContextPolicy(): CouncilContextPolicyConfig {
  return { mode: 'upstream', source_node_ids: [], history_rounds: 3, max_messages: 24, content_fields: [] };
}

export function defaultRetryPolicy(): CouncilRetryPolicyConfig {
  return { max_attempts: 1, backoff_seconds: 0 };
}

export function nextRoleOrder(roles: CouncilRoleConfig[]): number {
  return Math.max(0, ...roles.map((role) => role.order)) + 10;
}

export function splitCsv(value: string): string[] {
  return value.split(',').map((item) => item.trim()).filter(Boolean);
}

export function nextIdentifier(prefix: string, existingIds: string[]): string {
  const existing = new Set(existingIds);
  let suffix = 1;
  while (existing.has(`${prefix}_${suffix}`)) suffix += 1;
  return `${prefix}_${suffix}`;
}

export function uniqueIdentifier(preferred: string, existingIds: string[]): string {
  if (!existingIds.includes(preferred)) return preferred;
  return nextIdentifier(preferred, existingIds);
}

export function operationLabel(operation: string): string {
  return operation.replace(/_/g, ' ');
}

export function reasoningLabel(reasoning: string): string {
  const labels: Record<string, string> = { provider_default: '厂商默认', none: '关闭', minimal: '极低', low: '低', medium: '中', high: '高', xhigh: '极高' };
  return labels[reasoning] || reasoning;
}

function conditionSummary(field: string, operator: string, value: unknown): string {
  return `${field} ${operator}${value === undefined ? '' : ` ${String(value)}`}`;
}

function requiresOperation(nodeType: CouncilWorkflowNodeType): boolean {
  return ['router', 'deterministic', 'gate', 'subflow', 'human_review'].includes(nodeType);
}

function hasV2NodeSettings(node: CouncilWorkflowNodeConfig): boolean {
  return !['input', 'agent', 'chair'].includes(node.node_type)
    || Boolean(node.operation)
    || Boolean(node.phase_ids?.length)
    || Boolean(node.config && Object.keys(node.config).length)
    || Boolean(node.context_policy)
    || Boolean(node.retry_policy);
}

function hasV2EdgeSettings(edge: CouncilWorkflowEdgeConfig): boolean {
  return Boolean(edge.condition || edge.activation_group || edge.loop || (edge.activation_mode && edge.activation_mode !== 'all') || (edge.context_mode && edge.context_mode !== 'inherit'));
}

function hasCycle(nodes: CouncilWorkflowNodeConfig[], edges: CouncilWorkflowEdgeConfig[]): boolean {
  const indegrees = new Map(nodes.map((node) => [node.node_id, 0]));
  const adjacency = new Map(nodes.map((node) => [node.node_id, [] as string[]]));
  for (const edge of edges) {
    if (!indegrees.has(edge.source_node_id) || !indegrees.has(edge.target_node_id)) continue;
    adjacency.get(edge.source_node_id)?.push(edge.target_node_id);
    indegrees.set(edge.target_node_id, (indegrees.get(edge.target_node_id) || 0) + 1);
  }
  const queue = [...indegrees.entries()].filter(([, degree]) => degree === 0).map(([nodeId]) => nodeId);
  let visited = 0;
  while (queue.length > 0) {
    const current = queue.shift()!;
    visited += 1;
    for (const target of adjacency.get(current) || []) {
      const nextDegree = (indegrees.get(target) || 0) - 1;
      indegrees.set(target, nextDegree);
      if (nextDegree === 0) queue.push(target);
    }
  }
  return visited !== nodes.length;
}

function pathExists(sourceId: string, targetId: string, edges: CouncilWorkflowEdgeConfig[]): boolean {
  const adjacency = new Map<string, string[]>();
  for (const edge of edges) adjacency.set(edge.source_node_id, [...(adjacency.get(edge.source_node_id) || []), edge.target_node_id]);
  const pending = [sourceId];
  const visited = new Set<string>();
  while (pending.length > 0) {
    const current = pending.pop()!;
    if (current === targetId) return true;
    if (visited.has(current)) continue;
    visited.add(current);
    pending.push(...(adjacency.get(current) || []));
  }
  return false;
}
