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
        py: 4,
        bgcolor: 'background.default',
        backgroundImage: 'radial-gradient(at 50% 20%, rgba(29, 78, 216, 0.04) 0px, transparent 60%)',
      }}
    >
      <Paper
        component="form"
        onSubmit={submit}
        variant="outlined"
        sx={{
          width: '100%',
          maxWidth: 410,
          p: 0,
          overflow: 'hidden',
          borderRadius: 2,
          boxShadow: '0 20px 48px -12px rgba(15, 23, 42, 0.1), 0 1px 3px 0 rgba(15, 23, 42, 0.04)',
          borderColor: 'divider',
        }}
      >
        <Box
          sx={{
            px: 3,
            pt: 3,
            pb: 2.25,
            borderBottom: '1px solid',
            borderColor: 'divider',
            bgcolor: 'rgba(248, 250, 252, 0.65)',
          }}
        >
          <Stack direction="row" spacing={1.75} alignItems="center">
            <Box
              sx={{
                width: 42,
                height: 42,
                display: 'grid',
                placeItems: 'center',
                bgcolor: 'primary.main',
                color: 'primary.contrastText',
                borderRadius: '8px',
                flexShrink: 0,
                boxShadow: '0 2px 8px rgba(29, 78, 216, 0.28)',
              }}
            >
              <LockOutlinedIcon sx={{ fontSize: 22 }} />
            </Box>
            <Box sx={{ minWidth: 0, flex: 1 }}>
              <Stack direction="row" spacing={1} alignItems="center">
                <Typography variant="h2" sx={{ fontSize: 18, fontWeight: 750, letterSpacing: '-0.01em' }}>
                  FinBot
                </Typography>
                <Box
                  component="span"
                  sx={{
                    fontSize: 10,
                    fontWeight: 700,
                    px: 0.6,
                    py: 0.1,
                    borderRadius: '3px',
                    bgcolor: 'primary.light',
                    color: 'primary.dark',
                    letterSpacing: '0.04em',
                  }}
                >
                  SECURE VAULT
                </Box>
              </Stack>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.25 }}>
                管理员单点登录 · SHA-256 PoW 防护
              </Typography>
            </Box>
          </Stack>
        </Box>

        <Stack spacing={2.25} sx={{ px: 3, pt: 2.5, pb: 3.25 }}>
          {error && <Alert severity="error">{error}</Alert>}

          <Stack spacing={1.75}>
            <TextField
              label="用户名"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              required
              disabled={busy}
              fullWidth
              size="small"
              sx={{ '& .MuiOutlinedInput-root': { minHeight: 42 } }}
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
              sx={{ '& .MuiOutlinedInput-root': { minHeight: 42 } }}
            />
          </Stack>

          <Box
            sx={{
              p: 2,
              borderRadius: '6px',
              bgcolor: 'action.hover',
              border: '1px solid',
              borderColor: 'divider',
            }}
          >
            <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
              <Typography variant="caption" sx={{ fontWeight: 700, color: 'text.secondary', textTransform: 'uppercase', letterSpacing: '0.03em', fontSize: 11 }}>
                数学验证码
              </Typography>
              <Tooltip title="刷新验证码" arrow>
                <span>
                  <IconButton
                    aria-label="刷新验证码"
                    onClick={() => void loadChallenge()}
                    disabled={busy}
                    size="small"
                    sx={{ width: 28, height: 28 }}
                  >
                    <RefreshIcon sx={{ fontSize: 16 }} />
                  </IconButton>
                </span>
              </Tooltip>
            </Stack>

            <Box
              sx={{
                border: '1px solid',
                borderColor: 'divider',
                borderRadius: '4px',
                px: 2,
                py: 1,
                bgcolor: 'background.paper',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                minHeight: 40,
              }}
            >
              <Typography
                data-testid="auth-math-question"
                sx={{
                  fontSize: 18,
                  fontWeight: 750,
                  fontFamily: 'monospace',
                  letterSpacing: '0.05em',
                  color: 'primary.dark',
                }}
              >
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
            sx={{ '& .MuiOutlinedInput-root': { minHeight: 42 } }}
          />

          <Button
            type="submit"
            variant="contained"
            disabled={busy || !challenge || !username.trim() || !password || !mathAnswer}
            fullWidth
            sx={{
              minHeight: 42,
              fontWeight: 700,
              fontSize: 14,
              boxShadow: '0 2px 6px rgba(29, 78, 216, 0.25)',
            }}
          >
            {busy ? (
              <Stack direction="row" spacing={1} alignItems="center">
                <CircularProgress size={16} color="inherit" thickness={4.5} />
                <span>正在完成安全校验...</span>
              </Stack>
            ) : (
              '登录'
            )}
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
