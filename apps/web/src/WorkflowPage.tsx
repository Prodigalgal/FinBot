import AddIcon from '@mui/icons-material/Add';
import CalculateOutlinedIcon from '@mui/icons-material/CalculateOutlined';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import PublishIcon from '@mui/icons-material/Publish';
import RestoreIcon from '@mui/icons-material/Restore';
import SaveIcon from '@mui/icons-material/Save';
import ScienceOutlinedIcon from '@mui/icons-material/ScienceOutlined';
import { Background, Controls, ReactFlow, addEdge, useEdgesState, useNodesState } from '@xyflow/react';
import type { Connection, Edge, Node } from '@xyflow/react';
import { Alert, Box, Button, Chip, FormControlLabel, MenuItem, Paper, Stack, Switch, TextField, Typography } from '@mui/material';
import { useEffect, useMemo, useState } from 'react';

import { api } from './api';
import type { AgentRole, AiModel, AiModelBinding, AiProvider, ConfigurationSnapshot, ReasoningEffort, ResearchSummary, WorkflowCondition, WorkflowConditionOperandType, WorkflowDefinitionSummary, WorkflowEdge, WorkflowEstimate, WorkflowExecutionPlan, WorkflowFailurePolicy, WorkflowLearning, WorkflowNode, WorkflowNodeTestResult, WorkflowNodeType, WorkflowOutputContract, WorkflowSchema, WorkflowVersion } from './types';
import { ErrorBlock, LoadingBlock, SectionTitle, formatTime, statusColor, statusLabel } from './ui';

interface CanvasData extends Record<string, unknown> { label: string; workflowNode: WorkflowNode }
type CanvasNode = Node<CanvasData>;

