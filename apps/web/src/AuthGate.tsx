import LockOutlinedIcon from '@mui/icons-material/LockOutlined';
import RefreshIcon from '@mui/icons-material/Refresh';
import { Alert, Box, Button, CircularProgress, IconButton, Paper, Stack, TextField, Tooltip, Typography } from '@mui/material';
import type { FormEvent, ReactNode } from 'react';
import { useCallback, useEffect, useState } from 'react';

import { AUTH_REQUIRED_EVENT, ApiError, api } from './api';
import { solveProofOfWork } from './authPow';
import type { AuthChallenge } from './types';

export function AuthGate({ children }: { children: ReactNode }) {
  const [authenticated, setAuthenticated] = useState<boolean | null>(null);
  const [challenge, setChallenge] = useState<AuthChallenge | null>(null);
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [mathAnswer, setMathAnswer] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadChallenge = useCallback(async () => {
    setBusy(true);
    try {
      setChallenge(await api.authChallenge());
      setMathAnswer('');
    } catch (cause) {
      setError(readable(cause));
    } finally {
      setBusy(false);
    }
  }, []);

  const check = useCallback(async () => {
    try {
      const status = await api.authStatus();
      setAuthenticated(status.authenticated);
      if (!status.authenticated) await loadChallenge();
    } catch (cause) {
      setAuthenticated(false);
      setError(readable(cause));
      await loadChallenge();
    }
  }, [loadChallenge]);

  useEffect(() => {
    void check();
    const requireAuthentication = () => {
      setAuthenticated(false);
      void loadChallenge();
    };
    window.addEventListener(AUTH_REQUIRED_EVENT, requireAuthentication);
    return () => window.removeEventListener(AUTH_REQUIRED_EVENT, requireAuthentication);
  }, [check, loadChallenge]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!challenge || !Number.isInteger(Number(mathAnswer))) {
      setError('请输入有效的数学验证码');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const proofOfWorkSolution = await solveProofOfWork(challenge.nonce, challenge.proofOfWorkDifficulty);
      await api.login({
        username: username.trim(),
        password,
        challengeId: challenge.challengeId,
        proofOfWorkSolution,
        mathAnswer: Number(mathAnswer),
      });
      setPassword('');
      setAuthenticated(true);
    } catch (cause) {
      setError(readable(cause));
      await loadChallenge();
    } finally {
      setBusy(false);
    }
  };

  if (authenticated === null) {
    return <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center' }}><CircularProgress size={28} /></Box>;
  }
  if (authenticated) return children;

  return (
    <Box component="main" sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', px: 2, bgcolor: 'background.default' }}>
      <Paper component="form" onSubmit={submit} variant="outlined" sx={{ width: '100%', maxWidth: 390, p: 3 }}>
        <Stack spacing={2}>
          <Stack direction="row" spacing={1.25} alignItems="center">
            <Box sx={{ width: 38, height: 38, display: 'grid', placeItems: 'center', bgcolor: 'primary.main', color: 'white', borderRadius: 1 }}>
              <LockOutlinedIcon fontSize="small" />
            </Box>
            <Box>
              <Typography variant="h2">FinBot</Typography>
              <Typography variant="caption" color="text.secondary">管理员安全登录</Typography>
            </Box>
          </Stack>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField label="用户名" value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" required disabled={busy} />
          <TextField label="密码" type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" required disabled={busy} />
          <Stack direction="row" spacing={1} alignItems="stretch">
            <Box sx={{ flex: 1, border: '1px solid', borderColor: 'divider', borderRadius: 1, px: 1.5, py: 1 }}>
              <Typography variant="caption" color="text.secondary">数学验证码</Typography>
              <Typography variant="subtitle1" data-testid="auth-math-question">{challenge?.mathExpression || '正在获取'}</Typography>
            </Box>
            <Tooltip title="刷新验证码"><span><IconButton onClick={() => void loadChallenge()} disabled={busy}><RefreshIcon /></IconButton></span></Tooltip>
          </Stack>
          <TextField label="验证码答案" type="number" value={mathAnswer} onChange={(event) => setMathAnswer(event.target.value)} required disabled={busy || !challenge} />
          <Button type="submit" variant="contained" disabled={busy || !challenge || !username.trim() || !password || !mathAnswer}>
            {busy ? '正在完成安全校验' : '登录'}
          </Button>
        </Stack>
      </Paper>
    </Box>
  );
}

function readable(cause: unknown): string {
  if (cause instanceof ApiError && cause.status === 401) return '用户名、密码或安全校验不正确';
  return cause instanceof Error ? cause.message : String(cause);
}
