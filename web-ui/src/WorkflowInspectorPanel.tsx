import {
  Alert,
  Box,
  Button,
  Chip,
  Divider,
  FormControlLabel,
  IconButton,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import HubOutlinedIcon from '@mui/icons-material/HubOutlined';
import KeyOutlinedIcon from '@mui/icons-material/KeyOutlined';
import LinkOutlinedIcon from '@mui/icons-material/LinkOutlined';
import PersonOutlineIcon from '@mui/icons-material/PersonOutline';
import SettingsOutlinedIcon from '@mui/icons-material/SettingsOutlined';
import { useEffect, useState } from 'react';

import type {
  CouncilChairConfig,
  CouncilConditionConfig,
  CouncilPhaseConfig,
  CouncilRoleConfig,
  CouncilTemplateConfig,
  CouncilWorkflowEdgeConfig,
  CouncilWorkflowNodeConfig,
  WorkflowSchemaPayload,
} from './types';
import {
  defaultContextPolicy,
  defaultRetryPolicy,
  nextIdentifier,
  operationLabel,
  reasoningLabel,
  splitCsv,
  type AIConfigForm,
} from './councilWorkflowUtils';

const inputSx = {
  '& .MuiOutlinedInput-root': { minHeight: 38, borderRadius: 1, bgcolor: 'background.paper' },
  '& .MuiInputBase-input, & .MuiSelect-select': { fontSize: 13, lineHeight: 1.45 },
};

export type WorkflowInspectorMode = 'node' | 'edge' | 'workflow';

export function WorkflowInspectorPanel({
  mode,
  node,
  edge,
  template,
  aiForm,
  schema,
  onCommit,
  onDeleteNode,
  onDeleteEdge,
}: {
  mode: WorkflowInspectorMode;
  node: CouncilWorkflowNodeConfig | null;
  edge: CouncilWorkflowEdgeConfig | null;
  template: CouncilTemplateConfig;
  aiForm: AIConfigForm;
  schema: WorkflowSchemaPayload | null;
  onCommit: (next: CouncilTemplateConfig) => void;
  onDeleteNode: () => void;
  onDeleteEdge: () => void;
}) {
  if (mode === 'workflow') return <WorkflowInspector template={template} onCommit={onCommit} />;
  if (mode === 'edge') {
    return edge
      ? <EdgeInspector edge={edge} template={template} schema={schema} onCommit={onCommit} onDelete={onDeleteEdge} />
      : <EmptyInspector icon={<LinkOutlinedIcon />} text="在画布中选择一条连线以配置条件、上下文和循环。" />;
  }
  return node
    ? <NodeInspector node={node} template={template} aiForm={aiForm} schema={schema} onCommit={onCommit} onDelete={onDeleteNode} />
    : <EmptyInspector icon={<PersonOutlineIcon />} text="在画布中选择一个节点以配置角色和执行参数。" />;
}

function NodeInspector({
  node,
  template,
  aiForm,
  schema,
  onCommit,
  onDelete,
}: {
  node: CouncilWorkflowNodeConfig;
  template: CouncilTemplateConfig;
  aiForm: AIConfigForm;
  schema: WorkflowSchemaPayload | null;
  onCommit: (next: CouncilTemplateConfig) => void;
  onDelete: () => void;
}) {
  const isChair = node.node_type === 'chair';
  const role = node.role_id && !isChair ? template.roles.find((item) => item.role_id === node.role_id) || null : null;
  const chair = isChair ? template.chair : null;
  const binding = role || chair;
  const isAiNode = Boolean(binding);
  const canDelete = !['input', 'chair'].includes(node.node_type) && (!role || template.roles.length > 2);
  const contextPolicy = node.context_policy || defaultContextPolicy();
  const retryPolicy = node.retry_policy || defaultRetryPolicy();

  const updateNode = (patch: Partial<CouncilWorkflowNodeConfig>) => {
    onCommit({
      ...template,
      workflow: {
        ...template.workflow,
        nodes: template.workflow.nodes.map((item) => item.node_id === node.node_id ? { ...item, ...patch } : item),
      },
    });
  };
  const updateRole = (patch: Partial<CouncilRoleConfig>) => {
    if (!role) return;
    onCommit({ ...template, roles: template.roles.map((item) => item.role_id === role.role_id ? { ...item, ...patch } : item) });
  };
  const updateChair = (patch: Partial<CouncilChairConfig>) => {
    if (chair) onCommit({ ...template, chair: { ...chair, ...patch } });
  };
  const updateBinding = (patch: Partial<CouncilRoleConfig & CouncilChairConfig>) => role ? updateRole(patch) : updateChair(patch);

  const protocol = binding?.protocol || 'chat';
  const selectedSite = aiForm.sites.find((site) => site.site_id === binding?.site_id);
  const models = protocol === 'responses' ? selectedSite?.responses_models || [] : selectedSite?.chat_models || [];
  const chooseSite = (siteId: string) => {
    const site = aiForm.sites.find((item) => item.site_id === siteId);
    const model = protocol === 'responses' ? site?.default_responses_model : site?.default_chat_model;
    updateBinding({ site_id: siteId, model: model || null });
  };
  const chooseProtocol = (nextProtocol: string) => {
    const model = nextProtocol === 'responses' ? selectedSite?.default_responses_model : selectedSite?.default_chat_model;
    updateBinding({ protocol: nextProtocol, model: model || null });
  };

  return (
    <Stack spacing={1.25}>
      <InspectorHeading
        icon={isAiNode ? <PersonOutlineIcon color="primary" /> : <HubOutlinedIcon color="secondary" />}
        title={binding?.display_name || nodeTypeText(node.node_type)}
        action={canDelete ? (
          <Tooltip title="删除节点"><IconButton aria-label="删除节点" onClick={onDelete} size="small"><DeleteOutlineIcon fontSize="small" /></IconButton></Tooltip>
        ) : null}
      />
      <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>
        <Chip size="small" label={nodeTypeText(node.node_type)} />
        <Chip size="small" variant="outlined" label={node.node_id} />
      </Stack>

      {node.node_type !== 'input' && (
        <PhaseSelector node={node} template={template} updateNode={updateNode} />
      )}

      {isAiNode && binding && (
        <>
          <Divider><Typography variant="caption">角色与模型</Typography></Divider>
          <TextField label="节点名称" value={binding.display_name} onChange={(event) => updateBinding({ display_name: event.target.value })} sx={inputSx} />
          {role && (
            <>
              <TextField select label="角色立场" value={role.stance} onChange={(event) => updateRole({ stance: event.target.value })} sx={inputSx}>
                <MenuItem value="bullish">看多</MenuItem><MenuItem value="bearish">看空</MenuItem><MenuItem value="market">市场</MenuItem><MenuItem value="risk">风险</MenuItem><MenuItem value="neutral">中立</MenuItem>
              </TextField>
              <FormControlLabel control={<Switch checked={role.enabled} onChange={(event) => updateRole({ enabled: event.target.checked })} />} label="启用角色" />
            </>
          )}
          <TextField select label="厂商 / Key 档案" value={binding.site_id || ''} onChange={(event) => chooseSite(event.target.value)} sx={inputSx}>
            {aiForm.sites.map((site) => (
              <MenuItem key={site.site_id} value={site.site_id}>
                {site.display_name} · {site.api_key_configured ? 'Key 已配置' : '未配置 Key'}
              </MenuItem>
            ))}
          </TextField>
          {selectedSite && (
            <Stack direction="row" spacing={0.75} alignItems="center">
              <KeyOutlinedIcon sx={{ fontSize: 16, color: selectedSite.api_key_configured ? 'success.main' : 'warning.main' }} />
              <Typography variant="caption" color="text.secondary">工作流仅保存站点引用，不保存原始密钥。</Typography>
            </Stack>
          )}
          <TextField select label="调用协议" value={protocol} onChange={(event) => chooseProtocol(event.target.value)} sx={inputSx}>
            <MenuItem value="chat">Chat Completions</MenuItem><MenuItem value="responses">Responses API</MenuItem>
          </TextField>
          <TextField label="模型名称" value={binding.model || ''} onChange={(event) => updateBinding({ model: event.target.value })} select={models.length > 0} sx={inputSx}>
            {models.map((model) => <MenuItem key={model} value={model}>{model}</MenuItem>)}
          </TextField>
          <TextField select label="思考等级" value={binding.reasoning_effort || 'provider_default'} onChange={(event) => updateBinding({ reasoning_effort: event.target.value as CouncilRoleConfig['reasoning_effort'] })} sx={inputSx}>
            {(schema?.reasoning_efforts || ['provider_default', 'none', 'minimal', 'low', 'medium', 'high', 'xhigh']).map((effort) => <MenuItem key={effort} value={effort}>{reasoningLabel(effort)}</MenuItem>)}
          </TextField>
          <TextField label="备用站点 ID" value={(binding.fallback_site_ids || []).join(', ')} onChange={(event) => updateBinding({ fallback_site_ids: splitCsv(event.target.value) })} sx={inputSx} />
          {role && <TextField label="职责目标" value={role.objective} onChange={(event) => updateRole({ objective: event.target.value })} multiline minRows={2} sx={inputSx} />}
          <TextField label="角色提示词" value={binding.system_prompt || ''} onChange={(event) => updateBinding({ system_prompt: event.target.value })} multiline minRows={5} sx={inputSx} />
          <TextField label="输入模板" value={binding.user_prompt_template || ''} onChange={(event) => updateBinding({ user_prompt_template: event.target.value })} multiline minRows={2} sx={inputSx} />
        </>
      )}

      {!isAiNode && node.node_type !== 'input' && (
        <>
          <Divider><Typography variant="caption">受控执行</Typography></Divider>
          <TextField select label="注册操作" value={node.operation || ''} onChange={(event) => updateNode({ operation: event.target.value })} sx={inputSx}>
            {(schema?.operations || []).map((operation) => <MenuItem key={operation} value={operation}>{operationLabel(operation)}</MenuItem>)}
          </TextField>
          <JsonConfigEditor value={node.config || {}} onChange={(config) => updateNode({ config })} />
        </>
      )}

      {node.node_type !== 'input' && (
        <>
          <Divider><Typography variant="caption">上下文与容错</Typography></Divider>
          <TextField select label="上下文策略" value={contextPolicy.mode} onChange={(event) => updateNode({ context_policy: { ...contextPolicy, mode: event.target.value as typeof contextPolicy.mode } })} sx={inputSx}>
            {(schema?.context_modes || ['upstream', 'selected', 'latest', 'claims_only', 'summary', 'none']).map((mode) => <MenuItem key={mode} value={mode}>{contextModeText(mode)}</MenuItem>)}
          </TextField>
          {contextPolicy.mode === 'selected' && (
            <TextField label="指定来源节点 ID" value={contextPolicy.source_node_ids.join(', ')} onChange={(event) => updateNode({ context_policy: { ...contextPolicy, source_node_ids: splitCsv(event.target.value) } })} sx={inputSx} />
          )}
          <Stack direction="row" spacing={1}>
            <TextField type="number" label="保留轮次" value={contextPolicy.history_rounds} onChange={(event) => updateNode({ context_policy: { ...contextPolicy, history_rounds: clampInt(event.target.value, 0, 8) } })} sx={{ flex: 1, ...inputSx }} />
            <TextField type="number" label="最大消息" value={contextPolicy.max_messages} onChange={(event) => updateNode({ context_policy: { ...contextPolicy, max_messages: clampInt(event.target.value, 0, 64) } })} sx={{ flex: 1, ...inputSx }} />
          </Stack>
          <TextField label="保留字段" value={contextPolicy.content_fields.join(', ')} onChange={(event) => updateNode({ context_policy: { ...contextPolicy, content_fields: splitCsv(event.target.value) } })} sx={inputSx} />
          <Stack direction="row" spacing={1}>
            <TextField type="number" label="最大尝试" value={retryPolicy.max_attempts} onChange={(event) => updateNode({ retry_policy: { ...retryPolicy, max_attempts: clampInt(event.target.value, 1, 5) } })} sx={{ flex: 1, ...inputSx }} />
            <TextField type="number" label="退避秒数" value={retryPolicy.backoff_seconds} onChange={(event) => updateNode({ retry_policy: { ...retryPolicy, backoff_seconds: clampNumber(event.target.value, 0, 60) } })} sx={{ flex: 1, ...inputSx }} />
          </Stack>
        </>
      )}
    </Stack>
  );
}

function EdgeInspector({
  edge,
  template,
  schema,
  onCommit,
  onDelete,
}: {
  edge: CouncilWorkflowEdgeConfig;
  template: CouncilTemplateConfig;
  schema: WorkflowSchemaPayload | null;
  onCommit: (next: CouncilTemplateConfig) => void;
  onDelete: () => void;
}) {
  const condition = edge.condition;
  const updateEdge = (patch: Partial<CouncilWorkflowEdgeConfig>) => onCommit({
    ...template,
    workflow: {
      ...template.workflow,
      edges: template.workflow.edges.map((item) => item.edge_id === edge.edge_id ? { ...item, ...patch } : item),
    },
  });
  const updateCondition = (patch: Partial<CouncilConditionConfig>) => {
    const next = { field: 'current.needs_more', operator: 'truthy' as const, ...condition, ...patch };
    updateEdge({ condition: next });
  };
  return (
    <Stack spacing={1.25}>
      <InspectorHeading
        icon={<LinkOutlinedIcon color="primary" />}
        title="信息流转规则"
        action={<Tooltip title="删除连线"><IconButton aria-label="删除连线" onClick={onDelete} size="small"><DeleteOutlineIcon fontSize="small" /></IconButton></Tooltip>}
      />
      <Typography variant="caption" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>{edge.source_node_id} → {edge.target_node_id}</Typography>
      <FormControlLabel
        control={<Switch checked={Boolean(condition)} onChange={(event) => updateEdge({ condition: event.target.checked ? { field: 'current.needs_more', operator: 'truthy' } : undefined })} />}
        label="条件分支"
      />
      {condition && (
        <>
          <TextField label="条件字段" value={condition.field} onChange={(event) => updateCondition({ field: event.target.value })} helperText="例如 current.needs_more 或 input.market_type" sx={inputSx} />
          <TextField select label="运算符" value={condition.operator} onChange={(event) => updateCondition({ operator: event.target.value as CouncilConditionConfig['operator'] })} sx={inputSx}>
            {(schema?.condition_operators || ['exists', 'eq', 'ne', 'in', 'not_in', 'gt', 'gte', 'lt', 'lte', 'contains', 'truthy', 'falsy']).map((operator) => <MenuItem key={operator} value={operator}>{operator}</MenuItem>)}
          </TextField>
          {!['exists', 'truthy', 'falsy'].includes(condition.operator) && (
            <TextField label="比较值" value={stringifyConditionValue(condition.value)} onChange={(event) => updateCondition({ value: parseConditionValue(event.target.value) })} sx={inputSx} />
          )}
        </>
      )}
      <Divider><Typography variant="caption">汇合与上下文</Typography></Divider>
      <TextField label="激活组" value={edge.activation_group || ''} onChange={(event) => updateEdge({ activation_group: event.target.value || null })} helperText="同一目标的连线可使用相同组名" sx={inputSx} />
      <TextField select label="汇合模式" value={edge.activation_mode || 'all'} onChange={(event) => updateEdge({ activation_mode: event.target.value as 'all' | 'any' })} sx={inputSx}>
        <MenuItem value="all">全部满足后执行</MenuItem><MenuItem value="any">任一路径满足即执行</MenuItem>
      </TextField>
      <TextField select label="上下文传递" value={edge.context_mode || 'inherit'} onChange={(event) => updateEdge({ context_mode: event.target.value as NonNullable<CouncilWorkflowEdgeConfig['context_mode']> })} sx={inputSx}>
        {(schema?.edge_context_modes || ['inherit', 'include', 'exclude', 'latest', 'claims_only', 'summary']).map((mode) => <MenuItem key={mode} value={mode}>{edgeContextText(mode)}</MenuItem>)}
      </TextField>
      <Divider><Typography variant="caption">受限循环</Typography></Divider>
      <FormControlLabel control={<Switch checked={Boolean(edge.loop)} onChange={(event) => updateEdge({ loop: event.target.checked, max_traversals: event.target.checked ? edge.max_traversals || 1 : undefined, condition: event.target.checked && !edge.condition ? { field: 'current.needs_more', operator: 'truthy' } : edge.condition })} />} label="将该连线作为循环回边" />
      {edge.loop && (
        <TextField type="number" label="最大回边次数" value={edge.max_traversals || 1} onChange={(event) => updateEdge({ max_traversals: clampInt(event.target.value, 1, 8) })} helperText="达到上限后停止，避免失控循环" sx={inputSx} />
      )}
    </Stack>
  );
}

function WorkflowInspector({ template, onCommit }: { template: CouncilTemplateConfig; onCommit: (next: CouncilTemplateConfig) => void }) {
  const roundPolicy = template.round_policy || { default_rounds: 3, min_rounds: 1, max_rounds: 8 };
  const updatePhase = (phaseId: string, patch: Partial<CouncilPhaseConfig>) => onCommit({
    ...template,
    phases: template.phases.map((phase) => phase.phase_id === phaseId ? { ...phase, ...patch } : phase),
  });
  const addPhase = () => {
    const phaseId = nextIdentifier('debate_phase', template.phases.map((phase) => phase.phase_id));
    onCommit({
      ...template,
      phases: [...template.phases, { phase_id: phaseId, label: `辩论阶段 ${template.phases.length + 1}`, message_type: phaseId, scheduling_mode: 'round_robin', instructions: '审查上一阶段观点，提出反证并修订结论。' }],
    });
  };
  const removePhase = (phaseId: string) => {
    if (template.phases.length <= 1) return;
    onCommit({
      ...template,
      phases: template.phases.filter((phase) => phase.phase_id !== phaseId),
      workflow: { ...template.workflow, nodes: template.workflow.nodes.map((node) => ({ ...node, phase_ids: (node.phase_ids || []).filter((id) => id !== phaseId) })) },
    });
  };
  const movePhase = (phaseId: string, direction: -1 | 1) => {
    const index = template.phases.findIndex((phase) => phase.phase_id === phaseId);
    const target = index + direction;
    if (index < 0 || target < 0 || target >= template.phases.length) return;
    const phases = [...template.phases];
    [phases[index], phases[target]] = [phases[target], phases[index]];
    onCommit({ ...template, phases });
  };
  return (
    <Stack spacing={1.25}>
      <InspectorHeading icon={<SettingsOutlinedIcon color="primary" />} title="工作流设置" />
      <TextField label="工作流名称" value={template.display_name} onChange={(event) => onCommit({ ...template, display_name: event.target.value })} sx={inputSx} />
      <TextField label="用途说明" value={template.description || ''} onChange={(event) => onCommit({ ...template, description: event.target.value })} multiline minRows={2} sx={inputSx} />
      <FormControlLabel control={<Switch checked={template.enabled} onChange={(event) => onCommit({ ...template, enabled: event.target.checked })} />} label="启用工作流" />
      <Stack direction="row" spacing={1}>
        <TextField select label="成本层级" value={template.cost_tier || 'standard'} onChange={(event) => onCommit({ ...template, cost_tier: event.target.value as CouncilTemplateConfig['cost_tier'] })} sx={{ flex: 1, ...inputSx }}>
          <MenuItem value="quick">快速</MenuItem><MenuItem value="standard">标准</MenuItem><MenuItem value="deep">深度</MenuItem>
        </TextField>
        <TextField select label="失败策略" value={template.failure_policy || 'stop'} onChange={(event) => onCommit({ ...template, failure_policy: event.target.value as CouncilTemplateConfig['failure_policy'] })} sx={{ flex: 1, ...inputSx }}>
          <MenuItem value="stop">立即停止</MenuItem><MenuItem value="continue">降级继续</MenuItem><MenuItem value="replan">Director 重规划</MenuItem>
        </TextField>
      </Stack>
      <Stack direction="row" spacing={1}>
        <TextField type="number" label="最小轮次" value={roundPolicy.min_rounds} onChange={(event) => onCommit({ ...template, round_policy: { ...roundPolicy, min_rounds: clampInt(event.target.value, 1, 32) } })} sx={{ flex: 1, ...inputSx }} />
        <TextField type="number" label="默认轮次" value={roundPolicy.default_rounds} onChange={(event) => onCommit({ ...template, round_policy: { ...roundPolicy, default_rounds: clampInt(event.target.value, 1, 32) } })} sx={{ flex: 1, ...inputSx }} />
        <TextField type="number" label="最大轮次" value={roundPolicy.max_rounds} onChange={(event) => onCommit({ ...template, round_policy: { ...roundPolicy, max_rounds: clampInt(event.target.value, 1, 32) } })} sx={{ flex: 1, ...inputSx }} />
      </Stack>
      <Stack direction="row" spacing={1}>
        <TextField type="number" label="最大步骤" value={template.workflow.max_steps || 100} onChange={(event) => onCommit({ ...template, workflow: { ...template.workflow, max_steps: clampInt(event.target.value, 1, 500) } })} sx={{ flex: 1, ...inputSx }} />
        <TextField type="number" label="循环总上限" value={template.workflow.max_loop_iterations || 3} onChange={(event) => onCommit({ ...template, workflow: { ...template.workflow, max_loop_iterations: clampInt(event.target.value, 1, 8) } })} sx={{ flex: 1, ...inputSx }} />
      </Stack>
      <Divider><Typography variant="caption">辩论阶段</Typography></Divider>
      {template.phases.map((phase, index) => (
        <Box key={phase.phase_id} sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1.25 }}>
          <Stack spacing={1}>
            <Stack direction="row" spacing={0.5} alignItems="center">
              <Typography variant="subtitle1" sx={{ flexGrow: 1 }}>{index + 1}. {phase.label}</Typography>
              <IconButton size="small" aria-label="阶段上移" onClick={() => movePhase(phase.phase_id, -1)} disabled={index === 0}><ArrowUpwardIcon fontSize="small" /></IconButton>
              <IconButton size="small" aria-label="阶段下移" onClick={() => movePhase(phase.phase_id, 1)} disabled={index === template.phases.length - 1}><ArrowDownwardIcon fontSize="small" /></IconButton>
              <IconButton size="small" aria-label="删除阶段" onClick={() => removePhase(phase.phase_id)} disabled={template.phases.length <= 1}><DeleteOutlineIcon fontSize="small" /></IconButton>
            </Stack>
            <TextField label="阶段名称" value={phase.label} onChange={(event) => updatePhase(phase.phase_id, { label: event.target.value })} sx={inputSx} />
            <TextField select label="调度方式" value={phase.scheduling_mode} onChange={(event) => updatePhase(phase.phase_id, { scheduling_mode: event.target.value as CouncilPhaseConfig['scheduling_mode'] })} sx={inputSx}>
              <MenuItem value="parallel">并行独立分析</MenuItem><MenuItem value="round_robin">轮流质询</MenuItem><MenuItem value="moderated">主持人调度</MenuItem>
            </TextField>
            <TextField label="参与角色 ID" value={(phase.participant_role_ids || []).join(', ')} onChange={(event) => updatePhase(phase.phase_id, { participant_role_ids: splitCsv(event.target.value) })} helperText="留空表示全部启用角色" sx={inputSx} />
            {phase.scheduling_mode === 'moderated' && (
              <TextField select label="主持角色" value={phase.moderator_role_id || ''} onChange={(event) => updatePhase(phase.phase_id, { moderator_role_id: event.target.value || null })} sx={inputSx}>
                {template.roles.map((role) => <MenuItem key={role.role_id} value={role.role_id}>{role.display_name}</MenuItem>)}
              </TextField>
            )}
            <TextField label="阶段指令" value={phase.instructions} onChange={(event) => updatePhase(phase.phase_id, { instructions: event.target.value })} multiline minRows={2} sx={inputSx} />
          </Stack>
        </Box>
      ))}
      <Button variant="outlined" startIcon={<AddIcon />} onClick={addPhase}>添加辩论阶段</Button>
    </Stack>
  );
}

