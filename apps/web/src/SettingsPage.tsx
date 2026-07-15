import SaveIcon from '@mui/icons-material/Save';
import { Alert, Box, Button, Chip, FormControlLabel, MenuItem, Paper, Stack, Switch, Tab, Tabs, TextField, Typography } from '@mui/material';
import { useEffect, useState } from 'react';

import { api } from './api';
import type { AgentRole, AiExperiment, AiModel, AiModelBinding, AiProvider, ConfigurationSnapshot, ExecutionAiStage, ProviderModelCatalog, ReasoningEffort, RiskPolicy, SetupProfileDefinition, TradeAutomationConfiguration, WorkflowDefinitionSummary } from './types';
import { ErrorBlock, LoadingBlock, SectionTitle } from './ui';
import { AgentRolesPanel, AiExperimentsPanel, SetupProfilesPanel } from './SettingsAdvancedPanels';
import { replaceWorkspaceLocation, workspaceSubview } from './workspaceLocation';

const efforts: ReasoningEffort[] = ['PROVIDER_DEFAULT', 'NONE', 'MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'XHIGH', 'MAX'];
const settingsTabs = ['setup', 'runtime', 'providers', 'models', 'roles', 'execution', 'experiments'] as const;
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
  const changeTab = (next: SettingsTab) => { setTab(next); replaceWorkspaceLocation('settings', next); };
  if (error !== null && (!config || !trading)) return <ErrorBlock error={error} />;
  if (!config || !trading) return <LoadingBlock label="正在读取默认配置与密钥状态" />;
  return <Stack spacing={3}>
    {error !== null && <ErrorBlock error={error} />}{message && <Alert severity="success">{message}</Alert>}
    <Tabs value={tab} onChange={(_event, value: SettingsTab) => changeTab(value)} variant="scrollable" scrollButtons="auto"><Tab value="setup" label="快速启用" /><Tab value="runtime" label="运行基础" /><Tab value="providers" label="模型服务" /><Tab value="models" label="模型与费率" /><Tab value="roles" label="角色与提示词" /><Tab value="execution" label="交易机器人与风险" /><Tab value="experiments" label="A/B 实验" /></Tabs>
    {tab === 'setup' && <SetupProfilesPanel profiles={profiles} onApplied={load} />}
    {tab === 'runtime' && <Box><SectionTitle title="运行参数" /><Stack spacing={1}>{config.settings.map((setting) => <Paper key={setting.key} variant="outlined" sx={{ p: 1.5 }}><Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems={{ md: 'center' }}><Box sx={{ flex: 1 }}><Typography fontWeight={700}>{setting.description}</Typography><Typography variant="caption" color="text.secondary">{setting.key} · {setting.source}</Typography></Box><TextField defaultValue={setting.value} onBlur={(event) => { if (event.target.value !== setting.value) void saved(api.updateSetting(setting, event.target.value)); }} sx={{ width: { md: 260 } }} /></Stack></Paper>)}</Stack></Box>}
    {tab === 'providers' && <Box><SectionTitle title="AI 厂商" /><Stack spacing={1.25}>{config.providers.map((provider) => <ProviderEditor key={provider.profileId} provider={provider} save={(next) => saved(api.updateProvider(next))} />)}</Stack></Box>}
    {tab === 'models' && <Box><SectionTitle title="模型与默认费率" /><Stack spacing={1.25}>{config.models.map((model) => <ModelEditor key={model.modelProfileId} model={model} save={(next) => saved(api.updateModel(next))} />)}</Stack></Box>}
    {tab === 'roles' && <AgentRolesPanel roles={roles} providers={config.providers} models={config.models} onChanged={load} />}
    {tab === 'execution' && <><Box><SectionTitle title="最终交易机器人" /><Stack spacing={1.5}>{trading.aiStages.map((stage) => <ExecutionStageEditor key={stage.stage} stage={stage} providers={config.providers} models={config.models} save={(next) => {
      void saved(api.updateExecutionStage(next.stage, { primaryAiBinding: requestBinding(next.primaryAiBinding), fallbackAiBinding: next.fallbackAiBinding ? requestBinding(next.fallbackAiBinding) : null, systemPrompt: next.systemPrompt, userPromptTemplate: next.userPromptTemplate, maximumOutputTokens: next.maximumOutputTokens, timeoutSeconds: next.timeoutSeconds, retryMaximumAttempts: next.retryPolicy.maximumAttempts, retryBackoffSeconds: durationSeconds(next.retryPolicy.backoff), enabled: next.enabled, expectedVersion: next.version }));
    }} />)}</Stack></Box><RiskPolicyEditor policy={trading.activeRiskPolicy} save={(next) => saved(api.activateRiskPolicy(next))} /></>}
    {tab === 'experiments' && <AiExperimentsPanel experiments={experiments} definitions={definitions} onChanged={load} />}
  </Stack>;
}

