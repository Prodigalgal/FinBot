import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Divider,
  FormControlLabel,
  Stack,
  Switch,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
  useMediaQuery,
} from '@mui/material';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline';
import RocketLaunchIcon from '@mui/icons-material/RocketLaunch';
import { useTheme } from '@mui/material/styles';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { api } from './api';
import type { SetupPayload } from './types';

export function SetupPanel({ onApplied }: { onApplied: () => Promise<void> }) {
  const theme = useTheme();
  const compact = useMediaQuery(theme.breakpoints.down('sm'));
  const [snapshot, setSnapshot] = useState<SetupPayload | null>(null);
  const [selectedProfileId, setSelectedProfileId] = useState('recommended');
  const [preserveExisting, setPreserveExisting] = useState(true);
  const [loading, setLoading] = useState(true);
  const [applying, setApplying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const payload = await api.setup();
      setSnapshot(payload);
      setSelectedProfileId((current) => (
        payload.profiles.some((profile) => profile.profile_id === current)
          ? current
          : payload.profiles.find((profile) => profile.recommended)?.profile_id || payload.profiles[0]?.profile_id || ''
      ));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load().catch(() => undefined);
  }, [load]);

  const selectedProfile = useMemo(
    () => snapshot?.profiles.find((profile) => profile.profile_id === selectedProfileId),
    [selectedProfileId, snapshot],
  );

  const applyProfile = async () => {
    if (!selectedProfileId) return;
    setApplying(true);
    setError(null);
    setMessage(null);
    try {
      const payload = await api.applySetupProfile({
        profile_id: selectedProfileId,
        preserve_existing: preserveExisting,
      });
      setSnapshot(payload);
      await onApplied();
      const appliedCount = payload.application?.applied_keys.length || 0;
      const preservedCount = payload.application?.preserved_keys.length || 0;
      setMessage(`已应用 ${appliedCount} 项默认值，保留 ${preservedCount} 项现有配置。`);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setApplying(false);
    }
  };

  if (loading && !snapshot) {
    return (
      <Card>
        <CardContent>
          <Stack direction="row" spacing={1.5} alignItems="center">
            <CircularProgress size={20} />
            <Typography variant="body2" color="text.secondary">正在检查启用状态</Typography>
          </Stack>
        </CardContent>
      </Card>
    );
  }

  return (
    <Stack spacing={2}>
      {message && <Alert severity="success">{message}</Alert>}
      {error && <Alert severity="error">{error}</Alert>}
      <Card>
        <CardContent>
          <Stack spacing={2}>
            <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={1.5}>
              <Stack direction="row" alignItems="center" spacing={1}>
                <RocketLaunchIcon color="primary" />
                <Typography variant="h3">快速启用</Typography>
              </Stack>
              <Chip
                size="small"
                color={snapshot?.readiness.status === 'ready' ? 'success' : snapshot?.readiness.status === 'blocked' ? 'error' : 'warning'}
                label={snapshot?.readiness.status === 'ready' ? '已就绪' : snapshot?.readiness.status === 'blocked' ? '有阻断项' : '需关注'}
              />
            </Stack>
            <ToggleButtonGroup
              exclusive
              fullWidth
              orientation={compact ? 'vertical' : 'horizontal'}
              value={selectedProfileId}
              onChange={(_, value: string | null) => value && setSelectedProfileId(value)}
              size="small"
            >
              {(snapshot?.profiles || []).map((profile) => (
                <ToggleButton key={profile.profile_id} value={profile.profile_id} sx={{ minHeight: 42, letterSpacing: 0 }}>
                  {profile.display_name}
                </ToggleButton>
              ))}
            </ToggleButtonGroup>
            {selectedProfile && (
              <Box>
                <Typography variant="body2" sx={{ fontWeight: 700 }}>{selectedProfile.description}</Typography>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                  {selectedProfile.highlights.join(' · ')}
                </Typography>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                  默认值 {selectedProfile.value_count} 项 · 待补齐 {selectedProfile.missing_value_count} 项 · 已配置 {selectedProfile.configured_value_count} 项
                </Typography>
              </Box>
            )}
            <Stack direction={{ xs: 'column', sm: 'row' }} alignItems={{ xs: 'stretch', sm: 'center' }} justifyContent="space-between" spacing={1.5}>
              <FormControlLabel
                control={<Switch checked={preserveExisting} onChange={(event) => setPreserveExisting(event.target.checked)} />}
                label="保留现有配置"
              />
              <Button variant="contained" startIcon={applying ? <CircularProgress size={16} color="inherit" /> : <RocketLaunchIcon />} disabled={applying || !selectedProfileId} onClick={applyProfile}>
                应用配置
              </Button>
            </Stack>
          </Stack>
        </CardContent>
      </Card>
      <Card>
        <CardContent>
          <Stack spacing={1.25}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={1}>
              <Typography variant="h3">启用就绪度</Typography>
              <Typography variant="caption" color="text.secondary">
                {snapshot?.readiness.passed_count || 0}/{snapshot?.readiness.check_count || 0}
              </Typography>
            </Stack>
            {(snapshot?.readiness.checks || []).map((check, index) => (
              <Box key={check.check_id}>
                {index > 0 && <Divider sx={{ mb: 1.25 }} />}
                <Stack direction="row" alignItems="flex-start" spacing={1.25}>
                  {check.status === 'passed'
                    ? <CheckCircleOutlineIcon color="success" fontSize="small" />
                    : <ErrorOutlineIcon color={check.required ? 'error' : 'warning'} fontSize="small" />}
                  <Box sx={{ minWidth: 0, flexGrow: 1 }}>
                    <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
                      <Typography variant="body2" sx={{ fontWeight: 700 }}>{check.label}</Typography>
                      {!check.required && <Typography variant="caption" color="text.secondary">可选</Typography>}
                    </Stack>
                    <Typography variant="caption" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>{check.detail}</Typography>
                  </Box>
                </Stack>
              </Box>
            ))}
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  );
}
