import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import RefreshIcon from '@mui/icons-material/Refresh';
import { Alert, Button, Checkbox, Chip, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useCallback, useEffect, useState } from 'react';

import { api } from './api';
import type { NetworkDiagnostic, NetworkWorkspace } from './types';
import { ErrorBlock, LoadingBlock, SectionTitle, formatTime, statusColor, statusLabel } from './ui';

export function NetworkPage() {
  const [workspace, setWorkspace] = useState<NetworkWorkspace | null>(null);
  const [diagnostics, setDiagnostics] = useState<NetworkDiagnostic[]>([]);
  const [selected, setSelected] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const refresh = useCallback(async () => {
    try {
      const [routes, history] = await Promise.all([api.network(), api.networkDiagnostics(100)]);
      setWorkspace(routes); setDiagnostics(history);
      setSelected((current) => current.length ? current : routes.routes.filter((route) => route.enabled).map((route) => route.routeType));
    } catch (cause) { setError(cause); }
  }, []);
  useEffect(() => { void refresh(); const timer = window.setInterval(() => void refresh(), 10000); return () => window.clearInterval(timer); }, [refresh]);
  const start = async () => {
    setBusy(true); setError(null); setMessage('');
    try { const runs = await api.startNetworkDiagnostics(selected); setMessage(`${runs.length} 条后台诊断已启动`); await refresh(); }
    catch (cause) { setError(cause); } finally { setBusy(false); }
  };
  const toggle = (route: string, checked: boolean) => setSelected((current) => checked ? [...new Set([...current, route])] : current.filter((value) => value !== route));
  if (!workspace) return <LoadingBlock label="正在读取网络路由" />;
  return <Stack spacing={3}>
    {error !== null && <ErrorBlock error={error} />}{message && <Alert severity="success">{message}</Alert>}
    <SectionTitle title="出站路由" action={<Button startIcon={<RefreshIcon />} onClick={() => void refresh()}>刷新</Button>} />
    <Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell padding="checkbox" /><TableCell>用途</TableCell><TableCell>策略</TableCell><TableCell>解析端点</TableCell><TableCell>依赖状态</TableCell><TableCell>最近活动</TableCell></TableRow></TableHead><TableBody>{workspace.routes.map((route) => <TableRow key={route.routeId}><TableCell padding="checkbox"><Checkbox checked={selected.includes(route.routeType)} disabled={!route.enabled} onChange={(event) => toggle(route.routeType, event.target.checked)} /></TableCell><TableCell><Typography variant="body2" fontWeight={700}>{route.displayName}</Typography><Typography variant="caption" color="text.secondary">{route.routeType} · {route.expectedIpFamily}</Typography></TableCell><TableCell><Chip size="small" color={statusColor(route.status)} label={statusLabel(route.status)} /><Typography variant="caption" display="block">{route.requireProxy ? '强制代理，禁止直连' : route.allowDirect ? '允许直连' : '无直连回退'} · {route.proxyConfigured ? '代理已注入' : '代理未注入'}</Typography></TableCell><TableCell>{route.resolvedEndpoint}</TableCell><TableCell><Chip size="small" color={statusColor(route.latestDependencyStatus)} label={statusLabel(route.latestDependencyStatus)} />{route.latestError && <Typography variant="caption" color="error" display="block">{route.latestError}</Typography>}</TableCell><TableCell>{formatTime(route.latestActivityAt)}</TableCell></TableRow>)}</TableBody></Table></Paper>
    <Button variant="contained" startIcon={<PlayArrowIcon />} disabled={busy || selected.length === 0} onClick={() => void start()} sx={{ alignSelf: 'flex-end' }}>运行所选诊断</Button>
    <BoxHistory diagnostics={diagnostics} />
  </Stack>;
}

function BoxHistory({ diagnostics }: { diagnostics: NetworkDiagnostic[] }) {
  return <><SectionTitle title="诊断历史" /><Paper variant="outlined" sx={{ overflow: 'auto' }}><Table size="small"><TableHead><TableRow><TableCell>开始</TableCell><TableCell>路由</TableCell><TableCell>出口</TableCell><TableCell>HTTP</TableCell><TableCell>耗时</TableCell><TableCell>状态 / 错误</TableCell></TableRow></TableHead><TableBody>{diagnostics.map((item) => <TableRow key={item.diagnosticId}><TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(item.startedAt)}</TableCell><TableCell>{item.route}</TableCell><TableCell>{item.safeEndpoint}<br /><Typography variant="caption">{item.proxied ? '代理' : '直连'}</Typography></TableCell><TableCell>{item.httpStatus ?? '-'}</TableCell><TableCell>{item.latencyMilliseconds === null ? '-' : `${item.latencyMilliseconds} ms`}</TableCell><TableCell><Chip size="small" color={statusColor(item.status)} label={statusLabel(item.status)} />{item.errorMessage && <Typography variant="caption" color="error" display="block">{item.errorCode}: {item.errorMessage}</Typography>}</TableCell></TableRow>)}</TableBody></Table></Paper></>;
}