function ProviderEditor({ provider, save }: { provider: AiProvider; save: (value: AiProvider) => void }) {
  const [value, setValue] = useState(provider);
  const [probe, setProbe] = useState<ProviderModelCatalog | null>(null);
  const [probeError, setProbeError] = useState<unknown>(null);
  useEffect(() => setValue(provider), [provider]);
  const runProbe = async () => { setProbeError(null); try { setProbe(await api.probeProvider(value.profileId)); } catch (cause) { setProbeError(cause); } };
  return <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1.25}>{probeError !== null && <ErrorBlock error={probeError} />}<Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5} alignItems={{ lg: 'center' }}><Box sx={{ flex: 1 }}><Stack direction="row" spacing={1} alignItems="center"><Typography fontWeight={700}>{value.displayName}</Typography><Chip size="small" color={value.apiKeyConfigured ? 'success' : 'warning'} label={value.apiKeyConfigured ? 'Key 已注入' : 'Key 未配置'} /></Stack><Typography variant="caption" color="text.secondary">{value.profileId} · {value.protocol} · {value.apiKeyEnv}</Typography></Box><TextField label="Base URL" value={value.baseUrl || ''} onChange={(event) => setValue({ ...value, baseUrl: event.target.value || null, baseUrlEnv: null })} sx={{ minWidth: 280 }} /><FormControlLabel control={<Switch checked={value.enabled} onChange={(event) => setValue({ ...value, enabled: event.target.checked })} />} label="启用" /><Button onClick={() => void runProbe()}>探测模型</Button><Button startIcon={<SaveIcon />} onClick={() => save(value)}>保存</Button></Stack>{probe && <Alert severity={probe.status === 'READY' ? 'success' : 'error'}>{probe.status === 'READY' ? `可用模型 ${probe.models.length} 个：${probe.models.slice(0, 12).join('、')}${probe.models.length > 12 ? '…' : ''}` : `${probe.errorCode}: ${probe.errorMessage}`}</Alert>}</Stack></Paper>;
}

function ModelEditor({ model, save }: { model: AiModel; save: (value: AiModel) => void }) {
  const [value, setValue] = useState(model);
  useEffect(() => setValue(model), [model]);
  return <Paper variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5} alignItems={{ lg: 'center' }}><Box sx={{ flex: 1 }}><Typography fontWeight={700}>{value.modelName}</Typography><Typography variant="caption" color="text.secondary">{value.providerProfileId}</Typography></Box><TextField select label="默认思考强度" value={value.defaultReasoningEffort} onChange={(event) => setValue({ ...value, defaultReasoningEffort: event.target.value as ReasoningEffort })} sx={{ minWidth: 170 }}>{efforts.map((effort) => <MenuItem key={effort} value={effort}>{effort}</MenuItem>)}</TextField><TextField label="输入 $/M" type="number" value={value.inputUsdPerMillion} onChange={(event) => setValue({ ...value, inputUsdPerMillion: Number(event.target.value) })} sx={{ width: 120 }} /><TextField label="输出 $/M" type="number" value={value.outputUsdPerMillion} onChange={(event) => setValue({ ...value, outputUsdPerMillion: Number(event.target.value) })} sx={{ width: 120 }} /><Switch checked={value.enabled} onChange={(event) => setValue({ ...value, enabled: event.target.checked })} /><Button startIcon={<SaveIcon />} onClick={() => save(value)}>保存</Button></Stack></Paper>;
}

