import {
  Alert,
  Box,
  Button,
  Chip,
  Divider,
  IconButton,
  MenuItem,
  Stack,
  Tab,
  Tabs,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
} from '@mui/material';
import AccountTreeIcon from '@mui/icons-material/AccountTree';
import AddIcon from '@mui/icons-material/Add';
import AltRouteIcon from '@mui/icons-material/AltRoute';
import ApprovalOutlinedIcon from '@mui/icons-material/ApprovalOutlined';
import CalculateOutlinedIcon from '@mui/icons-material/CalculateOutlined';
import CenterFocusStrongIcon from '@mui/icons-material/CenterFocusStrong';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import CloudUploadOutlinedIcon from '@mui/icons-material/CloudUploadOutlined';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import GavelIcon from '@mui/icons-material/Gavel';
import HubOutlinedIcon from '@mui/icons-material/HubOutlined';
import InputIcon from '@mui/icons-material/Input';
import MergeTypeIcon from '@mui/icons-material/MergeType';
import PersonOutlineIcon from '@mui/icons-material/PersonOutline';
import PlayCircleOutlineIcon from '@mui/icons-material/PlayCircleOutline';
import RedoIcon from '@mui/icons-material/Redo';
import RestoreIcon from '@mui/icons-material/Restore';
import RuleOutlinedIcon from '@mui/icons-material/RuleOutlined';
import SaveOutlinedIcon from '@mui/icons-material/SaveOutlined';
import SchemaOutlinedIcon from '@mui/icons-material/SchemaOutlined';
import UndoIcon from '@mui/icons-material/Undo';
import UpgradeIcon from '@mui/icons-material/Upgrade';
import {
  Background,
  BackgroundVariant,
  Controls,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  applyEdgeChanges,
  applyNodeChanges,
  type Connection,
  type EdgeChange,
  type NodeChange,
  type NodeProps,
  type ReactFlowInstance,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { Dispatch, ReactNode, SetStateAction } from 'react';
import { useEffect, useMemo, useState } from 'react';

import { api } from './api';
import type {
  AIConfigPayload,
  CouncilRoleConfig,
  CouncilRolePreset,
  CouncilTemplateConfig,
  CouncilWorkflowNodeConfig,
  CouncilWorkflowNodeType,
  WorkflowEstimate,
  WorkflowNodeTestRecord,
  WorkflowSchemaPayload,
  WorkflowVersionRecord,
} from './types';
import {
  cloneTemplate,
  connectionCreatesLoop,
  connectionProblem,
  defaultControlNode,
  defaultRole,
  nextIdentifier,
  nextRoleOrder,
  normalizedRoundPolicy,
  toFlowEdges,
  toFlowNodes,
  uniqueIdentifier,
  upgradeTemplateToV2,
  validateWorkflow,
  type AIConfigForm,
  type WorkflowFlowEdge,
  type WorkflowFlowNode,
} from './councilWorkflowUtils';
import { WorkflowInspectorPanel, type WorkflowInspectorMode } from './WorkflowInspectorPanel';
import { WorkflowRunConsole } from './WorkflowRunConsole';

const inputSx = {
  '& .MuiOutlinedInput-root': { minHeight: 38, borderRadius: 1, bgcolor: 'background.paper' },
  '& .MuiInputBase-input, & .MuiSelect-select': { fontSize: 13, lineHeight: 1.45 },
};

const nodeTypes = { workflowNode: WorkflowNode };

const CONTROL_NODES: Array<{ type: Exclude<CouncilWorkflowNodeType, 'input' | 'agent' | 'chair'>; label: string; caption: string; icon: ReactNode }> = [
  { type: 'router', label: '条件路由', caption: '按结构化条件分流', icon: <AltRouteIcon fontSize="small" /> },
  { type: 'deterministic', label: '确定性处理', caption: '行情、证据和数据转换', icon: <RuleOutlinedIcon fontSize="small" /> },
  { type: 'gate', label: '质量门禁', caption: '阻断不达标输入', icon: <ApprovalOutlinedIcon fontSize="small" /> },
  { type: 'subflow', label: '子流程', caption: '调用受控研究子任务', icon: <SchemaOutlinedIcon fontSize="small" /> },
  { type: 'human_review', label: '人工复核', caption: '暂停并等待人工决定', icon: <GavelIcon fontSize="small" /> },
  { type: 'aggregator', label: '结果聚合', caption: '合并多个上游结果', icon: <MergeTypeIcon fontSize="small" /> },
];

export function CouncilWorkflowPanel({
  aiForm,
  setAiForm,
  rolePresets,
}: {
  aiForm: AIConfigForm;
  setAiForm: Dispatch<SetStateAction<AIConfigForm | null>>;
  rolePresets: CouncilRolePreset[];
}) {
  const [selectedTemplateId, setSelectedTemplateId] = useState(preferredTemplateId(aiForm.council_templates));
  const [selectedPresetId, setSelectedPresetId] = useState(rolePresets[0]?.preset_id || '');
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);
  const [inspectorMode, setInspectorMode] = useState<WorkflowInspectorMode>('workflow');
  const [flowNodes, setFlowNodes] = useState<WorkflowFlowNode[]>([]);
  const [flowEdges, setFlowEdges] = useState<WorkflowFlowEdge[]>([]);
  const [history, setHistory] = useState<CouncilTemplateConfig[]>([]);
  const [future, setFuture] = useState<CouncilTemplateConfig[]>([]);
  const [connectionError, setConnectionError] = useState<string | null>(null);
  const [flowInstance, setFlowInstance] = useState<ReactFlowInstance<WorkflowFlowNode, WorkflowFlowEdge> | null>(null);
  const [schema, setSchema] = useState<WorkflowSchemaPayload | null>(null);
  const [versions, setVersions] = useState<WorkflowVersionRecord[]>([]);
  const [selectedVersionId, setSelectedVersionId] = useState('');
  const [estimate, setEstimate] = useState<WorkflowEstimate | null>(null);
  const [nodeTest, setNodeTest] = useState<WorkflowNodeTestRecord | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const selectedTemplateIndex = aiForm.council_templates.findIndex((template) => template.template_id === selectedTemplateId);
  const template = aiForm.council_templates[selectedTemplateIndex] || aiForm.council_templates[0];
  const selectedNode = template?.workflow.nodes.find((node) => node.node_id === selectedNodeId) || null;
  const selectedEdge = template?.workflow.edges.find((edge) => edge.edge_id === selectedEdgeId) || null;
  const validationErrors = useMemo(() => template ? validateWorkflow(template) : ['没有可编辑的工作流模板。'], [template]);
  const selectedVersion = versions.find((item) => item.workflow_version_id === selectedVersionId) || null;
  const activeDraft = versions.find((item) => item.status === 'draft') || null;
  const roundPolicy = template ? normalizedRoundPolicy(template) : { min_rounds: 1, default_rounds: 3, max_rounds: 8 };

  useEffect(() => {
    let active = true;
    api.workflowSchema().then((payload) => { if (active) setSchema(payload); }).catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : String(reason)); });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!template) return;
    setFlowNodes(toFlowNodes(template, aiForm));
    setFlowEdges(toFlowEdges(template.workflow.edges));
    setSelectedNodeId((current) => current && template.workflow.nodes.some((node) => node.node_id === current) ? current : null);
    setSelectedEdgeId((current) => current && template.workflow.edges.some((edge) => edge.edge_id === current) ? current : null);
  }, [aiForm, template]);

  useEffect(() => {
    if (!selectedTemplateId) return;
    let active = true;
    api.workflowVersions(selectedTemplateId)
      .then((payload) => {
        if (!active) return;
        setVersions(payload.versions);
        const preferred = payload.versions.find((item) => item.status === 'draft') || payload.versions.find((item) => item.status === 'published') || payload.versions[0];
        setSelectedVersionId(preferred?.workflow_version_id || '');
      })
      .catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : String(reason)); });
    return () => { active = false; };
  }, [selectedTemplateId]);

  useEffect(() => {
    setHistory([]);
    setFuture([]);
    setConnectionError(null);
    setSelectedNodeId(null);
    setSelectedEdgeId(null);
    setInspectorMode('workflow');
  }, [selectedTemplateId]);

  useEffect(() => {
    if (!aiForm.council_templates.some((item) => item.template_id === selectedTemplateId)) setSelectedTemplateId(aiForm.council_templates[0]?.template_id || '');
  }, [aiForm.council_templates, selectedTemplateId]);

  useEffect(() => {
    if (!flowInstance) return;
    const frame = window.requestAnimationFrame(() => {
      void flowInstance.fitView({ padding: 0.08, duration: 0 }).then(() => {
        const preferredZoom = template.workflow.nodes.length > 10 ? 0.42 : template.workflow.nodes.length > 8 ? 0.54 : 0.62;
        if (flowInstance.getZoom() < preferredZoom) void flowInstance.zoomTo(preferredZoom, { duration: 0 });
      });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [flowInstance, template.template_id, template.workflow.nodes.length]);

  if (!template) return <Alert severity="warning">暂无工作流模板。</Alert>;

  const replaceTemplate = (next: CouncilTemplateConfig) => {
    setAiForm((current) => current ? { ...current, council_templates: current.council_templates.map((item) => item.template_id === next.template_id ? cloneTemplate(next) : item) } : current);
  };

  const commitTemplate = (next: CouncilTemplateConfig, recordHistory = true) => {
    if (recordHistory) {
      setHistory((current) => [...current, cloneTemplate(template)].slice(-40));
      setFuture([]);
    }
    replaceTemplate(next);
    setConnectionError(null);
    setMessage(null);
    setEstimate(null);
    setNodeTest(null);
  };

  const undo = () => {
    const previous = history[history.length - 1];
    if (!previous) return;
    setHistory((current) => current.slice(0, -1));
    setFuture((current) => [cloneTemplate(template), ...current].slice(0, 40));
    replaceTemplate(previous);
  };

  const redo = () => {
    const next = future[0];
    if (!next) return;
    setFuture((current) => current.slice(1));
    setHistory((current) => [...current, cloneTemplate(template)].slice(-40));
    replaceTemplate(next);
  };

  const duplicateTemplate = () => {
    const next = upgradeTemplateToV2(template);
    next.template_id = nextIdentifier('workflow', aiForm.council_templates.map((item) => item.template_id));
    next.display_name = `${template.display_name} 副本`;
    next.builtin = false;
    next.template_kind = 'custom';
    setAiForm((current) => current ? { ...current, council_templates: [...current.council_templates, next] } : current);
    setSelectedTemplateId(next.template_id);
  };

  const removeTemplate = () => {
    if (template.builtin || aiForm.council_templates.length <= 1) return;
    const remaining = aiForm.council_templates.filter((item) => item.template_id !== template.template_id);
    setAiForm((current) => current ? { ...current, council_templates: remaining } : current);
    setSelectedTemplateId(remaining[0]?.template_id || '');
  };

  const upgradeV2 = () => {
    commitTemplate(upgradeTemplateToV2(template));
    setMessage('已在编辑器中升级到 v2；保存草稿后才会写入版本库。');
  };

  const nextPosition = () => ({ x: 280 + (template.workflow.nodes.length % 3) * 230, y: 100 + (template.workflow.nodes.length % 5) * 115 });

  const addAgent = () => {
    if (template.workflow.version < 2) { setConnectionError('先升级到工作流 v2，再添加自由编排节点。'); return; }
    if (template.roles.length >= template.max_roles) return;
    const preset = rolePresets.find((item) => item.preset_id === selectedPresetId);
    const roleId = uniqueIdentifier(preset?.role_id || 'analyst', template.roles.map((role) => role.role_id));
    const primarySite = preset?.site_id || aiForm.sites.find((site) => site.enabled)?.site_id || aiForm.sites[0]?.site_id || null;
    const role: CouncilRoleConfig = preset ? {
      role_id: roleId,
      display_name: preset.display_name,
      stance: preset.stance,
      objective: preset.objective,
      enabled: true,
      order: nextRoleOrder(template.roles),
      site_id: preset.site_id || primarySite,
      protocol: preset.protocol || 'chat',
      model: preset.model,
      reasoning_effort: preset.reasoning_effort || 'medium',
      fallback_site_ids: [...preset.fallback_site_ids],
      system_prompt: preset.system_prompt,
      user_prompt_template: preset.user_prompt_template,
    } : defaultRole(roleId, template.roles.length, primarySite, aiForm);
    const nodeId = uniqueIdentifier(`node_${roleId}`, template.workflow.nodes.map((node) => node.node_id));
    const node: CouncilWorkflowNodeConfig = { node_id: nodeId, node_type: 'agent', role_id: roleId, position: nextPosition() };
    commitTemplate({ ...template, roles: [...template.roles, role], workflow: { ...template.workflow, nodes: [...template.workflow.nodes, node] } });
    setSelectedNodeId(nodeId);
    setSelectedEdgeId(null);
    setInspectorMode('node');
  };

  const addControlNode = (nodeType: Exclude<CouncilWorkflowNodeType, 'input' | 'agent' | 'chair'>) => {
    if (template.workflow.version < 2) { setConnectionError('先升级到工作流 v2，再添加控制节点。'); return; }
    const nodeId = nextIdentifier(`node_${nodeType}`, template.workflow.nodes.map((node) => node.node_id));
    const node = defaultControlNode(nodeType, nodeId, nextPosition());
    commitTemplate({ ...template, workflow: { ...template.workflow, nodes: [...template.workflow.nodes, node] } });
    setSelectedNodeId(nodeId);
    setSelectedEdgeId(null);
    setInspectorMode('node');
  };

  const deleteNodeIds = (nodeIds: string[]) => {
    const selected = template.workflow.nodes.filter((node) => nodeIds.includes(node.node_id) && !['input', 'chair'].includes(node.node_type));
    const roleIds = new Set(selected.map((node) => node.role_id).filter((roleId): roleId is string => Boolean(roleId)));
    if (template.roles.length - roleIds.size < 2) { setConnectionError('工作流至少需要两个 AI 角色。'); return; }
    const ids = new Set(selected.map((node) => node.node_id));
    if (!ids.size) return;
    commitTemplate({
      ...template,
      roles: template.roles.filter((role) => !roleIds.has(role.role_id)),
      workflow: {
        ...template.workflow,
        nodes: template.workflow.nodes.filter((node) => !ids.has(node.node_id)),
        edges: template.workflow.edges.filter((edge) => !ids.has(edge.source_node_id) && !ids.has(edge.target_node_id)),
      },
    });
    setSelectedNodeId(null);
    setInspectorMode('workflow');
  };

  const connectNodes = (connection: Connection) => {
    if (!connection.source || !connection.target) return;
    const problem = connectionProblem(template, connection.source, connection.target);
    if (problem) { setConnectionError(problem); return; }
    const loop = connectionCreatesLoop(template, connection.source, connection.target);
    const edgeId = nextIdentifier('edge', template.workflow.edges.map((edge) => edge.edge_id));
    commitTemplate({
      ...template,
      workflow: {
        ...template.workflow,
        edges: [...template.workflow.edges, {
          edge_id: edgeId,
          source_node_id: connection.source,
          target_node_id: connection.target,
          ...(loop ? { loop: true, max_traversals: 1, condition: { field: 'current.needs_more', operator: 'truthy' as const } } : {}),
        }],
      },
    });
    setSelectedEdgeId(edgeId);
    setSelectedNodeId(null);
    setInspectorMode('edge');
  };

  const removeEdges = (changes: EdgeChange<WorkflowFlowEdge>[]) => {
    setFlowEdges((current) => applyEdgeChanges(changes, current));
    const removedIds = new Set(changes.filter((change) => change.type === 'remove').map((change) => change.id));
    if (!removedIds.size) return;
    commitTemplate({ ...template, workflow: { ...template.workflow, edges: template.workflow.edges.filter((edge) => !removedIds.has(edge.edge_id)) } });
    setSelectedEdgeId(null);
  };

  const deleteSelectedEdge = () => {
    if (!selectedEdge) return;
    commitTemplate({ ...template, workflow: { ...template.workflow, edges: template.workflow.edges.filter((edge) => edge.edge_id !== selectedEdge.edge_id) } });
    setSelectedEdgeId(null);
    setInspectorMode('workflow');
  };

  const moveNode = (node: WorkflowFlowNode) => {
    const current = template.workflow.nodes.find((item) => item.node_id === node.id);
    if (!current || (current.position.x === node.position.x && current.position.y === node.position.y)) return;
    commitTemplate({ ...template, workflow: { ...template.workflow, nodes: template.workflow.nodes.map((item) => item.node_id === node.id ? { ...item, position: { x: Math.round(node.position.x), y: Math.round(node.position.y) } } : item) } });
  };

  const reloadVersions = async (preferredVersionId?: string) => {
    const payload = await api.workflowVersions(template.template_id);
    setVersions(payload.versions);
    const preferred = payload.versions.find((item) => item.workflow_version_id === preferredVersionId) || payload.versions.find((item) => item.status === 'draft') || payload.versions.find((item) => item.status === 'published') || payload.versions[0];
    setSelectedVersionId(preferred?.workflow_version_id || '');
  };

  const persistDraft = async () => {
    const response = await api.saveWorkflowDraft({
      template,
      workflow_version_id: activeDraft?.workflow_version_id,
      parent_version_id: activeDraft?.parent_version_id || versions.find((item) => item.status === 'published')?.workflow_version_id,
      expected_checksum: activeDraft?.checksum,
      change_note: 'AI 工作流 v2 可视化编排器保存',
    });
    setEstimate(response.estimate);
    await reloadVersions(response.version.workflow_version_id);
    return response.version;
  };

  const saveDraft = async () => runBusy(async () => {
    const saved = await persistDraft();
    setMessage(`草稿 v${saved.version_number} 已保存。`);
  });

  const publishDraft = async () => {
    if (validationErrors.length) return;
    await runBusy(async () => {
      const saved = await persistDraft();
      const response = await api.publishWorkflowVersion(saved.workflow_version_id);
      applyAiConfig(response.ai_config);
      await reloadVersions(response.version.workflow_version_id);
      setMessage(`工作流 v${response.version.version_number} 已发布，后续运行会读取该版本。`);
    });
  };

  const rollbackVersion = async () => {
    if (!selectedVersion || selectedVersion.status === 'published') return;
    await runBusy(async () => {
      const response = await api.rollbackWorkflowVersion(selectedVersion.workflow_version_id, true);
      if (response.ai_config) applyAiConfig(response.ai_config);
      await reloadVersions(response.version.workflow_version_id);
      setMessage(`已从 v${selectedVersion.version_number} 生成并发布回滚版本 v${response.version.version_number}。`);
    });
  };

  const estimateWorkflow = async () => runBusy(async () => setEstimate(await api.estimateWorkflow(template, roundPolicy.default_rounds)));
  const testSelectedNode = async () => {
    if (!selectedNode) return;
    await runBusy(async () => {
      const response = await api.testWorkflowNode({ template, node_id: selectedNode.node_id, workflow_version_id: activeDraft?.workflow_version_id || selectedVersion?.workflow_version_id, sample_input: { query: 'BTC 市场研究', symbol: 'BTC/USDT', evidence_count: 3 } });
      setNodeTest(response.test);
    });
  };

  const loadSelectedVersion = () => {
    if (!selectedVersion) return;
    commitTemplate(cloneTemplate(selectedVersion.content));
    setMessage(`已将 v${selectedVersion.version_number} 载入编辑器；保存草稿后才会形成新版本。`);
  };

  function applyAiConfig(payload: AIConfigPayload) {
    setAiForm({ sites: payload.sites, task_bindings: payload.task_bindings, prompts: payload.prompts, council_templates: payload.council_templates, experiments: payload.experiments });
  }

  async function runBusy(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    setMessage(null);
    try { await action(); } catch (reason) { setError(reason instanceof Error ? reason.message : String(reason)); } finally { setBusy(false); }
  }

  return (
    <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 1, overflow: 'hidden', bgcolor: 'background.paper', minWidth: 0 }}>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.75, alignItems: 'center', px: 1.25, py: 1, borderBottom: 1, borderColor: 'divider' }}>
        <AccountTreeIcon color="primary" />
        <Typography variant="h3" sx={{ mr: 0.5 }}>AI 研究工作流</Typography>
        <TextField select aria-label="工作流模板" value={template.template_id} onChange={(event) => setSelectedTemplateId(event.target.value)} sx={{ minWidth: { xs: '100%', sm: 220 }, ...inputSx }}>
          {aiForm.council_templates.map((item) => <MenuItem key={item.template_id} value={item.template_id}>{item.display_name}</MenuItem>)}
        </TextField>
        <TextField select aria-label="工作流版本" value={selectedVersionId} onChange={(event) => setSelectedVersionId(event.target.value)} sx={{ minWidth: { xs: '100%', sm: 170 }, ...inputSx }}>
          <MenuItem value="">尚无版本</MenuItem>
          {versions.map((item) => <MenuItem key={item.workflow_version_id} value={item.workflow_version_id}>v{item.version_number} · {versionStatusText(item.status)}</MenuItem>)}
        </TextField>
        <Chip size="small" color={template.workflow.version >= 2 ? 'success' : 'warning'} label={`Workflow v${template.workflow.version}`} />
        {template.builtin && <Chip size="small" variant="outlined" label="内置模板" />}
        <Tooltip title="复制为自定义工作流"><IconButton aria-label="复制工作流" onClick={duplicateTemplate}><AddIcon /></IconButton></Tooltip>
        <Tooltip title={template.builtin ? '内置模板不可删除，可复制后修改' : '删除自定义工作流'}><span><IconButton aria-label="删除工作流" onClick={removeTemplate} disabled={Boolean(template.builtin) || aiForm.council_templates.length <= 1}><DeleteOutlineIcon /></IconButton></span></Tooltip>
        <Box sx={{ flexGrow: 1 }} />
        <Button size="small" variant="outlined" startIcon={<SaveOutlinedIcon />} onClick={() => void saveDraft()} disabled={busy || validationErrors.length > 0}>保存草稿</Button>
        <Button size="small" variant="contained" startIcon={<CloudUploadOutlinedIcon />} onClick={() => void publishDraft()} disabled={busy || validationErrors.length > 0}>发布</Button>
      </Box>

      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, alignItems: 'center', px: 1.25, py: 0.9, borderBottom: 1, borderColor: 'divider', bgcolor: '#f8fafc' }}>
        {template.workflow.version < 2 && <Button size="small" variant="outlined" color="warning" startIcon={<UpgradeIcon />} onClick={upgradeV2}>升级 v2</Button>}
        <Tooltip title="撤销"><span><IconButton aria-label="撤销" onClick={undo} disabled={!history.length}><UndoIcon /></IconButton></span></Tooltip>
        <Tooltip title="重做"><span><IconButton aria-label="重做" onClick={redo} disabled={!future.length}><RedoIcon /></IconButton></span></Tooltip>
        <Tooltip title="适应画布"><IconButton aria-label="适应画布" onClick={() => flowInstance?.fitView({ padding: 0.2, duration: 200 })}><CenterFocusStrongIcon /></IconButton></Tooltip>
        <Tooltip title="成本预估"><IconButton aria-label="成本预估" onClick={() => void estimateWorkflow()} disabled={busy}><CalculateOutlinedIcon /></IconButton></Tooltip>
        <Tooltip title="测试选中节点"><span><IconButton aria-label="测试选中节点" onClick={() => void testSelectedNode()} disabled={busy || !selectedNode}><PlayCircleOutlineIcon /></IconButton></span></Tooltip>
        <Tooltip title="载入所选历史版本"><span><IconButton aria-label="载入版本" onClick={loadSelectedVersion} disabled={!selectedVersion}><SchemaOutlinedIcon /></IconButton></span></Tooltip>
        <Tooltip title="发布所选历史版本的回滚副本"><span><IconButton aria-label="回滚版本" color="warning" onClick={() => void rollbackVersion()} disabled={busy || !selectedVersion || selectedVersion.status === 'published'}><RestoreIcon /></IconButton></span></Tooltip>
        <Divider orientation="vertical" flexItem />
        <Typography variant="caption" fontWeight={700}>执行策略</Typography>
        <ToggleButtonGroup exclusive size="small" value={template.cost_tier || 'standard'} onChange={(_, value: CouncilTemplateConfig['cost_tier'] | null) => value && commitTemplate({ ...template, cost_tier: value })}>
          <ToggleButton value="quick">快速</ToggleButton><ToggleButton value="standard">标准</ToggleButton><ToggleButton value="deep">深度</ToggleButton>
        </ToggleButtonGroup>
        <TextField type="number" label="最小轮次" value={roundPolicy.min_rounds} onChange={(event) => commitTemplate({ ...template, round_policy: { ...roundPolicy, min_rounds: clampInt(event.target.value, 1, 32) } })} sx={{ width: 100, ...inputSx }} />
        <TextField type="number" label="默认轮次" value={roundPolicy.default_rounds} onChange={(event) => commitTemplate({ ...template, round_policy: { ...roundPolicy, default_rounds: clampInt(event.target.value, 1, 32) } })} sx={{ width: 100, ...inputSx }} />
        <TextField type="number" label="最大轮次" value={roundPolicy.max_rounds} onChange={(event) => commitTemplate({ ...template, round_policy: { ...roundPolicy, max_rounds: clampInt(event.target.value, 1, 32) } })} sx={{ width: 100, ...inputSx }} />
        <TextField select label="失败策略" value={template.failure_policy || 'stop'} onChange={(event) => commitTemplate({ ...template, failure_policy: event.target.value as CouncilTemplateConfig['failure_policy'] })} sx={{ minWidth: 145, ...inputSx }}>
          <MenuItem value="stop">立即停止</MenuItem><MenuItem value="continue">降级继续</MenuItem><MenuItem value="replan">重规划</MenuItem>
        </TextField>
        <Box sx={{ flexGrow: 1 }} />
        {validationErrors.length === 0 ? <Chip icon={<CheckCircleOutlineIcon />} color="success" label={`${template.workflow.nodes.length} 节点 · ${template.workflow.edges.length} 连线 · 可发布`} /> : <Chip color="error" label={`${validationErrors.length} 项待修正`} />}
      </Box>

      {(message || error || estimate || nodeTest || connectionError || validationErrors.length > 0) && (
        <Stack spacing={0.75} sx={{ px: 1.25, py: 1, borderBottom: 1, borderColor: 'divider' }}>
          {message && <Alert severity="success" onClose={() => setMessage(null)}>{message}</Alert>}
          {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}
          {connectionError && <Alert severity="warning" onClose={() => setConnectionError(null)}>{connectionError}</Alert>}
          {validationErrors.length > 0 && <Alert severity="error"><Typography variant="body2" fontWeight={700}>发布前需要修正</Typography>{validationErrors.slice(0, 5).map((item) => <Typography key={item} variant="caption" display="block">{item}</Typography>)}</Alert>}
          {estimate && <Alert severity={estimate.cost_status === 'known' ? 'info' : 'warning'}>预计 {estimate.invocation_count} 次模型调用 · {estimate.estimated_total_tokens.toLocaleString('zh-CN')} Token · {estimate.estimated_cost_usd === null || estimate.estimated_cost_usd === undefined ? '部分模型缺少费率' : `$${estimate.estimated_cost_usd.toFixed(4)}`}</Alert>}
          {nodeTest && <Alert severity="success">节点 {nodeTest.node_id} 契约验证通过；未发送外部 AI 请求。</Alert>}
        </Stack>
      )}

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: '210px minmax(0, 1fr)' }, minWidth: 0, '@media (min-width: 1400px)': { gridTemplateColumns: '200px minmax(0, 1fr) 340px' } }}>
        <Box sx={{ p: 1.25, borderRight: { md: 1 }, borderBottom: { xs: 1, md: 0 }, borderColor: 'divider', minWidth: 0, overflowY: 'auto', '@media (min-width: 1400px)': { maxHeight: 660 } }}>
          <Typography variant="subtitle1" sx={{ mb: 1 }}>节点库</Typography>
          <Stack spacing={0.75}>
            <PaletteButton icon={<InputIcon color="success" fontSize="small" />} label="研究输入" caption="唯一入口" disabled />
            <Divider><Typography variant="caption">AI 分析</Typography></Divider>
            <TextField select label="角色预设" value={selectedPresetId} onChange={(event) => setSelectedPresetId(event.target.value)} disabled={!rolePresets.length} sx={inputSx}>
              {rolePresets.map((preset) => <MenuItem key={preset.preset_id} value={preset.preset_id}>{preset.display_name}</MenuItem>)}
            </TextField>
            <PaletteButton icon={<PersonOutlineIcon color="primary" fontSize="small" />} label="AI 角色" caption="独立模型、提示词与思考等级" onClick={addAgent} />
            <Divider><Typography variant="caption">控制与审计</Typography></Divider>
            {CONTROL_NODES.map((item) => <PaletteButton key={item.type} icon={item.icon} label={item.label} caption={item.caption} onClick={() => addControlNode(item.type)} />)}
            <Divider><Typography variant="caption">输出</Typography></Divider>
            <PaletteButton icon={<GavelIcon color="warning" fontSize="small" />} label="主席裁决" caption="唯一终点" disabled />
          </Stack>
        </Box>

        <Box sx={{ height: { xs: 520, md: 660 }, minWidth: 0, bgcolor: '#fbfcfe', position: 'relative' }}>
          <ReactFlow<WorkflowFlowNode, WorkflowFlowEdge>
            nodes={flowNodes}
            edges={flowEdges}
            nodeTypes={nodeTypes}
            onInit={setFlowInstance}
            onNodesChange={(changes: NodeChange<WorkflowFlowNode>[]) => setFlowNodes((current) => applyNodeChanges(changes, current))}
            onNodesDelete={(nodes) => deleteNodeIds(nodes.map((node) => node.id))}
            onEdgesChange={removeEdges}
            onNodeClick={(_, node) => { setSelectedNodeId(node.id); setSelectedEdgeId(null); setInspectorMode('node'); }}
            onEdgeClick={(_, edge) => { setSelectedEdgeId(edge.id); setSelectedNodeId(null); setInspectorMode('edge'); }}
            onPaneClick={() => { setSelectedNodeId(null); setSelectedEdgeId(null); setInspectorMode('workflow'); }}
            onNodeDragStop={(_, node) => moveNode(node)}
            onConnect={connectNodes}
            deleteKeyCode={['Backspace', 'Delete']}
            fitView
            fitViewOptions={{ padding: 0.18 }}
            defaultEdgeOptions={{ type: 'smoothstep', markerEnd: { type: MarkerType.ArrowClosed }, style: { strokeWidth: 1.6, stroke: '#64748b' } }}
            minZoom={0.2}
            maxZoom={1.6}
          >
            <Background variant={BackgroundVariant.Dots} gap={18} size={1} color="#d9e0e8" />
            <Controls showInteractive={false} position="bottom-left" />
          </ReactFlow>
          <Stack direction="row" spacing={0.75} sx={{ position: 'absolute', left: 12, top: 12, pointerEvents: 'none' }}><Chip size="small" variant="outlined" label="拖拽节点调整布局" /><Chip size="small" variant="outlined" label="从端口拉线定义流向" /></Stack>
        </Box>

        <Box sx={{ gridColumn: { xs: '1', md: '1 / -1' }, borderTop: 1, borderColor: 'divider', minWidth: 0, overflow: 'hidden', '@media (min-width: 1400px)': { gridColumn: 'auto', borderTop: 0, borderLeft: 1, maxHeight: 660 } }}>
          <Tabs value={inspectorMode} onChange={(_, value: WorkflowInspectorMode) => setInspectorMode(value)} variant="fullWidth" sx={{ borderBottom: 1, borderColor: 'divider' }}>
            <Tab value="node" label="节点" disabled={!selectedNode} /><Tab value="edge" label="连线" disabled={!selectedEdge} /><Tab value="workflow" label="流程" />
          </Tabs>
          <Box sx={{ p: 1.5, maxHeight: 620, overflowY: 'auto', '@media (min-width: 1400px)': { height: 610, maxHeight: 610 } }}>
            <WorkflowInspectorPanel mode={inspectorMode} node={selectedNode} edge={selectedEdge} template={template} aiForm={aiForm} schema={schema} onCommit={commitTemplate} onDeleteNode={() => selectedNode && deleteNodeIds([selectedNode.node_id])} onDeleteEdge={deleteSelectedEdge} />
          </Box>
        </Box>
      </Box>

      <WorkflowRunConsole templateId={template.template_id} templateName={template.display_name} defaultDepth={template.cost_tier || 'standard'} defaultRounds={roundPolicy.default_rounds} workflowVersionId={selectedVersionId || undefined} />
    </Box>
  );
}

