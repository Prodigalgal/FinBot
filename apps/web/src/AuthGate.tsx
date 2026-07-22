import LockOutlinedIcon from '@mui/icons-material/LockOutlined';
import RefreshIcon from '@mui/icons-material/Refresh';
import { Alert, Box, Button, CircularProgress, IconButton, Paper, Stack, TextField, Tooltip, Typography } from '@mui/material';
import type { FormEvent, ReactNode } from 'react';
import { useCallback, useEffect, useState } from 'react';

import { AUTH_REQUIRED_EVENT, ApiError, api } from './api';
import { solveProofOfWork } from './authPow';
import { SecretTextField } from './SecretTextField';
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
    return <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', bgcolor: 'background.default' }}><CircularProgress size={24} /></Box>;
  }
  if (authenticated) return children;

  return (
    <Box
      component="main"
      sx={{
        minHeight: '100vh',
        display: 'grid',
        placeItems: 'center',
        px: 2,
        py: 3,
        bgcolor: 'background.default',
      }}
    >
      <Paper
        component="form"
        onSubmit={submit}
        variant="outlined"
        sx={{
          width: '100%',
          maxWidth: 390,
          p: 0,
          overflow: 'hidden',
        }}
      >
        <Box
          sx={{
            px: 3,
            pt: 3,
            pb: 2,
            borderBottom: '1px solid',
            borderColor: 'divider',
          }}
        >
          <Stack direction="row" spacing={1.5} alignItems="center">
            <Box
              sx={{
                width: 40,
                height: 40,
                display: 'grid',
                placeItems: 'center',
                bgcolor: 'primary.main',
                color: 'primary.contrastText',
                borderRadius: 1,
                flexShrink: 0,
              }}
            >
              <LockOutlinedIcon sx={{ fontSize: 20 }} />
            </Box>
            <Box>
              <Typography variant="h2" sx={{ fontSize: 18, letterSpacing: 0, mb: 0.25 }}>FinBot</Typography>
              <Typography variant="caption" color="text.secondary">管理员安全登录</Typography>
            </Box>
          </Stack>
        </Box>

        <Stack spacing={2.5} sx={{ px: 3, pt: 2.5, pb: 3 }}>
          {error && <Alert severity="error">{error}</Alert>}

          <Stack spacing={2}>
            <TextField
              label="用户名"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              required
              disabled={busy}
              fullWidth
              size="small"
              sx={{ '& .MuiOutlinedInput-root': { minHeight: 44 } }}
            />

            <SecretTextField
              label="密码"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              required
              disabled={busy}
              fullWidth
              size="small"
              sx={{ '& .MuiOutlinedInput-root': { minHeight: 44 } }}
            />
          </Stack>

          <Box>
            <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
              <Typography variant="caption" color="text.secondary">数学验证码</Typography>
              <Box sx={{ flex: 1 }} />
              <Tooltip title="刷新验证码">
                <span>
                  <IconButton
                    aria-label="刷新验证码"
                    onClick={() => void loadChallenge()}
                    disabled={busy}
                    size="small"
                    sx={{ width: 44, height: 44 }}
                  >
                    <RefreshIcon fontSize="small" />
                  </IconButton>
                </span>
              </Tooltip>
            </Stack>

            <Box
              sx={{
                border: '1px solid',
                borderColor: 'divider',
                borderRadius: 1,
                px: 2,
                py: 1.5,
                bgcolor: 'background.default',
                minHeight: 44,
                display: 'flex',
                alignItems: 'center',
              }}
            >
              <Typography variant="subtitle1" data-testid="auth-math-question" sx={{ fontSize: 16 }}>
                {challenge?.mathExpression || '正在获取'}
              </Typography>
            </Box>
          </Box>

          <TextField
            label="验证码答案"
            type="number"
            value={mathAnswer}
            onChange={(event) => setMathAnswer(event.target.value)}
            required
            disabled={busy || !challenge}
            fullWidth
            size="small"
            sx={{ '& .MuiOutlinedInput-root': { minHeight: 44 } }}
          />

          <Button
            type="submit"
            variant="contained"
            disabled={busy || !challenge || !username.trim() || !password || !mathAnswer}
            fullWidth
            sx={{ minHeight: 44 }}
          >
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
