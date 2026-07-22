import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import KeyIcon from '@mui/icons-material/Key';
import SaveIcon from '@mui/icons-material/Save';
import { Alert, Autocomplete, Box, Button, Chip, FormControlLabel, MenuItem, Paper, Stack, Switch, Tab, Tabs, TextField, Typography } from '@mui/material';
import { useEffect, useState } from 'react';

import { api } from './api';
import { SecretTextField } from './SecretTextField';
import type { AgentRole, AiExperiment, AiModel, AiModelBinding, AiProvider, ConfigurationSnapshot, ExecutionAiStage, ProviderModelCatalog, ReasoningEffort, RiskPolicy, SetupProfileDefinition, TradeAutomationConfiguration, WorkflowDefinitionSummary } from './types';
import { ErrorBlock, LoadingBlock, SectionTitle } from './ui';
import { AgentRolesPanel, AiExperimentsPanel, SetupProfilesPanel } from './SettingsAdvancedPanels';
import { ApiTokensPanel } from './ApiTokensPanel';
import { replaceWorkspaceLocation, workspaceSubview } from './workspaceLocation';

const efforts: ReasoningEffort[] = ['PROVIDER_DEFAULT', 'NONE', 'MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'];
const settingsTabs = ['setup', 'runtime', 'providers', 'models', 'roles', 'execution', 'experiments', 'tokens'] as const;
type SettingsTab = typeof settingsTabs[number];

