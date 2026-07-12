import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import CalculateIcon from '@mui/icons-material/Calculate';
import SecurityIcon from '@mui/icons-material/Security';
import { useState } from 'react';

import { api } from './api';
import type { ExecutionRiskGateResult, LeveragePreviewResult } from './types';

const numberInputSx = { minWidth: 0 };

export function QuantRiskPanel() {
  const [form, setForm] = useState({
    symbol: 'BTC_USDT',
    environment: 'testnet',
    entryPrice: 63900,
    stopPrice: 64539,
    riskBudget: 10,
    requestedLeverage: 500,
    maxLeverage: 200,
    equity: 1000,
    peakEquity: 1000,
    dayStartEquity: 1000,
    realizedPnlToday: 0,
    unrealizedPnl: 0,
    consecutiveLosses: 0,
  });
  const [preview, setPreview] = useState<LeveragePreviewResult | null>(null);
  const [gate, setGate] = useState<ExecutionRiskGateResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const update = (field: keyof typeof form, value: string) => {
    setForm((current) => ({
      ...current,
      [field]: field === 'symbol' || field === 'environment' ? value : Number(value),
    }));
  };

  const calculate = async () => {
    setLoading(true);
    setError(null);
    setPreview(null);
    setGate(null);
    try {
      const nextPreview = await api.leveragePreview({
        contract: {
          venue: 'gate',
          symbol: form.symbol,
          contract_multiplier: 0.0001,
          min_quantity: 1,
          quantity_step: 1,
          min_notional_usdt: 1,
          min_leverage: 1,
          max_leverage: form.maxLeverage,
          leverage_step: 1,
          maintenance_margin_rate: 0.003,
          taker_fee_rate: 0.00075,
        },
        risk: {
          side: form.stopPrice > form.entryPrice ? 'SELL' : 'BUY',
          entry_price: form.entryPrice,
          stop_price: form.stopPrice,
          risk_budget_usdt: form.riskBudget,
          requested_leverage: form.requestedLeverage,
          environment: form.environment,
        },
      });
      const nextGate = await api.executionRiskGate({
        current_equity_usdt: form.equity,
        peak_equity_usdt: form.peakEquity,
        day_start_equity_usdt: form.dayStartEquity,
        realized_pnl_today_usdt: form.realizedPnlToday,
        unrealized_pnl_usdt: form.unrealizedPnl,
        consecutive_losses: form.consecutiveLosses,
        proposed_max_loss_usdt: nextPreview.estimated_max_loss_usdt,
        proposed_gross_exposure_usdt: nextPreview.notional_usdt,
        liquidation_distance_pct: nextPreview.liquidation_distance_pct,
        environment: form.environment,
      });
      setPreview(nextPreview);
      setGate(nextGate);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : String(caught));
    } finally {
      setLoading(false);
    }
  };

  return (
    <Stack spacing={2} data-testid="quant-risk-panel">
      <Card>
        <CardContent>
          <Stack direction={{ xs: 'column', lg: 'row' }} spacing={2} alignItems={{ lg: 'flex-end' }}>
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Typography variant="subtitle1">仓位与杠杆</Typography>
              <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', xl: 'repeat(4, minmax(0, 1fr))' }, gap: 1.5, mt: 1.5 }}>
                <TextField label="合约" value={form.symbol} onChange={(event) => update('symbol', event.target.value)} sx={numberInputSx} />
                <TextField select label="环境" value={form.environment} onChange={(event) => update('environment', event.target.value)}>
                  <MenuItem value="paper">Paper</MenuItem>
                  <MenuItem value="testnet">Gate TestNet</MenuItem>
                  <MenuItem value="demo">Demo</MenuItem>
                </TextField>
                <NumberField label="入场价" value={form.entryPrice} onChange={(value) => update('entryPrice', value)} />
                <NumberField label="止损价" value={form.stopPrice} onChange={(value) => update('stopPrice', value)} />
                <NumberField label="最大亏损 (USDT)" value={form.riskBudget} onChange={(value) => update('riskBudget', value)} />
                <NumberField label="请求杠杆" value={form.requestedLeverage} onChange={(value) => update('requestedLeverage', value)} />
                <NumberField label="交易所杠杆上限" value={form.maxLeverage} onChange={(value) => update('maxLeverage', value)} />
                <NumberField label="当前权益 (USDT)" value={form.equity} onChange={(value) => update('equity', value)} />
                <NumberField label="权益峰值" value={form.peakEquity} onChange={(value) => update('peakEquity', value)} />
                <NumberField label="日初权益" value={form.dayStartEquity} onChange={(value) => update('dayStartEquity', value)} />
                <NumberField label="今日已实现盈亏" value={form.realizedPnlToday} onChange={(value) => update('realizedPnlToday', value)} />
                <NumberField label="当前未实现盈亏" value={form.unrealizedPnl} onChange={(value) => update('unrealizedPnl', value)} />
                <NumberField label="连续亏损次数" value={form.consecutiveLosses} onChange={(value) => update('consecutiveLosses', value)} />
              </Box>
            </Box>
            <Button variant="contained" startIcon={loading ? <CircularProgress size={16} /> : <CalculateIcon />} disabled={loading} onClick={calculate}>
              计算并检查
            </Button>
          </Stack>
        </CardContent>
      </Card>

      {error && <Alert severity="error">{error}</Alert>}
      {preview && gate && (
        <Card>
          <CardContent>
            <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}>
              <Box>
                <Typography variant="subtitle1">风险结论</Typography>
                <Typography variant="caption" color="text.secondary">AI 不可覆盖硬风控结果</Typography>
              </Box>
              <Stack direction="row" spacing={1}>
                <Chip color={preview.status === 'passed' ? 'success' : 'error'} label={`杠杆 ${preview.status === 'passed' ? '通过' : '阻断'}`} />
                <Chip color={gate.status === 'passed' ? 'success' : 'error'} icon={<SecurityIcon />} label={`账户风控 ${gate.status === 'passed' ? '通过' : '阻断'}`} />
              </Stack>
            </Stack>
            {[...preview.reasons, ...gate.reasons.map((reason) => `${riskReason(reason.code)}: ${reason.actual} / ${reason.limit}`)].map((reason) => (
              <Alert key={reason} severity="warning" sx={{ mt: 1 }}>{reason}</Alert>
            ))}
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: 'repeat(4, minmax(0, 1fr))' }, gap: 1, mt: 2 }}>
              <Metric label="安全杠杆上限" value={`${preview.max_safe_leverage}x`} />
              <Metric label="有效杠杆" value={`${preview.effective_leverage}x`} />
              <Metric label="名义价值" value={`${money(preview.notional_usdt)} USDT`} />
              <Metric label="初始保证金" value={`${money(preview.initial_margin_usdt)} USDT`} />
              <Metric label="估算最大亏损" value={`${money(preview.estimated_max_loss_usdt)} USDT`} />
              <Metric label="强平距离" value={`${preview.liquidation_distance_pct.toFixed(3)}%`} />
              <Metric label="估算强平价" value={money(preview.approximate_liquidation_price)} />
              <Metric label="合约数量" value={String(preview.quantity)} />
            </Box>
          </CardContent>
        </Card>
      )}
    </Stack>
  );
}

function NumberField({ label, value, onChange }: { label: string; value: number; onChange: (value: string) => void }) {
  return <TextField type="number" label={label} value={value} onChange={(event) => onChange(event.target.value)} slotProps={{ htmlInput: { step: 'any' } }} sx={numberInputSx} />;
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <Box sx={{ borderTop: '1px solid', borderColor: 'divider', pt: 1 }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="subtitle1" sx={{ overflowWrap: 'anywhere' }}>{value}</Typography>
    </Box>
  );
}

function money(value: number): string {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(value);
}

function riskReason(code: string): string {
  const labels: Record<string, string> = {
    max_trade_loss: '单笔亏损超限',
    max_daily_loss: '日亏损超限',
    max_drawdown: '回撤熔断',
    consecutive_losses: '连续止损熔断',
    liquidation_distance: '强平距离不足',
    gross_exposure: '总暴露超限',
    environment_forbidden: '环境禁止',
  };
  return labels[code] || code;
}