export function WorkflowPage() {
  const [definitions, setDefinitions] = useState<WorkflowDefinitionSummary[]>([]);
  const [definition, setDefinition] = useState<WorkflowDefinitionSummary | null>(null);
  const [version, setVersion] = useState<WorkflowVersion | null>(null);
  const [versions, setVersions] = useState<WorkflowVersion[]>([]);
  const [schema, setSchema] = useState<WorkflowSchema | null>(null);
  const [config, setConfig] = useState<ConfigurationSnapshot | null>(null);
  const [roles, setRoles] = useState<AgentRole[]>([]);
  const [estimate, setEstimate] = useState<WorkflowEstimate | null>(null);
  const [executionPlan, setExecutionPlan] = useState<WorkflowExecutionPlan | null>(null);
  const [learning, setLearning] = useState<WorkflowLearning | null>(null);
  const [nodeTest, setNodeTest] = useState<WorkflowNodeTestResult | null>(null);
  const [runs, setRuns] = useState<ResearchSummary[]>([]);
  const [testPrompt, setTestPrompt] = useState('验证该节点在当前模型、提示词、输出契约和思考强度下能否返回有效的结构化研究摘要。');
  const [rollbackVersionId, setRollbackVersionId] = useState('');
  const [nodes, setNodes, onNodesChange] = useNodesState<CanvasNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null);
  const [newNodeType, setNewNodeType] = useState<WorkflowNodeType>('AGENT');
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    Promise.all([api.workflowDefinitions(), api.workflowSchema(), api.configuration(), api.agentRoles(), api.researchHistory(undefined, 200)]).then(async ([items, workflowSchema, configuration, roleTemplates, researchRuns]) => {
      setDefinitions(items); setSchema(workflowSchema); setConfig(configuration); setRoles(roleTemplates); setRuns(researchRuns);
      const first = items[0]; setDefinition(first || null);
      const versionId = first?.draftVersionId || first?.publishedVersionId;
      if (versionId && first) { const history = await api.workflowVersions(first.definitionId); setVersions(history); setRollbackVersionId(history.find((item) => item.versionId !== versionId)?.versionId || ''); applyVersion(await api.workflowVersion(versionId)); }
    }).catch(setError);
  }, []);

  const applyVersion = (loaded: WorkflowVersion) => {
    setVersion(loaded);
    setNodes(loaded.nodes.map((node) => ({ id: node.nodeId, position: { x: Number(node.positionX), y: Number(node.positionY) }, data: { label: `${node.displayName}\n${node.primaryAiBinding?.modelName || node.nodeType}`, workflowNode: node } })));
    setEdges(loaded.edges.map((edge) => ({ id: edge.edgeId, source: edge.sourceNodeId, target: edge.targetNodeId, data: { workflowEdge: edge } })));
    setSelectedId(null);
    setSelectedEdgeId(null);
    setEstimate(null); setExecutionPlan(null); setLearning(null); setNodeTest(null);
  };

  const selectDefinition = async (definitionId: string) => {
    const next = definitions.find((item) => item.definitionId === definitionId) || null;
    setDefinition(next); setMessage('');
    const versionId = next?.draftVersionId || next?.publishedVersionId;
    if (versionId && next) try { const history = await api.workflowVersions(next.definitionId); setVersions(history); setRollbackVersionId(history.find((item) => item.versionId !== versionId)?.versionId || ''); applyVersion(await api.workflowVersion(versionId)); } catch (cause) { setError(cause); }
  };

  const onConnect = (connection: Connection) => {
    if (!connection.source || !connection.target) return;
    const workflowEdge: WorkflowEdge = { edgeId: `edge_${crypto.randomUUID().replace(/-/g, '').slice(0, 16)}`, sourceNodeId: connection.source, targetNodeId: connection.target, activationMode: 'ALL', contextMode: 'INCLUDE', condition: null, loopEdge: false, maximumTraversals: null };
    setEdges((current) => addEdge({ ...connection, id: workflowEdge.edgeId, data: { workflowEdge } }, current));
    setSelectedId(null);
    setSelectedEdgeId(workflowEdge.edgeId);
  };

  const selected = nodes.find((node) => node.id === selectedId) || null;
  const selectedEdge = edges.find((edge) => edge.id === selectedEdgeId) || null;
  const versionRuns = useMemo(() => runs.filter((run) => run.workflowVersionId === version?.versionId).slice(0, 20), [runs, version?.versionId]);
  const updateSelected = (patch: Partial<WorkflowNode>) => {
    if (!selectedId) return;
    setNodes((current) => current.map((node) => node.id === selectedId ? { ...node, data: { ...node.data, label: `${patch.displayName || node.data.workflowNode.displayName}\n${patch.primaryAiBinding?.modelName || node.data.workflowNode.primaryAiBinding?.modelName || patch.nodeType || node.data.workflowNode.nodeType}`, workflowNode: { ...node.data.workflowNode, ...patch } } } : node));
  };

  const addNode = () => {
    if (!config) return;
    const nodeId = `node_${newNodeType.toLowerCase()}_${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}`;
    const binding = defaultAiBinding(config.providers, config.models);
    if (isLlmBacked(newNodeType) && !binding) { setError(new Error('没有可用的 AI 厂商或模型')); return; }
    const executionReflection = newNodeType === 'EXECUTION_REVIEW' && nodes.some((node) => node.data.workflowNode.nodeType === 'EXECUTION_REVIEW' && ['draft', 'execution_draft'].includes((node.data.workflowNode.operation || '').toLowerCase()));
    const workflowNode: WorkflowNode = {
      nodeId,
      nodeType: newNodeType,
      displayName: executionReflection ? '执行机器人独立反思' : defaultNodeName(newNodeType),
      roleName: executionReflection ? 'Execution Reflection' : isLlmBacked(newNodeType) ? 'Custom Role' : null,
      roleTemplateId: null,
      logicalRoleKey: isDebateSeat(newNodeType) ? `role_${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}` : null,
      primaryAiBinding: isLlmBacked(newNodeType) ? binding : null,
      fallbackAiBinding: null,
      systemPrompt: isLlmBacked(newNodeType) ? executionReflection ? '你是最终执行反思机器人。独立检查初审决策、证据和风险边界，只输出严格 JSON；字段矛盾或证据不足必须 REJECT。不得输出隐藏思维链。' : defaultSystemPrompt(newNodeType) : null,
      userPromptTemplate: isLlmBacked(newNodeType) ? executionReflection ? '输出 verdict、reasons 和修订后的 decision；REJECT 时 decision 必须为 null。' : defaultUserPrompt(newNodeType) : null,
      outputContract: newNodeType === 'SOCIAL_CHOICE' ? 'CONSENSUS_RESULT' : isLlmBacked(newNodeType) ? executionReflection ? 'EXECUTION_VERDICT' : defaultOutputContract(newNodeType) : null,
      contextMode: newNodeType === 'INPUT' ? 'NONE' : 'UPSTREAM',
      contextHistoryRounds: isLlmBacked(newNodeType) ? version?.defaultDebateRounds || 3 : 0,
      contextMaximumMessages: isLlmBacked(newNodeType) ? 24 : 0,
      maximumOutputTokens: 4096,
      timeoutSeconds: 180,
      retryMaximumAttempts: 2,
      retryBackoffSeconds: 2,
      operation: executionReflection ? 'reflection' : defaultOperation(newNodeType),
      positionX: 760,
      positionY: 120 + nodes.length * 45,
      enabled: true,
    };
    setNodes((current) => [...current, { id: nodeId, position: { x: workflowNode.positionX, y: workflowNode.positionY }, data: { label: `${workflowNode.displayName}\n${workflowNode.primaryAiBinding?.modelName || workflowNode.nodeType}`, workflowNode } }]);
    setSelectedId(nodeId);
    setSelectedEdgeId(null);
  };

  const removeSelected = () => {
    if (!selectedId) return;
    setNodes((current) => current.filter((node) => node.id !== selectedId));
    setEdges((current) => current.filter((edge) => edge.source !== selectedId && edge.target !== selectedId));
    setSelectedId(null);
  };

  const duplicateSelectedSeat = () => {
    if (!selected || !config || !isLlmBacked(selected.data.workflowNode.nodeType)) return;
    const source = selected.data.workflowNode;
    const sourceProviderId = source.primaryAiBinding ? providerId(source.primaryAiBinding) : '';
    const alternative = config.models.find((model) => model.enabled && model.providerProfileId !== sourceProviderId)
      || config.models.find((model) => model.enabled && model.modelName !== source.primaryAiBinding?.modelName);
    if (!alternative) { setError(new Error('没有可用于新增异构席位的其他模型')); return; }
    const nodeId = `node_${source.nodeType.toLowerCase()}_${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}`;
    const primaryAiBinding: AiModelBinding = { providerProfileId: alternative.providerProfileId, modelName: alternative.modelName, reasoningEffort: alternative.defaultReasoningEffort };
    const fallbackAiBinding = source.primaryAiBinding && providerId(source.primaryAiBinding) !== alternative.providerProfileId ? source.primaryAiBinding : source.fallbackAiBinding;
    const workflowNode: WorkflowNode = { ...source, nodeId, displayName: `${source.roleName || source.displayName} / ${alternative.modelName} 席位`, primaryAiBinding, fallbackAiBinding, positionX: selected.position.x + 30, positionY: selected.position.y + 100 };
    const clonedEdges = edges.filter((edge) => edge.source === source.nodeId || edge.target === source.nodeId).map((edge) => {
      const stored = edge.data?.workflowEdge as WorkflowEdge;
      const workflowEdge: WorkflowEdge = { ...stored, edgeId: `edge_${crypto.randomUUID().replace(/-/g, '').slice(0, 16)}`, sourceNodeId: edge.source === source.nodeId ? nodeId : edge.source, targetNodeId: edge.target === source.nodeId ? nodeId : edge.target };
      return { id: workflowEdge.edgeId, source: workflowEdge.sourceNodeId, target: workflowEdge.targetNodeId, data: { workflowEdge } } as Edge;
    });
    setNodes((current) => [...current, { id: nodeId, position: { x: workflowNode.positionX, y: workflowNode.positionY }, data: { label: `${workflowNode.displayName}\n${alternative.modelName}`, workflowNode } }]);
    setEdges((current) => [...current, ...clonedEdges]);
    setSelectedId(nodeId);
    setSelectedEdgeId(null);
  };

  const updateSelectedEdge = (patch: Partial<WorkflowEdge>) => {
    if (!selectedEdgeId) return;
    setEdges((current) => current.map((edge) => {
      if (edge.id !== selectedEdgeId) return edge;
      const stored = edge.data?.workflowEdge as WorkflowEdge;
      return { ...edge, data: { ...edge.data, workflowEdge: { ...stored, ...patch } } };
    }));
  };

  const removeSelectedEdge = () => {
    if (!selectedEdgeId) return;
    setEdges((current) => current.filter((edge) => edge.id !== selectedEdgeId));
    setSelectedEdgeId(null);
  };

  const cloneWorkflow = async () => {
    if (!version || !definition) return;
    setSaving(true); setError(null); setMessage('');
    try {
      const currentNodes = nodes.map((node) => ({ ...node.data.workflowNode, positionX: node.position.x, positionY: node.position.y }));
      const currentEdges = edges.map((edge) => ({ ...(edge.data?.workflowEdge as WorkflowEdge), sourceNodeId: edge.source, targetNodeId: edge.target }));
      const saved = await api.saveWorkflowDraft({ definitionId: null, versionId: null, name: `${definition.name} 副本`, description: definition.description, defaultDebateRounds: version.defaultDebateRounds, debateProtocol: version.debateProtocol, maximumSteps: version.maximumSteps, maximumDurationSeconds: version.maximumDurationSeconds, maximumTokens: version.maximumTokens, maximumCostUsd: version.maximumCostUsd, failurePolicy: version.failurePolicy, expectedChecksum: null, nodes: currentNodes, edges: currentEdges });
      const updated = await api.workflowDefinitions();
      const created = updated.find((item) => item.definitionId === saved.definitionId) || null;
      setDefinitions(updated); setDefinition(created); setVersions([saved]); applyVersion(saved); setMessage('已复制为新的独立工作流草稿');
    } catch (cause) { setError(cause); } finally { setSaving(false); }
  };

  const save = async () => {
    if (!version || !definition) return;
    setSaving(true); setError(null); setMessage('');
    try {
      const currentNodes = nodes.map((node) => ({ ...node.data.workflowNode, positionX: node.position.x, positionY: node.position.y }));
      const currentEdges = edges.map((edge) => {
        const stored = edge.data?.workflowEdge as WorkflowEdge | undefined;
        return stored ? { ...stored, sourceNodeId: edge.source, targetNodeId: edge.target } : { edgeId: edge.id, sourceNodeId: edge.source, targetNodeId: edge.target, activationMode: 'ALL', contextMode: 'INCLUDE', condition: null, loopEdge: false, maximumTraversals: null };
      });
      const saved = await api.saveWorkflowDraft({ definitionId: definition.definitionId, versionId: version.status === 'DRAFT' ? version.versionId : null, name: definition.name, description: definition.description, defaultDebateRounds: version.defaultDebateRounds, debateProtocol: version.debateProtocol, maximumSteps: version.maximumSteps, maximumDurationSeconds: version.maximumDurationSeconds, maximumTokens: version.maximumTokens, maximumCostUsd: version.maximumCostUsd, failurePolicy: version.failurePolicy, expectedChecksum: version.status === 'DRAFT' ? version.checksum : null, nodes: currentNodes, edges: currentEdges });
      applyVersion(saved); setMessage('草稿已保存');
      const updatedDefinitions = await api.workflowDefinitions(); setDefinitions(updatedDefinitions); setDefinition(updatedDefinitions.find((item) => item.definitionId === definition.definitionId) || definition);
    } catch (cause) { setError(cause); } finally { setSaving(false); }
  };

  const publish = async () => {
    if (!version || version.status !== 'DRAFT') return;
    setSaving(true); try { applyVersion(await api.publishWorkflow(version.versionId)); setMessage('工作流已发布'); setDefinitions(await api.workflowDefinitions()); } catch (cause) { setError(cause); } finally { setSaving(false); }
  };

  const setActive = async (active: boolean) => {
    if (!definition) return;
    setSaving(true); setError(null); setMessage('');
    try {
      const updated = await api.setWorkflowActive(definition.definitionId, active);
      setDefinition(updated);
      setDefinitions((current) => current.map((item) => item.definitionId === updated.definitionId ? updated : item));
      setMessage(active ? '工作流已加入定时调度' : '工作流已停止定时调度');
    } catch (cause) { setError(cause); } finally { setSaving(false); }
  };

  const loadEstimate = async () => { if (!version) return; setSaving(true); setError(null); try { setEstimate(await api.workflowEstimate(version.versionId)); } catch (cause) { setError(cause); } finally { setSaving(false); } };
  const loadPlan = async () => { if (!version) return; setSaving(true); setError(null); try { setExecutionPlan(await api.workflowPlan(version.versionId)); } catch (cause) { setError(cause); } finally { setSaving(false); } };
  const loadLearning = async () => { if (!version) return; setSaving(true); setError(null); try { setLearning(await api.workflowLearning(version.versionId)); } catch (cause) { setError(cause); } finally { setSaving(false); } };
  const testSelectedNode = async () => { if (!version || !selected) return; setSaving(true); setError(null); setNodeTest(null); try { setNodeTest(await api.testWorkflowNode(version.versionId, selected.id, testPrompt, crypto.randomUUID())); } catch (cause) { setError(cause); } finally { setSaving(false); } };
  const rollback = async () => { if (!definition || !rollbackVersionId) return; setSaving(true); setError(null); try { const restored = await api.rollbackWorkflow(definition.definitionId, rollbackVersionId); applyVersion(restored); const history = await api.workflowVersions(definition.definitionId); setVersions(history); setMessage(`已从历史版本生成并发布 v${restored.versionNumber}`); setDefinitions(await api.workflowDefinitions()); } catch (cause) { setError(cause); } finally { setSaving(false); } };

  if (error !== null && (!version || !schema || !config || !definition)) return <ErrorBlock error={error} />;
  if (!version || !schema || !config || !definition) return <LoadingBlock label="正在加载工作流定义" />;
  return <Stack spacing={2}>
    {error !== null && <ErrorBlock error={error} />}{message && <Alert severity="success">{message}</Alert>}
    <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.25} alignItems={{ md: 'center' }} flexWrap={{ md: 'wrap' }} useFlexGap>
      <TextField select label="工作流" value={definition.definitionId} onChange={(event) => void selectDefinition(event.target.value)} sx={{ minWidth: 240 }}>{definitions.map((item) => <MenuItem key={item.definitionId} value={item.definitionId}>{item.name}{item.active ? '（调度中）' : ''}</MenuItem>)}</TextField>
      <TextField select label="版本" value={version.versionId} onChange={(event) => { const selectedVersion = versions.find((item) => item.versionId === event.target.value); if (selectedVersion) applyVersion(selectedVersion); }} sx={{ minWidth: 180 }}>{versions.map((item) => <MenuItem key={item.versionId} value={item.versionId}>v{item.versionNumber} · {item.status}</MenuItem>)}</TextField>
      <Chip label={`${version.status} v${version.versionNumber}`} color={version.status === 'PUBLISHED' ? 'success' : 'warning'} />
      <FormControlLabel control={<Switch checked={definition.active} disabled={saving || definition.publishedVersionId === null} onChange={(event) => void setActive(event.target.checked)} />} label="参与定时调度" />
      <Box sx={{ flex: 1 }} />
      <Button onClick={() => void cloneWorkflow()}>复制为新工作流</Button>
      <Button onClick={() => void loadPlan()}>执行计划</Button>
      <Button startIcon={<CalculateOutlinedIcon />} onClick={() => void loadEstimate()}>估算</Button>
      <Button startIcon={<ScienceOutlinedIcon />} onClick={() => void loadLearning()}>学习统计</Button>
      <TextField select size="small" label="节点类型" value={newNodeType} onChange={(event) => setNewNodeType(event.target.value as WorkflowNodeType)} sx={{ minWidth: 170 }}>{schema.nodeTypes.map((type) => <MenuItem key={type} value={type}>{nodeTypeLabel(type)}</MenuItem>)}</TextField>
      <Button startIcon={<AddIcon />} onClick={addNode}>添加节点</Button>
      <Button variant="contained" startIcon={<SaveIcon />} disabled={saving} onClick={() => void save()}>保存草稿</Button>
      <Button color="success" variant="contained" startIcon={<PublishIcon />} disabled={saving || version.status !== 'DRAFT'} onClick={() => void publish()}>发布</Button>
    </Stack>
    <Paper variant="outlined" sx={{ p: 2 }}>
      <SectionTitle title="工作流运行约束" />
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0, 1fr))', xl: 'repeat(4, minmax(0, 1fr))' }, gap: 1.25 }}>
        <TextField label="工作流名称" value={definition.name} onChange={(event) => setDefinition({ ...definition, name: event.target.value })} />
        <TextField label="用途说明" value={definition.description} onChange={(event) => setDefinition({ ...definition, description: event.target.value })} />
        <TextField label={version.debateProtocol.protocol === 'SDB_SCA_V1' ? '协议周期' : '辩论轮次'} type="number" value={version.defaultDebateRounds} disabled={version.debateProtocol.protocol === 'SDB_SCA_V1'} onChange={(event) => setVersion({ ...version, defaultDebateRounds: Number(event.target.value) })} inputProps={{ min: 1, max: 8 }} helperText={version.debateProtocol.protocol === 'SDB_SCA_V1' ? 'V1 固定执行一次完整四阶段周期' : undefined} />
        <TextField select label="失败策略" value={version.failurePolicy} onChange={(event) => setVersion({ ...version, failurePolicy: event.target.value as WorkflowFailurePolicy })}>{schema.failurePolicies.map((policy) => <MenuItem key={policy} value={policy}>{failurePolicyLabel(policy)}</MenuItem>)}</TextField>
        <TextField label="最大步骤" type="number" value={version.maximumSteps} onChange={(event) => setVersion({ ...version, maximumSteps: Number(event.target.value) })} inputProps={{ min: 1, max: 1000 }} />
        <TextField label="最大时长（秒）" type="number" value={version.maximumDurationSeconds} onChange={(event) => setVersion({ ...version, maximumDurationSeconds: Number(event.target.value) })} inputProps={{ min: 10, max: 86400 }} />
        <TextField label="最大 Token" type="number" value={version.maximumTokens} onChange={(event) => setVersion({ ...version, maximumTokens: Number(event.target.value) })} inputProps={{ min: 1000, max: 10000000 }} />
        <TextField label="最大成本（USD）" type="number" value={version.maximumCostUsd} onChange={(event) => setVersion({ ...version, maximumCostUsd: Number(event.target.value) })} inputProps={{ min: 0, step: 0.1 }} />
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0, 1fr))', xl: 'repeat(5, minmax(0, 1fr))' }, gap: 1.25, mt: 1.25 }}>
        <TextField select label="辩论协议" value={version.debateProtocol.protocol} onChange={(event) => { const protocol = event.target.value as typeof version.debateProtocol.protocol; setVersion({ ...version, defaultDebateRounds: protocol === 'SDB_SCA_V1' ? 1 : version.defaultDebateRounds, debateProtocol: { ...version.debateProtocol, protocol, critiqueAssignmentPolicy: protocol === 'LEGACY_CHAIR_V1' ? 'FULL_MATRIX' : version.debateProtocol.critiqueAssignmentPolicy } }); }}><MenuItem value="SDB_SCA_V1">SDB-SCA 双盲同时博弈</MenuItem><MenuItem value="LEGACY_CHAIR_V1">Legacy 主席辩论</MenuItem></TextField>
        <TextField label="最低参与席位" type="number" value={version.debateProtocol.minimumParticipantSeats} onChange={(event) => setVersion({ ...version, debateProtocol: { ...version.debateProtocol, minimumParticipantSeats: Number(event.target.value) } })} inputProps={{ min: 2, max: 32 }} />
        <TextField label="最低逻辑角色" type="number" value={version.debateProtocol.minimumQuorumRoles} onChange={(event) => setVersion({ ...version, debateProtocol: { ...version.debateProtocol, minimumQuorumRoles: Number(event.target.value) } })} inputProps={{ min: 2, max: 32 }} />
        <TextField label="阶段超时（秒）" type="number" value={version.debateProtocol.stageTimeoutSeconds} onChange={(event) => setVersion({ ...version, debateProtocol: { ...version.debateProtocol, stageTimeoutSeconds: Number(event.target.value) } })} inputProps={{ min: 30, max: 7200 }} />
        <TextField select label="交叉评审分配" value={version.debateProtocol.critiqueAssignmentPolicy} disabled={version.debateProtocol.protocol === 'LEGACY_CHAIR_V1'} onChange={(event) => setVersion({ ...version, debateProtocol: { ...version.debateProtocol, critiqueAssignmentPolicy: event.target.value as typeof version.debateProtocol.critiqueAssignmentPolicy } })}><MenuItem value="FULL_MATRIX">全矩阵</MenuItem><MenuItem value="BALANCED_INCOMPLETE">均衡不完全矩阵</MenuItem></TextField>
      </Box>
    </Paper>
    <Paper variant="outlined" sx={{ p: 1.5 }}><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.25} alignItems={{ md: 'center' }}><TextField select size="small" label="回滚目标" value={rollbackVersionId} onChange={(event) => setRollbackVersionId(event.target.value)} sx={{ minWidth: 220 }}><MenuItem value="">选择历史版本</MenuItem>{versions.filter((item) => item.versionId !== version.versionId).map((item) => <MenuItem key={item.versionId} value={item.versionId}>v{item.versionNumber} · {item.status}</MenuItem>)}</TextField><Button startIcon={<RestoreIcon />} disabled={!rollbackVersionId || saving} onClick={() => void rollback()}>复制并发布回滚版本</Button><Box sx={{ flex: 1 }} />{selected && isLlmBacked(selected.data.workflowNode.nodeType) && <><TextField size="small" label="节点测试输入" value={testPrompt} onChange={(event) => setTestPrompt(event.target.value)} fullWidth inputProps={{ maxLength: 20000 }} /><Button variant="outlined" startIcon={<ScienceOutlinedIcon />} disabled={saving || !testPrompt.trim()} onClick={() => void testSelectedNode()}>实测节点</Button></>}</Stack></Paper>
    {estimate && <Paper variant="outlined" sx={{ p: 2 }}><SectionTitle title="运行估算" /><Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: 'repeat(5, minmax(0, 1fr))' }, gap: 1.25 }}><EstimateMetric label="模型调用" value={String(estimate.estimatedCalls)} /><EstimateMetric label="输入 Token" value={estimate.estimatedInputTokens.toLocaleString()} /><EstimateMetric label="最大输出" value={estimate.maximumOutputTokens.toLocaleString()} /><EstimateMetric label="主模型成本" value={`$${Number(estimate.primaryCostUsd).toFixed(4)}`} /><EstimateMetric label="含兜底最坏成本" value={`$${Number(estimate.fallbackWorstCaseCostUsd).toFixed(4)}`} /></Box>{estimate.warnings.map((warning) => <Alert key={warning} severity="warning" sx={{ mt: 1 }}>{warning}</Alert>)}</Paper>}
    {executionPlan && <Paper variant="outlined" sx={{ p: 2 }}><SectionTitle title="执行计划" />{executionPlan.warnings.map((warning) => <Alert key={warning} severity="warning" sx={{ mb: 1 }}>{warning}</Alert>)}<Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>基础 {executionPlan.defaultDebateRounds} 轮，条件循环最多 {executionPlan.maximumDebateRounds} 轮 · {executionPlan.maximumSteps} 步 · {executionPlan.maximumTokens.toLocaleString()} tokens · ${executionPlan.maximumCostUsd}</Typography><Stack spacing={.75}>{executionPlan.nodes.map((node) => <Stack key={node.nodeId} direction={{ xs: 'column', md: 'row' }} spacing={1.25} alignItems={{ md: 'center' }} sx={{ py: 1, borderTop: '1px solid', borderColor: 'divider', opacity: node.enabled ? 1 : .5 }}><Chip size="small" label={node.sequence} /><Box sx={{ minWidth: { md: 220 }, flex: 1 }}><Typography fontWeight={700}>{node.displayName}</Typography><Typography variant="caption" color="text.secondary">{node.nodeType} · {node.runtimeHandler}</Typography></Box><Typography variant="body2" sx={{ minWidth: { md: 180 } }}>{node.invocationPolicy}</Typography><Typography variant="caption" color="text.secondary" sx={{ minWidth: { md: 240 } }}>{node.modelName ? `${node.providerProfileId} / ${node.modelName} / ${node.reasoningEffort}` : `上游 ${node.upstreamNodeIds.join(', ') || '-'}`}</Typography></Stack>)}</Stack></Paper>}
    {learning && <Paper variant="outlined" sx={{ p: 2 }}><SectionTitle title="运行与学习统计" /><Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}><EstimateMetric label="运行" value={String(learning.runCount)} /><EstimateMetric label="完成" value={String(learning.completedRunCount)} /><EstimateMetric label="失败" value={String(learning.failedRunCount)} /><EstimateMetric label="累计成本" value={`$${Number(learning.totalCostUsd).toFixed(4)}`} /></Stack><Stack spacing={.75} sx={{ mt: 1.5 }}>{learning.nodes.map((node) => <Stack key={node.nodeId} direction={{ xs: 'column', md: 'row' }} spacing={1.25} sx={{ py: 1, borderTop: '1px solid', borderColor: 'divider' }}><Typography fontWeight={700} sx={{ width: { md: 220 } }}>{node.displayName}</Typography><Typography variant="body2">调用 {node.invocationCount} · 成功 {node.successfulInvocationCount} · 失败 {node.failedInvocationCount}</Typography><Typography variant="body2">{node.inputTokens + node.outputTokens} tokens · ${Number(node.costUsd).toFixed(4)} · {node.averageLatencyMilliseconds ?? '-'} ms</Typography></Stack>)}</Stack></Paper>}
    {nodeTest && <Alert severity={nodeTest.status === 'COMPLETED' ? 'success' : 'error'}><Typography fontWeight={700}>节点测试 {nodeTest.status} · {nodeTest.runId}</Typography><Typography component="pre" variant="body2" sx={{ whiteSpace: 'pre-wrap', m: 0 }}>{nodeTest.output || `${nodeTest.errorCode}: ${nodeTest.errorMessage}`}</Typography></Alert>}
    <Paper variant="outlined" sx={{ p: 2 }}><SectionTitle title="该版本最近运行" action={<Button href="#review">进入复核与效果</Button>} />{versionRuns.length === 0 ? <Typography color="text.secondary">该版本尚无研究运行；节点实测不会进入正式研究样本。</Typography> : <Stack spacing={.75}>{versionRuns.map((run) => <Stack key={run.runId} direction={{ xs: 'column', md: 'row' }} spacing={1.25} alignItems={{ md: 'center' }} sx={{ py: .75, borderTop: '1px solid', borderColor: 'divider' }}><Chip size="small" color={statusColor(run.status)} label={statusLabel(run.status)} /><Typography variant="body2" fontWeight={700} sx={{ flex: 1 }}>{run.requestSummary}</Typography><Typography variant="caption" color="text.secondary">{run.inputTokens + run.outputTokens} tokens · ${Number(run.costUsd).toFixed(4)} · {formatTime(run.updatedAt)}</Typography><Typography variant="caption" sx={{ fontFamily: 'monospace' }}>{run.runId}</Typography></Stack>)}</Stack>}</Paper>
    <Stack direction={{ xs: 'column', xl: 'row' }} spacing={1.5} alignItems="stretch">
      <Paper variant="outlined" sx={{ height: { xs: 560, xl: 'calc(100vh - 235px)' }, minHeight: 560, flex: 1, overflow: 'hidden' }}><ReactFlow nodes={nodes} edges={edges} onNodesChange={onNodesChange} onEdgesChange={onEdgesChange} onConnect={onConnect} onNodeClick={(_event, node) => { setSelectedId(node.id); setSelectedEdgeId(null); }} onEdgeClick={(_event, edge) => { setSelectedEdgeId(edge.id); setSelectedId(null); }} onPaneClick={() => { setSelectedId(null); setSelectedEdgeId(null); }} fitView minZoom={0.2} maxZoom={1.5} snapToGrid snapGrid={[20, 20]}><Background gap={20} size={1} /><Controls /></ReactFlow></Paper>
      <Paper variant="outlined" sx={{ width: { xs: '100%', xl: 390 }, p: 2, overflow: 'auto', maxHeight: { xl: 'calc(100vh - 235px)' } }}>
        {selected ? <NodeEditor node={selected.data.workflowNode} schema={schema} providers={config.providers} models={config.models} roles={roles} update={updateSelected} duplicateSeat={duplicateSelectedSeat} remove={removeSelected} /> : selectedEdge ? <EdgeEditor edge={selectedEdge.data?.workflowEdge as WorkflowEdge} schema={schema} update={updateSelectedEdge} remove={removeSelectedEdge} /> : <Box sx={{ py: 6, textAlign: 'center', color: 'text.secondary' }}><Typography>选择节点或连线后在这里配置</Typography></Box>}
      </Paper>
    </Stack>
  </Stack>;
}

