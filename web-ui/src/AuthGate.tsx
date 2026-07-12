import LockOutlinedIcon from '@mui/icons-material/LockOutlined';
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import type { FormEvent, ReactNode } from 'react';
import { useCallback, useEffect, useState } from 'react';

import { api, ApiError, AUTH_REQUIRED_EVENT } from './api';

type AuthState = 'loading' | 'authenticated' | 'login';

export function AuthGate({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>('loading');
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const status = await api.authStatus();
      setState(!status.enabled || status.authenticated ? 'authenticated' : 'login');
    } catch (requestError) {
      setError(readableError(requestError));
      setState('login');
    }
  }, []);

  useEffect(() => {
    refresh();
    const requireLogin = () => setState('login');
    window.addEventListener(AUTH_REQUIRED_EVENT, requireLogin);
    return () => window.removeEventListener(AUTH_REQUIRED_EVENT, requireLogin);
  }, [refresh]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await api.login(username.trim(), password);
      setPassword('');
      setState('authenticated');
    } catch (requestError) {
      setError(readableError(requestError));
    } finally {
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
            autoFocus
          />
          <Button type="submit" variant="contained" disabled={submitting || !username.trim() || !password}>
            {submitting ? '正在登录' : '登录'}
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