export function SettingsPage() {
  const [tab, setTab] = useState<SettingsTab>(() => workspaceSubview('settings', settingsTabs, 'setup'));
  const [config, setConfig] = useState<ConfigurationSnapshot | null>(null);
  const [trading, setTrading] = useState<TradeAutomationConfiguration | null>(null);
  const [profiles, setProfiles] = useState<SetupProfileDefinition[]>([]);
  const [roles, setRoles] = useState<AgentRole[]>([]);
  const [experiments, setExperiments] = useState<AiExperiment[]>([]);
  const [definitions, setDefinitions] = useState<WorkflowDefinitionSummary[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const load = async () => { try { const [system, execution, setupProfiles, agentRoles, aiExperiments, workflows] = await Promise.all([api.configuration(), api.tradeAutomationConfiguration(), api.setupProfiles(), api.agentRoles(), api.aiExperiments(), api.workflowDefinitions()]); setConfig(system); setTrading(execution); setProfiles(setupProfiles); setRoles(agentRoles); setExperiments(aiExperiments); setDefinitions(workflows); } catch (cause) { setError(cause); } };
  useEffect(() => { void load(); }, []);
  const saved = async (work: Promise<unknown>) => { setError(null); setMessage(''); try { await work; setMessage('配置已保存'); await load(); } catch (cause) { setError(cause); } };
  const createProvider = async (body: Record<string, unknown>, apiKey: string, modelNames: string[], maximumEffort: ReasoningEffort) => {
    setError(null); setMessage('');
    let provider: AiProvider;
    try {
      provider = await api.createProvider(body);
    } catch (cause) {
      setError(cause);
      return false;
    }
    try {
      await api.putRuntimeSecret('AI_PROVIDER', provider.profileId, 'API_KEY', apiKey, provider.credentialVersion);
      for (const modelName of modelNames) {
        await api.createModel({ providerProfileId: provider.profileId, modelName, defaultReasoningEffort: maximumEffort, maximumReasoningEffort: maximumEffort, inputUsdPerMillion: 0, outputUsdPerMillion: 0, enabled: true });
      }
      setMessage(`已创建厂商并导入 ${modelNames.length} 个探测模型`);
    } catch (cause) {
      setError(new Error(`厂商已创建，但密钥或模型导入未完全完成，请在厂商卡片继续处理：${cause instanceof Error ? cause.message : String(cause)}`));
    }
    await load();
    return true;
  };
  const importProviderModels = async (providerProfileId: string, modelNames: string[], maximumEffort: ReasoningEffort) => {
    setError(null); setMessage('');
    try {
      for (const modelName of modelNames) {
        await api.createModel({ providerProfileId, modelName, defaultReasoningEffort: maximumEffort, maximumReasoningEffort: maximumEffort, inputUsdPerMillion: 0, outputUsdPerMillion: 0, enabled: true });
      }
      setMessage(`已导入 ${modelNames.length} 个探测模型`);
      await load();
      return true;
    } catch (cause) {
      setError(cause);
      await load();
      return false;
    }
  };
  const changeTab = (next: SettingsTab) => { setTab(next); replaceWorkspaceLocation('settings', next); };
  if (error !== null && (!config || !trading)) return <ErrorBlock error={error} />;
  if (!config || !trading) return <LoadingBlock label="正在读取默认配置与密钥状态" />;
  return <Stack spacing={3}>
    {error !== null && <ErrorBlock error={error} />}{message && <Alert severity="success">{message}</Alert>}
    <Tabs value={tab} onChange={(_event, value: SettingsTab) => changeTab(value)} variant="scrollable" scrollButtons="auto"><Tab value="setup" label="快速启用" /><Tab value="runtime" label="运行基础" /><Tab value="providers" label="模型服务" /><Tab value="models" label="模型与费率" /><Tab value="roles" label="角色与提示词" /><Tab value="execution" label="交易机器人与风险" /><Tab value="experiments" label="A/B 实验" /><Tab value="tokens" label="API Token" /></Tabs>
    {tab === 'setup' && <SetupProfilesPanel profiles={profiles} onApplied={load} />}
    {tab === 'runtime' && <Box><SectionTitle title="运行参数" /><Stack spacing={1}>{config.settings.map((setting) => <Paper key={setting.key} variant="outlined" sx={{ p: 1.5 }}><Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems={{ md: 'center' }}><Box sx={{ flex: 1 }}><Typography fontWeight={700}>{setting.description}</Typography><Typography variant="caption" color="text.secondary">{setting.key} · {setting.source}</Typography></Box><TextField defaultValue={setting.value} onBlur={(event) => { if (event.target.value !== setting.value) void saved(api.updateSetting(setting, event.target.value)); }} sx={{ width: { md: 260 } }} /></Stack></Paper>)}</Stack></Box>}
    {tab === 'providers' && <Box><SectionTitle title="AI 厂商" /><Stack spacing={1.25}><ProviderCreatePanel create={createProvider} />{config.providers.map((provider) => <ProviderEditor key={provider.profileId} provider={provider} models={config.models.filter((model) => model.providerProfileId === provider.profileId)} save={(next) => saved(api.updateProvider(next))} remove={() => saved(api.deleteProvider(provider.profileId, provider.version))} putCredential={(value, version) => saved(api.putRuntimeSecret('AI_PROVIDER', provider.profileId, 'API_KEY', value, version))} clearCredential={(version) => saved(api.clearRuntimeSecret('AI_PROVIDER', provider.profileId, 'API_KEY', version))} importModels={(modelNames, effort) => importProviderModels(provider.profileId, modelNames, effort)} />)}</Stack></Box>}
    {tab === 'models' && <Box><SectionTitle title="模型与默认费率" /><Stack spacing={1.25}><Alert severity="info">模型只能从“模型服务”页通过厂商测活结果导入；此处维护已导入模型的思考能力、默认强度和费率。</Alert>{config.models.map((model) => <ModelEditor key={model.modelProfileId} model={model} providerName={config.providers.find((provider) => provider.profileId === model.providerProfileId)?.displayName || model.providerProfileId} save={(next) => saved(api.updateModel(next))} />)}</Stack></Box>}
    {tab === 'roles' && <AgentRolesPanel roles={roles} providers={config.providers} models={config.models} onChanged={load} />}
    {tab === 'execution' && <><Box><SectionTitle title="最终交易机器人" /><Stack spacing={1.5}>{trading.aiStages.map((stage) => <ExecutionStageEditor key={stage.stage} stage={stage} providers={config.providers} models={config.models} save={(next) => {
      void saved(api.updateExecutionStage(next.stage, { primaryAiBinding: requestBinding(next.primaryAiBinding), fallbackAiBinding: next.fallbackAiBinding ? requestBinding(next.fallbackAiBinding) : null, systemPrompt: next.systemPrompt, userPromptTemplate: next.userPromptTemplate, maximumOutputTokens: next.maximumOutputTokens, timeoutSeconds: next.timeoutSeconds, retryMaximumAttempts: next.retryPolicy.maximumAttempts, retryBackoffSeconds: durationSeconds(next.retryPolicy.backoff), enabled: next.enabled, expectedVersion: next.version }));
    }} />)}</Stack></Box><RiskPolicyEditor policy={trading.activeRiskPolicy} save={(next) => saved(api.activateRiskPolicy(next))} /></>}
    {tab === 'experiments' && <AiExperimentsPanel experiments={experiments} definitions={definitions} onChanged={load} />}
    {tab === 'tokens' && <ApiTokensPanel />}
  </Stack>;
}

export function ProviderCreatePanel({ create }: { create: (body: Record<string, unknown>, apiKey: string, modelNames: string[], maximumEffort: ReasoningEffort) => Promise<boolean> }) {
  const [displayName, setDisplayName] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [protocol, setProtocol] = useState('RESPONSES');
  const [reasoningStyle, setReasoningStyle] = useState('NESTED');
  const [maximumEffort, setMaximumEffort] = useState<ReasoningEffort>('MAX');
  const [maximumConcurrentRequests, setMaximumConcurrentRequests] = useState(5);
  const [acquireTimeoutSeconds, setAcquireTimeoutSeconds] = useState(1800);
  const [requestTimeoutSeconds, setRequestTimeoutSeconds] = useState(1800);
  const [probe, setProbe] = useState<ProviderModelCatalog | null>(null);
  const [probeError, setProbeError] = useState<unknown>(null);
  const [selectedModels, setSelectedModels] = useState<string[]>([]);
  const [probeBusy, setProbeBusy] = useState(false);
  const [createBusy, setCreateBusy] = useState(false);
  const invalidateProbe = () => { setProbe(null); setProbeError(null); setSelectedModels([]); };
  const runProbe = async () => {
    setProbeBusy(true); setProbeError(null); setProbe(null); setSelectedModels([]);
    try {
      const result = await api.probeProviderDraft({ baseUrl: baseUrl.trim(), apiKey: apiKey.trim(), requestTimeoutSeconds });
      setProbe(result);
    } catch (cause) {
      setProbeError(cause);
    } finally {
      setProbeBusy(false);
    }
  };
  const submit = async () => {
    setCreateBusy(true);
    try {
      const created = await create({ displayName: displayName.trim(), protocol, reasoningParameterStyle: reasoningStyle, baseUrl: baseUrl.trim(), enabled: true, connectTimeoutSeconds: 10, requestTimeoutSeconds, maximumConcurrentRequests, acquireTimeoutSeconds }, apiKey.trim(), selectedModels, maximumEffort);
      if (created) {
        setDisplayName(''); setBaseUrl(''); setApiKey(''); setProbe(null); setSelectedModels([]);
      }
    } finally { setCreateBusy(false); }
  };
  const probeReady = probe?.status === 'READY' && probe.models.length > 0;
  const limitsValid = validProviderLimits(maximumConcurrentRequests, acquireTimeoutSeconds, requestTimeoutSeconds);
  return <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1.5}>
    {probeError !== null && <ErrorBlock error={probeError} />}
    <Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5}><TextField label="厂商名称" value={displayName} onChange={(event) => setDisplayName(event.target.value)} /><TextField fullWidth label="Base URL" value={baseUrl} onChange={(event) => { setBaseUrl(event.target.value); invalidateProbe(); }} /><TextField select label="协议" value={protocol} onChange={(event) => { setProtocol(event.target.value); invalidateProbe(); }} sx={{ minWidth: 130 }}><MenuItem value="CHAT">CHAT</MenuItem><MenuItem value="RESPONSES">RESPONSES</MenuItem></TextField><TextField select label="思考参数" value={reasoningStyle} onChange={(event) => setReasoningStyle(event.target.value)} sx={{ minWidth: 130 }}><MenuItem value="NONE">NONE</MenuItem><MenuItem value="FLAT">FLAT</MenuItem><MenuItem value="NESTED">NESTED</MenuItem></TextField></Stack>
    <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'flex-start' }}><SecretTextField fullWidth autoComplete="new-password" label="API Key" value={apiKey} onChange={(event) => { setApiKey(event.target.value); invalidateProbe(); }} helperText="测活只用于本次请求，创建后加密保存并立即生效" inputProps={{ maxLength: 16384 }} /><Button variant="outlined" disabled={probeBusy || !baseUrl.trim() || apiKey.trim().length < 8 || !limitsValid} onClick={() => void runProbe()} sx={{ flexShrink: 0 }}>{probeBusy ? '正在测活' : '测活并探测模型'}</Button></Stack>
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}><TextField label="最大并发请求" type="number" value={maximumConcurrentRequests} onChange={(event) => setMaximumConcurrentRequests(Number(event.target.value))} inputProps={{ min: 1, max: 32, step: 1 }} /><TextField label="排队等待（秒）" type="number" value={acquireTimeoutSeconds} onChange={(event) => setAcquireTimeoutSeconds(Number(event.target.value))} inputProps={{ min: 5, max: 7200, step: 1 }} /><TextField label="单次请求超时（秒）" type="number" value={requestTimeoutSeconds} onChange={(event) => { setRequestTimeoutSeconds(Number(event.target.value)); invalidateProbe(); }} inputProps={{ min: 5, max: 3600, step: 1 }} /></Stack>
    {probe && <Alert severity={probeReady ? 'success' : 'error'}>{probeReady ? `连接正常，探测到 ${probe.models.length} 个模型，耗时 ${probe.latencyMilliseconds ?? '-'} ms。` : probe.status === 'READY' ? '连接正常，但模型目录为空，无法创建可用模型配置。' : `${probe.errorCode}: ${probe.errorMessage}`}</Alert>}
    {probeReady && <Stack spacing={1.25}><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }}><Autocomplete multiple disableCloseOnSelect options={probe.models} value={selectedModels} onChange={(_event, value) => setSelectedModels(value)} renderInput={(params) => <TextField {...params} label="导入模型" helperText="模型名来自厂商 /models 探测结果，不能手工填写" />} sx={{ flex: 1 }} /><TextField select label="模型思考上限" value={maximumEffort} onChange={(event) => setMaximumEffort(event.target.value as ReasoningEffort)} sx={{ minWidth: 170 }}>{efforts.map((effort) => <MenuItem key={effort} value={effort}>{effort}</MenuItem>)}</TextField></Stack><Stack direction="row" spacing={1} justifyContent="flex-end"><Button onClick={() => setSelectedModels(selectedModels.length === probe.models.length ? [] : probe.models)}>{selectedModels.length === probe.models.length ? '清空选择' : '选择全部'}</Button><Button variant="contained" startIcon={<AddIcon />} disabled={createBusy || !displayName.trim() || selectedModels.length === 0 || !limitsValid} onClick={() => void submit()}>{createBusy ? '正在创建' : `创建并导入 ${selectedModels.length} 个模型`}</Button></Stack></Stack>}
  </Stack></Paper>;
}