function NodeEditor({ node, schema, providers, models, roles, update, duplicateSeat, remove }: { node: WorkflowNode; schema: WorkflowSchema; providers: AiProvider[]; models: AiModel[]; roles: AgentRole[]; update: (patch: Partial<WorkflowNode>) => void; duplicateSeat: () => void; remove: () => void }) {
  const llmBacked = isLlmBacked(node.nodeType);
  const changeNodeType = (nodeType: WorkflowNodeType) => {
    if (!isLlmBacked(nodeType)) {
      update({ nodeType, roleName: null, roleTemplateId: null, logicalRoleKey: null, primaryAiBinding: null, fallbackAiBinding: null, systemPrompt: null, userPromptTemplate: null, outputContract: nodeType === 'SOCIAL_CHOICE' ? 'CONSENSUS_RESULT' : null, operation: defaultOperation(nodeType) });
      return;
    }
    const primaryAiBinding = node.primaryAiBinding || defaultAiBinding(providers, models);
    update({
      nodeType,
      roleName: node.roleName || 'Custom Analyst',
      logicalRoleKey: isDebateSeat(nodeType) ? node.logicalRoleKey || `role_${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}` : null,
      primaryAiBinding,
      systemPrompt: node.systemPrompt || defaultSystemPrompt(nodeType),
      userPromptTemplate: node.userPromptTemplate || defaultUserPrompt(nodeType),
      outputContract: node.outputContract || defaultOutputContract(nodeType),
      operation: defaultOperation(nodeType),
    });
  };
  return <Stack spacing={1.5}><SectionTitle title="节点配置" action={<Stack direction="row" spacing={.5}>{llmBacked && <Button size="small" startIcon={<ContentCopyIcon />} onClick={duplicateSeat}>新增异构席位</Button>}<Button color="error" size="small" startIcon={<DeleteOutlineIcon />} onClick={remove}>删除</Button></Stack>} /><TextField label="节点 ID" value={node.nodeId} disabled /><TextField label="标题" value={node.displayName} onChange={(event) => update({ displayName: event.target.value })} /><TextField select label="节点类型" value={node.nodeType} onChange={(event) => changeNodeType(event.target.value as WorkflowNodeType)}>{schema.nodeTypes.map((type) => <MenuItem key={type} value={type}>{type}</MenuItem>)}</TextField><FormControlLabel control={<Switch checked={node.enabled} onChange={(event) => update({ enabled: event.target.checked })} />} label="启用节点" />
    {node.nodeType === 'QUANT'
      ? <TextField select label="量化方案" value={node.operation || ''} onChange={(event) => update({ operation: event.target.value || null })} helperText="每种策略都会附带 MACD、均线交叉、RSI、布林带、ATR、支撑与压力指标供后续 AI 参考">{QUANT_OPERATIONS.map((operation) => <MenuItem key={operation.id} value={operation.id}>{operation.label}</MenuItem>)}</TextField>
      : <TextField label="受控操作" value={node.operation || ''} onChange={(event) => update({ operation: event.target.value || null })} helperText="填写后端登记的 operation ID，不执行任意脚本或 URL" />}
    {llmBacked && node.primaryAiBinding && <><TextField select label="角色模板" value={node.roleTemplateId || ''} onChange={(event) => { const role = roles.find((item) => item.roleTemplateId === event.target.value); if (!role) { update({ roleTemplateId: null }); return; } update({ roleTemplateId: role.roleTemplateId, logicalRoleKey: isDebateSeat(node.nodeType) ? role.roleTemplateId : null, roleName: role.displayName, systemPrompt: role.systemPrompt, userPromptTemplate: role.userPromptTemplate, outputContract: role.outputContract, primaryAiBinding: { providerProfileId: role.defaultProviderProfileId, modelName: role.defaultModelName, reasoningEffort: role.defaultReasoningEffort } }); }}><MenuItem value="">不绑定模板</MenuItem>{roles.map((role) => <MenuItem key={role.roleTemplateId} value={role.roleTemplateId}>{role.displayName}</MenuItem>)}</TextField><TextField label="角色名称" value={node.roleName || ''} onChange={(event) => update({ roleName: event.target.value })} />{isDebateSeat(node.nodeType) && <TextField label="逻辑角色 Key" value={node.logicalRoleKey || ''} onChange={(event) => update({ logicalRoleKey: event.target.value.toLowerCase() })} helperText="同一角色的异构模型席位必须使用相同 Key；社会选择时该角色总权重固定为 1" inputProps={{ pattern: '^[a-z][a-z0-9_-]{1,79}$' }} />}<TextField select label="输出契约" value={node.outputContract || ''} onChange={(event) => update({ outputContract: event.target.value as WorkflowOutputContract })}>{schema.outputContracts.map((contract) => <MenuItem key={contract} value={contract}>{contract}</MenuItem>)}</TextField><AiBindingEditor title="主模型" binding={node.primaryAiBinding} providers={providers} models={models} efforts={schema.reasoningEfforts} update={(primaryAiBinding) => update({ primaryAiBinding })} /><FormControlLabel control={<Switch checked={node.fallbackAiBinding !== null} onChange={(event) => {
      if (!event.target.checked) { update({ fallbackAiBinding: null }); return; }
      const fallbackModel = models.find((model) => model.enabled && model.providerProfileId !== providerId(node.primaryAiBinding!)) || models.find((model) => model.enabled) || models[0];
      if (fallbackModel) update({ fallbackAiBinding: { providerProfileId: fallbackModel.providerProfileId, modelName: fallbackModel.modelName, reasoningEffort: fallbackModel.defaultReasoningEffort } });
    }} />} label="启用兜底模型" />{node.fallbackAiBinding && <AiBindingEditor title="兜底模型" binding={node.fallbackAiBinding} providers={providers} models={models} efforts={schema.reasoningEfforts} update={(fallbackAiBinding) => update({ fallbackAiBinding })} />}<TextField multiline minRows={5} label="系统提示词" value={node.systemPrompt || ''} onChange={(event) => update({ systemPrompt: event.target.value })} /><TextField multiline minRows={3} label="用户提示模板" value={node.userPromptTemplate || ''} onChange={(event) => update({ userPromptTemplate: event.target.value })} /></>}
    <TextField select label="上下文模式" value={node.contextMode} onChange={(event) => update({ contextMode: event.target.value })}>{schema.contextModes.map((mode) => <MenuItem key={mode} value={mode}>{mode}</MenuItem>)}</TextField><Stack direction="row" spacing={1}><TextField label="历史轮次" type="number" value={node.contextHistoryRounds} onChange={(event) => update({ contextHistoryRounds: Number(event.target.value) })} /><TextField label="上下文消息" type="number" value={node.contextMaximumMessages} onChange={(event) => update({ contextMaximumMessages: Number(event.target.value) })} /></Stack><Stack direction="row" spacing={1}><TextField label="重试次数" type="number" value={node.retryMaximumAttempts} onChange={(event) => update({ retryMaximumAttempts: Number(event.target.value) })} /><TextField label="退避秒数" type="number" value={node.retryBackoffSeconds} onChange={(event) => update({ retryBackoffSeconds: Number(event.target.value) })} /></Stack><Stack direction="row" spacing={1}><TextField label="最大 Token" type="number" value={node.maximumOutputTokens} onChange={(event) => update({ maximumOutputTokens: Number(event.target.value) })} /><TextField label="超时秒数" type="number" value={node.timeoutSeconds} onChange={(event) => update({ timeoutSeconds: Number(event.target.value) })} inputProps={{ min: 5, max: 3600, step: 1 }} /></Stack>
  </Stack>;
}