function ExecutionStageEditor({ stage, providers, models, save }: { stage: ExecutionAiStage; providers: AiProvider[]; models: AiModel[]; save: (value: ExecutionAiStage) => void }) {
  const [value, setValue] = useState(stage);
  useEffect(() => setValue(stage), [stage]);
  return <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1.5}><Stack direction="row" justifyContent="space-between"><Box><Typography fontWeight={700}>{value.stage === 'DRAFT' ? '初稿决策' : '反思终审'}</Typography><Typography variant="caption" color="text.secondary">执行前结构化判断，不输出隐藏思维链</Typography></Box><FormControlLabel control={<Switch checked={value.enabled} onChange={(event) => setValue({ ...value, enabled: event.target.checked })} />} label="启用" /></Stack><ExecutionBindingEditor title="主模型" binding={value.primaryAiBinding} providers={providers} models={models} update={(primaryAiBinding) => setValue({ ...value, primaryAiBinding })} /><FormControlLabel control={<Switch checked={value.fallbackAiBinding !== null} onChange={(event) => {
    if (!event.target.checked) { setValue({ ...value, fallbackAiBinding: null }); return; }
    const fallback = models.find((model) => model.enabled && model.providerProfileId !== bindingProviderId(value.primaryAiBinding)) || models.find((model) => model.enabled) || models[0];
    if (fallback) setValue({ ...value, fallbackAiBinding: { providerProfileId: fallback.providerProfileId, modelName: fallback.modelName, reasoningEffort: fallback.defaultReasoningEffort } });
  }} />} label="启用兜底模型" />{value.fallbackAiBinding && <ExecutionBindingEditor title="兜底模型" binding={value.fallbackAiBinding} providers={providers} models={models} update={(fallbackAiBinding) => setValue({ ...value, fallbackAiBinding })} />}<Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}><TextField label="最大 Token" type="number" value={value.maximumOutputTokens} onChange={(event) => setValue({ ...value, maximumOutputTokens: Number(event.target.value) })} /><TextField label="单次超时（秒）" type="number" value={value.timeoutSeconds} onChange={(event) => setValue({ ...value, timeoutSeconds: Number(event.target.value) })} /><TextField label="每个模型重试次数" type="number" value={value.retryPolicy.maximumAttempts} onChange={(event) => setValue({ ...value, retryPolicy: { ...value.retryPolicy, maximumAttempts: Number(event.target.value) } })} /><TextField label="重试退避（秒）" type="number" value={durationSeconds(value.retryPolicy.backoff)} onChange={(event) => setValue({ ...value, retryPolicy: { ...value.retryPolicy, backoff: Number(event.target.value) } })} /></Stack><TextField multiline minRows={3} label="系统提示词" value={value.systemPrompt} onChange={(event) => setValue({ ...value, systemPrompt: event.target.value })} /><TextField multiline minRows={2} label="用户提示模板" value={value.userPromptTemplate} onChange={(event) => setValue({ ...value, userPromptTemplate: event.target.value })} /><Button variant="contained" startIcon={<SaveIcon />} onClick={() => save(value)} sx={{ alignSelf: 'flex-end' }}>保存阶段</Button></Stack></Paper>;
}

