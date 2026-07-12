import LockOutlinedIcon from '@mui/icons-material/LockOutlined';
import RefreshIcon from '@mui/icons-material/Refresh';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  IconButton,
  Paper,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import type { FormEvent, ReactNode } from 'react';
import { useCallback, useEffect, useState } from 'react';

import { api, ApiError, AUTH_REQUIRED_EVENT } from './api';
import type { AuthChallenge } from './api';
import { solveProofOfWork } from './authPow';

type AuthState = 'loading' | 'authenticated' | 'login';

export function AuthGate({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>('loading');
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [challenge, setChallenge] = useState<AuthChallenge | null>(null);
  const [mathAnswer, setMathAnswer] = useState('');
  const [challengeLoading, setChallengeLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [powCalculating, setPowCalculating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadChallenge = useCallback(async (clearError = true) => {
    setChallengeLoading(true);
    setChallenge(null);
    setMathAnswer('');
    if (clearError) setError(null);
    try {
      const payload = await api.authChallenge();
      if (!payload.enabled || !payload.challenge) {
        throw new Error('认证 Challenge 未启用');
      }
      setChallenge(payload.challenge);
    } catch (requestError) {
      setError(readableError(requestError));
    } finally {
      setChallengeLoading(false);
    }
  }, []);

  const refresh = useCallback(async () => {
    try {
      const status = await api.authStatus();
      if (!status.enabled || status.authenticated) {
        setState('authenticated');
        return;
      }
      setState('login');
      await loadChallenge();
    } catch (requestError) {
      setError(readableError(requestError));
      setState('login');
    }
  }, [loadChallenge]);

  useEffect(() => {
    refresh();
    const requireLogin = () => {
      setState('login');
      void loadChallenge();
    };
    window.addEventListener(AUTH_REQUIRED_EVENT, requireLogin);
    return () => window.removeEventListener(AUTH_REQUIRED_EVENT, requireLogin);
  }, [loadChallenge, refresh]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!challenge || !mathAnswer.trim() || !Number.isInteger(Number(mathAnswer))) {
      setError('请输入有效的数学验证码');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      setPowCalculating(true);
      const powNonce = await solveProofOfWork(challenge.pow_prefix, challenge.difficulty_bits);
      setPowCalculating(false);
      await api.login({
        username: username.trim(),
        password,
        challenge_id: challenge.challenge_id,
        math_answer: Number(mathAnswer),
        pow_nonce: powNonce,
      });
      setPassword('');
      setMathAnswer('');
      setChallenge(null);
      setState('authenticated');
    } catch (requestError) {
      setError(readableError(requestError));
      await loadChallenge(false);
    } finally {
      setPowCalculating(false);
      setSubmitting(false);
    }
  };

  if (state === 'loading') {
    return (
      <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', bgcolor: 'background.default' }}>
        <CircularProgress size={28} />
      </Box>
    );
  }

  if (state === 'authenticated') {
    return children;
  }

  return (
    <Box
      component="main"
      sx={{
        minHeight: '100vh',
        display: 'grid',
        placeItems: 'center',
        bgcolor: 'background.default',
        px: 2,
      }}
    >
      <Paper component="form" onSubmit={submit} variant="outlined" sx={{ width: '100%', maxWidth: 380, p: 3 }}>
        <Stack spacing={2.25}>
          <Stack direction="row" spacing={1.25} alignItems="center">
            <Box sx={{ width: 36, height: 36, display: 'grid', placeItems: 'center', bgcolor: 'primary.main', color: 'primary.contrastText' }}>
              <LockOutlinedIcon fontSize="small" />
            </Box>
            <Box>
              <Typography variant="h2">FinBot</Typography>
              <Typography variant="caption" color="text.secondary">管理员登录</Typography>
            </Box>
          </Stack>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField
            label="用户名"
            autoComplete="username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            disabled={submitting}
            required
            fullWidth
          />
          <TextField
            label="密码"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            disabled={submitting}
            required
            fullWidth
          />
          <Stack direction="row" spacing={1} alignItems="center">
            <Box sx={{ flex: 1, minWidth: 0, border: '1px solid', borderColor: 'divider', px: 1.5, py: 1.25 }}>
              <Typography variant="caption" color="text.secondary">数学验证码</Typography>
              <Typography data-testid="auth-math-question" variant="subtitle1">
                {challengeLoading ? '正在生成' : challenge?.math_question || '暂不可用'}
              </Typography>
            </Box>
            <Tooltip title="刷新数学验证码">
              <span>
                <IconButton
                  aria-label="刷新数学验证码"
                  onClick={() => void loadChallenge()}
                  disabled={submitting || challengeLoading}
                >
                  {challengeLoading ? <CircularProgress size={20} /> : <RefreshIcon />}
                </IconButton>
              </span>
            </Tooltip>
          </Stack>
          <TextField
            label="验证码答案"
            type="number"
            value={mathAnswer}
            onChange={(event) => setMathAnswer(event.target.value)}
            disabled={submitting || challengeLoading || !challenge}
            slotProps={{ htmlInput: { inputMode: 'numeric', step: 1 } }}
            required
            fullWidth
          />
          <Button
            type="submit"
            variant="contained"
            disabled={submitting || challengeLoading || !challenge || !username.trim() || !password || !mathAnswer.trim()}
          >
            {powCalculating ? '正在计算 PoW' : submitting ? '正在登录' : '登录'}
          </Button>
        </Stack>
      </Paper>
    </Box>
  );
}

function readableError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return '用户名或密码错误';
    if (error.status === 429) return '登录尝试过于频繁，请稍后重试';
  }
  return error instanceof Error ? error.message : String(error);
}