function EdgeEditor({ edge, schema, update, remove }: { edge: WorkflowEdge; schema: WorkflowSchema; update: (patch: Partial<WorkflowEdge>) => void; remove: () => void }) {
  const condition = edge.condition;
  const setConditionEnabled = (enabled: boolean) => update({ condition: enabled ? defaultCondition() : null, loopEdge: enabled ? edge.loopEdge : false, maximumTraversals: enabled ? edge.maximumTraversals : null });
  const updateCondition = (patch: Partial<WorkflowCondition>) => {
    if (!condition) return;
    update({ condition: { ...condition, ...patch } });
  };
  const updateOperator = (operator: string) => {
    if (!condition) return;
    updateCondition({ operator, operand: operatorRequiresOperand(operator) ? condition.operand || defaultCondition().operand : null });
  };
  return <Stack spacing={1.5}>
    <SectionTitle title="连线配置" action={<Button color="error" size="small" startIcon={<DeleteOutlineIcon />} onClick={remove}>删除</Button>} />
    <TextField label="连线 ID" value={edge.edgeId} disabled />
    <Stack direction="row" spacing={1}><TextField label="来源节点" value={edge.sourceNodeId} disabled /><TextField label="目标节点" value={edge.targetNodeId} disabled /></Stack>
    <TextField select label="激活模式" value={edge.activationMode} onChange={(event) => update({ activationMode: event.target.value })}><MenuItem value="ALL">全部上游满足</MenuItem><MenuItem value="ANY">任一上游满足</MenuItem></TextField>
    <TextField select label="上下文传递" value={edge.contextMode} onChange={(event) => update({ contextMode: event.target.value })}>{schema.edgeContextModes.map((mode) => <MenuItem key={mode} value={mode}>{edgeContextLabel(mode)}</MenuItem>)}</TextField>
    <FormControlLabel control={<Switch checked={condition !== null} onChange={(event) => setConditionEnabled(event.target.checked)} />} label="使用受控条件" />
    {condition && <>
      <TextField label="状态字段路径" value={condition.field} onChange={(event) => updateCondition({ field: event.target.value })} helperText="例如 current.confidence 或 current.needs_revision" />
      <TextField select label="条件操作符" value={condition.operator} onChange={(event) => updateOperator(event.target.value)}>{schema.conditionOperators.map((operator) => <MenuItem key={operator} value={operator}>{conditionOperatorLabel(operator)}</MenuItem>)}</TextField>
      {condition.operand && <ConditionOperandEditor operand={condition.operand} update={(operand) => updateCondition({ operand })} />}
    </>}
    <FormControlLabel control={<Switch checked={edge.loopEdge} onChange={(event) => {
      const loopEdge = event.target.checked;
      update({ loopEdge, condition: loopEdge ? condition || defaultCondition() : condition, maximumTraversals: loopEdge ? edge.maximumTraversals || 1 : null });
    }} />} label="条件循环边" />
    {edge.loopEdge && <TextField label="最大循环次数" type="number" value={edge.maximumTraversals || 1} onChange={(event) => update({ maximumTraversals: Number(event.target.value) })} inputProps={{ min: 1, max: 8 }} />}
  </Stack>;
}

