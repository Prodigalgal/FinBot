import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import KeyIcon from '@mui/icons-material/Key';
import SaveIcon from '@mui/icons-material/Save';
import { Alert, Autocomplete, Box, Button, Chip, FormControlLabel, MenuItem, Paper, Stack, Switch, Tab, Tabs, TextField, Typography } from '@mui/material';
import { useEffect, useRef, useState, type HTMLAttributes } from 'react';

import { api } from './api';
import { SecretTextField } from './SecretTextField';
import type { AgentRole, AiExperiment, AiModel, AiModelBinding, AiProvider, ConfigurationSnapshot, DiscoveredModelCapability, ExecutionAiStage, ModelAvailability, ProviderModelCatalog, ReasoningEffort, RiskPolicy, SetupProfileDefinition, TokenLimitParameterStyle, TradeAutomationConfiguration, WorkflowDefinitionSummary } from './types';
import { ErrorBlock, LoadingBlock, SectionTitle } from './ui';
import { AgentRolesPanel, AiExperimentsPanel, SetupProfilesPanel } from './SettingsAdvancedPanels';
import { ApiTokensPanel } from './ApiTokensPanel';
import { replaceWorkspaceLocation, workspaceSubview } from './workspaceLocation';

const efforts: ReasoningEffort[] = ['PROVIDER_DEFAULT', 'NONE', 'MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'];
const tokenLimitStyles: Array<{ value: TokenLimitParameterStyle; label: string }> = [
  { value: 'PROTOCOL_DEFAULT', label: '协议默认' },
  { value: 'MAX_TOKENS', label: 'max_tokens' },
  { value: 'MAX_COMPLETION_TOKENS', label: 'max_completion_tokens' },
  { value: 'MAX_OUTPUT_TOKENS', label: 'max_output_tokens' },
  { value: 'NONE', label: '不发送' },
];
const settingsTabs = ['setup', 'runtime', 'providers', 'models', 'roles', 'execution', 'experiments', 'tokens'] as const;
type SettingsTab = typeof settingsTabs[number];

