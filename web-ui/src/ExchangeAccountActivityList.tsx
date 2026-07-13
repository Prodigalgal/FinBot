import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Collapse,
  Divider,
  IconButton,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import KeyboardArrowDownIcon from '@mui/icons-material/KeyboardArrowDown';
import KeyboardArrowUpIcon from '@mui/icons-material/KeyboardArrowUp';
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong';
import { Fragment, useState } from 'react';

import type { ExchangeAccountActivity, ExchangeAccountActivityPayload } from './types';
import { formatTime, statusColor, statusText } from './utils';

export function ExchangeAccountActivityList({ payload }: { payload: ExchangeAccountActivityPayload }) {
  const [expanded, setExpanded] = useState<string | null>(null);
  if (!payload.activities.length) {
    return (
      <Card>
        <CardContent sx={{ py: 5, textAlign: 'center' }}>
          <ReceiptLongIcon color="disabled" />
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>当前筛选条件下没有真实操作记录</Typography>
        </CardContent>
      </Card>
    );
  }
  return (
    <Card>
      <CardContent sx={{ p: 0, '&:last-child': { pb: 0 } }}>
        <TableContainer sx={{ display: { xs: 'none', md: 'block' } }}>
          <Table size="small" aria-label="交易账户操作历史">
            <TableHead>
              <TableRow>
                <TableCell padding="checkbox" />
                <TableCell>发生时间</TableCell>
                <TableCell>来源 / 阶段</TableCell>
                <TableCell>交易所 / 合约</TableCell>
                <TableCell>方向</TableCell>
                <TableCell align="right">数量 / 成交</TableCell>
                <TableCell align="right">价格 / 已实现盈亏</TableCell>
                <TableCell>状态</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {payload.activities.map((activity) => {
                const open = expanded === activity.activity_id;
                return (
                  <Fragment key={activity.activity_id}>
                    <TableRow hover selected={open}>
                      <TableCell padding="checkbox">
                        <IconButton size="small" aria-label={open ? '收起详情' : '展开详情'} onClick={() => setExpanded(open ? null : activity.activity_id)}>
                          {open ? <KeyboardArrowUpIcon /> : <KeyboardArrowDownIcon />}
                        </IconButton>
                      </TableCell>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(activity.occurred_at)}</TableCell>
                      <TableCell><ActivitySourceStage activity={activity} /></TableCell>
                      <TableCell>
                        <Typography variant="body2" fontWeight={700}>{adapterText(activity.adapter_id)}</Typography>
                        <Typography variant="caption" color="text.secondary">{activity.symbol || '-'}</Typography>
                      </TableCell>
                      <TableCell>{activity.side ? <ActivityDirectionChip side={activity.side === 'BUY' ? 'long' : activity.side === 'SELL' ? 'short' : 'unknown'} /> : '-'}</TableCell>
                      <TableCell align="right">{quantity(activity.quantity)} / {quantity(activity.filled_quantity)}</TableCell>
                      <TableCell align="right">
                        <Typography variant="body2">{price(activity.average_fill_price ?? activity.price)}</Typography>
                        {typeof activity.realized_pnl === 'number' && (
                          <Typography variant="caption" sx={{ color: pnlColor(activity.realized_pnl), fontWeight: 700 }}>
                            {money(activity.realized_pnl, true)}
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell><Chip size="small" color={statusColor(activity.status)} label={activityStatusText(activity.status)} /></TableCell>
                    </TableRow>
                    <TableRow>
                      <TableCell colSpan={8} sx={{ py: 0, borderBottom: open ? undefined : 0 }}>
                        <Collapse in={open} timeout="auto" unmountOnExit>
                          <ActivityDetails activity={activity} />
                        </Collapse>
                      </TableCell>
                    </TableRow>
                  </Fragment>
                );
              })}
            </TableBody>
          </Table>
        </TableContainer>
        <Stack sx={{ display: { xs: 'flex', md: 'none' } }} divider={<Divider flexItem />}>
          {payload.activities.map((activity) => {
            const open = expanded === activity.activity_id;
            return (
              <Box key={activity.activity_id} sx={{ px: 1.5, py: 1.25 }}>
                <Stack direction="row" spacing={1} justifyContent="space-between" alignItems="flex-start">
                  <Box sx={{ minWidth: 0 }}>
                    <Typography variant="subtitle1">{activity.symbol || activity.title}</Typography>
                    <Typography variant="caption" color="text.secondary">{formatTime(activity.occurred_at)} · {adapterText(activity.adapter_id)}</Typography>
                  </Box>
                  <Chip size="small" color={statusColor(activity.status)} label={activityStatusText(activity.status)} />
                </Stack>
                <Box sx={{ mt: 1, display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 1 }}>
                  <ActivityFact label="来源 / 阶段" value={`${sourceText(activity.source_type)} / ${stageText(activity.stage)}`} />
                  <ActivityFact label="方向" value={activity.side || '-'} />
                  <ActivityFact label="数量 / 成交" value={`${quantity(activity.quantity)} / ${quantity(activity.filled_quantity)}`} />
                  <ActivityFact
                    label={typeof activity.realized_pnl === 'number' ? '已实现盈亏' : '成交均价'}
                    value={typeof activity.realized_pnl === 'number' ? money(activity.realized_pnl, true) : price(activity.average_fill_price ?? activity.price)}
                    pnl={activity.realized_pnl}
                  />
                </Box>
                <Button
                  size="small"
                  endIcon={open ? <KeyboardArrowUpIcon /> : <KeyboardArrowDownIcon />}
                  onClick={() => setExpanded(open ? null : activity.activity_id)}
                  sx={{ mt: 1 }}
                >
                  {open ? '收起详情' : '查看详情'}
                </Button>
                <Collapse in={open} timeout="auto" unmountOnExit><ActivityDetails activity={activity} /></Collapse>
              </Box>
            );
          })}
        </Stack>
      </CardContent>
    </Card>
  );
}

function ActivitySourceStage({ activity }: { activity: ExchangeAccountActivity }) {
  return (
    <Box>
      <Typography variant="body2" fontWeight={700}>{sourceText(activity.source_type)}</Typography>
      <Typography variant="caption" color="text.secondary">{stageText(activity.stage)}</Typography>
    </Box>
  );
}

function ActivityDetails({ activity }: { activity: ExchangeAccountActivity }) {
  const baseFacts: Array<[string, unknown]> = [
    ['事件', activity.title],
    ['说明', activity.detail],
    ['订单类型', activity.order_type],
    ['委托数量', activity.quantity],
    ['成交数量', activity.filled_quantity],
    ['剩余数量', activity.remaining_quantity],
    ['委托价格', activity.price],
    ['成交均价', activity.average_fill_price],
    ['手续费', activity.fee],
    ['已实现盈亏 / 账户变动', activity.realized_pnl],
    ['Client Order ID', activity.client_order_id],
    ['Exchange Order ID', activity.exchange_order_id],
    ['OMS Order ID', activity.oms_order_id],
    ['Execution ID', activity.paper_execution_id],
    ['Decision ID', activity.decision_id],
    ['Loop Run ID', activity.loop_run_id],
  ];
  const facts = [
    ...baseFacts,
    ...Object.entries(activity.details || {}).map(([key, value]) => [detailLabel(key), value] as [string, unknown]),
  ].filter(([, value]) => value !== null && value !== undefined && value !== '');
  return (
    <Box sx={{ py: 1.5, px: { xs: 0, md: 2 }, bgcolor: 'action.hover' }}>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, minmax(0, 1fr))', xl: 'repeat(3, minmax(0, 1fr))' }, gap: 1.25 }}>
        {facts.map(([label, value], index) => (
          <Box key={`${label}-${index}`} sx={{ minWidth: 0 }}>
            <Typography variant="caption" color="text.secondary">{label}</Typography>
            <Typography variant="body2" sx={{ overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}>{detailValue(value)}</Typography>
          </Box>
        ))}
      </Box>
    </Box>
  );
}


function sourceText(sourceType: ExchangeAccountActivity['source_type']): string {
  return sourceType === 'exchange' ? '交易所事实' : '本地审计';
}

function stageText(stage: ExchangeAccountActivity['stage']): string {
  const labels: Record<ExchangeAccountActivity['stage'], string> = {
    decision: 'AI 决策',
    proposal: '建议草案',
    execution: '执行引擎',
    order: '订单',
    fill: '成交',
    account: '账户变动',
  };
  return labels[stage] || stage;
}

function adapterText(adapterId?: string | null): string {
  if (adapterId === 'gate_testnet') {
    return 'Gate TestNet';
  }
  if (adapterId === 'bybit_demo') {
    return 'Bybit Demo';
  }
  return adapterId === 'local' || !adapterId ? 'FinBot 本地' : adapterId;
}

function activityStatusText(status: string): string {
  const labels: Record<string, string> = {
    open: '挂单中',
    partial: '部分成交',
    filled: '已成交',
    submitted: '已提交',
    planned: '已计划',
    dry_run: '仅演练',
    cancelled: '已撤销',
    rejected: '已拒绝',
    failed: '失败',
    blocked: '已阻断',
    skipped_policy: '策略跳过',
    skipped_unmapped: '未映射',
    skipped_existing_position: '已有持仓',
    actionable: '可执行建议',
    watch: '观察',
    recorded: '已入账',
  };
  return labels[status] || statusText(status);
}

function detailLabel(key: string): string {
  const labels: Record<string, string> = {
    confidence: '置信度',
    score: '评分',
    target_price: '目标价格',
    invalidation_price: '失效价格',
    risk_warnings: '风险提示',
    report_id: 'Report ID',
    advice_id: 'Advice ID',
    market_type: '市场类型',
    execution_mode: '执行模式',
    requested_notional: '请求名义价值',
    error: '错误原因',
    request: '下单请求白名单',
    response: '交易所回执白名单',
    from_status: '原状态',
    to_status: '新状态',
    sequence: '事件序号',
    version: 'OMS 版本',
    reduce_only: '只减仓',
    finish_as: '结束原因',
    tif: '有效方式',
    timeInForce: '有效方式',
    create_time: '创建时间',
    finish_time: '完成时间',
    trade_id: '成交 ID',
    close_size: '平仓数量',
    role: '成交角色',
    point_fee: '点卡手续费',
    cumExecValue: '累计成交价值',
    rejectReason: '拒绝原因',
    cancelType: '撤单原因',
    execId: '成交 ID',
    execType: '成交类型',
    execValue: '成交价值',
    feeCurrency: '手续费币种',
    feeRate: '手续费率',
    closedSize: '平仓数量',
    isMaker: 'Maker 成交',
    change: '账户变动金额',
    balance: '变动后余额',
    ledger_type: '流水类型',
  };
  return labels[key] || key;
}

function detailValue(value: unknown): string {
  if (typeof value === 'boolean') {
    return value ? '是' : '否';
  }
  if (typeof value === 'number') {
    return Number.isFinite(value) ? new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 8 }).format(value) : '-';
  }
  if (typeof value === 'object' && value !== null) {
    return JSON.stringify(value, null, 2);
  }
  return String(value ?? '-');
}


function ActivityDirectionChip({ side }: { side: 'long' | 'short' | 'unknown' }) {
  return (
    <Chip
      size="small"
      variant="outlined"
      color={side === 'long' ? 'success' : side === 'short' ? 'error' : 'default'}
      label={side === 'long' ? '多' : side === 'short' ? '空' : '未知'}
    />
  );
}

function ActivityFact({ label, value, pnl }: { label: string; value: string; pnl?: number | null }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography variant="body2" sx={{ color: pnlColor(pnl), fontWeight: 700, overflowWrap: 'anywhere' }}>{value}</Typography>
    </Box>
  );
}

function money(value?: number | null, signed = false): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return '-';
  }
  const prefix = signed && value > 0 ? '+' : '';
  return `${prefix}${new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(value)} USDT`;
}

function price(value?: number | null): string {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
    return '-';
  }
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 8 }).format(value);
}

function quantity(value?: number | null): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return '-';
  }
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 8 }).format(value);
}

function pnlColor(value?: number | null): string | undefined {
  if (typeof value !== 'number' || value === 0) {
    return undefined;
  }
  return value > 0 ? 'success.main' : 'error.main';
}
