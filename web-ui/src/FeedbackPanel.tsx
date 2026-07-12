import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  IconButton,
  LinearProgress,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
import CheckIcon from '@mui/icons-material/Check';
import CloseIcon from '@mui/icons-material/Close';
import InsightsIcon from '@mui/icons-material/Insights';
import NotificationsNoneIcon from '@mui/icons-material/NotificationsNone';
import RefreshIcon from '@mui/icons-material/Refresh';
import type { ReactNode } from 'react';
import { useCallback, useEffect, useState } from 'react';

import { api } from './api';
import { numberText, percentText, recommendationActionText } from './humanReadable';
import type { ResearchFeedbackPayload, ResearchNotification, ShadowPosition } from './types';
import { formatTime, statusColor, statusText } from './utils';

export function FeedbackPanel() {
  const [feedback, setFeedback] = useState<ResearchFeedbackPayload | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [updatingNotificationId, setUpdatingNotificationId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const loadFeedback = useCallback(async () => {
    setLoading(true);
    try {
      setFeedback(await api.researchFeedback());
      setError(null);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadFeedback();
  }, [loadFeedback]);

  const refresh = async () => {
    setRefreshing(true);
    try {
      setFeedback(await api.refreshResearchFeedback());
      setMessage('效果评估、虚拟组合和通知已刷新。');
      setError(null);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setRefreshing(false);
    }
  };

  const updateNotification = async (notification: ResearchNotification, status: 'read' | 'dismissed') => {
    setUpdatingNotificationId(notification.notification_id);
    try {
      await api.updateNotification(notification.notification_id, status);
      setFeedback((current) => current ? {
        ...current,
        notifications: {
          ...current.notifications,
          items: current.notifications.items.map((item) => item.notification_id === notification.notification_id ? { ...item, status } : item),
          counts: notificationCounts(current.notifications.items, notification.notification_id, status),
        },
      } : current);
      setError(null);
    } catch (err) {
      setError(errorText(err));
    } finally {
      setUpdatingNotificationId(null);
    }
  };

  const shadow = feedback?.shadow_portfolio;
  const backtest = feedback?.backtest;
  const calibration = feedback?.calibration;

  return (
    <Stack spacing={2}>
      <Box sx={{ border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper', px: { xs: 1.5, md: 2 }, py: 1.25 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25} justifyContent="space-between" alignItems={{ xs: 'stretch', sm: 'center' }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <InsightsIcon color="primary" />
            <Box>
              <Typography variant="h3">建议效果与虚拟组合</Typography>
              <Typography variant="caption" color="text.secondary">仅使用公开行情与已批准建议，不读取真实账户、不产生真实订单</Typography>
            </Box>
          </Stack>
          <Stack direction="row" spacing={1} alignItems="center">
            {shadow && <Chip size="small" variant="outlined" color={statusColor(shadow.status)} label={`组合${statusText(shadow.status)}`} />}
            <Button variant="outlined" startIcon={refreshing ? <CircularProgress size={16} /> : <RefreshIcon />} disabled={refreshing} onClick={() => void refresh()}>
              刷新评估
            </Button>
          </Stack>
        </Stack>
      </Box>

      {error && <Alert severity="error">{error}</Alert>}
      {message && <Alert severity="success" onClose={() => setMessage(null)}>{message}</Alert>}
      {loading && !feedback ? <Stack alignItems="center" py={8}><CircularProgress size={28} /></Stack> : null}

      {feedback && (
        <>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(6, minmax(0, 1fr))' }, gap: 1 }}>
            <Metric label="组合权益" value={moneyText(shadow?.equity_usdt)} />
            <Metric label="现金" value={moneyText(shadow?.cash_usdt)} />
            <Metric label="总敞口" value={moneyText(shadow?.gross_exposure_usdt)} />
            <Metric label="未实现盈亏" value={signedMoneyText(shadow?.unrealized_pnl_usdt)} tone={pnlTone(shadow?.unrealized_pnl_usdt)} />
            <Metric label="已实现盈亏" value={signedMoneyText(shadow?.realized_pnl_usdt)} tone={pnlTone(shadow?.realized_pnl_usdt)} />
            <Metric label="当前回撤" value={percentText(shadow?.drawdown_pct)} tone={(shadow?.drawdown_pct || 0) > 5 ? 'warning.main' : undefined} />
          </Box>

          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', xl: 'minmax(0, 1.2fr) minmax(340px, 0.8fr)' }, gap: 2, alignItems: 'start' }}>
            <Section title="Shadow Portfolio" meta={`${shadow?.metrics.open_position_count || 0} 个持仓中 · 标价覆盖 ${percentText(shadow?.metrics.mark_coverage, true)}`}>
              {shadow?.positions.length ? (
                <Stack divider={<Divider />} spacing={0}>
                  {shadow.positions.map((position) => <PositionRow key={position.position_id} position={position} />)}
                </Stack>
              ) : (
                <EmptyState text="暂无已批准的方向性建议。人工批准后，系统才会建立固定名义金额的虚拟持仓。" />
              )}
            </Section>

            <Section title="Point-in-time 回测" meta={`方向样本 ${backtest?.sample_count || 0}`}>
              {backtest?.status === 'ready' ? (
                <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 1 }}>
                  <Metric label="方向命中率" value={percentText(backtest.hit_rate, true)} />
                  <Metric label="平均收益" value={percentText(backtest.average_return_pct)} />
                  <Metric label="累计收益" value={percentText(backtest.cumulative_return_pct)} />
                  <Metric label="最大回撤" value={percentText(backtest.max_drawdown_pct)} />
                </Box>
              ) : (
                <Alert severity="info" variant="outlined">方向性建议尚未形成足够的到期样本，系统不会用空样本展示伪精度指标。</Alert>
              )}
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
                已评估 {backtest?.evaluated_count || 0} · 待到期 {backtest?.pending_count || 0} · 数据不足 {backtest?.insufficient_data_count || 0}
              </Typography>
            </Section>
          </Box>

          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 1fr) minmax(320px, 0.75fr)' }, gap: 2, alignItems: 'start' }}>
            <Section title="置信度校准" meta={`样本 ${calibration?.sample_count || 0}`}>
              {calibration?.status === 'ready' && calibration.bins.length ? (
                <Stack divider={<Divider />} spacing={0}>
                  {calibration.bins.map((bin) => (
                    <Box key={`${bin.lower}-${bin.upper}`} sx={{ display: 'grid', gridTemplateColumns: { xs: '70px minmax(0, 1fr)', sm: '80px minmax(120px, 1fr) 110px 110px' }, gap: 1, py: 0.8, alignItems: 'center' }}>
                      <Typography variant="caption" sx={{ fontWeight: 800 }}>{Math.round(bin.lower * 100)}-{Math.round(bin.upper * 100)}%</Typography>
                      <LinearProgress variant="determinate" value={Math.max(0, Math.min(100, bin.actual_hit_rate * 100))} sx={{ height: 7 }} />
                      <Typography variant="caption" color="text.secondary">预测 {percentText(bin.average_confidence, true)}</Typography>
                      <Typography variant="caption" color="text.secondary">实际 {percentText(bin.actual_hit_rate, true)} · n={bin.sample_count}</Typography>
                    </Box>
                  ))}
                </Stack>
              ) : (
                <Alert severity="info" variant="outlined">至少需要 10 个到期方向样本后才展示置信度校准分桶。</Alert>
              )}
              {calibration?.status === 'ready' && (
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
                  Brier {numberText(calibration.brier_score, 4)} · ECE {percentText(calibration.expected_calibration_error, true)}
                </Typography>
              )}
            </Section>

            <Section title="系统反思" meta="只给建议，不自动改 Prompt">
              {feedback.reflections.length ? (
                <Stack divider={<Divider />} spacing={0}>
                  {feedback.reflections.map((reflection) => (
                    <Box key={reflection.code} sx={{ py: 1 }}>
                      <Stack direction="row" spacing={1} alignItems="center">
                        <Chip size="small" variant="outlined" color={severityColor(reflection.severity)} label={severityText(reflection.severity)} />
                        <Typography variant="body2" sx={{ fontWeight: 800 }}>{reflection.title}</Typography>
                      </Stack>
                      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.6 }}>{reflection.detail}</Typography>
                    </Box>
                  ))}
                </Stack>
              ) : <EmptyState text="当前没有需要人工处理的效果反思。" />}
            </Section>
          </Box>

          <Section title="站内通知" meta={`${feedback.notifications.counts.unread || 0} 条未读`} icon={<NotificationsNoneIcon color="primary" />}>
            {feedback.notifications.items.filter((item) => item.status !== 'dismissed').length ? (
              <Stack divider={<Divider />} spacing={0}>
                {feedback.notifications.items.filter((item) => item.status !== 'dismissed').map((notification) => (
                  <Box key={notification.notification_id} sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr auto', md: '120px minmax(0, 1fr) auto' }, gap: 1, py: 1, alignItems: 'center', opacity: notification.status === 'read' ? 0.68 : 1 }}>
                    <Chip size="small" variant="outlined" color={severityColor(notification.severity)} label={notificationCategoryText(notification.category)} sx={{ justifySelf: 'start' }} />
                    <Box sx={{ minWidth: 0 }}>
                      <Typography variant="body2" sx={{ fontWeight: notification.status === 'unread' ? 800 : 600 }}>{notification.title}</Typography>
                      <Typography variant="caption" color="text.secondary">{notification.body} · {formatTime(notification.created_at)}</Typography>
                    </Box>
                    <Stack direction="row" spacing={0.25}>
                      {notification.status === 'unread' && (
                        <Tooltip title="标记已读"><span><IconButton size="small" disabled={updatingNotificationId === notification.notification_id} onClick={() => void updateNotification(notification, 'read')}><CheckIcon fontSize="small" /></IconButton></span></Tooltip>
                      )}
                      <Tooltip title="忽略通知"><span><IconButton size="small" disabled={updatingNotificationId === notification.notification_id} onClick={() => void updateNotification(notification, 'dismissed')}><CloseIcon fontSize="small" /></IconButton></span></Tooltip>
                    </Stack>
                  </Box>
                ))}
              </Stack>
            ) : <EmptyState text="暂无站内通知。" />}
          </Section>
        </>
      )}
    </Stack>
  );
}