interface ModelImportConfiguration {
  modelName: string;
  defaultReasoningEffort: ReasoningEffort;
  maximumReasoningEffort: ReasoningEffort;
  tokenLimitParameterStyle: TokenLimitParameterStyle;
}

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
  const createProvider = async (body: Record<string, unknown>, apiKey: string, modelImports: ModelImportConfiguration[]) => {
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
      for (const model of modelImports) {
        await api.createModel({ providerProfileId: provider.profileId, ...model, inputUsdPerMillion: 0, outputUsdPerMillion: 0, enabled: true });
      }
      setMessage(`已创建厂商并导入 ${modelImports.length} 个探测模型`);
    } catch (cause) {
      setError(new Error(`厂商已创建，但密钥或模型导入未完全完成，请在厂商卡片继续处理：${cause instanceof Error ? cause.message : String(cause)}`));
    }
    await load();
    return true;
  };
  const importProviderModels = async (providerProfileId: string, modelImports: ModelImportConfiguration[]) => {
    setError(null); setMessage('');
    try {
      for (const model of modelImports) {
        await api.createModel({ providerProfileId, ...model, inputUsdPerMillion: 0, outputUsdPerMillion: 0, enabled: true });
      }
      setMessage(`已导入 ${modelImports.length} 个探测模型`);
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
    {tab === 'providers' && <Box><SectionTitle title="AI 厂商" /><Stack spacing={1.25}><ProviderCreatePanel create={createProvider} />{config.providers.map((provider) => <ProviderEditor key={provider.profileId} provider={provider} models={config.models.filter((model) => model.providerProfileId === provider.profileId)} save={(next) => saved(api.updateProvider(next))} remove={() => saved(api.deleteProvider(provider.profileId, provider.version))} putCredential={(value, version) => saved(api.putRuntimeSecret('AI_PROVIDER', provider.profileId, 'API_KEY', value, version))} clearCredential={(version) => saved(api.clearRuntimeSecret('AI_PROVIDER', provider.profileId, 'API_KEY', version))} importModels={(modelImports) => importProviderModels(provider.profileId, modelImports)} />)}</Stack></Box>}
    {tab === 'models' && <Box><SectionTitle title="模型与默认费率" /><Stack spacing={1.25}><Alert severity="info">模型从“模型服务”页读取目录后导入；此处维护已导入模型的思考能力、输出参数和费率。</Alert>{config.models.map((model) => <ModelEditor key={model.modelProfileId} model={model} providerName={config.providers.find((provider) => provider.profileId === model.providerProfileId)?.displayName || model.providerProfileId} save={(next) => saved(api.updateModel(next))} />)}</Stack></Box>}
    {tab === 'roles' && <AgentRolesPanel roles={roles} providers={config.providers} models={config.models} onChanged={load} />}
    {tab === 'execution' && <><Box><SectionTitle title="最终交易机器人" /><Stack spacing={1.5}>{trading.aiStages.map((stage) => <ExecutionStageEditor key={stage.stage} stage={stage} providers={config.providers} models={config.models} save={(next) => {
      void saved(api.updateExecutionStage(next.stage, { primaryAiBinding: requestBinding(next.primaryAiBinding), fallbackAiBinding: next.fallbackAiBinding ? requestBinding(next.fallbackAiBinding) : null, systemPrompt: next.systemPrompt, userPromptTemplate: next.userPromptTemplate, maximumOutputTokens: next.maximumOutputTokens, timeoutSeconds: next.timeoutSeconds, retryMaximumAttempts: next.retryPolicy.maximumAttempts, retryBackoffSeconds: durationSeconds(next.retryPolicy.backoff), enabled: next.enabled, expectedVersion: next.version }));
    }} />)}</Stack></Box><RiskPolicyEditor policy={trading.activeRiskPolicy} save={(next) => saved(api.activateRiskPolicy(next))} /></>}
    {tab === 'experiments' && <AiExperimentsPanel experiments={experiments} definitions={definitions} onChanged={load} />}
    {tab === 'tokens' && <ApiTokensPanel />}
  </Stack>;
}

export function ProviderCreatePanel({ create }: { create: (body: Record<string, unknown>, apiKey: string, modelImports: ModelImportConfiguration[]) => Promise<boolean> }) {
  const [displayName, setDisplayName] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [protocol, setProtocol] = useState('RESPONSES');
  const [reasoningStyle, setReasoningStyle] = useState('NESTED');
  const [maximumConcurrentRequests, setMaximumConcurrentRequests] = useState(5);
  const [acquireTimeoutSeconds, setAcquireTimeoutSeconds] = useState(1800);
  const [requestTimeoutSeconds, setRequestTimeoutSeconds] = useState(1800);
  const [probe, setProbe] = useState<ProviderModelCatalog | null>(null);
  const [probeError, setProbeError] = useState<unknown>(null);
  const [selectedModels, setSelectedModels] = useState<string[]>([]);
  const [probeBusy, setProbeBusy] = useState(false);
  const [createBusy, setCreateBusy] = useState(false);
  const probeGeneration = useRef(0);
  const invalidateProbe = () => { probeGeneration.current += 1; setProbe(null); setProbeError(null); setSelectedModels([]); setProbeBusy(false); };
  const runProbe = async () => {
    const generation = ++probeGeneration.current;
    const request = { baseUrl: baseUrl.trim(), apiKey: apiKey.trim(), requestTimeoutSeconds };
    setProbeBusy(true); setProbeError(null); setProbe(null); setSelectedModels([]);
    try {
      const result = await api.probeProviderDraft(request);
      if (generation === probeGeneration.current) setProbe(result);
    } catch (cause) {
      if (generation === probeGeneration.current) setProbeError(cause);
    } finally {
      if (generation === probeGeneration.current) setProbeBusy(false);
    }
  };
  const submit = async () => {
    setCreateBusy(true);
    try {
      const created = await create(
        { displayName: displayName.trim(), protocol, reasoningParameterStyle: reasoningStyle, baseUrl: baseUrl.trim(), enabled: true, connectTimeoutSeconds: 10, requestTimeoutSeconds, maximumConcurrentRequests, acquireTimeoutSeconds },
        apiKey.trim(),
        selectedModels.map((modelName) => modelImportConfiguration(capabilityByName(probe, modelName), protocol)),
      );
      if (created) {
        setDisplayName(''); setBaseUrl(''); setApiKey(''); setProbe(null); setSelectedModels([]);
      }
    } finally { setCreateBusy(false); }
  };
  const capabilities = catalogCapabilities(probe);
  const probeReady = probe?.status === 'READY' && capabilities.length > 0;
  const selectedCapabilities = capabilities.filter((capability) => selectedModels.includes(capability.modelName));
  const automaticallySelectable = capabilities.filter((capability) => isAutomaticallySelectable(capability, protocol)).map((capability) => capability.modelName);
  const limitsValid = validProviderLimits(maximumConcurrentRequests, acquireTimeoutSeconds, requestTimeoutSeconds);
  return <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1.5}>
    {probeError !== null && <ErrorBlock error={probeError} />}
    <Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5}><TextField label="厂商名称" value={displayName} onChange={(event) => setDisplayName(event.target.value)} /><TextField fullWidth label="Base URL" value={baseUrl} onChange={(event) => { setBaseUrl(event.target.value); invalidateProbe(); }} /><TextField select label="协议" value={protocol} onChange={(event) => { setProtocol(event.target.value); invalidateProbe(); }} sx={{ minWidth: 130 }}><MenuItem value="CHAT">CHAT</MenuItem><MenuItem value="RESPONSES">RESPONSES</MenuItem></TextField><TextField select label="思考参数" value={reasoningStyle} onChange={(event) => setReasoningStyle(event.target.value)} sx={{ minWidth: 130 }}><MenuItem value="NONE">NONE</MenuItem><MenuItem value="FLAT">FLAT</MenuItem><MenuItem value="NESTED">NESTED</MenuItem></TextField></Stack>
    <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'flex-start' }}><SecretTextField fullWidth autoComplete="new-password" label="API Key" value={apiKey} onChange={(event) => { setApiKey(event.target.value); invalidateProbe(); }} helperText="目录请求只使用本次输入；创建后加密保存并立即生效" inputProps={{ maxLength: 16384 }} /><Button variant="outlined" disabled={probeBusy || !baseUrl.trim() || apiKey.trim().length < 8 || !limitsValid} onClick={() => void runProbe()} sx={{ flexShrink: 0 }}>{probeBusy ? '正在读取' : '读取模型目录'}</Button></Stack>
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}><TextField label="最大并发请求" type="number" value={maximumConcurrentRequests} onChange={(event) => setMaximumConcurrentRequests(Number(event.target.value))} inputProps={{ min: 1, max: 32, step: 1 }} /><TextField label="排队等待（秒）" type="number" value={acquireTimeoutSeconds} onChange={(event) => setAcquireTimeoutSeconds(Number(event.target.value))} inputProps={{ min: 5, max: 7200, step: 1 }} /><TextField label="单次请求超时（秒）" type="number" value={requestTimeoutSeconds} onChange={(event) => { setRequestTimeoutSeconds(Number(event.target.value)); invalidateProbe(); }} inputProps={{ min: 5, max: 3600, step: 1 }} /></Stack>
    {probe && <Alert severity={probeReady ? 'success' : 'error'}>{probeReady ? `目录读取成功，共 ${capabilities.length} 个模型，耗时 ${probe.latencyMilliseconds ?? '-'} ms。` : probe.status === 'READY' ? '目录接口可访问，但没有返回模型。' : `${probe.errorCode}: ${probe.errorMessage}`}</Alert>}
    {probe?.warnings?.map((warning, index) => <Alert key={`${warning.code}:${warning.modelName ?? ''}:${index}`} severity="warning">{warning.modelName ? `${warning.modelName}：` : ''}{warning.message}</Alert>)}
    {probeReady && <Stack spacing={1.25}><ModelAvailabilitySummary capabilities={capabilities} /><Autocomplete multiple disableCloseOnSelect options={capabilities} value={selectedCapabilities} onChange={(_event, value) => setSelectedModels(value.map((capability) => capability.modelName))} getOptionLabel={(capability) => capability.modelName} isOptionEqualToValue={(option, value) => option.modelName === value.modelName} getOptionDisabled={(capability) => !isCapabilitySelectable(capability, protocol)} renderOption={(props, capability) => <CapabilityOption {...props} key={capability.modelName} capability={capability} protocol={protocol} />} renderTags={(values, getTagProps) => values.map((capability, index) => <Chip {...getTagProps({ index })} key={capability.modelName} size="small" color={availabilityColor(capability.availability)} label={`${capability.modelName} · ${availabilityLabel(capability.availability)}`} sx={{ maxWidth: '100%', '& .MuiChip-label': { overflow: 'hidden', textOverflow: 'ellipsis' } }} />)} renderInput={(params) => <TextField {...params} label="导入模型" helperText="不可用或协议不兼容的模型不能选择；能力未知的模型需手工选择" />} sx={{ flex: 1 }} /><Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} justifyContent="flex-end"><Button onClick={() => setSelectedModels(selectedModels.length > 0 ? [] : automaticallySelectable)} sx={{ width: { xs: '100%', sm: 'auto' } }}>{selectedModels.length > 0 ? '清空选择' : '选择已知可用'}</Button><Button variant="contained" startIcon={<AddIcon />} disabled={createBusy || !displayName.trim() || selectedModels.length === 0 || !limitsValid} onClick={() => void submit()} sx={{ width: { xs: '100%', sm: 'auto' } }}>{createBusy ? '正在创建' : `创建并导入 ${selectedModels.length} 个模型`}</Button></Stack></Stack>}
  </Stack></Paper>;
}