function ConditionOperandEditor({ operand, update }: { operand: NonNullable<WorkflowCondition['operand']>; update: (operand: NonNullable<WorkflowCondition['operand']>) => void }) {
  const changeType = (type: WorkflowConditionOperandType) => update({ type, textValue: type === 'TEXT' ? '' : null, decimalValue: type === 'DECIMAL' ? 0 : null, booleanValue: type === 'BOOLEAN' ? true : null, textValues: type === 'TEXT_LIST' ? [] : null });
  return <Stack spacing={1.25}>
    <TextField select label="比较值类型" value={operand.type} onChange={(event) => changeType(event.target.value as WorkflowConditionOperandType)}><MenuItem value="TEXT">文本</MenuItem><MenuItem value="DECIMAL">数值</MenuItem><MenuItem value="BOOLEAN">布尔</MenuItem><MenuItem value="TEXT_LIST">文本列表</MenuItem></TextField>
    {operand.type === 'TEXT' && <TextField label="比较文本" value={operand.textValue || ''} onChange={(event) => update({ ...operand, textValue: event.target.value })} />}
    {operand.type === 'DECIMAL' && <TextField label="比较数值" type="number" value={operand.decimalValue ?? 0} onChange={(event) => update({ ...operand, decimalValue: Number(event.target.value) })} />}
    {operand.type === 'BOOLEAN' && <TextField select label="比较布尔值" value={String(operand.booleanValue ?? true)} onChange={(event) => update({ ...operand, booleanValue: event.target.value === 'true' })}><MenuItem value="true">true</MenuItem><MenuItem value="false">false</MenuItem></TextField>}
    {operand.type === 'TEXT_LIST' && <TextField label="比较列表" value={(operand.textValues || []).join(', ')} onChange={(event) => update({ ...operand, textValues: event.target.value.split(',').map((value) => value.trim()).filter(Boolean) })} helperText="使用英文逗号分隔" />}
  </Stack>;
}