function PhaseSelector({ node, template, updateNode }: { node: CouncilWorkflowNodeConfig; template: CouncilTemplateConfig; updateNode: (patch: Partial<CouncilWorkflowNodeConfig>) => void }) {
  return (
    <TextField
      select
      label="参与阶段"
      value={node.phase_ids || []}
      onChange={(event) => updateNode({ phase_ids: event.target.value as unknown as string[] })}
      SelectProps={{ multiple: true, renderValue: (selected) => (selected as string[]).length ? (selected as string[]).map((id) => template.phases.find((phase) => phase.phase_id === id)?.label || id).join('、') : '全部阶段' }}
      helperText="留空表示参与全部阶段"
      sx={inputSx}
    >
      {template.phases.map((phase) => <MenuItem key={phase.phase_id} value={phase.phase_id}>{phase.label}</MenuItem>)}
    </TextField>
  );
}

function JsonConfigEditor({ value, onChange }: { value: Record<string, unknown>; onChange: (value: Record<string, unknown>) => void }) {
  const [text, setText] = useState(() => JSON.stringify(value, null, 2));
  const [error, setError] = useState<string | null>(null);
  useEffect(() => setText(JSON.stringify(value, null, 2)), [value]);
  const commit = () => {
    try {
      const parsed = JSON.parse(text) as unknown;
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error('配置必须是 JSON 对象');
      onChange(parsed as Record<string, unknown>);
      setError(null);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    }
  };
  return <TextField label="节点配置 JSON" value={text} onChange={(event) => setText(event.target.value)} onBlur={commit} error={Boolean(error)} helperText={error || '仅传入已注册操作，不执行任意代码或 HTTP'} multiline minRows={4} sx={inputSx} />;
}