export function ProviderEditor({ provider, models, save, remove, putCredential, clearCredential, importModels }: { provider: AiProvider; models: AiModel[]; save: (value: AiProvider) => Promise<void>; remove: () => Promise<void>; putCredential: (value: string, version: number) => Promise<void>; clearCredential: (version: number) => Promise<void>; importModels: (modelImports: ModelImportConfiguration[]) => Promise<boolean> }) {
  const [value, setValue] = useState(provider);
  const [credential, setCredential] = useState('');
  const [credentialBusy, setCredentialBusy] = useState(false);
  const [actionBusy, setActionBusy] = useState(false);
  const [probe, setProbe] = useState<ProviderModelCatalog | null>(null);
  const [probeError, setProbeError] = useState<unknown>(null);
  const [selectedModels, setSelectedModels] = useState<string[]>([]);
  const [importBusy, setImportBusy] = useState(false);
  const probeGeneration = useRef(0);
  const probeInFlight = useRef(false);
  const invalidateProbe = () => {
    probeGeneration.current += 1; setProbe(null); setProbeError(null); setSelectedModels([]);
    if (probeInFlight.current) { probeInFlight.current = false; setActionBusy(false); }
  };
  useEffect(() => { setValue(provider); invalidateProbe(); }, [provider]);
  const connectionChanged = value.baseUrl !== provider.baseUrl
    || value.requestTimeoutSeconds !== provider.requestTimeoutSeconds;
  const runProbe = async () => {
    const generation = ++probeGeneration.current;
    const pendingCredential = credential.trim();
    probeInFlight.current = true;
    setActionBusy(true); setProbeError(null); setProbe(null); setSelectedModels([]);
    try {
      if (pendingCredential) {
        const result = await api.probeProviderDraft({ baseUrl: value.baseUrl || '', apiKey: pendingCredential, requestTimeoutSeconds: value.requestTimeoutSeconds });
        if (generation === probeGeneration.current) setProbe(result);
      } else if (connectionChanged) {
        throw new Error('连接参数尚未保存；请输入用于当前参数测试的 API Key，或先保存厂商配置。');
      } else {
        const result = await api.probeProvider(value.profileId);
        if (generation === probeGeneration.current) setProbe(result);
      }
    } catch (cause) {
      if (generation === probeGeneration.current) setProbeError(cause);
    } finally {
      if (generation === probeGeneration.current) { probeInFlight.current = false; setActionBusy(false); }
    }
  };
  const storeCredential = async () => { if (!credential.trim()) return; setCredentialBusy(true); try { await putCredential(credential.trim(), value.credentialVersion); setCredential(''); } finally { setCredentialBusy(false); } };
  const removeCredential = async () => { setCredentialBusy(true); try { await clearCredential(value.credentialVersion); setCredential(''); } finally { setCredentialBusy(false); } };
  const usage = `工作流节点 ${value.workflowNodeUsageCount} · 角色 ${value.roleTemplateUsageCount} · 执行阶段 ${value.executionStageUsageCount}`;
  const saveProvider = async () => { setActionBusy(true); try { await save(value); } finally { setActionBusy(false); } };
  const deleteProvider = async () => { if (!window.confirm(`确认删除“${value.displayName}”？`)) return; setActionBusy(true); try { await remove(); } finally { setActionBusy(false); } };
  const availableModels = probe?.status === 'READY' ? catalogCapabilities(probe).filter((capability) => !models.some((model) => model.modelName === capability.modelName)) : [];
  const selectedCapabilities = availableModels.filter((capability) => selectedModels.includes(capability.modelName));
  const automaticallySelectable = availableModels.filter((capability) => isAutomaticallySelectable(capability, value.protocol)).map((capability) => capability.modelName);
  const importDetectedModels = async () => { setImportBusy(true); try { if (await importModels(selectedModels.map((modelName) => modelImportConfiguration(capabilityByName(probe, modelName), value.protocol)))) setSelectedModels([]); } finally { setImportBusy(false); } };
  const limitsValid = validProviderLimits(value.maximumConcurrentRequests, value.acquireTimeoutSeconds, value.requestTimeoutSeconds);
  const pendingCredential = credential.trim();
  const canReadCatalog = Boolean(value.baseUrl?.trim()) && limitsValid
    && (connectionChanged ? pendingCredential.length >= 8 : value.apiKeyConfigured || pendingCredential.length >= 8);
  return <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1.5}>{probeError !== null && <ErrorBlock error={probeError} />}{value.totalUsageCount > 0 && <Alert severity="warning">当前引用：{usage}。修改可能影响后续运行，删除前必须先在工作流、角色和执行阶段中解绑。</Alert>}<Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5} alignItems={{ lg: 'center' }}><Box sx={{ flex: 1 }}><Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap"><Typography fontWeight={700}>{value.displayName}</Typography><Chip size="small" color={value.apiKeyConfigured ? 'success' : 'warning'} label={credentialSourceLabel(value.credentialSource)} /></Stack><Typography variant="caption" color="text.secondary">{value.protocol} · {value.reasoningParameterStyle}{value.credentialFingerprint ? ` · 指纹 ${value.credentialFingerprint}` : ''}</Typography></Box><TextField label="Base URL" value={value.baseUrl || ''} onChange={(event) => { setValue({ ...value, baseUrl: event.target.value || null }); invalidateProbe(); }} sx={{ minWidth: 280 }} /><FormControlLabel control={<Switch checked={value.enabled} onChange={(event) => setValue({ ...value, enabled: event.target.checked })} />} label="启用" /><Button disabled={actionBusy || !canReadCatalog} onClick={() => void runProbe()}>读取模型目录</Button><Button disabled={actionBusy || !limitsValid} startIcon={<SaveIcon />} onClick={() => void saveProvider()}>保存厂商</Button><Button color="error" startIcon={<DeleteIcon />} disabled={actionBusy || value.totalUsageCount > 0} onClick={() => void deleteProvider()}>删除</Button></Stack><Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}><TextField label="最大并发请求" type="number" value={value.maximumConcurrentRequests} onChange={(event) => setValue({ ...value, maximumConcurrentRequests: Number(event.target.value) })} inputProps={{ min: 1, max: 32, step: 1 }} /><TextField label="排队等待（秒）" type="number" value={value.acquireTimeoutSeconds} onChange={(event) => setValue({ ...value, acquireTimeoutSeconds: Number(event.target.value) })} inputProps={{ min: 5, max: 7200, step: 1 }} /><TextField label="单次请求超时（秒）" type="number" value={value.requestTimeoutSeconds} onChange={(event) => { setValue({ ...value, requestTimeoutSeconds: Number(event.target.value) }); invalidateProbe(); }} inputProps={{ min: 5, max: 3600, step: 1 }} /></Stack><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }}><SecretTextField fullWidth autoComplete="new-password" label="API Key" value={credential} onChange={(event) => { setCredential(event.target.value); invalidateProbe(); }} helperText={connectionChanged ? '连接参数已修改，请输入用于本次目录请求的 API Key' : '保存后立即生效；系统不会回显旧值'} inputProps={{ maxLength: 16384 }} /><Button variant="contained" startIcon={<KeyIcon />} disabled={credentialBusy || credential.trim().length < 8} onClick={() => void storeCredential()} sx={{ flexShrink: 0 }}>{value.credentialSource === 'DATABASE_OVERRIDE' ? '轮换 Key' : '设置 Key'}</Button><Button color="error" disabled={credentialBusy || value.credentialSource !== 'DATABASE_OVERRIDE'} onClick={() => void removeCredential()} sx={{ flexShrink: 0 }}>清除热配置</Button></Stack>{probe && <Alert severity={probe.status === 'READY' ? 'success' : 'error'}>{probe.status === 'READY' ? `探测到 ${catalogCapabilities(probe).length} 个模型；已配置 ${models.length} 个，待导入 ${availableModels.length} 个。` : `${probe.errorCode}: ${probe.errorMessage}`}</Alert>}{probe?.warnings?.map((warning, index) => <Alert key={`${warning.code}:${warning.modelName ?? ''}:${index}`} severity="warning">{warning.modelName ? `${warning.modelName}：` : ''}{warning.message}</Alert>)}{availableModels.length > 0 && <Stack spacing={1.25}><ModelAvailabilitySummary capabilities={availableModels} /><Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5} alignItems={{ lg: 'center' }}><Autocomplete multiple disableCloseOnSelect options={availableModels} value={selectedCapabilities} onChange={(_event, selected) => setSelectedModels(selected.map((capability) => capability.modelName))} getOptionLabel={(capability) => capability.modelName} isOptionEqualToValue={(option, selected) => option.modelName === selected.modelName} getOptionDisabled={(capability) => !isCapabilitySelectable(capability, value.protocol)} renderOption={(props, capability) => <CapabilityOption {...props} key={capability.modelName} capability={capability} protocol={value.protocol} />} renderTags={(values, getTagProps) => values.map((capability, index) => <Chip {...getTagProps({ index })} key={capability.modelName} size="small" color={availabilityColor(capability.availability)} label={`${capability.modelName} · ${availabilityLabel(capability.availability)}`} sx={{ maxWidth: '100%', '& .MuiChip-label': { overflow: 'hidden', textOverflow: 'ellipsis' } }} />)} renderInput={(params) => <TextField {...params} label="导入新探测模型" helperText="不可用或协议不兼容的模型不能选择；未知模型不会被自动选择" />} sx={{ flex: 1 }} /><Button onClick={() => setSelectedModels(selectedModels.length > 0 ? [] : automaticallySelectable)}>{selectedModels.length > 0 ? '清空' : '选择已知可用'}</Button><Button variant="contained" disabled={importBusy || selectedModels.length === 0} onClick={() => void importDetectedModels()}>{importBusy ? '正在导入' : `导入 ${selectedModels.length} 个`}</Button></Stack></Stack>}</Stack></Paper>;
}