function ProviderEditor({ provider, models, save, remove, putCredential, clearCredential, importModels }: { provider: AiProvider; models: AiModel[]; save: (value: AiProvider) => Promise<void>; remove: () => Promise<void>; putCredential: (value: string, version: number) => Promise<void>; clearCredential: (version: number) => Promise<void>; importModels: (modelNames: string[], maximumEffort: ReasoningEffort) => Promise<boolean> }) {
  const [value, setValue] = useState(provider);
  const [credential, setCredential] = useState('');
  const [credentialBusy, setCredentialBusy] = useState(false);
  const [actionBusy, setActionBusy] = useState(false);
  const [probe, setProbe] = useState<ProviderModelCatalog | null>(null);
  const [probeError, setProbeError] = useState<unknown>(null);
  const [selectedModels, setSelectedModels] = useState<string[]>([]);
  const [maximumEffort, setMaximumEffort] = useState<ReasoningEffort>('MAX');
  const [importBusy, setImportBusy] = useState(false);
  useEffect(() => setValue(provider), [provider]);
  const runProbe = async () => { setActionBusy(true); setProbeError(null); setSelectedModels([]); try { setProbe(await api.probeProvider(value.profileId)); } catch (cause) { setProbeError(cause); } finally { setActionBusy(false); } };
  const storeCredential = async () => { if (!credential.trim()) return; setCredentialBusy(true); try { await putCredential(credential.trim(), value.credentialVersion); setCredential(''); } finally { setCredentialBusy(false); } };
  const removeCredential = async () => { setCredentialBusy(true); try { await clearCredential(value.credentialVersion); setCredential(''); } finally { setCredentialBusy(false); } };
  const usage = `工作流节点 ${value.workflowNodeUsageCount} · 角色 ${value.roleTemplateUsageCount} · 执行阶段 ${value.executionStageUsageCount}`;
  const saveProvider = async () => { setActionBusy(true); try { await save(value); } finally { setActionBusy(false); } };
  const deleteProvider = async () => { if (!window.confirm(`确认删除“${value.displayName}”？`)) return; setActionBusy(true); try { await remove(); } finally { setActionBusy(false); } };
  const availableModels = probe?.status === 'READY' ? probe.models.filter((modelName) => !models.some((model) => model.modelName === modelName)) : [];
  const importDetectedModels = async () => { setImportBusy(true); try { if (await importModels(selectedModels, maximumEffort)) setSelectedModels([]); } finally { setImportBusy(false); } };
  const limitsValid = validProviderLimits(value.maximumConcurrentRequests, value.acquireTimeoutSeconds, value.requestTimeoutSeconds);
  return <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1.5}>{probeError !== null && <ErrorBlock error={probeError} />}{value.totalUsageCount > 0 && <Alert severity="warning">当前引用：{usage}。修改可能影响后续运行，删除前必须先在工作流、角色和执行阶段中解绑。</Alert>}<Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5} alignItems={{ lg: 'center' }}><Box sx={{ flex: 1 }}><Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap"><Typography fontWeight={700}>{value.displayName}</Typography><Chip size="small" color={value.apiKeyConfigured ? 'success' : 'warning'} label={credentialSourceLabel(value.credentialSource)} /></Stack><Typography variant="caption" color="text.secondary">{value.protocol} · {value.reasoningParameterStyle}{value.credentialFingerprint ? ` · 指纹 ${value.credentialFingerprint}` : ''}</Typography></Box><TextField label="Base URL" value={value.baseUrl || ''} onChange={(event) => setValue({ ...value, baseUrl: event.target.value || null })} sx={{ minWidth: 280 }} /><FormControlLabel control={<Switch checked={value.enabled} onChange={(event) => setValue({ ...value, enabled: event.target.checked })} />} label="启用" /><Button disabled={actionBusy || !value.apiKeyConfigured} onClick={() => void runProbe()}>热测试并探测</Button><Button disabled={actionBusy || !limitsValid} startIcon={<SaveIcon />} onClick={() => void saveProvider()}>保存厂商</Button><Button color="error" startIcon={<DeleteIcon />} disabled={actionBusy || value.totalUsageCount > 0} onClick={() => void deleteProvider()}>删除</Button></Stack><Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}><TextField label="最大并发请求" type="number" value={value.maximumConcurrentRequests} onChange={(event) => setValue({ ...value, maximumConcurrentRequests: Number(event.target.value) })} inputProps={{ min: 1, max: 32, step: 1 }} /><TextField label="排队等待（秒）" type="number" value={value.acquireTimeoutSeconds} onChange={(event) => setValue({ ...value, acquireTimeoutSeconds: Number(event.target.value) })} inputProps={{ min: 5, max: 7200, step: 1 }} /><TextField label="单次请求超时（秒）" type="number" value={value.requestTimeoutSeconds} onChange={(event) => setValue({ ...value, requestTimeoutSeconds: Number(event.target.value) })} inputProps={{ min: 5, max: 3600, step: 1 }} /></Stack><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }}><SecretTextField fullWidth autoComplete="new-password" label="API Key" value={credential} onChange={(event) => setCredential(event.target.value)} helperText="保存后立即生效；系统不会回显旧值" inputProps={{ maxLength: 16384 }} /><Button variant="contained" startIcon={<KeyIcon />} disabled={credentialBusy || credential.trim().length < 8} onClick={() => void storeCredential()} sx={{ flexShrink: 0 }}>{value.credentialSource === 'DATABASE_OVERRIDE' ? '轮换 Key' : '设置 Key'}</Button><Button color="error" disabled={credentialBusy || value.credentialSource !== 'DATABASE_OVERRIDE'} onClick={() => void removeCredential()} sx={{ flexShrink: 0 }}>清除热配置</Button></Stack>{probe && <Alert severity={probe.status === 'READY' ? 'success' : 'error'}>{probe.status === 'READY' ? `探测到 ${probe.models.length} 个模型；已配置 ${models.length} 个，待导入 ${availableModels.length} 个。` : `${probe.errorCode}: ${probe.errorMessage}`}</Alert>}{availableModels.length > 0 && <Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5} alignItems={{ lg: 'center' }}><Autocomplete multiple disableCloseOnSelect options={availableModels} value={selectedModels} onChange={(_event, selected) => setSelectedModels(selected)} renderInput={(params) => <TextField {...params} label="导入新探测模型" />} sx={{ flex: 1 }} /><TextField select label="思考上限" value={maximumEffort} onChange={(event) => setMaximumEffort(event.target.value as ReasoningEffort)} sx={{ minWidth: 150 }}>{efforts.map((effort) => <MenuItem key={effort} value={effort}>{effort}</MenuItem>)}</TextField><Button onClick={() => setSelectedModels(selectedModels.length === availableModels.length ? [] : availableModels)}>{selectedModels.length === availableModels.length ? '清空' : '全选'}</Button><Button variant="contained" disabled={importBusy || selectedModels.length === 0} onClick={() => void importDetectedModels()}>{importBusy ? '正在导入' : `导入 ${selectedModels.length} 个`}</Button></Stack>}</Stack></Paper>;
}