function AiBindingEditor({ title, binding, providers, models, efforts, update }: { title: string; binding: AiModelBinding; providers: AiProvider[]; models: AiModel[]; efforts: ReasoningEffort[]; update: (binding: AiModelBinding) => void }) {
  const selectedProviderId = providerId(binding);
  const providerModels = models.filter((model) => model.providerProfileId === selectedProviderId);
  const selectedModel = providerModels.find((model) => model.modelName === binding.modelName);
  const supportedEfforts = selectedModel ? reasoningEffortsForModel(efforts, selectedModel) : efforts;
  return <Stack spacing={1.25} sx={{ borderTop: '1px solid', borderColor: 'divider', pt: 1.5 }}><Typography variant="subtitle2">{title}</Typography><TextField select label="AI 厂商" value={selectedProviderId} onChange={(event) => {
    const nextModel = models.find((model) => model.enabled && model.providerProfileId === event.target.value) || models.find((model) => model.providerProfileId === event.target.value);
    update({ providerProfileId: event.target.value, modelName: nextModel?.modelName || binding.modelName, reasoningEffort: nextModel?.defaultReasoningEffort || binding.reasoningEffort });
  }}>{providers.map((provider) => <MenuItem key={provider.profileId} value={provider.profileId}>{provider.displayName}</MenuItem>)}</TextField><TextField select label="模型" value={binding.modelName} onChange={(event) => { const model = providerModels.find((item) => item.modelName === event.target.value); update({ ...binding, modelName: event.target.value, reasoningEffort: model?.defaultReasoningEffort || binding.reasoningEffort }); }}>{providerModels.map((model) => <MenuItem key={model.modelProfileId} value={model.modelName}>{model.modelName}</MenuItem>)}{!providerModels.some((model) => model.modelName === binding.modelName) && <MenuItem value={binding.modelName}>{binding.modelName}</MenuItem>}</TextField><TextField select label="思考强度" value={binding.reasoningEffort} onChange={(event) => update({ ...binding, reasoningEffort: event.target.value as ReasoningEffort })}>{supportedEfforts.map((effort) => <MenuItem key={effort} value={effort}>{effort}</MenuItem>)}</TextField></Stack>;
}