function catalogCapabilities(catalog: ProviderModelCatalog | null): DiscoveredModelCapability[] {
  if (!catalog) return [];
  if (catalog.modelCapabilities?.length) return catalog.modelCapabilities;
  return catalog.models.map(unknownCapability);
}

function capabilityByName(catalog: ProviderModelCatalog | null, modelName: string): DiscoveredModelCapability {
  return catalogCapabilities(catalog).find((capability) => capability.modelName === modelName) || unknownCapability(modelName);
}

function unknownCapability(modelName: string): DiscoveredModelCapability {
  const sources = {
    availability: 'UNKNOWN', supportedProtocols: 'UNKNOWN', maximumReasoningEffort: 'UNKNOWN',
    tokenLimitParameterStyles: 'UNKNOWN', streaming: 'UNKNOWN', tools: 'UNKNOWN', multimodal: 'UNKNOWN',
    maximumContextTokens: 'UNKNOWN', maximumInputTokens: 'UNKNOWN', maximumOutputTokens: 'UNKNOWN',
  } as const;
  return { modelName, availability: 'UNKNOWN', supportedProtocols: [], maximumReasoningEffort: null, tokenLimitParameterStyles: [], streaming: 'UNKNOWN', tools: 'UNKNOWN', multimodal: 'UNKNOWN', maximumContextTokens: null, maximumInputTokens: null, maximumOutputTokens: null, sources };
}