function validProviderLimits(maximumConcurrentRequests: number, acquireTimeoutSeconds: number, requestTimeoutSeconds: number): boolean {
  return Number.isInteger(maximumConcurrentRequests) && maximumConcurrentRequests >= 1 && maximumConcurrentRequests <= 32
    && Number.isInteger(acquireTimeoutSeconds) && acquireTimeoutSeconds >= 5 && acquireTimeoutSeconds <= 7200
    && Number.isInteger(requestTimeoutSeconds) && requestTimeoutSeconds >= 5 && requestTimeoutSeconds <= 3600;
}

function credentialSourceLabel(source: AiProvider['credentialSource']): string { return ({ DATABASE_OVERRIDE: '后台热配置', ENVIRONMENT_FALLBACK: '启动备用配置', UNCONFIGURED: 'Key 未配置' } as const)[source]; }

function ModelEditor({ model, providerName, save }: { model: AiModel; providerName: string; save: (value: AiModel) => void }) {
  const [value, setValue] = useState(model);
  useEffect(() => setValue(model), [model]);
  const supportedEfforts = efforts.filter((effort) => value.maximumReasoningEffort === 'PROVIDER_DEFAULT'
    ? effort === 'PROVIDER_DEFAULT'
    : effort === 'PROVIDER_DEFAULT' || efforts.indexOf(effort) <= efforts.indexOf(value.maximumReasoningEffort));
  return <Paper variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5} alignItems={{ lg: 'center' }}><Box sx={{ flex: 1 }}><Typography fontWeight={700}>{value.modelName}</Typography><Typography variant="caption" color="text.secondary">{providerName}</Typography></Box><TextField select label="能力上限" value={value.maximumReasoningEffort} onChange={(event) => { const maximumReasoningEffort = event.target.value as ReasoningEffort; const defaultReasoningEffort = efforts.indexOf(value.defaultReasoningEffort) <= efforts.indexOf(maximumReasoningEffort) ? value.defaultReasoningEffort : maximumReasoningEffort; setValue({ ...value, maximumReasoningEffort, defaultReasoningEffort }); }} sx={{ minWidth: 150 }}>{efforts.map((effort) => <MenuItem key={effort} value={effort}>{effort}</MenuItem>)}</TextField><TextField select label="默认思考强度" value={value.defaultReasoningEffort} onChange={(event) => setValue({ ...value, defaultReasoningEffort: event.target.value as ReasoningEffort })} sx={{ minWidth: 170 }}>{supportedEfforts.map((effort) => <MenuItem key={effort} value={effort}>{effort}</MenuItem>)}</TextField><TextField label="输入 $/M" type="number" value={value.inputUsdPerMillion} onChange={(event) => setValue({ ...value, inputUsdPerMillion: Number(event.target.value) })} sx={{ width: 120 }} /><TextField label="输出 $/M" type="number" value={value.outputUsdPerMillion} onChange={(event) => setValue({ ...value, outputUsdPerMillion: Number(event.target.value) })} sx={{ width: 120 }} /><Switch checked={value.enabled} onChange={(event) => setValue({ ...value, enabled: event.target.checked })} /><Button startIcon={<SaveIcon />} onClick={() => save(value)}>保存</Button></Stack></Paper>;
}