function ExecutionBindingEditor({ title, binding, providers, models, update }: { title: string; binding: AiModelBinding; providers: AiProvider[]; models: AiModel[]; update: (binding: AiModelBinding) => void }) {
  const selectedProviderId = bindingProviderId(binding);
  const availableModels = models.filter((model) => model.providerProfileId === selectedProviderId);
  return <Stack spacing={1.25} sx={{ borderTop: '1px solid', borderColor: 'divider', pt: 1.5 }}><Typography variant="subtitle2">{title}</Typography><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}><TextField select label="厂商" value={selectedProviderId} onChange={(event) => {
    const selectedModel = models.find((model) => model.enabled && model.providerProfileId === event.target.value) || models.find((model) => model.providerProfileId === event.target.value);
    update({ providerProfileId: event.target.value, modelName: selectedModel?.modelName || binding.modelName, reasoningEffort: selectedModel?.defaultReasoningEffort || binding.reasoningEffort });
  }} sx={{ minWidth: 220 }}>{providers.map((item) => <MenuItem key={item.profileId} value={item.profileId}>{item.displayName}</MenuItem>)}</TextField><TextField select label="模型" value={binding.modelName} onChange={(event) => update({ ...binding, modelName: event.target.value })} sx={{ minWidth: 220 }}>{availableModels.map((item) => <MenuItem key={item.modelProfileId} value={item.modelName}>{item.modelName}</MenuItem>)}{!availableModels.some((item) => item.modelName === binding.modelName) && <MenuItem value={binding.modelName}>{binding.modelName}</MenuItem>}</TextField><TextField select label="思考强度" value={binding.reasoningEffort} onChange={(event) => update({ ...binding, reasoningEffort: event.target.value as ReasoningEffort })} sx={{ minWidth: 150 }}>{efforts.map((effort) => <MenuItem key={effort} value={effort}>{effort}</MenuItem>)}</TextField></Stack></Stack>;
}

function RiskPolicyEditor({ policy, save }: { policy: RiskPolicy; save: (value: RiskPolicy & { policyVersion: string }) => void }) {
  const [value, setValue] = useState(policy);
  const [version, setVersion] = useState(`paper-custom-${new Date().toISOString().slice(0, 10).replace(/-/g, '')}-v1`);
  useEffect(() => setValue(policy), [policy]);
  const fields: Array<[keyof RiskPolicy, string]> = [['minimumConfidence', '最低置信度'], ['riskBudgetUsdt', '单次风险预算 USDT'], ['maximumNotionalUsdt', '最大名义价值 USDT'], ['preferredLeverage', '期望实际杠杆'], ['maximumLeverage', '风控杠杆上限'], ['maximumOpenPositions', '最大持仓数'], ['maximumStopDistance', '最大止损距离'], ['takerFeeRate', 'Taker 费率'], ['slippageRate', '滑点率'], ['liquidationBufferRate', '强平缓冲率']];
  return <Box><SectionTitle title="模拟交易风险策略" /><Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1.5}><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} flexWrap={{ md: 'wrap' }} useFlexGap><TextField label="新策略版本" value={version} onChange={(event) => setVersion(event.target.value)} sx={{ width: { xs: '100%', md: 260 } }} />{fields.map(([key, label]) => <TextField key={key} label={label} type="number" value={String(value[key])} onChange={(event) => setValue({ ...value, [key]: key === 'maximumOpenPositions' ? Number.parseInt(event.target.value, 10) : Number(event.target.value) })} sx={{ width: { xs: '100%', md: 170 } }} />)}</Stack><Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} justifyContent="space-between" alignItems={{ sm: 'center' }}><FormControlLabel control={<Switch checked={value.testEnvironmentOnly} onChange={(event) => setValue({ ...value, testEnvironmentOnly: event.target.checked })} />} label="仅 TestNet / Demo" /><Button variant="contained" startIcon={<SaveIcon />} onClick={() => save({ ...value, policyVersion: version })}>创建并启用版本</Button></Stack></Stack></Paper></Box>;
}

function bindingProviderId(binding: AiModelBinding): string { return typeof binding.providerProfileId === 'string' ? binding.providerProfileId : binding.providerProfileId.value; }

function requestBinding(binding: AiModelBinding) {
  return { providerProfileId: bindingProviderId(binding), modelName: binding.modelName, reasoningEffort: binding.reasoningEffort };
}

function durationSeconds(value: number | string): number {
  if (typeof value === 'number') return value;
  const match = /^PT([0-9]+(?:\.[0-9]+)?)S$/.exec(value);
  return match ? Number(match[1]) : 0;
}