function modelImportConfiguration(capability: DiscoveredModelCapability, protocol: string): ModelImportConfiguration {
  const maximumReasoningEffort = capability.maximumReasoningEffort || 'PROVIDER_DEFAULT';
  return {
    modelName: capability.modelName,
    defaultReasoningEffort: maximumReasoningEffort,
    maximumReasoningEffort,
    tokenLimitParameterStyle: preferredTokenLimitStyle(capability.tokenLimitParameterStyles, protocol),
  };
}

function preferredTokenLimitStyle(styles: TokenLimitParameterStyle[], protocol: string): TokenLimitParameterStyle {
  if (styles.length === 0) return 'PROTOCOL_DEFAULT';
  const preference: TokenLimitParameterStyle[] = protocol === 'RESPONSES'
    ? ['MAX_OUTPUT_TOKENS', 'PROTOCOL_DEFAULT', 'NONE', 'MAX_COMPLETION_TOKENS', 'MAX_TOKENS']
    : ['MAX_COMPLETION_TOKENS', 'MAX_TOKENS', 'PROTOCOL_DEFAULT', 'NONE', 'MAX_OUTPUT_TOKENS'];
  return preference.find((style) => styles.includes(style)) || styles[0];
}

function isCapabilitySelectable(capability: DiscoveredModelCapability, protocol: string): boolean {
  return capability.availability !== 'UNAVAILABLE'
    && (capability.supportedProtocols.length === 0 || capability.supportedProtocols.includes(protocol as AiProvider['protocol']));
}

