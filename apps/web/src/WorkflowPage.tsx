import AddIcon from '@mui/icons-material/Add';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import PublishIcon from '@mui/icons-material/Publish';
import SaveIcon from '@mui/icons-material/Save';
import { Background, Controls, ReactFlow, addEdge, useEdgesState, useNodesState } from '@xyflow/react';
import type { Connection, Edge, Node } from '@xyflow/react';
import { Alert, Box, Button, Chip, FormControlLabel, MenuItem, Paper, Stack, Switch, TextField, Typography } from '@mui/material';
import { useEffect, useMemo, useState } from 'react';

import { api } from './api';
import type { AiModel, AiProvider, ConfigurationSnapshot, ReasoningEffort, WorkflowDefinitionSummary, WorkflowEdge, WorkflowNode, WorkflowSchema, WorkflowVersion } from './types';
import { ErrorBlock, LoadingBlock, SectionTitle } from './ui';

interface CanvasData extends Record<string, unknown> { label: string; workflowNode: WorkflowNode }
type CanvasNode = Node<CanvasData>;

export function WorkflowPage() {
  const [definitions, setDefinitions] = useState<WorkflowDefinitionSummary[]>([]);
  const [definition, setDefinition] = useState<WorkflowDefinitionSummary | null>(null);
  const [version, setVersion] = useState<WorkflowVersion | null>(null);
  const [schema, setSchema] = useState<WorkflowSchema | null>(null);
  const [config, setConfig] = useState<ConfigurationSnapshot | null>(null);
  const [nodes, setNodes, onNodesChange] = useNodesState<CanvasNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    Promise.all([api.workflowDefinitions(), api.workflowSchema(), api.configuration()]).then(async ([items, workflowSchema, configuration]) => {
      setDefinitions(items); setSchema(workflowSchema); setConfig(configuration);
      const first = items[0]; setDefinition(first || null);
      const versionId = first?.draftVersionId || first?.publishedVersionId;
      if (versionId) applyVersion(await api.workflowVersion(versionId));
    }).catch(setError);
  }, []);

  const applyVersion = (loaded: WorkflowVersion) => {
    setVersion(loaded);
    setNodes(loaded.nodes.map((node) => ({ id: node.nodeId, position: { x: Number(node.positionX), y: Number(node.positionY) }, data: { label: `${node.displayName}\n${node.modelName || node.nodeType}`, workflowNode: node } })));
    setEdges(loaded.edges.map((edge) => ({ id: edge.edgeId, source: edge.sourceNodeId, target: edge.targetNodeId, data: { workflowEdge: edge } })));
    setSelectedId(null);
  };

  const selectDefinition = async (definitionId: string) => {
    const next = definitions.find((item) => item.definitionId === definitionId) || null;
    setDefinition(next); setMessage('');
    const versionId = next?.draftVersionId || next?.publishedVersionId;
    if (versionId) try { applyVersion(await api.workflowVersion(versionId)); } catch (cause) { setError(cause); }
  };

  const onConnect = (connection: Connection) => {
    if (!connection.source || !connection.target) return;
    const workflowEdge: WorkflowEdge = { edgeId: `edge_${crypto.randomUUID().replace(/-/g, '').slice(0, 16)}`, sourceNodeId: connection.source, targetNodeId: connection.target, activationMode: 'ALL', contextMode: 'INCLUDE', condition: null, loopEdge: false, maximumTraversals: null };
    setEdges((current) => addEdge({ ...connection, id: workflowEdge.edgeId, data: { workflowEdge } }, current));
  };

  const selected = nodes.find((node) => node.id === selectedId) || null;
  const updateSelected = (patch: Partial<WorkflowNode>) => {
    if (!selectedId) return;
    setNodes((current) => current.map((node) => node.id === selectedId ? { ...node, data: { ...node.data, label: `${patch.displayName || node.data.workflowNode.displayName}\n${patch.modelName || node.data.workflowNode.modelName || patch.nodeType || node.data.workflowNode.nodeType}`, workflowNode: { ...node.data.workflowNode, ...patch } } } : node));
  };

  const addAgent = () => {
    if (!config) return;
    const provider = config.providers.find((item) => item.enabled) || config.providers[0];
    const model = config.models.find((item) => item.enabled && item.providerProfileId === provider?.profileId) || config.models[0];
    if (!provider || !model) { setError(new Error('没有可用的 AI 厂商或模型')); return; }
    const nodeId = `node_agent_${crypto.randomUUID().replace(/-/g, '').slice(0, 12)}`;
    const workflowNode: WorkflowNode = { nodeId, nodeType: 'AGENT', displayName: '新分析角色', roleName: 'Custom Analyst', roleTemplateId: null, providerProfileId: provider.profileId, modelName: model.modelName, reasoningEffort: model.defaultReasoningEffort, systemPrompt: '你是专业市场研究角色。基于上游证据进行独立分析，只输出工作流要求的严格 JSON。', userPromptTemplate: '分析上游研究证据和其他角色观点，给出可验证结论、挑战与修订。', outputContract: 'DEBATE_ARGUMENT', contextMode: 'UPSTREAM', contextHistoryRounds: version?.defaultDebateRounds || 3, contextMaximumMessages: 24, maximumOutputTokens: 4096, timeoutSeconds: 180, retryMaximumAttempts: 2, retryBackoffSeconds: 2, operation: null, positionX: 760, positionY: 120 + nodes.length * 45, enabled: true };
    setNodes((current) => [...current, { id: nodeId, position: { x: workflowNode.positionX, y: workflowNode.positionY }, data: { label: `${workflowNode.displayName}\n${workflowNode.modelName}`, workflowNode } }]);
    setSelectedId(nodeId);
  };

  const removeSelected = () => {
    if (!selectedId) return;
    setNodes((current) => current.filter((node) => node.id !== selectedId));
    setEdges((current) => current.filter((edge) => edge.source !== selectedId && edge.target !== selectedId));
    setSelectedId(null);
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
      const saved = await api.saveWorkflowDraft({ definitionId: definition.definitionId, versionId: version.status === 'DRAFT' ? version.versionId : null, name: definition.name, description: definition.description, defaultDebateRounds: version.defaultDebateRounds, maximumSteps: version.maximumSteps, maximumDurationSeconds: version.maximumDurationSeconds, maximumTokens: version.maximumTokens, maximumCostUsd: version.maximumCostUsd, failurePolicy: version.failurePolicy, expectedChecksum: version.status === 'DRAFT' ? version.checksum : null, nodes: currentNodes, edges: currentEdges });
      applyVersion(saved); setMessage('草稿已保存');
      const updatedDefinitions = await api.workflowDefinitions(); setDefinitions(updatedDefinitions); setDefinition(updatedDefinitions.find((item) => item.definitionId === definition.definitionId) || definition);
    } catch (cause) { setError(cause); } finally { setSaving(false); }
  };

  const publish = async () => {
    if (!version || version.status !== 'DRAFT') return;
    setSaving(true); try { applyVersion(await api.publishWorkflow(version.versionId)); setMessage('工作流已发布'); setDefinitions(await api.workflowDefinitions()); } catch (cause) { setError(cause); } finally { setSaving(false); }
  };

  if (error !== null && (!version || !schema || !config || !definition)) return <ErrorBlock error={error} />;
  if (!version || !schema || !config || !definition) return <LoadingBlock label="正在加载工作流定义" />;
  return <Stack spacing={2}>
    {error !== null && <ErrorBlock error={error} />}{message && <Alert severity="success">{message}</Alert>}
    <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.25} alignItems={{ md: 'center' }}><TextField select label="工作流" value={definition.definitionId} onChange={(event) => void selectDefinition(event.target.value)} sx={{ minWidth: 260 }}>{definitions.map((item) => <MenuItem key={item.definitionId} value={item.definitionId}>{item.name}</MenuItem>)}</TextField><TextField label="辩论轮次" type="number" value={version.defaultDebateRounds} onChange={(event) => setVersion({ ...version, defaultDebateRounds: Number(event.target.value) })} inputProps={{ min: 1, max: 8 }} sx={{ width: 120 }} /><Chip label={`${version.status} v${version.versionNumber}`} color={version.status === 'PUBLISHED' ? 'success' : 'warning'} /><Box sx={{ flex: 1 }} /><Button startIcon={<AddIcon />} onClick={addAgent}>添加角色</Button><Button variant="contained" startIcon={<SaveIcon />} disabled={saving} onClick={() => void save()}>保存草稿</Button><Button color="success" variant="contained" startIcon={<PublishIcon />} disabled={saving || version.status !== 'DRAFT'} onClick={() => void publish()}>发布</Button></Stack>
    <Stack direction={{ xs: 'column', xl: 'row' }} spacing={1.5} alignItems="stretch">
      <Paper variant="outlined" sx={{ height: { xs: 560, xl: 'calc(100vh - 235px)' }, minHeight: 560, flex: 1, overflow: 'hidden' }}><ReactFlow nodes={nodes} edges={edges} onNodesChange={onNodesChange} onEdgesChange={onEdgesChange} onConnect={onConnect} onNodeClick={(_event, node) => setSelectedId(node.id)} fitView minZoom={0.2} maxZoom={1.5} snapToGrid snapGrid={[20, 20]}><Background gap={20} size={1} /><Controls /></ReactFlow></Paper>
      <Paper variant="outlined" sx={{ width: { xs: '100%', xl: 390 }, p: 2, overflow: 'auto', maxHeight: { xl: 'calc(100vh - 235px)' } }}>
        {selected ? <NodeEditor node={selected.data.workflowNode} schema={schema} providers={config.providers} models={config.models} update={updateSelected} remove={removeSelected} /> : <Box sx={{ py: 6, textAlign: 'center', color: 'text.secondary' }}><Typography>未选择节点</Typography></Box>}
      </Paper>
    </Stack>
  </Stack>;
}