function providerId(binding: AiModelBinding): string {
  return binding.providerProfileId;
}

function reasoningEffortsForModel(efforts: ReasoningEffort[], model: AiModel): ReasoningEffort[] {
  const maximumIndex = efforts.indexOf(model.maximumReasoningEffort);
  return efforts.filter((effort, index) => effort === 'PROVIDER_DEFAULT' || (maximumIndex > 0 && index <= maximumIndex));
}

function isLlmBacked(nodeType: string): boolean {
  return ['AI_CLEANER', 'COMPRESSOR', 'COMPRESSION_VALIDATOR', 'AGENT', 'AGGREGATOR', 'CHAIR', 'EXECUTION_REVIEW'].includes(nodeType);
}

function isDebateSeat(nodeType: string): boolean {
  return nodeType === 'AGENT' || nodeType === 'AGGREGATOR';
}

const NODE_LABELS: Record<string, string> = {
  INPUT: '研究输入', ROUTER: '条件路由', DETERMINISTIC: '确定性处理', COLLECTOR: '信息采集', CLEANER: '确定性证据清洗', AI_CLEANER: 'AI 清洗审查', COMPRESSOR: 'AI 信息压缩', COMPRESSION_VALIDATOR: '压缩独立验证', AGENT: 'AI 分析角色', GATE: '条件门禁', QUANT: '量化研究', RISK: '确定性风控', SUBFLOW: '子工作流', HUMAN_REVIEW: '人工复核', AGGREGATOR: 'AI 聚合', CHAIR: '主席仲裁', SOCIAL_CHOICE: '对称社会选择', EXECUTION_REVIEW: '执行机器人', OUTPUT: '研究输出',
};