function isAutomaticallySelectable(capability: DiscoveredModelCapability, protocol: string): boolean {
  return (capability.availability === 'AVAILABLE' || capability.availability === 'DEGRADED')
    && isCapabilitySelectable(capability, protocol);
}

function ModelAvailabilitySummary({ capabilities }: { capabilities: DiscoveredModelCapability[] }) {
  const counts = capabilities.reduce<Record<ModelAvailability, number>>((result, capability) => {
    result[capability.availability] += 1;
    return result;
  }, { UNKNOWN: 0, AVAILABLE: 0, DEGRADED: 0, UNAVAILABLE: 0 });
  return <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap>{(['AVAILABLE', 'DEGRADED', 'UNKNOWN', 'UNAVAILABLE'] as ModelAvailability[]).filter((status) => counts[status] > 0).map((status) => <Chip key={status} size="small" color={availabilityColor(status)} label={`${availabilityLabel(status)} ${counts[status]}`} />)}</Stack>;
}

function CapabilityOption({ capability, protocol, ...props }: HTMLAttributes<HTMLLIElement> & { capability: DiscoveredModelCapability; protocol: string }) {
  const reasoning = capability.maximumReasoningEffort || '未知';
  const tokenStyle = capability.tokenLimitParameterStyles.join(' / ') || '未知';
  const protocolCompatible = capability.supportedProtocols.length === 0 || capability.supportedProtocols.includes(protocol as AiProvider['protocol']);
  return <li {...props}><Box sx={{ minWidth: 0, flex: 1 }}><Stack direction="row" spacing={0.75} alignItems="center" flexWrap="wrap" useFlexGap><Typography variant="body2" fontWeight={700} sx={{ minWidth: 0, overflowWrap: 'anywhere' }}>{capability.modelName}</Typography><Chip size="small" color={protocolCompatible ? availabilityColor(capability.availability) : 'error'} label={protocolCompatible ? availabilityLabel(capability.availability) : `不支持 ${protocol}`} /><Chip size="small" variant="outlined" label={capabilitySourceLabel(protocolCompatible ? capability.sources.availability : capability.sources.supportedProtocols)} /></Stack><Typography variant="caption" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>思考上限 {reasoning} · 输出参数 {tokenStyle}</Typography></Box></li>;
}

function availabilityColor(availability: ModelAvailability): 'default' | 'success' | 'warning' | 'error' {
  return ({ AVAILABLE: 'success', DEGRADED: 'warning', UNAVAILABLE: 'error', UNKNOWN: 'default' } as const)[availability];
}

function availabilityLabel(availability: ModelAvailability): string {
  return ({ AVAILABLE: '可用', DEGRADED: '性能下降', UNAVAILABLE: '不可用', UNKNOWN: '能力未知' } as const)[availability];
}