function WorkflowNode({ data, selected }: NodeProps<WorkflowFlowNode>) {
  const palette = nodePalette(data.nodeType);
  return (
    <Box sx={{ width: 216, minHeight: 108, border: selected ? '2px solid #235789' : `1px solid ${palette.border}`, borderRadius: 1, bgcolor: 'background.paper', boxShadow: selected ? '0 0 0 3px rgba(35, 87, 137, 0.12)' : '0 2px 7px rgba(15, 23, 42, 0.08)', overflow: 'hidden' }}>
      {data.nodeType !== 'input' && <Handle type="target" position={Position.Left} style={{ width: 10, height: 10 }} />}
      <Stack direction="row" spacing={0.75} alignItems="center" sx={{ px: 1.15, py: 0.85, color: palette.color, bgcolor: palette.background }}>
        {palette.icon}<Typography variant="subtitle1" color="text.primary" noWrap sx={{ flexGrow: 1 }}>{data.label}</Typography><Typography variant="caption" fontWeight={700}>{nodeTypeShort(data.nodeType)}</Typography>
      </Stack>
      <Divider />
      <Stack spacing={0.25} sx={{ px: 1.15, py: 0.8 }}>
        {data.operation ? <Typography variant="caption" color="text.secondary" noWrap>操作 {data.operation}</Typography> : <><Typography variant="caption" color="text.secondary" noWrap>厂商 {data.provider}</Typography><Typography variant="caption" color="text.secondary" noWrap>模型 {data.model}</Typography><Typography variant="caption" color="text.secondary" noWrap>思考 {data.reasoning}</Typography></>}
        <Typography variant="caption" color={data.enabled ? 'success.main' : 'text.disabled'}>{data.enabled ? '● 已启用' : '○ 已停用'}</Typography>
      </Stack>
      {data.nodeType !== 'chair' && <Handle type="source" position={Position.Right} style={{ width: 10, height: 10 }} />}
    </Box>
  );
}

