import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import RefreshIcon from '@mui/icons-material/Refresh';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import { Accordion, AccordionDetails, AccordionSummary, Alert, Box, Button, Checkbox, Chip, FormControl, FormControlLabel, InputLabel, MenuItem, Paper, Select, Stack, Switch, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography } from '@mui/material';
import { useCallback, useEffect, useRef, useState } from 'react';

import { api } from './api';
import { SecretTextField } from './SecretTextField';
import type { NetworkDiagnostic, NetworkWorkspace, ProxyGatewayRuntimeStatus } from './types';
import { ErrorBlock, LoadingBlock, SectionTitle, formatTime, statusColor, statusLabel } from './ui';

export function NetworkPage() {
  const [workspace, setWorkspace] = useState<NetworkWorkspace | null>(null);
  const [gatewayStatuses, setGatewayStatuses] = useState<Record<string, ProxyGatewayRuntimeStatus | null>>({});
  const [diagnostics, setDiagnostics] = useState<NetworkDiagnostic[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [proxyValues, setProxyValues] = useState<Record<string, string>>({});
  const [gatewayDrafts, setGatewayDrafts] = useState<Record<string, GatewayDraft>>({});
  const [gatewaySecrets, setGatewaySecrets] = useState<Record<string, string>>({});
  const [activeSection, setActiveSection] = useState<NetworkSection>('gateways');
  const [expandedGateway, setExpandedGateway] = useState<string | false>(false);
  const gatewayExpansionInitialized = useRef(false);
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const refresh = useCallback(async () => {
    try {
      const [routes, history] = await Promise.all([api.network(), api.networkDiagnostics(100)]);
      setWorkspace(routes); setDiagnostics(history);
      setSelected((current) => current.length ? current : routes.routes.filter((route) => route.enabled).map((route) => route.routeType));
      const statuses = await Promise.all(routes.proxyGateways.map(async (gateway) => {
        if (!gateway.enabled) return [gateway.gatewayId, null] as const;
        try { return [gateway.gatewayId, await api.proxyGatewayStatus(gateway.gatewayId)] as const; }
        catch { return [gateway.gatewayId, null] as const; }
      }));
      setGatewayStatuses(Object.fromEntries(statuses));
    } catch (cause) { setError(cause); }
  }, []);
  useEffect(() => { void refresh(); const timer = window.setInterval(() => void refresh(), 10000); return () => window.clearInterval(timer); }, [refresh]);
  useEffect(() => {
    if (gatewayExpansionInitialized.current || !workspace) return;
    const degraded = workspace.proxyGateways.find((gateway) => {
      const runtime = gatewayStatuses[gateway.gatewayId];
      return gateway.enabled && runtime && (!runtime.serviceReady || !runtime.egressReady);
    });
    if (degraded || workspace.proxyGateways[0]) {
      setExpandedGateway((degraded || workspace.proxyGateways[0]).gatewayId);
      gatewayExpansionInitialized.current = true;
    }
  }, [gatewayStatuses, workspace]);
  const start = async () => {
    setBusy(true); setError(null); setMessage('');
    try { const runs = await api.startNetworkDiagnostics(selected); setMessage(`${runs.length} 条后台诊断已启动`); await refresh(); }
    catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const toggle = (route: string, checked: boolean) => setSelected((current) => checked ? [...new Set([...current, route])] : current.filter((value) => value !== route));
  const putProxy = async (route: NetworkWorkspace['routes'][number]) => {
    const proxyUrl = (proxyValues[route.routeType] || '').trim();
    if (!proxyUrl) return;
    setBusy(true); setError(null); setMessage('');
    try { await api.putRuntimeSecret('PROXY_ROUTE', route.routeType, 'PROXY_URL', proxyUrl, route.proxyCredentialVersion); setProxyValues((current) => ({ ...current, [route.routeType]: '' })); await api.startNetworkDiagnostics([route.routeType]); setMessage(`${route.displayName}已热更新并启动诊断`); await refresh(); }
    catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const clearProxy = async (route: NetworkWorkspace['routes'][number]) => {
    setBusy(true); setError(null); setMessage('');
    try { await api.clearRuntimeSecret('PROXY_ROUTE', route.routeType, 'PROXY_URL', route.proxyCredentialVersion); setMessage(`${route.displayName}已清除热配置并恢复启动备用值`); await refresh(); }
    catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const updateGatewayDraft = (gateway: NetworkWorkspace['proxyGateways'][number], change: Partial<GatewayDraft>) => {
    setGatewayDrafts((current) => ({ ...current, [gateway.gatewayId]: { ...toGatewayDraft(gateway), ...current[gateway.gatewayId], ...change } }));
  };
  const saveGateway = async (gateway: NetworkWorkspace['proxyGateways'][number]) => {
    const draft = gatewayDrafts[gateway.gatewayId] || toGatewayDraft(gateway);
    setBusy(true); setError(null); setMessage('');
    try {
      await api.updateProxyGateway({ ...gateway, ...draft });
      if (draft.enabled) await api.reloadProxyGateway(gateway.gatewayId);
      setGatewayDrafts((current) => withoutKey(current, gateway.gatewayId));
      setMessage(`${gateway.displayName}配置已热更新${draft.enabled ? '并重新加载' : ''}`);
      await refresh();
    } catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const reloadGateway = async (gateway: NetworkWorkspace['proxyGateways'][number]) => {
    setBusy(true); setError(null); setMessage('');
    try { await api.reloadProxyGateway(gateway.gatewayId); setMessage(`${gateway.displayName}已重新探测节点`); await refresh(); }
    catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const putGatewaySecret = async (gateway: NetworkWorkspace['proxyGateways'][number], secretName: 'SUBSCRIPTION_URL' | 'INLINE_NODES') => {
    const key = gatewaySecretKey(gateway.gatewayId, secretName);
    const value = (gatewaySecrets[key] || '').trim();
    if (!value) return;
    const expectedVersion = secretName === 'SUBSCRIPTION_URL' ? gateway.subscriptionVersion : gateway.inlineNodesVersion;
    setBusy(true); setError(null); setMessage('');
    try {
      await api.putRuntimeSecret('PROXY_GATEWAY', gateway.gatewayId, secretName, value, expectedVersion);
      await api.reloadProxyGateway(gateway.gatewayId);
      setGatewaySecrets((current) => ({ ...current, [key]: '' }));
      setMessage(`${gateway.displayName}节点来源已热更新并重新加载`);
      await refresh();
    } catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const clearGatewaySecret = async (gateway: NetworkWorkspace['proxyGateways'][number], secretName: 'SUBSCRIPTION_URL' | 'INLINE_NODES') => {
    const expectedVersion = secretName === 'SUBSCRIPTION_URL' ? gateway.subscriptionVersion : gateway.inlineNodesVersion;
    setBusy(true); setError(null); setMessage('');
    try {
      await api.clearRuntimeSecret('PROXY_GATEWAY', gateway.gatewayId, secretName, expectedVersion);
      await api.reloadProxyGateway(gateway.gatewayId);
      setMessage(`${gateway.displayName}节点来源已恢复启动备用值并重新加载`);
      await refresh();
    } catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  if (!workspace) return <LoadingBlock label="正在读取网络路由" />;
  const enabledGateways = workspace.proxyGateways.filter((gateway) => gateway.enabled);
  const degradedGateways = enabledGateways.filter((gateway) => {
    const runtime = gatewayStatuses[gateway.gatewayId];
    return runtime !== null && runtime !== undefined && (!runtime.serviceReady || !runtime.egressReady);
  });
  const routeIssues = workspace.routes.filter((route) => ['FAILED', 'BLOCKED'].includes(route.latestDependencyStatus));
  const directFallbackRoutes = workspace.routes.filter((route) => route.allowDirect && !route.requireProxy && route.proxyCredentialSource !== 'UNCONFIGURED');
  const readyGateways = enabledGateways.filter((gateway) => gatewayStatuses[gateway.gatewayId]?.egressReady).length;
  const readyRoutes = workspace.routes.filter((route) => route.status === 'READY').length;
  return <Stack spacing={2.5}>
    {error !== null && <ErrorBlock error={error} />}
    {message && <Alert severity="success">{message}</Alert>}
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} justifyContent="space-between" alignItems={{ sm: 'center' }}>
      <Box><Typography variant="h2">网络状态</Typography><Typography variant="body2" color="text.secondary">先看出口是否可用，再修改节点来源或执行路由诊断。</Typography></Box>
      <Button startIcon={<RefreshIcon />} onClick={() => void refresh()}>刷新状态</Button>
    </Stack>
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, minmax(0, 1fr))' }, gap: 1.25 }}>
      <NetworkMetric label="可用代理池" value={`${readyGateways}/${enabledGateways.length}`} detail={degradedGateways.length ? `${degradedGateways.length} 个出口异常` : '启用池均可用'} tone={degradedGateways.length ? 'warning' : 'success'} />
      <NetworkMetric label="可用出站路由" value={`${readyRoutes}/${workspace.routes.length}`} detail={routeIssues.length ? `${routeIssues.length} 个依赖需要复核` : '路由状态正常'} tone={routeIssues.length ? 'warning' : 'success'} />
      <NetworkMetric label="诊断记录" value={String(diagnostics.length)} detail="最近 100 条后台结果" tone="info" />
    </Box>
    {directFallbackRoutes.length > 0 && <Alert severity="warning">{directFallbackRoutes.map((route) => route.displayName).join('、')} 当前允许代理失败后直连回退；这能保持业务可用，但不代表代理出口已验证成功。</Alert>}
    <Paper variant="outlined" sx={{ px: .5 }}>
      <Tabs value={activeSection} onChange={(_event, value: NetworkSection) => setActiveSection(value)} variant="scrollable" allowScrollButtonsMobile aria-label="网络管理分区">
        <Tab value="gateways" label={`代理池 ${enabledGateways.length}`} />
        <Tab value="routes" label={`出站路由 ${workspace.routes.length}`} />
        <Tab value="history" label={`诊断历史 ${diagnostics.length}`} />
      </Tabs>
    </Paper>
    {activeSection === 'gateways' && <Box>
      <SectionTitle title="代理池热配置" />
      <Stack spacing={1.25}>{workspace.proxyGateways.map((gateway) => {
        const draft = gatewayDrafts[gateway.gatewayId] || toGatewayDraft(gateway);
        const runtimeLoaded = Object.prototype.hasOwnProperty.call(gatewayStatuses, gateway.gatewayId);
        const runtime = gatewayStatuses[gateway.gatewayId];
        return <Accordion key={gateway.gatewayId} expanded={expandedGateway === gateway.gatewayId} onChange={(_event, expanded) => setExpandedGateway(expanded ? gateway.gatewayId : false)} disableGutters variant="outlined" sx={{ '&:before': { display: 'none' } }}>
          <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ px: { xs: 1.5, sm: 2 }, '& .MuiAccordionSummary-content': { my: 1.25 } }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: .5, sm: 1.5 }} alignItems={{ sm: 'center' }} justifyContent="space-between" sx={{ width: '100%', pr: 1 }}>
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap"><Typography fontWeight={700}>{gateway.displayName}</Typography><Chip size="small" variant="outlined" color={statusColor(gateway.status)} label={`配置 ${statusLabel(gateway.status)}`} /><Chip data-testid={`proxy-runtime-${gateway.gatewayId}`} size="small" color={gatewayRuntimeColor(gateway, runtime, runtimeLoaded)} label={gatewayRuntimeLabel(gateway, runtime, runtimeLoaded)} /></Stack>
              <Typography variant="caption" color="text.secondary">{gatewayRuntimeSummary(gateway, runtime, runtimeLoaded)} · 更新于 {formatTime(gateway.updatedAt)}</Typography>
            </Stack>
          </AccordionSummary>
          <AccordionDetails sx={{ px: { xs: 1.5, sm: 2 }, pb: 2 }}><Stack spacing={2}>
            {gateway.enabled && runtime && <Stack direction={{ xs: 'column', md: 'row' }} spacing={{ xs: 0.5, md: 2 }}>
              <Typography variant="body2">运行内核 {proxyEngineLabel(runtime.engine)}</Typography>
              <Typography variant="body2">健康节点 {runtime.healthyNodeCount}/{runtime.nodeCount}</Typography>
              <Typography variant="body2">探测目标 {runtime.validationTarget || '未配置'}</Typography>
              <Typography variant="body2">最近探测 {formatTime(runtime.lastRefreshAt)}</Typography>
              {runtime.healthyNodeIndices.length > 0 && <Typography variant="body2">节点索引 {runtime.healthyNodeIndices.join(', ')}</Typography>}
            </Stack>}
            {gateway.enabled && runtime && Object.keys(runtime.probeFailureCounts).length > 0 && <Typography variant="caption" color={runtime.egressReady ? 'text.secondary' : 'error'}>{Object.entries(runtime.probeFailureCounts).map(([code, count]) => `${probeFailureLabel(code)} × ${count}`).join('；')}</Typography>}
            {gateway.enabled && runtime?.error && <Typography variant="caption" color="error">{proxyRuntimeError(runtime.error)}</Typography>}
            {draft.engine === 'XRAY' && <Alert severity="info">Xray 仅加载 VLESS 节点；节点源包含 Hysteria2 时会拒绝切换并保留失败状态。</Alert>}
            <Stack direction={{ xs: 'column', lg: 'row' }} spacing={1} alignItems={{ lg: 'center' }}>
              <FormControl size="small" sx={{ minWidth: { lg: 150 } }}>
                <InputLabel id={`${gateway.gatewayId}-engine-label`}>代理内核</InputLabel>
                <Select labelId={`${gateway.gatewayId}-engine-label`} label="代理内核" value={draft.engine} onChange={(event) => updateGatewayDraft(gateway, { engine: event.target.value as Gateway['engine'] })}>
                  <MenuItem value="SING_BOX">sing-box</MenuItem>
                  <MenuItem value="XRAY">Xray</MenuItem>
                </Select>
              </FormControl>
              <TextField size="small" label="优选标签" value={draft.preferredNames} onChange={(event) => updateGatewayDraft(gateway, { preferredNames: event.target.value })} sx={{ minWidth: { lg: 240 } }} />
              <TextField size="small" label="最大节点数" type="number" value={draft.maximumNodes} inputProps={{ min: 1, max: 128 }} onChange={(event) => updateGatewayDraft(gateway, { maximumNodes: Number(event.target.value) })} sx={{ width: { lg: 140 } }} />
              <TextField size="small" label="刷新周期（秒）" type="number" value={draft.refreshSeconds} inputProps={{ min: 60, max: 86400 }} onChange={(event) => updateGatewayDraft(gateway, { refreshSeconds: Number(event.target.value) })} sx={{ width: { lg: 170 } }} />
              <FormControlLabel control={<Switch checked={draft.enabled} onChange={(event) => updateGatewayDraft(gateway, { enabled: event.target.checked })} />} label="启用" />
              <FormControlLabel control={<Switch color="warning" checked={draft.allowInsecureTls} onChange={(event) => updateGatewayDraft(gateway, { allowInsecureTls: event.target.checked })} />} label="允许不安全 TLS" />
              <Button variant="contained" disabled={busy || draft.maximumNodes < 1 || draft.refreshSeconds < 60} onClick={() => void saveGateway(gateway)}>保存</Button>
              <Button startIcon={<RestartAltIcon />} disabled={busy || !gateway.enabled} onClick={() => void reloadGateway(gateway)}>重新探测</Button>
            </Stack>
            {gateway.subscriptionSupported && <GatewaySecretEditor gateway={gateway} secretName="SUBSCRIPTION_URL" label="订阅地址" values={gatewaySecrets} busy={busy} onChange={setGatewaySecrets} onSave={putGatewaySecret} onClear={clearGatewaySecret} />}
            {gateway.inlineNodesSupported && <GatewaySecretEditor gateway={gateway} secretName="INLINE_NODES" label="内联节点" values={gatewaySecrets} busy={busy} onChange={setGatewaySecrets} onSave={putGatewaySecret} onClear={clearGatewaySecret} />}
          </Stack></AccordionDetails>
        </Accordion>;
      })}</Stack>
    </Box>}
    {activeSection === 'routes' && <Box>
      <SectionTitle title="出站路由" action={<Typography variant="body2" color="text.secondary">勾选后可批量运行诊断</Typography>} />
      <Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small" sx={{ minWidth: 980 }}><TableHead><TableRow><TableCell padding="checkbox" /><TableCell>用途</TableCell><TableCell>策略</TableCell><TableCell>解析端点</TableCell><TableCell>代理热配置</TableCell><TableCell>依赖状态</TableCell><TableCell>最近活动</TableCell></TableRow></TableHead><TableBody>{workspace.routes.map((route) => <TableRow key={route.routeId}><TableCell padding="checkbox"><Checkbox checked={selected.includes(route.routeType)} disabled={!route.enabled} onChange={(event) => toggle(route.routeType, event.target.checked)} /></TableCell><TableCell><Typography variant="body2" fontWeight={700}>{route.displayName}</Typography><Typography variant="caption" color="text.secondary">{route.routeType} · {route.expectedIpFamily}</Typography></TableCell><TableCell><Chip size="small" color={statusColor(route.status)} label={statusLabel(route.status)} /><Typography variant="caption" display="block">{route.requireProxy ? '强制代理，禁止直连' : route.allowDirect ? '允许直连' : '无直连回退'} · {proxySourceLabel(route.proxyCredentialSource)}</Typography></TableCell><TableCell>{route.resolvedEndpoint}{route.proxyCredentialFingerprint && <Typography variant="caption" color="text.secondary" display="block">指纹 {route.proxyCredentialFingerprint}</Typography>}</TableCell><TableCell sx={{ minWidth: 360 }}><Stack direction="row" spacing={1}><SecretTextField size="small" autoComplete="new-password" placeholder="http://proxy:port" value={proxyValues[route.routeType] || ''} onChange={(event) => setProxyValues((current) => ({ ...current, [route.routeType]: event.target.value }))} /><Button size="small" disabled={busy || !(proxyValues[route.routeType] || '').trim()} onClick={() => void putProxy(route)}>保存并测试</Button><Button size="small" color="error" disabled={busy || route.proxyCredentialSource !== 'DATABASE_OVERRIDE'} onClick={() => void clearProxy(route)}>清除</Button></Stack></TableCell><TableCell><Chip size="small" color={statusColor(route.latestDependencyStatus)} label={statusLabel(route.latestDependencyStatus)} />{route.latestError && <Typography variant="caption" color="error" display="block">{route.latestError}</Typography>}</TableCell><TableCell>{formatTime(route.latestActivityAt)}</TableCell></TableRow>)}</TableBody></Table></Paper>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} justifyContent="space-between" alignItems={{ sm: 'center' }} sx={{ mt: 1.5 }}><Typography variant="body2" color="text.secondary">诊断会在后台执行，结果会出现在“诊断历史”。</Typography><Button variant="contained" startIcon={<PlayArrowIcon />} disabled={busy || selected.length === 0} onClick={() => void start()}>运行所选诊断</Button></Stack>
    </Box>}
    {activeSection === 'history' && <BoxHistory diagnostics={diagnostics} />}
  </Stack>;
}

type NetworkSection = 'gateways' | 'routes' | 'history';
type Gateway = NetworkWorkspace['proxyGateways'][number];
type GatewayDraft = Pick<Gateway, 'engine' | 'preferredNames' | 'maximumNodes' | 'refreshSeconds' | 'allowInsecureTls' | 'enabled'>;

function GatewaySecretEditor({ gateway, secretName, label, values, busy, onChange, onSave, onClear }: {
  gateway: Gateway;
  secretName: 'SUBSCRIPTION_URL' | 'INLINE_NODES';
  label: string;
  values: Record<string, string>;
  busy: boolean;
  onChange: React.Dispatch<React.SetStateAction<Record<string, string>>>;
  onSave: (gateway: Gateway, secretName: 'SUBSCRIPTION_URL' | 'INLINE_NODES') => Promise<void>;
  onClear: (gateway: Gateway, secretName: 'SUBSCRIPTION_URL' | 'INLINE_NODES') => Promise<void>;
}) {
  const key = gatewaySecretKey(gateway.gatewayId, secretName);
  const source = secretName === 'SUBSCRIPTION_URL' ? gateway.subscriptionSource : gateway.inlineNodesSource;
  const fingerprint = secretName === 'SUBSCRIPTION_URL' ? gateway.subscriptionFingerprint : gateway.inlineNodesFingerprint;
  return <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} alignItems={{ md: 'center' }}>
    <SecretTextField fullWidth size="small" autoComplete="new-password" label={label} value={values[key] || ''} onChange={(event) => onChange((current) => ({ ...current, [key]: event.target.value }))} />
    <Chip size="small" label={runtimeSourceLabel(source)} />
    {fingerprint && <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>指纹 {fingerprint}</Typography>}
    <Button disabled={busy || !(values[key] || '').trim()} onClick={() => void onSave(gateway, secretName)}>保存并加载</Button>
    <Button color="error" disabled={busy || source !== 'DATABASE_OVERRIDE'} onClick={() => void onClear(gateway, secretName)}>清除</Button>
  </Stack>;
}

function toGatewayDraft(gateway: Gateway): GatewayDraft {
  return { engine: gateway.engine, preferredNames: gateway.preferredNames, maximumNodes: gateway.maximumNodes, refreshSeconds: gateway.refreshSeconds, allowInsecureTls: gateway.allowInsecureTls, enabled: gateway.enabled };
}

function gatewaySecretKey(gatewayId: string, secretName: string): string { return `${gatewayId}:${secretName}`; }

function withoutKey<T>(record: Record<string, T>, key: string): Record<string, T> {
  const next = { ...record };
  delete next[key];
  return next;
}

function gatewayRuntimeLabel(gateway: Gateway, runtime: ProxyGatewayRuntimeStatus | null | undefined, loaded: boolean): string {
  if (!gateway.enabled) return '已停用';
  if (!loaded) return '检测中';
  if (!runtime) return '状态不可达';
  if (!runtime.serviceReady) return '网关未就绪';
  return runtime.egressReady ? '出口可用' : '无可用出口';
}

function gatewayRuntimeColor(gateway: Gateway, runtime: ProxyGatewayRuntimeStatus | null | undefined, loaded: boolean): 'success' | 'warning' | 'error' | 'default' {
  if (!gateway.enabled || !loaded) return 'default';
  if (!runtime || !runtime.serviceReady) return 'error';
  return runtime.egressReady ? 'success' : 'warning';
}

function probeFailureLabel(code: string): string {
  return ({ HTTP_403: '上游风控 403', HTTP_429: '上游限流 429', TLS_ERROR: 'TLS 失败', CONNECTION_RESET: '连接被重置', CONNECTION_ERROR: '连接失败', TIMEOUT: '连接超时', BODY_MISMATCH: '响应校验失败' } as Record<string, string>)[code] || code;
}

function proxyRuntimeError(error: string): string {
  return ({
    'Proxy target validation found no healthy nodes': '目标探测未发现可用节点',
    'Proxy subscription contains no secure supported nodes': '订阅中没有安全且受支持的节点',
  } as Record<string, string>)[error] || error;
}

function runtimeSourceLabel(source: string): string { return ({ DATABASE_OVERRIDE: '后台热配置', ENVIRONMENT_FALLBACK: '启动备用配置', UNCONFIGURED: '未配置', NOT_SUPPORTED: '不支持' } as Record<string, string>)[source] || source; }

function proxyEngineLabel(engine: Gateway['engine']): string { return engine === 'XRAY' ? 'Xray' : 'sing-box'; }

function proxySourceLabel(source: NetworkWorkspace['routes'][number]['proxyCredentialSource']): string { return ({ DATABASE_OVERRIDE: '后台热配置', ENVIRONMENT_FALLBACK: '启动备用配置', UNCONFIGURED: '代理未配置' } as const)[source]; }

function gatewayRuntimeSummary(
  gateway: Gateway,
  runtime: ProxyGatewayRuntimeStatus | null | undefined,
  loaded: boolean,
): string {
  if (!gateway.enabled) return '已停用';
  if (!loaded || !runtime) return '运行态读取中';
  return `${runtime.healthyNodeCount}/${runtime.nodeCount} 节点可用`;
}

function NetworkMetric({ label, value, detail, tone }: {
  label: string;
  value: string;
  detail: string;
  tone: 'success' | 'warning' | 'info';
}) {
  return <Paper variant="outlined" sx={{ p: 1.5, borderLeft: '4px solid', borderLeftColor: `${tone}.main` }}><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="h3" sx={{ mt: .25 }}>{value}</Typography><Typography variant="body2" color="text.secondary">{detail}</Typography></Paper>;
}

function BoxHistory({ diagnostics }: { diagnostics: NetworkDiagnostic[] }) {
  return <><SectionTitle title="诊断历史" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>开始</TableCell><TableCell>路由</TableCell><TableCell>出口</TableCell><TableCell>HTTP</TableCell><TableCell>耗时</TableCell><TableCell>状态 / 错误</TableCell></TableRow></TableHead><TableBody>{diagnostics.map((item) => <TableRow key={item.diagnosticId}><TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(item.startedAt)}</TableCell><TableCell>{item.route}</TableCell><TableCell>{item.safeEndpoint}<br /><Typography variant="caption">{item.proxied ? '代理' : '直连'}</Typography></TableCell><TableCell>{item.httpStatus ?? '-'}</TableCell><TableCell>{item.latencyMilliseconds === null ? '-' : `${item.latencyMilliseconds} ms`}</TableCell><TableCell><Chip size="small" color={statusColor(item.status)} label={statusLabel(item.status)} />{item.errorMessage && <Typography variant="caption" color="error" display="block">{item.errorCode}: {item.errorMessage}</Typography>}</TableCell></TableRow>)}</TableBody></Table></Paper></>;
}