function NodeEditor({ node, schema, providers, models, update, remove }: { node: WorkflowNode; schema: WorkflowSchema; providers: AiProvider[]; models: AiModel[]; update: (patch: Partial<WorkflowNode>) => void; remove: () => void }) {
  const llmBacked = ['COMPRESSOR', 'AGENT', 'AGGREGATOR', 'CHAIR', 'EXECUTION_REVIEW'].includes(node.nodeType);
  const providerModels = models.filter((model) => model.providerProfileId === node.providerProfileId);
  return <Stack spacing={1.5}><SectionTitle title="节点配置" action={<Button color="error" size="small" startIcon={<DeleteOutlineIcon />} onClick={remove}>删除</Button>} /><TextField label="节点 ID" value={node.nodeId} disabled /><TextField label="标题" value={node.displayName} onChange={(event) => update({ displayName: event.target.value })} /><TextField select label="节点类型" value={node.nodeType} onChange={(event) => update({ nodeType: event.target.value })}>{schema.nodeTypes.map((type) => <MenuItem key={type} value={type}>{type}</MenuItem>)}</TextField><FormControlLabel control={<Switch checked={node.enabled} onChange={(event) => update({ enabled: event.target.checked })} />} label="启用节点" />
    {llmBacked && <><TextField label="角色名称" value={node.roleName || ''} onChange={(event) => update({ roleName: event.target.value })} /><TextField select label="AI 厂商" value={node.providerProfileId || ''} onChange={(event) => update({ providerProfileId: event.target.value, modelName: models.find((model) => model.providerProfileId === event.target.value)?.modelName || node.modelName })}>{providers.map((provider) => <MenuItem key={provider.profileId} value={provider.profileId}>{provider.displayName}</MenuItem>)}</TextField><TextField select label="模型" value={node.modelName || ''} onChange={(event) => update({ modelName: event.target.value })}>{providerModels.map((model) => <MenuItem key={model.modelProfileId} value={model.modelName}>{model.modelName}</MenuItem>)}{node.modelName && !providerModels.some((model) => model.modelName === node.modelName) && <MenuItem value={node.modelName}>{node.modelName}</MenuItem>}</TextField><TextField select label="思考强度" value={node.reasoningEffort || 'PROVIDER_DEFAULT'} onChange={(event) => update({ reasoningEffort: event.target.value as ReasoningEffort })}>{schema.reasoningEfforts.map((effort) => <MenuItem key={effort} value={effort}>{effort}</MenuItem>)}</TextField><TextField multiline minRows={5} label="系统提示词" value={node.systemPrompt || ''} onChange={(event) => update({ systemPrompt: event.target.value })} /><TextField multiline minRows={3} label="用户提示模板" value={node.userPromptTemplate || ''} onChange={(event) => update({ userPromptTemplate: event.target.value })} /></>}
    <TextField select label="上下文模式" value={node.contextMode} onChange={(event) => update({ contextMode: event.target.value })}>{schema.contextModes.map((mode) => <MenuItem key={mode} value={mode}>{mode}</MenuItem>)}</TextField><Stack direction="row" spacing={1}><TextField label="历史轮次" type="number" value={node.contextHistoryRounds} onChange={(event) => update({ contextHistoryRounds: Number(event.target.value) })} /><TextField label="上下文消息" type="number" value={node.contextMaximumMessages} onChange={(event) => update({ contextMaximumMessages: Number(event.target.value) })} /></Stack><Stack direction="row" spacing={1}><TextField label="重试次数" type="number" value={node.retryMaximumAttempts} onChange={(event) => update({ retryMaximumAttempts: Number(event.target.value) })} /><TextField label="退避秒数" type="number" value={node.retryBackoffSeconds} onChange={(event) => update({ retryBackoffSeconds: Number(event.target.value) })} /></Stack><Stack direction="row" spacing={1}><TextField label="最大 Token" type="number" value={node.maximumOutputTokens} onChange={(event) => update({ maximumOutputTokens: Number(event.target.value) })} /><TextField label="超时秒数" type="number" value={node.timeoutSeconds} onChange={(event) => update({ timeoutSeconds: Number(event.target.value) })} /></Stack>
  </Stack>;
}