const QUANT_OPERATIONS = [
  { id: 'multi_strategy_ensemble', label: '多策略投票集成（推荐）' },
  { id: 'moving_average_crossover', label: '均线交叉趋势' },
  { id: 'breakout', label: '区间突破' },
  { id: 'mean_reversion', label: '均值回归' },
  { id: 'rsi_momentum', label: 'RSI 动量' },
  { id: 'volume_confirmed_trend', label: '成交量确认趋势' },
  { id: 'statistical_analysis', label: '仅统计分析' },
] as const;

function nodeTypeLabel(nodeType: string): string {
  return `${NODE_LABELS[nodeType] || nodeType} · ${nodeType}`;
}

function defaultNodeName(nodeType: string): string {
  return NODE_LABELS[nodeType] || '新工作流节点';
}

function defaultOperation(nodeType: string): string | null {
  return ({ INPUT: 'research_input', ROUTER: 'route_candidate', DETERMINISTIC: 'transform_research_state', COLLECTOR: 'collect_enabled_sources', CLEANER: 'normalize_and_deduplicate', QUANT: 'multi_strategy_ensemble', GATE: 'evaluate_research_gate', RISK: 'evaluate_risk_gate', SUBFLOW: 'invoke_published_subflow', HUMAN_REVIEW: 'operator_review', SOCIAL_CHOICE: 'schulze_social_choice', EXECUTION_REVIEW: 'draft', OUTPUT: 'research_output' } as Record<string, string>)[nodeType] || null;
}

function defaultOutputContract(nodeType: string): WorkflowOutputContract {
  return ({ AI_CLEANER: 'RESEARCH_FINDINGS', COMPRESSOR: 'RESEARCH_FINDINGS', COMPRESSION_VALIDATOR: 'RESEARCH_FINDINGS', AGGREGATOR: 'RESEARCH_FINDINGS', CHAIR: 'CHAIR_VERDICT', SOCIAL_CHOICE: 'CONSENSUS_RESULT', EXECUTION_REVIEW: 'TRADE_DECISIONS' } as Partial<Record<WorkflowNodeType, WorkflowOutputContract>>)[nodeType as WorkflowNodeType] || 'DEBATE_ARGUMENT';
}

function defaultSystemPrompt(nodeType: string): string {
  if (nodeType === 'EXECUTION_REVIEW') return '你是模拟交易执行机器人。只依据主席裁决与可追溯证据生成严格 JSON；证据不足时必须 WATCH。不得输出隐藏思维链。';
  if (nodeType === 'CHAIR') return '你是独立主席。综合全部可审计观点、反例和修订，输出严格 JSON 裁决，不得输出隐藏思维链。';
  if (nodeType === 'AI_CLEANER') return '你是证据清洗审查员。识别广告、导航、重复、无关和疑似污染内容；不得改写事实，输出严格 JSON 并保留引用。';
  if (nodeType === 'COMPRESSOR') return '你是研究信息压缩器。只能压缩输入证据，不得新增事实，输出严格 JSON 并保留引用。';
  if (nodeType === 'COMPRESSION_VALIDATOR') return '你是独立压缩验证员。对照原文审查全部候选，修复遗漏和事实漂移，只输出经过验证的严格 JSON。';
  return '你是专业市场研究角色。基于上游证据进行独立分析，只输出工作流要求的严格 JSON。';
}

function defaultUserPrompt(nodeType: string): string {
  if (nodeType === 'EXECUTION_REVIEW') return '审查主席裁决并生成可执行或观察决策，输出工作流指定的交易 JSON。';
  if (nodeType === 'CHAIR') return '独立综合完整多轮辩论，保留主要分歧、缺失证据和最终裁决。';
  if (nodeType === 'AI_CLEANER') return '审查单个证据文档的噪声、重复、相关性和事实边界，输出清洗建议、关键点、风险、缺口和引用。';
  if (nodeType === 'COMPRESSOR') return '压缩单个证据文档，输出摘要、关键点、风险、缺口和引用。';
  if (nodeType === 'COMPRESSION_VALIDATOR') return '对照原文验证所有清洗与压缩候选，输出无遗漏、无事实漂移的最终摘要。';
  return '分析上游研究证据和其他角色观点，给出可验证结论、挑战与修订。';
}

function defaultCondition(): WorkflowCondition {
  return { field: 'current.confidence', operator: 'GTE', operand: { type: 'DECIMAL', textValue: null, decimalValue: 0.5, booleanValue: null, textValues: null } };
}

function operatorRequiresOperand(operator: string): boolean {
  return !['EXISTS', 'TRUTHY', 'FALSY'].includes(operator);
}

function conditionOperatorLabel(operator: string): string {
  return ({ EXISTS: '字段存在', EQ: '等于', NE: '不等于', IN: '属于列表', NOT_IN: '不属于列表', GT: '大于', GTE: '大于等于', LT: '小于', LTE: '小于等于', CONTAINS: '包含', TRUTHY: '为真', FALSY: '为假' } as Record<string, string>)[operator] || operator;
}

function edgeContextLabel(mode: string): string {
  return ({ INHERIT: '继承节点策略', INCLUDE: '传递完整上下文', EXCLUDE: '不传递上下文', LATEST: '仅最新结果', CLAIMS_ONLY: '仅结构化主张', SUMMARY: '仅摘要' } as Record<string, string>)[mode] || mode;
}

function failurePolicyLabel(policy: string): string {
  return ({ STOP: '失败即停止', CONTINUE: '记录失败并继续', REPLAN: '停止并请求重规划' } as Record<string, string>)[policy] || policy;
}

function defaultAiBinding(providers: AiProvider[], models: AiModel[]): AiModelBinding | null {
  const provider = providers.find((item) => item.enabled) || providers[0];
  const model = models.find((item) => item.enabled && item.providerProfileId === provider?.profileId)
    || models.find((item) => item.providerProfileId === provider?.profileId)
    || models.find((item) => item.enabled)
    || models[0];
  if (!provider || !model) return null;
  return { providerProfileId: provider.profileId, modelName: model.modelName, reasoningEffort: model.defaultReasoningEffort };
}

function EstimateMetric({ label, value }: { label: string; value: string }) {
  return <Box><Typography variant="caption" color="text.secondary">{label}</Typography><Typography fontWeight={800}>{value}</Typography></Box>;
}