function InspectorHeading({ icon, title, action }: { icon: React.ReactNode; title: string; action?: React.ReactNode }) {
  return <Stack direction="row" spacing={1} alignItems="center">{icon}<Typography variant="h3" sx={{ flexGrow: 1, minWidth: 0, overflowWrap: 'anywhere' }}>{title}</Typography>{action}</Stack>;
}

function EmptyInspector({ icon, text }: { icon: React.ReactNode; text: string }) {
  return <Stack spacing={1.25} alignItems="flex-start" sx={{ py: 2, color: 'text.secondary' }}>{icon}<Typography variant="body2">{text}</Typography></Stack>;
}

function nodeTypeText(nodeType: CouncilWorkflowNodeConfig['node_type']): string {
  const labels = { input: '研究输入', router: '条件路由', deterministic: '确定性处理', agent: 'AI 角色', gate: '质量门禁', subflow: '子流程', human_review: '人工复核', aggregator: '结果聚合', chair: '主席裁决' };
  return labels[nodeType];
}

function contextModeText(mode: string): string {
  return ({ upstream: '全部上游', selected: '指定节点', latest: '仅最新消息', claims_only: '仅结构化观点', summary: '摘要上下文', none: '不传递上下文' } as Record<string, string>)[mode] || mode;
}

function edgeContextText(mode: string): string {
  return ({ inherit: '继承节点策略', include: '纳入上下文', exclude: '仅控制流，不传上下文', latest: '仅最新消息', claims_only: '仅结构化观点', summary: '摘要传递' } as Record<string, string>)[mode] || mode;
}

function stringifyConditionValue(value: unknown): string {
  if (value === undefined || value === null) return '';
  return typeof value === 'string' ? value : JSON.stringify(value);
}

function parseConditionValue(value: string): unknown {
  const clean = value.trim();
  if (!clean) return '';
  try { return JSON.parse(clean) as unknown; } catch { return value; }
}

function clampInt(value: string, minimum: number, maximum: number): number {
  const parsed = Number.parseInt(value, 10);
  return Math.max(minimum, Math.min(maximum, Number.isFinite(parsed) ? parsed : minimum));
}

function clampNumber(value: string, minimum: number, maximum: number): number {
  const parsed = Number.parseFloat(value);
  return Math.max(minimum, Math.min(maximum, Number.isFinite(parsed) ? parsed : minimum));
}