function capabilitySourceLabel(source: DiscoveredModelCapability['sources']['availability']): string {
  return ({ UNKNOWN: '来源未知', DECLARED: '目录声明', PROBED: '真实探测', MANUAL_OVERRIDE: '人工覆盖' } as const)[source];
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
  return <Paper variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5} alignItems={{ lg: 'center' }}><Box sx={{ flex: 1 }}><Typography fontWeight={700}>{value.modelName}</Typography><Typography variant="caption" color="text.secondary">{providerName}</Typography></Box><TextField select label="能力上限" value={value.maximumReasoningEffort} onChange={(event) => { const maximumReasoningEffort = event.target.value as ReasoningEffort; const defaultReasoningEffort = efforts.indexOf(value.defaultReasoningEffort) <= efforts.indexOf(maximumReasoningEffort) ? value.defaultReasoningEffort : maximumReasoningEffort; setValue({ ...value, maximumReasoningEffort, defaultReasoningEffort }); }} sx={{ minWidth: 150 }}>{efforts.map((effort) => <MenuItem key={effort} value={effort}>{effort}</MenuItem>)}</TextField><TextField select label="默认思考强度" value={value.defaultReasoningEffort} onChange={(event) => setValue({ ...value, defaultReasoningEffort: event.target.value as ReasoningEffort })} sx={{ minWidth: 170 }}>{supportedEfforts.map((effort) => <MenuItem key={effort} value={effort}>{effort}</MenuItem>)}</TextField><TextField select label="输出上限参数" value={value.tokenLimitParameterStyle} onChange={(event) => setValue({ ...value, tokenLimitParameterStyle: event.target.value as TokenLimitParameterStyle })} sx={{ minWidth: 190 }}>{tokenLimitStyles.map((style) => <MenuItem key={style.value} value={style.value}>{style.label}</MenuItem>)}</TextField><TextField label="输入 $/M" type="number" value={value.inputUsdPerMillion} onChange={(event) => setValue({ ...value, inputUsdPerMillion: Number(event.target.value) })} sx={{ width: 120 }} /><TextField label="输出 $/M" type="number" value={value.outputUsdPerMillion} onChange={(event) => setValue({ ...value, outputUsdPerMillion: Number(event.target.value) })} sx={{ width: 120 }} /><Switch checked={value.enabled} onChange={(event) => setValue({ ...value, enabled: event.target.checked })} /><Button startIcon={<SaveIcon />} onClick={() => save(value)}>保存</Button></Stack></Paper>;
}

export function ExecutionStageEditor({ stage, providers, models, save }: { stage: ExecutionAiStage; providers: AiProvider[]; models: AiModel[]; save: (value: ExecutionAiStage) => void }) {
  const [value, setValue] = useState(stage);
  useEffect(() => setValue(stage), [stage]);
  const bindingsValid = isEnabledExecutionBinding(value.primaryAiBinding, providers, models)
    && (value.fallbackAiBinding === null || isEnabledExecutionBinding(value.fallbackAiBinding, providers, models));
  return <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1.5}>
    <Stack direction="row" justifyContent="space-between"><Box><Typography fontWeight={700}>{value.stage === 'DRAFT' ? '初稿决策' : '反思终审'}</Typography><Typography variant="caption" color="text.secondary">执行前结构化判断，不输出隐藏思维链</Typography></Box><FormControlLabel control={<Switch checked={value.enabled} onChange={(event) => setValue({ ...value, enabled: event.target.checked })} />} label="启用" /></Stack>
    <ExecutionBindingEditor title="主模型" binding={value.primaryAiBinding} providers={providers} models={models} update={(primaryAiBinding) => setValue({ ...value, primaryAiBinding })} />
    <FormControlLabel control={<Switch checked={value.fallbackAiBinding !== null} onChange={(event) => {
      if (!event.target.checked) {
        setValue({ ...value, fallbackAiBinding: null });
        return;
      }
      const alternateProviders = providers.filter((provider) => provider.profileId !== bindingProviderId(value.primaryAiBinding));
      const fallback = defaultExecutionBinding(alternateProviders, models) || defaultExecutionBinding(providers, models);
      if (fallback) setValue({ ...value, fallbackAiBinding: fallback });
    }} />} label="启用兜底模型" />
    {value.fallbackAiBinding && <ExecutionBindingEditor title="兜底模型" binding={value.fallbackAiBinding} providers={providers} models={models} update={(fallbackAiBinding) => setValue({ ...value, fallbackAiBinding })} />}
    <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}>
      <TextField label="最大 Token" type="number" value={value.maximumOutputTokens} onChange={(event) => setValue({ ...value, maximumOutputTokens: Number(event.target.value) })} />
      <TextField label="单次超时（秒）" type="number" value={value.timeoutSeconds} onChange={(event) => setValue({ ...value, timeoutSeconds: Number(event.target.value) })} inputProps={{ min: 10, max: 3600, step: 1 }} />
      <TextField label="每个模型重试次数" type="number" value={value.retryPolicy.maximumAttempts} onChange={(event) => setValue({ ...value, retryPolicy: { ...value.retryPolicy, maximumAttempts: Number(event.target.value) } })} />
      <TextField label="重试退避（秒）" type="number" value={durationSeconds(value.retryPolicy.backoff)} onChange={(event) => setValue({ ...value, retryPolicy: { ...value.retryPolicy, backoff: Number(event.target.value) } })} />
    </Stack>
    <TextField multiline minRows={3} label="系统提示词" value={value.systemPrompt} onChange={(event) => setValue({ ...value, systemPrompt: event.target.value })} />
    <TextField multiline minRows={2} label="用户提示模板" value={value.userPromptTemplate} onChange={(event) => setValue({ ...value, userPromptTemplate: event.target.value })} />
    <Button variant="contained" startIcon={<SaveIcon />} disabled={!bindingsValid} onClick={() => save(value)} sx={{ alignSelf: 'flex-end' }}>保存阶段</Button>
  </Stack></Paper>;
}

