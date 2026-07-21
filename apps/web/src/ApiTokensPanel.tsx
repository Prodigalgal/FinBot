import AddIcon from '@mui/icons-material/Add';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import DeleteIcon from '@mui/icons-material/Delete';
import { Alert, Box, Button, Chip, IconButton, MenuItem, Paper, Stack, TextField, Tooltip, Typography } from '@mui/material';
import { useEffect, useState } from 'react';

import { api } from './api';
import { SecretTextField } from './SecretTextField';
import type { AdminApiToken, CreatedAdminApiToken } from './types';
import { EmptyBlock, ErrorBlock, LoadingBlock, SectionTitle, formatTime, statusColor, statusLabel } from './ui';

const expiryOptions = [
  { value: '30', label: '30 天' },
  { value: '90', label: '90 天' },
  { value: '365', label: '365 天' },
  { value: 'never', label: '永不过期' },
] as const;

export function ApiTokensPanel() {
  const [tokens, setTokens] = useState<AdminApiToken[] | null>(null);
  const [displayName, setDisplayName] = useState('');
  const [expiry, setExpiry] = useState('90');
  const [created, setCreated] = useState<CreatedAdminApiToken | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);

  const load = async () => {
    try {
      setTokens(await api.apiTokens());
    } catch (cause) {
      setError(cause);
    }
  };
  useEffect(() => { void load(); }, []);

  const create = async () => {
    setBusy(true); setError(null); setMessage(''); setCreated(null);
    try {
      const result = await api.createApiToken({
        displayName: displayName.trim(),
        expiresInDays: expiry === 'never' ? null : Number(expiry),
      });
      setCreated(result);
      setDisplayName('');
      setMessage('Token 已创建');
      await load();
    } catch (cause) {
      setError(cause);
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (token: AdminApiToken) => {
    if (!window.confirm(`确认吊销“${token.displayName}”？`)) return;
    setBusy(true); setError(null); setMessage('');
    try {
      await api.revokeApiToken(token.tokenId, token.version);
      setMessage('Token 已吊销');
      await load();
    } catch (cause) {
      setError(cause);
    } finally {
      setBusy(false);
    }
  };

  const copyToken = async () => {
    if (!created) return;
    try {
      if (!navigator.clipboard) throw new Error('当前浏览器不支持剪贴板');
      await navigator.clipboard.writeText(created.rawToken);
      setMessage('Token 已复制');
    } catch (cause) {
      setError(cause);
    }
  };

  return <Box>
    <SectionTitle title="API Token" />
    <Stack spacing={1.5}>
      {error !== null && <ErrorBlock error={error} />}
      {message && <Alert severity="success">{message}</Alert>}
      {created && <Alert severity="warning"><Stack spacing={1}><Typography fontWeight={700}>新 Token 仅显示一次</Typography><Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'center' }}><SecretTextField fullWidth label="新 API Token" value={created.rawToken} InputProps={{ readOnly: true }} /><Tooltip title="复制 Token"><IconButton aria-label="复制 Token" onClick={() => void copyToken()}><ContentCopyIcon /></IconButton></Tooltip></Stack></Stack></Alert>}
      <Paper variant="outlined" sx={{ p: 2 }}><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }}><TextField fullWidth label="Token 名称" value={displayName} onChange={(event) => setDisplayName(event.target.value)} inputProps={{ maxLength: 120 }} /><TextField select label="有效期" value={expiry} onChange={(event) => setExpiry(event.target.value)} sx={{ minWidth: 150 }}>{expiryOptions.map((option) => <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>)}</TextField><Button variant="contained" startIcon={<AddIcon />} disabled={busy || !displayName.trim()} onClick={() => void create()} sx={{ flexShrink: 0 }}>{busy ? '正在处理' : '申请 Token'}</Button></Stack></Paper>
      {tokens === null ? <LoadingBlock label="正在读取 API Token" /> : tokens.length === 0 ? <EmptyBlock>暂无 API Token</EmptyBlock> : <Stack spacing={1}>{tokens.map((token) => <TokenRow key={token.tokenId} token={token} busy={busy} revoke={revoke} />)}</Stack>}
    </Stack>
  </Box>;
}

function TokenRow({ token, busy, revoke }: { token: AdminApiToken; busy: boolean; revoke: (token: AdminApiToken) => Promise<void> }) {
  return <Paper variant="outlined" sx={{ p: 1.5 }}><Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }}><Box sx={{ flex: 1, minWidth: 0 }}><Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap"><Typography fontWeight={700}>{token.displayName}</Typography><Chip size="small" color={statusColor(token.status)} label={statusLabel(token.status)} /></Stack><Typography variant="caption" color="text.secondary">指纹 {token.fingerprint} · 创建 {formatTime(token.createdAt)} · 到期 {formatTime(token.expiresAt)} · 最近使用 {formatTime(token.lastUsedAt)}</Typography></Box><Tooltip title="吊销 Token"><span><IconButton color="error" aria-label={`吊销 ${token.displayName}`} disabled={busy || token.status === 'REVOKED'} onClick={() => void revoke(token)}><DeleteIcon /></IconButton></span></Tooltip></Stack></Paper>;
}
