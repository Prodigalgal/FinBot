import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import RefreshIcon from '@mui/icons-material/Refresh';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import { Alert, Button, Checkbox, Chip, FormControlLabel, Paper, Stack, Switch, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useCallback, useEffect, useState } from 'react';

import { api } from './api';
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
  return <Stack spacing={3}>
    {error !== null && <ErrorBlock error={error} />}{message && <Alert severity="success">{message}</Alert>}
    <SectionTitle title="代理池热配置" />
    <Stack spacing={2}>{workspace.proxyGateways.map((gateway) => {
      const draft = gatewayDrafts[gateway.gatewayId] || toGatewayDraft(gateway);
      const runtimeLoaded = Object.prototype.hasOwnProperty.call(gatewayStatuses, gateway.gatewayId);
      const runtime = gatewayStatuses[gateway.gatewayId];
      return <Paper key={gateway.gatewayId} variant="outlined" sx={{ p: 2 }}><Stack spacing={2}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'center' }} justifyContent="space-between">
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap"><Typography fontWeight={700}>{gateway.displayName}</Typography><Chip size="small" variant="outlined" color={statusColor(gateway.status)} label={`配置 ${statusLabel(gateway.status)}`} /><Chip data-testid={`proxy-runtime-${gateway.gatewayId}`} size="small" color={gatewayRuntimeColor(gateway, runtime, runtimeLoaded)} label={gatewayRuntimeLabel(gateway, runtime, runtimeLoaded)} /></Stack>
          <Typography variant="caption" color="text.secondary">更新于 {formatTime(gateway.updatedAt)}</Typography>
        </Stack>
        {gateway.enabled && runtime && <Stack direction={{ xs: 'column', md: 'row' }} spacing={{ xs: 0.5, md: 2 }}>
          <Typography variant="body2">健康节点 {runtime.healthyNodeCount}/{runtime.nodeCount}</Typography>
          <Typography variant="body2">探测目标 {runtime.validationTarget || '未配置'}</Typography>
          <Typography variant="body2">最近探测 {formatTime(runtime.lastRefreshAt)}</Typography>
          {runtime.healthyNodeIndices.length > 0 && <Typography variant="body2">节点索引 {runtime.healthyNodeIndices.join(', ')}</Typography>}
        </Stack>}
        {gateway.enabled && runtime && Object.keys(runtime.probeFailureCounts).length > 0 && <Typography variant="caption" color={runtime.egressReady ? 'text.secondary' : 'error'}>{Object.entries(runtime.probeFailureCounts).map(([code, count]) => `${probeFailureLabel(code)} × ${count}`).join('；')}</Typography>}
        {gateway.enabled && runtime?.error && <Typography variant="caption" color="error">{proxyRuntimeError(runtime.error)}</Typography>}
        <Stack direction={{ xs: 'column', lg: 'row' }} spacing={1} alignItems={{ lg: 'center' }}>
          <TextField size="small" label="优选标签" value={draft.preferredNames} onChange={(event) => updateGatewayDraft(gateway, { preferredNames: event.target.value })} sx={{ minWidth: 240 }} />
          <TextField size="small" label="最大节点数" type="number" value={draft.maximumNodes} inputProps={{ min: 1, max: 128 }} onChange={(event) => updateGatewayDraft(gateway, { maximumNodes: Number(event.target.value) })} sx={{ width: 140 }} />
          <TextField size="small" label="刷新周期（秒）" type="number" value={draft.refreshSeconds} inputProps={{ min: 60, max: 86400 }} onChange={(event) => updateGatewayDraft(gateway, { refreshSeconds: Number(event.target.value) })} sx={{ width: 170 }} />
          <FormControlLabel control={<Switch checked={draft.enabled} onChange={(event) => updateGatewayDraft(gateway, { enabled: event.target.checked })} />} label="启用" />
          <FormControlLabel control={<Switch color="warning" checked={draft.allowInsecureTls} onChange={(event) => updateGatewayDraft(gateway, { allowInsecureTls: event.target.checked })} />} label="允许不安全 TLS" />
          <Button variant="contained" disabled={busy || draft.maximumNodes < 1 || draft.refreshSeconds < 60} onClick={() => void saveGateway(gateway)}>保存</Button>
          <Button startIcon={<RestartAltIcon />} disabled={busy || !gateway.enabled} onClick={() => void reloadGateway(gateway)}>重新探测</Button>
        </Stack>
        {gateway.subscriptionSupported && <GatewaySecretEditor gateway={gateway} secretName="SUBSCRIPTION_URL" label="订阅地址" values={gatewaySecrets} busy={busy} onChange={setGatewaySecrets} onSave={putGatewaySecret} onClear={clearGatewaySecret} />}
        {gateway.inlineNodesSupported && <GatewaySecretEditor gateway={gateway} secretName="INLINE_NODES" label="内联节点" values={gatewaySecrets} busy={busy} onChange={setGatewaySecrets} onSave={putGatewaySecret} onClear={clearGatewaySecret} />}
      </Stack></Paper>;
    })}</Stack>
    <SectionTitle title="出站路由" action={<Button startIcon={<RefreshIcon />} onClick={() => void refresh()}>刷新</Button>} />
    <Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell padding="checkbox" /><TableCell>用途</TableCell><TableCell>策略</TableCell><TableCell>解析端点</TableCell><TableCell>代理热配置</TableCell><TableCell>依赖状态</TableCell><TableCell>最近活动</TableCell></TableRow></TableHead><TableBody>{workspace.routes.map((route) => <TableRow key={route.routeId}><TableCell padding="checkbox"><Checkbox checked={selected.includes(route.routeType)} disabled={!route.enabled} onChange={(event) => toggle(route.routeType, event.target.checked)} /></TableCell><TableCell><Typography variant="body2" fontWeight={700}>{route.displayName}</Typography><Typography variant="caption" color="text.secondary">{route.routeType} · {route.expectedIpFamily}</Typography></TableCell><TableCell><Chip size="small" color={statusColor(route.status)} label={statusLabel(route.status)} /><Typography variant="caption" display="block">{route.requireProxy ? '强制代理，禁止直连' : route.allowDirect ? '允许直连' : '无直连回退'} · {proxySourceLabel(route.proxyCredentialSource)}</Typography></TableCell><TableCell>{route.resolvedEndpoint}{route.proxyCredentialFingerprint && <Typography variant="caption" color="text.secondary" display="block">指纹 {route.proxyCredentialFingerprint}</Typography>}</TableCell><TableCell sx={{ minWidth: 360 }}><Stack direction="row" spacing={1}><TextField size="small" type="password" autoComplete="new-password" placeholder="http://proxy:port" value={proxyValues[route.routeType] || ''} onChange={(event) => setProxyValues((current) => ({ ...current, [route.routeType]: event.target.value }))} /><Button size="small" disabled={busy || !(proxyValues[route.routeType] || '').trim()} onClick={() => void putProxy(route)}>保存并测试</Button><Button size="small" color="error" disabled={busy || route.proxyCredentialSource !== 'DATABASE_OVERRIDE'} onClick={() => void clearProxy(route)}>清除</Button></Stack></TableCell><TableCell><Chip size="small" color={statusColor(route.latestDependencyStatus)} label={statusLabel(route.latestDependencyStatus)} />{route.latestError && <Typography variant="caption" color="error" display="block">{route.latestError}</Typography>}</TableCell><TableCell>{formatTime(route.latestActivityAt)}</TableCell></TableRow>)}</TableBody></Table></Paper>
    <Button variant="contained" startIcon={<PlayArrowIcon />} disabled={busy || selected.length === 0} onClick={() => void start()} sx={{ alignSelf: 'flex-end' }}>运行所选诊断</Button>
    <BoxHistory diagnostics={diagnostics} />
  </Stack>;
}