function ExecutionStageEditor({ stage, providers, models, save }: { stage: ExecutionAiStage; providers: AiProvider[]; models: AiModel[]; save: (value: ExecutionAiStage) => void }) {
  const [value, setValue] = useState(stage);
  useEffect(() => setValue(stage), [stage]);
  return <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1.5}><Stack direction="row" justifyContent="space-between"><Box><Typography fontWeight={700}>{value.stage === 'DRAFT' ? '初稿决策' : '反思终审'}</Typography><Typography variant="caption" color="text.secondary">执行前结构化判断，不输出隐藏思维链</Typography></Box><FormControlLabel control={<Switch checked={value.enabled} onChange={(event) => setValue({ ...value, enabled: event.target.checked })} />} label="启用" /></Stack><ExecutionBindingEditor title="主模型" binding={value.primaryAiBinding} providers={providers} models={models} update={(primaryAiBinding) => setValue({ ...value, primaryAiBinding })} /><FormControlLabel control={<Switch checked={value.fallbackAiBinding !== null} onChange={(event) => {
    if (!event.target.checked) { setValue({ ...value, fallbackAiBinding: null }); return; }
    const fallback = models.find((model) => model.enabled && model.providerProfileId !== bindingProviderId(value.primaryAiBinding)) || models.find((model) => model.enabled) || models[0];
    if (fallback) setValue({ ...value, fallbackAiBinding: { providerProfileId: fallback.providerProfileId, modelName: fallback.modelName, reasoningEffort: fallback.defaultReasoningEffort } });
  }} />} label="启用兜底模型" />{value.fallbackAiBinding && <ExecutionBindingEditor title="兜底模型" binding={value.fallbackAiBinding} providers={providers} models={models} update={(fallbackAiBinding) => setValue({ ...value, fallbackAiBinding })} />}<Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}><TextField label="最大 Token" type="number" value={value.maximumOutputTokens} onChange={(event) => setValue({ ...value, maximumOutputTokens: Number(event.target.value) })} /><TextField label="单次超时（秒）" type="number" value={value.timeoutSeconds} onChange={(event) => setValue({ ...value, timeoutSeconds: Number(event.target.value) })} inputProps={{ min: 10, max: 3600, step: 1 }} /><TextField label="每个模型重试次数" type="number" value={value.retryPolicy.maximumAttempts} onChange={(event) => setValue({ ...value, retryPolicy: { ...value.retryPolicy, maximumAttempts: Number(event.target.value) } })} /><TextField label="重试退避（秒）" type="number" value={durationSeconds(value.retryPolicy.backoff)} onChange={(event) => setValue({ ...value, retryPolicy: { ...value.retryPolicy, backoff: Number(event.target.value) } })} /></Stack><TextField multiline minRows={3} label="系统提示词" value={value.systemPrompt} onChange={(event) => setValue({ ...value, systemPrompt: event.target.value })} /><TextField multiline minRows={2} label="用户提示模板" value={value.userPromptTemplate} onChange={(event) => setValue({ ...value, userPromptTemplate: event.target.value })} /><Button variant="contained" startIcon={<SaveIcon />} onClick={() => save(value)} sx={{ alignSelf: 'flex-end' }}>保存阶段</Button></Stack></Paper>;
}