function ExecutionBindingEditor({ title, binding, providers, models, update }: { title: string; binding: AiModelBinding; providers: AiProvider[]; models: AiModel[]; update: (binding: AiModelBinding) => void }) {
  const selectedProviderId = bindingProviderId(binding);
  const selectableProviders = providers.filter((provider) => provider.enabled && enabledExecutionModels(models, provider.profileId).length > 0);
  const availableModels = enabledExecutionModels(models, selectedProviderId);
  const selectedModel = availableModels.find((model) => model.modelName === binding.modelName);
  const supportedEfforts = selectedModel ? efforts.filter((effort, index) => effort === 'PROVIDER_DEFAULT' || (efforts.indexOf(selectedModel.maximumReasoningEffort) > 0 && index <= efforts.indexOf(selectedModel.maximumReasoningEffort))) : efforts;
  const providerValue = selectableProviders.some((provider) => provider.profileId === selectedProviderId) ? selectedProviderId : '';
  return <Stack spacing={1.25} sx={{ borderTop: '1px solid', borderColor: 'divider', pt: 1.5 }}><Typography variant="subtitle2">{title}</Typography>{(!providerValue || !selectedModel) && <Alert severity="error">当前绑定不可用，请重新选择启用的厂商及其模型。</Alert>}<Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}><TextField select label="厂商" value={providerValue} onChange={(event) => {
    const nextModel = enabledExecutionModels(models, event.target.value)[0];
    if (!nextModel) return;
    update({ providerProfileId: event.target.value, modelName: nextModel.modelName, reasoningEffort: nextModel.defaultReasoningEffort });
  }} sx={{ minWidth: 220 }}><MenuItem value="" disabled>请选择启用的厂商</MenuItem>{selectableProviders.map((item) => <MenuItem key={item.profileId} value={item.profileId}>{item.displayName}</MenuItem>)}</TextField><TextField select label="模型" value={selectedModel?.modelName || ''} onChange={(event) => { const model = availableModels.find((item) => item.modelName === event.target.value); if (!model) return; update({ ...binding, modelName: model.modelName, reasoningEffort: model.defaultReasoningEffort }); }} sx={{ minWidth: 220 }}><MenuItem value="" disabled>请选择启用的模型</MenuItem>{availableModels.map((item) => <MenuItem key={item.modelProfileId} value={item.modelName}>{item.modelName}</MenuItem>)}</TextField><TextField select label="思考强度" value={supportedEfforts.includes(binding.reasoningEffort) ? binding.reasoningEffort : ''} onChange={(event) => update({ ...binding, reasoningEffort: event.target.value as ReasoningEffort })} sx={{ minWidth: 150 }}><MenuItem value="" disabled>请选择模型支持的强度</MenuItem>{supportedEfforts.map((effort) => <MenuItem key={effort} value={effort}>{effort}</MenuItem>)}</TextField></Stack></Stack>;
}

export function enabledExecutionModels(models: AiModel[], providerProfileId: string): AiModel[] {
  return models.filter((model) => model.enabled && model.providerProfileId === providerProfileId);
}

export function defaultExecutionBinding(providers: AiProvider[], models: AiModel[]): AiModelBinding | null {
  const provider = providers.find((candidate) => candidate.enabled && enabledExecutionModels(models, candidate.profileId).length > 0);
  if (!provider) return null;
  const model = enabledExecutionModels(models, provider.profileId)[0];
  return { providerProfileId: provider.profileId, modelName: model.modelName, reasoningEffort: model.defaultReasoningEffort };
}

function isEnabledExecutionBinding(binding: AiModelBinding, providers: AiProvider[], models: AiModel[]): boolean {
  const model = enabledExecutionModels(models, binding.providerProfileId)
    .find((candidate) => candidate.modelName === binding.modelName);
  return providers.some((provider) => provider.enabled && provider.profileId === binding.providerProfileId)
    && model !== undefined
    && (binding.reasoningEffort === 'PROVIDER_DEFAULT'
      || (model.maximumReasoningEffort !== 'PROVIDER_DEFAULT'
        && efforts.indexOf(binding.reasoningEffort) <= efforts.indexOf(model.maximumReasoningEffort)));
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