type Gateway = NetworkWorkspace['proxyGateways'][number];
type GatewayDraft = Pick<Gateway, 'preferredNames' | 'maximumNodes' | 'refreshSeconds' | 'allowInsecureTls' | 'enabled'>;

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
    <TextField fullWidth size="small" type="password" autoComplete="new-password" label={label} value={values[key] || ''} onChange={(event) => onChange((current) => ({ ...current, [key]: event.target.value }))} />
    <Chip size="small" label={runtimeSourceLabel(source)} />
    {fingerprint && <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>指纹 {fingerprint}</Typography>}
    <Button disabled={busy || !(values[key] || '').trim()} onClick={() => void onSave(gateway, secretName)}>保存并加载</Button>
    <Button color="error" disabled={busy || source !== 'DATABASE_OVERRIDE'} onClick={() => void onClear(gateway, secretName)}>清除</Button>
  </Stack>;
}

function toGatewayDraft(gateway: Gateway): GatewayDraft {
  return { preferredNames: gateway.preferredNames, maximumNodes: gateway.maximumNodes, refreshSeconds: gateway.refreshSeconds, allowInsecureTls: gateway.allowInsecureTls, enabled: gateway.enabled };
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
  return ({ HTTP_403: '上游风控 403', HTTP_429: '上游限流 429', TLS_ERROR: 'TLS 失败', CONNECTION_ERROR: '连接失败', TIMEOUT: '连接超时', BODY_MISMATCH: '响应校验失败' } as Record<string, string>)[code] || code;
}

function proxyRuntimeError(error: string): string {
  return ({
    'Proxy target validation found no healthy nodes': '目标探测未发现可用节点',
    'Proxy subscription contains no secure supported nodes': '订阅中没有安全且受支持的节点',
  } as Record<string, string>)[error] || error;
}

function runtimeSourceLabel(source: string): string { return ({ DATABASE_OVERRIDE: '后台热配置', ENVIRONMENT_FALLBACK: '启动备用配置', UNCONFIGURED: '未配置', NOT_SUPPORTED: '不支持' } as Record<string, string>)[source] || source; }

function proxySourceLabel(source: NetworkWorkspace['routes'][number]['proxyCredentialSource']): string { return ({ DATABASE_OVERRIDE: '后台热配置', ENVIRONMENT_FALLBACK: '启动备用配置', UNCONFIGURED: '代理未配置' } as const)[source]; }

function BoxHistory({ diagnostics }: { diagnostics: NetworkDiagnostic[] }) {
  return <><SectionTitle title="诊断历史" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>开始</TableCell><TableCell>路由</TableCell><TableCell>出口</TableCell><TableCell>HTTP</TableCell><TableCell>耗时</TableCell><TableCell>状态 / 错误</TableCell></TableRow></TableHead><TableBody>{diagnostics.map((item) => <TableRow key={item.diagnosticId}><TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(item.startedAt)}</TableCell><TableCell>{item.route}</TableCell><TableCell>{item.safeEndpoint}<br /><Typography variant="caption">{item.proxied ? '代理' : '直连'}</Typography></TableCell><TableCell>{item.httpStatus ?? '-'}</TableCell><TableCell>{item.latencyMilliseconds === null ? '-' : `${item.latencyMilliseconds} ms`}</TableCell><TableCell><Chip size="small" color={statusColor(item.status)} label={statusLabel(item.status)} />{item.errorMessage && <Typography variant="caption" color="error" display="block">{item.errorCode}: {item.errorMessage}</Typography>}</TableCell></TableRow>)}</TableBody></Table></Paper></>;
}
