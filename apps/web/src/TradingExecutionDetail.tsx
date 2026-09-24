import AccountTreeIcon from '@mui/icons-material/AccountTree';
import GavelIcon from '@mui/icons-material/Gavel';
import SecurityIcon from '@mui/icons-material/Security';
import ShowChartIcon from '@mui/icons-material/ShowChart';
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong';
import { Box, Chip, Divider, Paper, Stack, Typography } from '@mui/material';

import { DebateProtocolPanel } from './DebateProtocolPanel';
import type { TradeAutomationDetail } from './types';
import { CopyableText, SectionTitle, StatusBadge, formatMoney, jsonList, statusLabel } from './ui';

export function TradingExecutionDetail({ detail }: { detail: TradeAutomationDetail }) {
  const hasDebateTrace = Boolean(detail.executionDebate || detail.principalReviewDebate);

  return (
    <Stack spacing={2.25}>
      <SectionTitle
        title="执行审计详情"
        subtitle="主审独立审计、执行委员会共识、确定性风控评估与交易所提交记录"
      />

      {/* 交易决策总览 */}
      {detail.decision && (
        <Paper
          variant="outlined"
          sx={{
            p: 2.25,
            borderRadius: '10px',
            borderLeft: '4px solid',
            borderColor: detail.decision.action === 'BUY'
              ? 'success.main'
              : detail.decision.action === 'SELL'
                ? 'error.main'
                : 'info.main',
            boxShadow: '0 1px 3px rgba(15, 23, 42, 0.04)',
          }}
        >
          <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1.5} alignItems={{ sm: 'center' }}>
            <Box>
              <Stack direction="row" spacing={1} alignItems="center">
                <Typography fontWeight={800} variant="h6" sx={{ fontSize: '1.05rem' }}>
                  {detail.decision.symbol} · {statusLabel(detail.decision.action)}
                </Typography>
                <Chip
                  size="small"
                  label={detail.decision.decisionKind}
                  sx={{ height: 20, fontSize: '0.68rem', fontWeight: 700 }}
                />
              </Stack>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.25 }}>
                {detail.estimatedTrades.length > 0
                  ? '不可执行产品已转为内部预估，不会调用交易所下单接口'
                  : `建议 ${statusLabel(detail.decision.proposalStatus)}，由主审审计、执行委员会、风控与 OMS 自动流转`}
              </Typography>
            </Box>
            <StatusBadge status="INFO" label={`置信度 ${(Number(detail.decision.confidence) * 100).toFixed(0)}%`} />
          </Stack>

          <Box
            sx={{
              mt: 1.5,
              p: 1.5,
              bgcolor: 'surfaceMuted',
              borderRadius: '8px',
              display: 'grid',
              gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, 1fr)' },
              gap: 1.5,
            }}
          >
            <Box>
              <Typography variant="caption" color="text.secondary">入场参考价</Typography>
              <Typography variant="body2" fontWeight={800} sx={{ fontFeatureSettings: '"tnum"' }}>
                {detail.decision.entryReference ?? '-'}
              </Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary">目标止盈价</Typography>
              <Typography variant="body2" fontWeight={800} color="success.main" sx={{ fontFeatureSettings: '"tnum"' }}>
                {detail.decision.targetPrice ?? '-'}
              </Typography>
            </Box>
            <Box>
              <Typography variant="caption" color="text.secondary">失效止损价</Typography>
              <Typography variant="body2" fontWeight={800} color="error.main" sx={{ fontFeatureSettings: '"tnum"' }}>
                {detail.decision.invalidationPrice ?? '-'}
              </Typography>
            </Box>
          </Box>

          <Box sx={{ mt: 1.5 }}>
            <Typography variant="caption" color="text.secondary" fontWeight={700}>
              决策依据与审计约束：
            </Typography>
            <Stack spacing={0.5} sx={{ mt: 0.5 }}>
              {jsonList(detail.decision.rationaleJson).map((reason) => (
                <Typography key={reason} variant="body2" color="text.secondary" sx={{ pl: 1, borderLeft: '2px solid', borderColor: 'divider' }}>
                  {reason}
                </Typography>
              ))}
            </Stack>
          </Box>
        </Paper>
      )}

      {/* 主审独立审计面板 (PRINCIPAL_REVIEW) */}
      {detail.principalReviewDebate && (
        <Paper
          variant="outlined"
          sx={{
            p: 2.25,
            borderRadius: '10px',
            borderColor: 'secondary.light',
            bgcolor: 'rgba(147, 51, 234, 0.015)',
          }}
        >
          <Stack direction="row" spacing={1.25} alignItems="center" sx={{ mb: 1.5 }}>
            <SecurityIcon color="secondary" />
            <Box sx={{ flex: 1 }}>
              <Typography fontWeight={800} sx={{ fontSize: '0.95rem' }}>
                主审独立审计决议 (Principal Review)
              </Typography>
              <Typography variant="caption" color="text.secondary">
                拥有强一票否决权与参数收紧裁决，基于历史反例与极端行情独立审计
              </Typography>
            </Box>
            <Chip
              size="small"
              color="secondary"
              label="独立审计关口"
              sx={{ fontWeight: 700, fontSize: '0.72rem' }}
            />
          </Stack>
          <DebateProtocolPanel trace={detail.principalReviewDebate} />
        </Paper>
      )}

      {/* 执行委员会共识决策面板 (EXECUTION) */}
      {detail.executionDebate && (
        <Paper
          variant="outlined"
          sx={{
            p: 2.25,
            borderRadius: '10px',
            borderColor: 'warning.light',
            bgcolor: 'rgba(245, 158, 11, 0.015)',
          }}
        >
          <Stack direction="row" spacing={1.25} alignItems="center" sx={{ mb: 1.5 }}>
            <AccountTreeIcon color="warning" />
            <Box sx={{ flex: 1 }}>
              <Typography fontWeight={800} sx={{ fontSize: '0.95rem' }}>
                执行委员会多 Agent 社会选择 (Execution SDB-SCA)
              </Typography>
              <Typography variant="caption" color="text.secondary">
                微观流动性、拆单、滑点最小化多角色双盲博弈与 Schulze 排序决策
              </Typography>
            </Box>
            <Chip
              size="small"
              color="warning"
              label="社会选择共识"
              sx={{ fontWeight: 700, fontSize: '0.72rem' }}
            />
          </Stack>
          <DebateProtocolPanel trace={detail.executionDebate} />
        </Paper>
      )}

      {/* 历史非对称 review 兼容 */}
      {!hasDebateTrace && detail.aiReviews.length > 0 && (
        <Stack spacing={1}>
          <Typography variant="subtitle2" fontWeight={750} color="text.secondary">
            执行机器人复核记录
          </Typography>
          <Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5}>
            {detail.aiReviews.map((review) => (
              <Paper
                key={review.reviewId}
                variant="outlined"
                sx={{ p: 2, flex: 1, minWidth: 0, borderRadius: '10px', boxShadow: '0 1px 3px rgba(15, 23, 42, 0.04)' }}
              >
                <Stack direction="row" justifyContent="space-between" alignItems="center">
                  <Typography fontWeight={700}>
                    {review.stage === 'DRAFT' ? '执行机器人初审' : '执行机器人反思终审'}
                  </Typography>
                  <StatusBadge status={review.status} />
                </Stack>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.25 }}>
                  {review.providerProfileId} / {review.modelName} / {review.reasoningEffort}
                </Typography>
                <Typography
                  component="pre"
                  variant="body2"
                  sx={{
                    mt: 1.25,
                    p: 1.25,
                    bgcolor: 'action.hover',
                    borderRadius: '6px',
                    border: '1px solid',
                    borderColor: 'divider',
                    whiteSpace: 'pre-wrap',
                    overflowWrap: 'anywhere',
                    maxHeight: 260,
                    overflow: 'auto',
                    fontFamily: 'monospace',
                    fontSize: 12,
                  }}
                >
                  {pretty(review.outputJson || review.errorMessage || '-')}
                </Typography>
              </Paper>
            ))}
          </Stack>
        </Stack>
      )}

      {/* 真实感撮合与滑点预估 (Estimated Trades) */}
      {detail.estimatedTrades.length > 0 && (
        <Stack spacing={1}>
          <Typography variant="subtitle2" fontWeight={750} color="text.secondary">
            高真实感撮合预估
          </Typography>
          {detail.estimatedTrades.map((projection) => (
            <Paper
              key={projection.projectionId}
              variant="outlined"
              sx={{
                p: 2.25,
                borderRadius: '10px',
                borderColor: 'primary.light',
                bgcolor: 'rgba(29, 78, 216, 0.015)',
              }}
            >
              <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1} alignItems={{ sm: 'center' }}>
                <Box>
                  <Typography fontWeight={750} sx={{ fontSize: '0.95rem' }}>
                    {projection.exchange} · {projection.symbol} {statusLabel(projection.side)}
                  </Typography>
                  <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 0.25 }}>
                    <Typography variant="caption" color="text.secondary">预估编号</Typography>
                    <CopyableText text={projection.projectionId} display={projection.projectionId.slice(0, 16) + '...'} />
                    <Typography variant="caption" color="text.secondary">· 风控 {projection.policyVersion}</Typography>
                  </Stack>
                </Box>
                <StatusBadge status="ESTIMATED" label="仅预估，不会下单" />
              </Stack>

              <Box
                sx={{
                  mt: 1.5,
                  p: 1.5,
                  bgcolor: 'background.paper',
                  borderRadius: '8px',
                  border: '1px solid',
                  borderColor: 'divider',
                  display: 'grid',
                  gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', md: 'repeat(4, 1fr)' },
                  gap: 1.5,
                }}
              >
                <Box>
                  <Typography variant="caption" color="text.secondary">名义价值</Typography>
                  <Typography variant="body2" fontWeight={750} sx={{ fontFeatureSettings: '"tnum"' }}>
                    {formatMoney(projection.notionalUsdt)}
                  </Typography>
                </Box>
                <Box>
                  <Typography variant="caption" color="text.secondary">预估保证金 ({projection.leverage}x)</Typography>
                  <Typography variant="body2" fontWeight={750} sx={{ fontFeatureSettings: '"tnum"' }}>
                    {formatMoney(projection.initialMarginUsdt)}
                  </Typography>
                </Box>
                <Box>
                  <Typography variant="caption" color="text.secondary">预估净盈利</Typography>
                  <Typography variant="body2" fontWeight={750} color="success.main" sx={{ fontFeatureSettings: '"tnum"' }}>
                    {formatMoney(projection.estimatedProfitUsdt)}
                  </Typography>
                </Box>
                <Box>
                  <Typography variant="caption" color="text.secondary">预估净亏损</Typography>
                  <Typography variant="body2" fontWeight={750} color="error.main" sx={{ fontFeatureSettings: '"tnum"' }}>
                    {formatMoney(projection.estimatedLossUsdt)}
                  </Typography>
                </Box>
              </Box>

              <Typography variant="body2" sx={{ mt: 1.25, fontFeatureSettings: '"tnum"', fontSize: '0.85rem' }}>
                最新价 {projection.marketPrice} · 入场 {projection.entryReference} · 止盈 {projection.targetPrice} · 止损 {projection.stopPrice} · 盈亏比 {Number(projection.riskRewardRatio).toFixed(2)}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                模拟撮合费用已计入：入场 {formatMoney(projection.estimatedEntryCostUsdt)}，止盈退出 {formatMoney(projection.estimatedTargetExitCostUsdt)}，止损退出 {formatMoney(projection.estimatedStopExitCostUsdt)}。含动态滑点与 Taker/Maker 深度损耗。
              </Typography>
            </Paper>
          ))}
        </Stack>
      )}

      {/* 风控评估记录 */}
      {detail.riskAssessments.length > 0 && (
        <Stack spacing={1}>
          <Typography variant="subtitle2" fontWeight={750} color="text.secondary">
            风控评估
          </Typography>
          {detail.riskAssessments.map((risk) => (
            <Paper key={risk.assessmentId} variant="outlined" sx={{ p: 2, borderRadius: '10px' }}>
              <Stack direction="row" justifyContent="space-between" alignItems="center">
                <Typography fontWeight={700}>风控 · {risk.accountId}</Typography>
                <StatusBadge status={risk.status} />
              </Stack>
              <Typography variant="body2" sx={{ mt: 0.75, fontFeatureSettings: '"tnum"' }}>
                数量 {risk.quantity ?? '-'} · 名义价值 {formatMoney(risk.notionalUsdt)} · 保证金 {formatMoney(risk.initialMarginUsdt)} · 杠杆 {risk.leverage ?? '-'}x · 最大损失 {formatMoney(risk.estimatedMaximumLossUsdt)}
              </Typography>
              {jsonList(risk.reasonsJson).map((reason) => (
                <Typography key={reason} variant="caption" color="text.secondary" display="block" sx={{ mt: 0.25 }}>
                  • {reason}
                </Typography>
              ))}
            </Paper>
          ))}
        </Stack>
      )}

      {/* OMS 订单提交 */}
      {detail.orders.length > 0 && (
        <Stack spacing={1}>
          <Typography variant="subtitle2" fontWeight={750} color="text.secondary">
            OMS 订单流转
          </Typography>
          {detail.orders.map((order) => (
            <Paper key={order.orderId} variant="outlined" sx={{ p: 2, borderRadius: '10px' }}>
              <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1} alignItems={{ sm: 'center' }}>
                <Box>
                  <Typography fontWeight={750}>
                    {order.exchange} {order.environment} · {order.symbol} {statusLabel(order.side)}
                  </Typography>
                  <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 0.25 }}>
                    <CopyableText text={order.orderId} display={order.orderId.slice(0, 16) + '...'} />
                    <Typography variant="caption" color="text.secondary">· client {order.clientOrderId || '-'}</Typography>
                  </Stack>
                </Box>
                <StatusBadge status={order.status} />
              </Stack>
              <Typography variant="body2" sx={{ mt: 1, fontFeatureSettings: '"tnum"' }}>
                请求 {order.requestedQuantity} · 成交 {order.filledQuantity} · 均价 {order.averageFillPrice ?? '-'} · {order.leverage}x
              </Typography>
              {order.submissionAttempts.map((attempt) => (
                <Typography
                  key={attempt.attemptId}
                  variant="caption"
                  display="block"
                  color={attempt.status === 'REJECTED' ? 'error.main' : 'text.secondary'}
                  sx={{ mt: 0.35, fontFeatureSettings: '"tnum"' }}
                >
                  提交 #{attempt.attemptNumber} {statusLabel(attempt.status)} · HTTP {attempt.httpStatus ?? '-'} · {attempt.errorMessage || attempt.exchangeOrderId || '-'}
                </Typography>
              ))}
            </Paper>
          ))}
        </Stack>
      )}
    </Stack>
  );
}

function pretty(value: string): string {
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}