function PaletteButton({ icon, label, caption, onClick, disabled = false }: { icon: ReactNode; label: string; caption: string; onClick?: () => void; disabled?: boolean }) {
  return (
    <Button variant="outlined" color="inherit" onClick={onClick} disabled={disabled} fullWidth sx={{ justifyContent: 'flex-start', minHeight: 52, px: 1, py: 0.7, textAlign: 'left' }}>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ width: '100%', minWidth: 0 }}>{icon}<Box sx={{ minWidth: 0 }}><Typography variant="body2" fontWeight={700}>{label}</Typography><Typography variant="caption" color="text.secondary" sx={{ display: 'block', whiteSpace: 'normal', lineHeight: 1.25 }}>{caption}</Typography></Box></Stack>
    </Button>
  );
}

function nodePalette(nodeType: CouncilWorkflowNodeType) {
  const palettes: Record<CouncilWorkflowNodeType, { border: string; color: string; background: string; icon: ReactNode }> = {
    input: { border: '#5b9b72', color: '#2f7d5c', background: '#edf7f1', icon: <InputIcon fontSize="small" /> },
    router: { border: '#6589aa', color: '#235789', background: '#eef5fb', icon: <AltRouteIcon fontSize="small" /> },
    deterministic: { border: '#6f8a93', color: '#45636d', background: '#f1f5f6', icon: <RuleOutlinedIcon fontSize="small" /> },
    agent: { border: '#7f72c4', color: '#5d4eb3', background: '#f4f1fb', icon: <PersonOutlineIcon fontSize="small" /> },
    gate: { border: '#b26a00', color: '#9a5b00', background: '#fff7e8', icon: <ApprovalOutlinedIcon fontSize="small" /> },
    subflow: { border: '#3a7f91', color: '#216b7d', background: '#edf8fa', icon: <SchemaOutlinedIcon fontSize="small" /> },
    human_review: { border: '#b26a00', color: '#9a5b00', background: '#fff7e8', icon: <GavelIcon fontSize="small" /> },
    aggregator: { border: '#4f7f72', color: '#2f6f60', background: '#eef7f4', icon: <MergeTypeIcon fontSize="small" /> },
    chair: { border: '#c47a19', color: '#a15c05', background: '#fff6e9', icon: <GavelIcon fontSize="small" /> },
  };
  return palettes[nodeType];
}

function nodeTypeShort(type: CouncilWorkflowNodeType): string {
  return ({ input: 'IN', router: 'IF', deterministic: 'OP', agent: 'AI', gate: 'GATE', subflow: 'SUB', human_review: 'HUMAN', aggregator: 'MERGE', chair: 'OUT' } as Record<CouncilWorkflowNodeType, string>)[type];
}

function versionStatusText(status: WorkflowVersionRecord['status']): string {
  return ({ draft: '草稿', published: '已发布', archived: '已归档' } as const)[status];
}

function clampInt(value: string, minimum: number, maximum: number): number {
  const parsed = Number.parseInt(value, 10);
  return Math.max(minimum, Math.min(maximum, Number.isFinite(parsed) ? parsed : minimum));
}

function preferredTemplateId(templates: CouncilTemplateConfig[]): string {
  return templates.find((template) => template.template_id === 'standard_product_research')?.template_id
    || templates.find((template) => template.workflow.version >= 2)?.template_id
    || templates[0]?.template_id
    || '';
}