function ExecutionBindingEditor({ title, binding, providers, models, update }: { title: string; binding: AiModelBinding; providers: AiProvider[]; models: AiModel[]; update: (binding: AiModelBinding) => void }) {
  const selectedProviderId = bindingProviderId(binding);
  const availableModels = models.filter((model) => model.providerProfileId === selectedProviderId);
  const selectedModel = availableModels.find((model) => model.modelName === binding.modelName);
  const supportedEfforts = selectedModel ? efforts.filter((effort, index) => effort === 'PROVIDER_DEFAULT' || (efforts.indexOf(selectedModel.maximumReasoningEffort) > 0 && index <= efforts.indexOf(selectedModel.maximumReasoningEffort))) : efforts;
  return <Stack spacing={1.25} sx={{ borderTop: '1px solid', borderColor: 'divider', pt: 1.5 }}><Typography variant="subtitle2">{title}</Typography><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}><TextField select label="厂商" value={selectedProviderId} onChange={(event) => {
    const selectedModel = models.find((model) => model.enabled && model.providerProfileId === event.target.value) || models.find((model) => model.providerProfileId === event.target.value);
    update({ providerProfileId: event.target.value, modelName: selectedModel?.modelName || binding.modelName, reasoningEffort: selectedModel?.defaultReasoningEffort || binding.reasoningEffort });
  }} sx={{ minWidth: 220 }}>{providers.map((item) => <MenuItem key={item.profileId} value={item.profileId}>{item.displayName}</MenuItem>)}</TextField><TextField select label="模型" value={binding.modelName} onChange={(event) => { const model = availableModels.find((item) => item.modelName === event.target.value); update({ ...binding, modelName: event.target.value, reasoningEffort: model?.defaultReasoningEffort || binding.reasoningEffort }); }} sx={{ minWidth: 220 }}>{availableModels.map((item) => <MenuItem key={item.modelProfileId} value={item.modelName}>{item.modelName}</MenuItem>)}{!availableModels.some((item) => item.modelName === binding.modelName) && <MenuItem value={binding.modelName}>{binding.modelName}</MenuItem>}</TextField><TextField select label="思考强度" value={binding.reasoningEffort} onChange={(event) => update({ ...binding, reasoningEffort: event.target.value as ReasoningEffort })} sx={{ minWidth: 150 }}>{supportedEfforts.map((effort) => <MenuItem key={effort} value={effort}>{effort}</MenuItem>)}</TextField></Stack></Stack>;
}