function PositionRow({ position }: { position: ShadowPosition }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr auto', md: 'minmax(120px, 0.9fr) 90px repeat(4, minmax(90px, 1fr))' }, gap: 1, py: 1, alignItems: 'center' }}>
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="body2" sx={{ fontWeight: 800 }}>{position.symbol}</Typography>
        <Typography variant="caption" color="text.secondary">{position.provider || '-'} · {position.market_type || '-'}</Typography>
      </Box>
      <Chip size="small" variant="outlined" color={position.side === 'BUY' ? 'success' : 'error'} label={recommendationActionText(position.side)} />
      <PositionFact label="名义金额" value={moneyText(position.notional_usdt)} />
      <PositionFact label="入场" value={numberText(position.entry_price)} />
      <PositionFact label="标记" value={numberText(position.current_price)} />
      <PositionFact label="盈亏" value={signedMoneyText(position.status === 'closed' ? position.realized_pnl_usdt : position.unrealized_pnl_usdt)} tone={pnlTone(position.status === 'closed' ? position.realized_pnl_usdt : position.unrealized_pnl_usdt)} />
    </Box>
  );
}

function Section({ title, meta, icon, children }: { title: string; meta: string; icon?: ReactNode; children: ReactNode }) {
  return (
    <Box sx={{ border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper', p: { xs: 1.5, md: 2 }, minWidth: 0 }}>
      <Stack direction="row" spacing={1} justifyContent="space-between" alignItems="center" sx={{ mb: 1.25 }}>
        <Stack direction="row" spacing={0.75} alignItems="center">{icon}<Typography variant="subtitle1">{title}</Typography></Stack>
        <Typography variant="caption" color="text.secondary">{meta}</Typography>
      </Stack>
      {children}
    </Box>
  );
}

function Metric({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return <Box sx={{ border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper', px: 1.25, py: 1, minWidth: 0 }}><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="body2" color={tone} sx={{ fontWeight: 800, overflowWrap: 'anywhere' }}>{value}</Typography></Box>;
}

function PositionFact({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return <Box sx={{ display: { xs: 'none', md: 'block' }, minWidth: 0 }}><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="body2" color={tone} sx={{ fontWeight: 700, overflowWrap: 'anywhere' }}>{value}</Typography></Box>;
}

function EmptyState({ text }: { text: string }) {
  return <Box sx={{ py: 4, textAlign: 'center' }}><Typography variant="body2" color="text.secondary">{text}</Typography></Box>;
}

function moneyText(value?: number | null): string {
  return typeof value === 'number' && !Number.isNaN(value) ? `${numberText(value, 2)} USDT` : '-';
}

function signedMoneyText(value?: number | null): string {
  if (typeof value !== 'number' || Number.isNaN(value)) return '-';
  return `${value > 0 ? '+' : ''}${numberText(value, 2)} USDT`;
}

function pnlTone(value?: number | null): string | undefined {
  if (!value) return undefined;
  return value > 0 ? 'success.main' : 'error.main';
}

function severityColor(value: string): 'default' | 'primary' | 'success' | 'warning' | 'error' {
  if (value === 'critical' || value === 'error') return 'error';
  if (value === 'warning') return 'warning';
  if (value === 'success') return 'success';
  if (value === 'info') return 'primary';
  return 'default';
}

function severityText(value: string): string {
  const labels: Record<string, string> = { info: '信息', warning: '关注', error: '错误', critical: '严重', success: '正常' };
  return labels[value] || value;
}

function notificationCategoryText(value: string): string {
  const labels: Record<string, string> = { review: '人工复核', feedback: '效果反馈', shadow_portfolio: '虚拟组合' };
  return labels[value] || value;
}

function notificationCounts(items: ResearchNotification[], notificationId: string, status: 'read' | 'dismissed'): Record<string, number> {
  return items.reduce<Record<string, number>>((counts, item) => {
    const nextStatus = item.notification_id === notificationId ? status : item.status;
    counts[nextStatus] = (counts[nextStatus] || 0) + 1;
    return counts;
  }, {});
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