function RiskPolicyEditor({ policy, save }: { policy: RiskPolicy; save: (value: RiskPolicy & { policyVersion: string }) => void }) {
  const [value, setValue] = useState(policy);
  const [version, setVersion] = useState(`paper-custom-${new Date().toISOString().slice(0, 10).replace(/-/g, '')}-v1`);
  useEffect(() => setValue(policy), [policy]);
  const fields: Array<[keyof RiskPolicy, string]> = [['minimumConfidence', '最低置信度'], ['riskBudgetUsdt', '单次风险预算 USDT'], ['maximumNotionalUsdt', '最大名义价值 USDT'], ['preferredLeverage', '期望实际杠杆'], ['maximumLeverage', '风控杠杆上限'], ['maximumOpenPositions', '最大持仓数'], ['maximumStopDistance', '最大止损距离'], ['takerFeeRate', 'Taker 费率'], ['slippageRate', '滑点率'], ['liquidationBufferRate', '强平缓冲率']];
  return <Box><SectionTitle title="模拟交易风险策略" /><Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1.5}><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} flexWrap={{ md: 'wrap' }} useFlexGap><TextField label="新策略版本" value={version} onChange={(event) => setVersion(event.target.value)} sx={{ width: { xs: '100%', md: 260 } }} />{fields.map(([key, label]) => <TextField key={key} label={label} type="number" value={String(value[key])} onChange={(event) => setValue({ ...value, [key]: key === 'maximumOpenPositions' ? Number.parseInt(event.target.value, 10) : Number(event.target.value) })} sx={{ width: { xs: '100%', md: 170 } }} />)}</Stack><Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} justifyContent="space-between" alignItems={{ sm: 'center' }}><FormControlLabel control={<Switch checked={value.testEnvironmentOnly} onChange={(event) => setValue({ ...value, testEnvironmentOnly: event.target.checked })} />} label="仅 TestNet / Demo" /><Button variant="contained" startIcon={<SaveIcon />} onClick={() => save({ ...value, policyVersion: version })}>创建并启用版本</Button></Stack></Stack></Paper></Box>;
}

function bindingProviderId(binding: AiModelBinding): string { return binding.providerProfileId; }

function requestBinding(binding: AiModelBinding) {
  return { providerProfileId: bindingProviderId(binding), modelName: binding.modelName, reasoningEffort: binding.reasoningEffort };
}

function durationSeconds(value: number | string): number {
  if (typeof value === 'number') return value;
  const match = /^PT([0-9]+(?:\.[0-9]+)?)S$/.exec(value);
  return match ? Number(match[1]) : 0;
}
