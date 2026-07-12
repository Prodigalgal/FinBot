import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Divider,
  FormControlLabel,
  LinearProgress,
  MenuItem,
  Stack,
  Switch,
  Tab,
  Tabs,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Tooltip,
  Typography,
} from '@mui/material';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet';
import ArticleIcon from '@mui/icons-material/Article';
import AutorenewIcon from '@mui/icons-material/Autorenew';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import FactCheckOutlinedIcon from '@mui/icons-material/FactCheckOutlined';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import Inventory2Icon from '@mui/icons-material/Inventory2';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import PsychologyIcon from '@mui/icons-material/Psychology';
import QueryStatsIcon from '@mui/icons-material/QueryStats';
import RefreshIcon from '@mui/icons-material/Refresh';
import ScienceIcon from '@mui/icons-material/Science';
import ShieldIcon from '@mui/icons-material/Shield';
import StorageIcon from '@mui/icons-material/Storage';
import TimelineIcon from '@mui/icons-material/Timeline';
import TravelExploreIcon from '@mui/icons-material/TravelExplore';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import WorkIcon from '@mui/icons-material/Work';
import type { ReactNode } from 'react';
import { useCallback, useEffect, useState } from 'react';

import { api } from './api';
import {
  agentRoleText,
  autonomousStepText,
  councilPhaseText,
  decisionReadinessReasonText,
  decisionReadinessText,
  durationText,
  humanizeAiText,
  isRecord,
  numberText,
  percentText,
  recommendationActionText,
  recommendationNarrative,
  recordArray,
  reportKindDescription,
  reportKindTitle,
  researchStepText,
  shortId,
  stanceText,
  stepOutputText,
  triggerTypeText,
} from './humanReadable';
import type {
  AIDebateCouncil,
  AIDebateMessage,
  AITradeDecision,
  AutonomousLoopStep,
  AutonomousStatusPayload,
  JobRecord,
  ProductRecommendation,
  StatusPayload,
} from './types';
import { compactNumber, formatTime, jobKindText, jobStatusText, statusColor, statusText } from './utils';

type Navigate = (view: string) => void;
type AutonomousView = 'results' | 'process' | 'debate' | 'governance';

export function OverviewPanel({
  status,
  autonomous,
  jobs,
  counts,
  onNavigate,
}: {
  status: StatusPayload | null;
  autonomous: AutonomousStatusPayload | null;
  jobs: JobRecord[];
  counts: Record<string, number>;
  onNavigate: Navigate;
}) {
  const latestRun = autonomous?.recent_runs?.[0] || null;
  const latestCompletedRun = autonomous?.recent_runs?.find(
    (run) => run.loop_run_id === autonomous?.latest_result_loop_run_id,
  ) || autonomous?.recent_runs?.find((run) => Boolean(run.finished_at) && run.status !== 'abandoned') || null;
  const scheduler = autonomous?.scheduler;
  const workerCount = autonomous?.worker?.workers?.filter((worker) => worker.active).length || 0;
  const decisions = preferredRecommendations(autonomous);
  const readiness = autonomous?.latest_decision_readiness || latestCompletedRun?.decision_readiness || null;
  const failedSteps = latestRun?.steps?.filter((step) => step.status === 'failed').length || 0;
  const completedSteps = latestRun?.steps?.filter((step) => ['passed', 'succeeded', 'completed'].includes(step.status)).length || 0;
  const healthy = Boolean(scheduler?.enabled && workerCount > 0 && !scheduler.last_error && failedSteps === 0);
  const duration = numericValue((scheduler?.running ? latestCompletedRun : latestRun)?.summary, 'total_duration_ms');
  const attentionItems = [
    ...(readiness?.reasons || []).map(decisionReadinessReasonText),
    status?.latest_advisory_report?.status === 'partial' ? '最近市场建议报告为部分完成，个别产品行情获取失败。' : '',
    scheduler?.last_error ? `调度器最近错误：${scheduler.last_error}` : '',
    workerCount === 0 ? '当前没有可用的常驻 Worker。' : '',
  ].filter(Boolean);

  const metricGroups = [
    {
      label: '已收集证据',
      value: compactNumber(counts.raw_evidence),
      detail: `${compactNumber(counts.event_candidates)} 个事件候选`,
      icon: <StorageIcon fontSize="small" />,
    },
    {
      label: '研究卡片',
      value: compactNumber(counts.research_cards),
      detail: `${compactNumber(counts.research_councils)} 次委员会复核`,
      icon: <ScienceIcon fontSize="small" />,
    },
    {
      label: '本轮产品',
      value: compactNumber(counts.market_quotes),
      detail: `${autonomous?.latest_universe?.instruments?.length || 0} 个本轮产品`,
      icon: <QueryStatsIcon fontSize="small" />,
    },
    {
      label: '最终建议',
      value: String(decisions.length),
      detail: `${compactNumber(counts.advisory_reports)} 份历史建议报告`,
      icon: <WorkIcon fontSize="small" />,
    },
  ];

  return (
    <Stack spacing={2}>
      <Card sx={{ borderColor: healthy ? 'success.main' : 'warning.main' }}>
        <CardContent sx={{ py: 2, '&:last-child': { pb: 2 } }}>
          <Stack direction={{ xs: 'column', lg: 'row' }} spacing={2} alignItems={{ xs: 'stretch', lg: 'center' }}>
            <Stack direction="row" spacing={1.25} alignItems="center" sx={{ minWidth: 220 }}>
              {healthy ? <CheckCircleIcon color="success" /> : <WarningAmberIcon color="warning" />}
              <Box>
                <Typography variant="h3">{healthy ? '自动研究运行正常' : '自动研究需要关注'}</Typography>
                <Typography variant="caption" color="text.secondary">
                  {scheduler?.running ? '本轮任务正在执行' : `当前空闲 · ${workerCount} 个 Worker 在线`}
                </Typography>
              </Box>
            </Stack>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', sm: 'repeat(5, minmax(0, 1fr))' }, gap: 1.5, flexGrow: 1 }}>
              <CompactFact label="最近完成" value={formatTime(latestCompletedRun?.finished_at || scheduler?.last_finished_at)} />
              <CompactFact label={scheduler?.running ? '上轮耗时' : '本轮耗时'} value={durationText(duration)} />
              <CompactFact label="运行进度" value={`${completedSteps}/${latestRun?.steps?.length || 0} 步`} />
              <CompactFact label="决策就绪度" value={decisionReadinessText(readiness?.status)} chipStatus={readiness?.status} />
              <CompactFact label="下次运行" value={formatTime(scheduler?.next_run_at)} />
            </Box>
            <Button variant="contained" endIcon={<ArrowForwardIcon />} onClick={() => onNavigate('autonomous')}>
              查看自动研究
            </Button>
          </Stack>
        </CardContent>
      </Card>

      {attentionItems.length > 0 && (
        <Alert
          severity="warning"
          action={<Button color="inherit" size="small" onClick={() => onNavigate('autonomous')}>打开运行详情</Button>}
        >
          <Stack spacing={0.25}>
            {attentionItems.map((item) => <Typography key={item} variant="body2">{item}</Typography>)}
          </Stack>
        </Alert>
      )}

      <Card component="nav" aria-label="常用入口">
        <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.25} alignItems={{ xs: 'stretch', md: 'center' }}>
            <Typography variant="h3" sx={{ minWidth: 88 }}>常用入口</Typography>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(4, minmax(0, 1fr))' }, gap: 1, flexGrow: 1 }}>
              <Button data-testid="quick-products" variant="outlined" startIcon={<Inventory2Icon />} onClick={() => onNavigate('products')}>选择产品</Button>
              <Button data-testid="quick-instant" variant="outlined" startIcon={<TravelExploreIcon />} onClick={() => onNavigate('instant')}>发起研究</Button>
              <Button data-testid="quick-reviews" variant="outlined" startIcon={<FactCheckOutlinedIcon />} onClick={() => onNavigate('reviews')}>复核建议</Button>
              <Button data-testid="quick-accounts" variant="outlined" startIcon={<AccountBalanceWalletIcon />} onClick={() => onNavigate('accounts')}>查看账户</Button>
            </Box>
          </Stack>
        </CardContent>
      </Card>

      <Card>
        <CardContent sx={{ p: 0, '&:last-child': { pb: 0 } }}>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', lg: 'repeat(4, minmax(0, 1fr))' } }}>
            {metricGroups.map((metric, index) => (
              <Box
                key={metric.label}
                sx={{
                  px: { xs: 1.5, md: 2 },
                  py: 1.75,
                  borderRight: { xs: index % 2 === 0 ? '1px solid' : 0, lg: index < metricGroups.length - 1 ? '1px solid' : 0 },
                  borderBottom: { xs: index < 2 ? '1px solid' : 0, lg: 0 },
                  borderColor: 'divider',
                  minWidth: 0,
                }}
              >
                <Stack direction="row" spacing={0.75} alignItems="center" color="text.secondary">
                  {metric.icon}
                  <Typography variant="caption">{metric.label}</Typography>
                </Stack>
                <Typography variant="h2" sx={{ mt: 0.75 }}>{metric.value}</Typography>
                <Typography variant="caption" color="text.secondary">{metric.detail}</Typography>
              </Box>
            ))}
          </Box>
        </CardContent>
      </Card>

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', xl: '1.35fr 0.85fr' }, gap: 2 }}>
        <Card>
          <CardContent>
            <SectionHeading
              icon={<WorkIcon />}
              title="最新决策建议"
              detail="候选评分与研究/风险门禁后的最终结果"
              action={<Button size="small" endIcon={<ArrowForwardIcon />} onClick={() => onNavigate('autonomous')}>查看完整结果</Button>}
            />
            <Stack divider={<Divider />} spacing={0}>
              {decisions.length === 0 && <EmptyState text="自动研究尚未生成产品建议。" />}
              {decisions.slice(0, 4).map((item, index) => (
                <CompactRecommendationRow key={`${item.provider}-${item.symbol}-${index}`} item={item} />
              ))}
            </Stack>
          </CardContent>
        </Card>

        <Card>
          <CardContent>
            <SectionHeading
              icon={<TimelineIcon />}
              title={`自动研究进度 (${completedSteps}/${latestRun?.steps?.length || 0})`}
              detail={latestRun ? `${triggerTypeText(latestRun.trigger_type)} · ${shortId(latestRun.loop_run_id)}` : '暂无运行记录'}
            />
            <Stack divider={<Divider />} spacing={0}>
              {!latestRun?.steps?.length && <EmptyState text="暂无自动研究步骤。" />}
              {latestRun?.steps?.slice(-6).map((step) => <CompactStepRow key={step.step_id} step={step} />)}
            </Stack>
          </CardContent>
        </Card>
      </Box>

      {jobs.length > 0 && <RecentJobs jobs={jobs.slice(0, 5)} />}
    </Stack>
  );
}

export function AutonomousPanel({
  autonomous,
  onRefresh,
}: {
  autonomous: AutonomousStatusPayload | null;
  onRefresh: () => Promise<void>;
}) {
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [panelError, setPanelError] = useState<string | null>(null);
  const [view, setView] = useState<AutonomousView>('results');
  const scheduler = autonomous?.scheduler;
  const latestRun = autonomous?.recent_runs?.[0] || null;
  const resultRun = autonomous?.recent_runs?.find((run) => run.loop_run_id === autonomous.latest_result_loop_run_id) || null;
  const readiness = autonomous?.latest_decision_readiness || resultRun?.decision_readiness || null;
  const decisions = preferredRecommendations(autonomous);
  const worker = autonomous?.worker;
  const pendingRequestCount = (worker?.queue?.queued || 0) + (worker?.queue?.running || 0);
  const activeWorkerCount = worker?.workers?.filter((item) => item.active).length || 0;
  const completedSteps = latestRun?.steps?.filter((step) => ['passed', 'succeeded', 'completed'].includes(step.status)).length || 0;
  const duration = numericValue(latestRun?.summary, 'total_duration_ms');

  return (
    <Stack spacing={2}>
      <Card>
        <CardContent>
          <Stack direction={{ xs: 'column', lg: 'row' }} spacing={2} alignItems={{ xs: 'stretch', lg: 'center' }}>
            <Stack direction="row" spacing={1} alignItems="center" sx={{ minWidth: 190 }}>
              <AutorenewIcon color="primary" />
              <Box>
                <Typography variant="h3">自动研究任务</Typography>
                <Typography variant="caption" color="text.secondary">
                  {scheduler?.running ? '本轮正在执行' : scheduler?.enabled ? '调度服务已启用，当前空闲' : '调度服务未启用'}
                </Typography>
              </Box>
            </Stack>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: 'repeat(6, minmax(0, 1fr))' }, gap: 1.5, flexGrow: 1 }}>
              <CompactFact label="最近状态" value={statusText(latestRun?.status || scheduler?.status)} chipStatus={latestRun?.status || scheduler?.status} />
              <CompactFact label="决策就绪度" value={decisionReadinessText(readiness?.status)} chipStatus={readiness?.status} />
              <CompactFact label="运行进度" value={`${completedSteps}/${latestRun?.steps?.length || 0} 步`} />
              <CompactFact label="本轮耗时" value={scheduler?.running ? '正在计时' : durationText(duration)} />
              <CompactFact label="在线 Worker" value={String(activeWorkerCount)} />
              <CompactFact label="下次运行" value={formatTime(scheduler?.next_run_at)} />
            </Box>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
              <Button variant="outlined" startIcon={<RefreshIcon />} onClick={onRefresh} disabled={submitting}>刷新</Button>
              <Button
                variant="contained"
                startIcon={submitting ? <CircularProgress size={16} color="inherit" /> : <PlayArrowIcon />}
                disabled={submitting || Boolean(scheduler?.running) || pendingRequestCount > 0}
                onClick={async () => {
                  setSubmitting(true);
                  setPanelError(null);
                  setMessage(null);
                  try {
                    await api.runAutonomousNow({ trigger_type: 'manual-web' });
                    setMessage('已提交自动研究任务，常驻 Worker 将在后台执行。');
                    await onRefresh();
                  } catch (error) {
                    setPanelError(error instanceof Error ? error.message : String(error));
                  } finally {
                    setSubmitting(false);
                  }
                }}
              >
                立即运行一轮
              </Button>
            </Stack>
          </Stack>
        </CardContent>
      </Card>

      {message && <Alert severity="success">{message}</Alert>}
      {panelError && <Alert severity="error">{panelError}</Alert>}
      <Alert severity={readiness?.status === 'ready' ? 'success' : readiness?.status === 'blocked' ? 'error' : 'warning'} icon={readiness?.status === 'ready' ? <CheckCircleIcon /> : <ShieldIcon />}>
        <Typography variant="subtitle1">
          {decisionReadinessText(readiness?.status)}
        </Typography>
        <Typography variant="body2">
          {readiness?.reasons?.length
            ? readiness.reasons.map(decisionReadinessReasonText).join(' ')
            : readiness?.decision_ready
              ? `本轮 ${readiness.directional_recommendation_count} 条方向性建议已通过行情、研究和风险门禁，仍需人工复核后才能模拟执行。`
              : '等待新一轮运行生成结构化决策就绪度。'}
        </Typography>
      </Alert>

      <Tabs
        value={view}
        onChange={(_, value: AutonomousView) => setView(value)}
        variant="scrollable"
        scrollButtons="auto"
        sx={{ borderBottom: '1px solid', borderColor: 'divider', minHeight: 40 }}
      >
        <Tab value="results" label="本轮结果" />
        <Tab value="process" label="执行过程" />
        <Tab value="debate" label="多轮辩论" />
        <Tab value="governance" label="风险与成本" />
      </Tabs>

      {view === 'results' && <AutonomousResultsView autonomous={autonomous} decisions={decisions} />}
      {view === 'process' && <AutonomousProcessView autonomous={autonomous} />}
      {view === 'debate' && <DebateExplorer debates={autonomous?.latest_ai_debates || []} />}
      {view === 'governance' && <GovernanceView autonomous={autonomous} />}
    </Stack>
  );
}

export function OperatorPanel({
  status,
  autonomous,
  jobs,
  onSubmitted,
  onNavigate,
}: {
  status: StatusPayload | null;
  autonomous: AutonomousStatusPayload | null;
  jobs: JobRecord[];
  onSubmitted: () => void;
  onNavigate: Navigate;
}) {
  const [symbols, setSymbols] = useState('BTCUSDT');
  const [providers, setProviders] = useState('gate');
  const [intervals, setIntervals] = useState('1h,4h,1d');
  const [startBridges, setStartBridges] = useState(true);
  const [defaultsApplied, setDefaultsApplied] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [panelError, setPanelError] = useState<string | null>(null);
  const decisions = preferredRecommendations(autonomous);
  const recentJob = jobs.find((job) => job.kind === 'operator-workbench');

  useEffect(() => {
    if (defaultsApplied || !autonomous) {
      return;
    }
    const config = autonomous.config || {};
    if (Array.isArray(config.symbols) && config.symbols.length > 0) {
      setSymbols(config.symbols.map(String).join(','));
    }
    if (Array.isArray(config.providers) && config.providers.length > 0) {
      setProviders(config.providers.map(String).join(','));
    }
    if (Array.isArray(config.intervals) && config.intervals.length > 0) {
      setIntervals(config.intervals.map(String).join(','));
    }
    setDefaultsApplied(true);
  }, [autonomous, defaultsApplied]);

  return (
    <Stack spacing={2}>
      <Card>
        <CardContent>
          <SectionHeading
            icon={<WorkIcon />}
            title="运行快速市场分析"
            detail="只读取公共行情，不执行 AI 辩论或模拟交易"
            action={
              <Button
                variant="contained"
                startIcon={submitting ? <CircularProgress size={16} color="inherit" /> : <PlayArrowIcon />}
                disabled={submitting}
                onClick={async () => {
                  setSubmitting(true);
                  setMessage(null);
                  setPanelError(null);
                  try {
                    const result = await api.submitOperatorWorkbench({
                      symbols: splitCsv(symbols),
                      providers: splitCsv(providers),
                      intervals: splitCsv(intervals),
                      candle_limit: 5,
                      start_bridges: startBridges,
                      persist: true,
                    });
                    setMessage(`任务 ${shortId(result.job.job_id)} 已提交，结果将在后台生成。`);
                    onSubmitted();
                  } catch (error) {
                    setPanelError(error instanceof Error ? error.message : String(error));
                  } finally {
                    setSubmitting(false);
                  }
                }}
              >
                开始分析
              </Button>
            }
          />
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' }, gap: 1.5 }}>
            <TextField label="交易标的" value={symbols} onChange={(event) => setSymbols(event.target.value)} helperText="多个标的用逗号分隔" />
            <TextField label="行情来源" value={providers} onChange={(event) => setProviders(event.target.value)} helperText="默认使用已启用的公共交易所" />
            <TextField label="分析周期" value={intervals} onChange={(event) => setIntervals(event.target.value)} helperText="例如 1h,4h,1d" />
          </Box>
          <FormControlLabel sx={{ mt: 1 }} control={<Switch checked={startBridges} onChange={(event) => setStartBridges(event.target.checked)} />} label="按代理策略访问交易所" />
        </CardContent>
      </Card>
      {message && <Alert severity="success">{message}</Alert>}
      {panelError && <Alert severity="error">{panelError}</Alert>}

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', xl: '1.35fr 0.65fr' }, gap: 2 }}>
        <Card>
          <CardContent>
            <SectionHeading
              icon={<ShieldIcon />}
              title="自动研究最新建议"
              detail="来自完整 AI 辩论与风险门禁，不是本页快速分析结果"
              action={<Button size="small" endIcon={<ArrowForwardIcon />} onClick={() => onNavigate('autonomous')}>查看自动研究</Button>}
            />
            <DecisionList items={decisions.slice(0, 4)} compact />
          </CardContent>
        </Card>
        <Card>
          <CardContent>
            <SectionHeading icon={<TimelineIcon />} title="最近市场分析任务" detail="快速分析的报告与后台执行状态" />
            <Stack spacing={1.25}>
              <KeyValue label="报告状态" value={statusText(status?.latest_advisory_report?.status)} chipStatus={status?.latest_advisory_report?.status} />
              <KeyValue label="报告时间" value={formatTime(status?.latest_advisory_report?.generated_at)} />
              <KeyValue label="建议数量" value={String(status?.latest_advisory_report?.summary?.advice_count ?? 0)} />
              <Divider />
              <KeyValue label="后台任务" value={recentJob ? jobStatusText(recentJob.status) : '暂无任务'} chipStatus={recentJob?.status} />
              <KeyValue label="任务编号" value={shortId(recentJob?.job_id)} />
            </Stack>
          </CardContent>
        </Card>
      </Box>
    </Stack>
  );
}

export function ResearchPanel({
  status,
  jobs,
  onSubmitted,
}: {
  status: StatusPayload | null;
  jobs: JobRecord[];
  onSubmitted: () => void;
}) {
  const [mode, setMode] = useState<'dry-run' | 'run'>('dry-run');
  const [runIngestion, setRunIngestion] = useState(false);
  const [runAiCompression, setRunAiCompression] = useState(false);
  const [runFollowups, setRunFollowups] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [panelError, setPanelError] = useState<string | null>(null);
  const latestRun = status?.latest_pipeline_run;
  const recentJob = jobs.find((job) => job.kind === 'research-pipeline');
  const summary = latestRun?.summary || {};
  const passedStepCount = Array.isArray(summary.passed_steps) ? summary.passed_steps.length : 0;
  const failedStepCount = Array.isArray(summary.failed_steps) ? summary.failed_steps.length : 0;

  return (
    <Stack spacing={2}>
      <Card>
        <CardContent>
          <SectionHeading
            icon={<ScienceIcon />}
            title="手动采集与处理"
            detail="按需运行信息采集、AI 压缩和补证据；完整流程请使用“自动研究”"
            action={
              <Button
                variant="contained"
                startIcon={submitting ? <CircularProgress size={16} color="inherit" /> : <PlayArrowIcon />}
                disabled={submitting}
                onClick={async () => {
                  setSubmitting(true);
                  setMessage(null);
                  setPanelError(null);
                  try {
                    const result = await api.submitResearchPipeline({
                      dry_run: mode === 'dry-run',
                      run_ingestion: runIngestion,
                      run_ai_compression: runAiCompression,
                      ai_compression_dry_run: !runAiCompression,
                      run_followups: runFollowups,
                      followups_dry_run: !runFollowups,
                      profile: 'phase7-web-ui',
                    });
                    setMessage(`任务 ${shortId(result.job.job_id)} 已提交。`);
                    onSubmitted();
                  } catch (error) {
                    setPanelError(error instanceof Error ? error.message : String(error));
                  } finally {
                    setSubmitting(false);
                  }
                }}
              >
                提交处理任务
              </Button>
            }
          />
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.75 }}>执行模式</Typography>
          <ToggleButtonGroup
            exclusive
            value={mode}
            onChange={(_, value: 'dry-run' | 'run' | null) => value && setMode(value)}
            size="small"
            sx={{ mb: 2 }}
          >
            <ToggleButton value="dry-run">安全演练</ToggleButton>
            <ToggleButton value="run">真实执行</ToggleButton>
          </ToggleButtonGroup>
          <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, minmax(0, 1fr))' }, gap: 1 }}>
            <OptionToggle label="执行信息采集" detail="按信源目录发起网络采集" checked={runIngestion} onChange={setRunIngestion} />
            <OptionToggle label="执行 AI 压缩" detail="调用已配置模型压缩证据" checked={runAiCompression} onChange={setRunAiCompression} />
            <OptionToggle label="执行补证据任务" detail="处理已排队的研究缺口" checked={runFollowups} onChange={setRunFollowups} />
          </Box>
        </CardContent>
      </Card>
      {mode === 'run' && <Alert severity="info">真实执行会访问已启用的信息源和 AI 站点，并继续遵守代理池与预算门禁。</Alert>}
      {message && <Alert severity="success">{message}</Alert>}
      {panelError && <Alert severity="error">{panelError}</Alert>}

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '1.2fr 0.8fr' }, gap: 2 }}>
        <Card>
          <CardContent>
            <SectionHeading icon={<TimelineIcon />} title="最近处理任务" detail={latestRun ? `运行 ${shortId(latestRun.run_id)}` : '暂无运行记录'} />
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', sm: 'repeat(4, minmax(0, 1fr))' }, gap: 1.5, mb: 1.5 }}>
              <SummaryMetric label="状态" value={statusText(latestRun?.status)} chipStatus={latestRun?.status} />
              <SummaryMetric label="通过步骤" value={passedStepCount ? String(passedStepCount) : '-'} />
              <SummaryMetric label="失败步骤" value={String(failedStepCount)} />
              <SummaryMetric label="完成时间" value={formatTime(latestRun?.finished_at)} />
            </Box>
            {latestRun?.error && <Alert severity="error">{latestRun.error}</Alert>}
            {!latestRun?.error && (
              <Typography variant="body2" color="text.secondary">
                {latestRun ? `本轮采集与处理已${statusText(latestRun.status)}。详细步骤与产物可在运行报告中审计。` : '提交任务后，这里会显示最近一次运行结果。'}
              </Typography>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardContent>
            <SectionHeading icon={<AutorenewIcon />} title="后台任务" detail="任务提交与队列状态" />
            <Stack spacing={1.25}>
              <KeyValue label="任务状态" value={recentJob ? jobStatusText(recentJob.status) : '暂无任务'} chipStatus={recentJob?.status} />
              <KeyValue label="任务编号" value={shortId(recentJob?.job_id)} />
              <KeyValue label="提交时间" value={formatTime(recentJob?.created_at)} />
              <KeyValue label="完成时间" value={formatTime(recentJob?.finished_at)} />
              {recentJob?.error && <Alert severity="error">{recentJob.error}</Alert>}
            </Stack>
          </CardContent>
        </Card>
      </Box>
    </Stack>
  );
}

export function ReportsPanel() {
  const [reportKind, setReportKind] = useState('operator-workbench');
  const [report, setReport] = useState<Record<string, unknown> | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadReport = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setReport(await api.latestReport(reportKind));
    } catch (loadError) {
      setReport(null);
      setError(loadError instanceof Error ? loadError.message : String(loadError));
    } finally {
      setLoading(false);
    }
  }, [reportKind]);

  useEffect(() => {
    void loadReport();
  }, [loadReport]);

  return (
    <Stack spacing={2}>
      <Card>
        <CardContent>
          <SectionHeading
            icon={<ArticleIcon />}
            title="运行报告与审计"
            detail={reportKindDescription(reportKind)}
            action={
              <Button variant="outlined" startIcon={loading ? <CircularProgress size={16} /> : <RefreshIcon />} onClick={loadReport} disabled={loading}>
                刷新报告
              </Button>
            }
          />
          <TextField
            select
            label="报告类型"
            value={reportKind}
            onChange={(event) => setReportKind(event.target.value)}
            sx={{ width: { xs: '100%', sm: 280 } }}
          >
            <MenuItem value="autonomous-loop">自动研究报告</MenuItem>
            <MenuItem value="operator-workbench">市场分析报告</MenuItem>
            <MenuItem value="research-pipeline">采集与处理报告</MenuItem>
            <MenuItem value="ingestion-status">采集状态报告</MenuItem>
            <MenuItem value="recommendation-evaluation">建议历史评估</MenuItem>
            <MenuItem value="portfolio-risk">组合风险报告</MenuItem>
            <MenuItem value="ai-governance">AI 治理报告</MenuItem>
          </TextField>
        </CardContent>
      </Card>
      {error && <Alert severity="error">{error}</Alert>}
      {loading && !report && (
        <Card><CardContent><Stack direction="row" spacing={1.5} alignItems="center"><CircularProgress size={20} /><Typography variant="body2">正在载入最近报告</Typography></Stack></CardContent></Card>
      )}
      {report && <HumanReport reportKind={reportKind} report={report} />}
    </Stack>
  );
}

function AutonomousResultsView({
  autonomous,
  decisions,
}: {
  autonomous: AutonomousStatusPayload | null;
  decisions: ProductRecommendation[];
}) {
  const universe = autonomous?.latest_universe;
  return (
    <Stack spacing={2}>
      <Card>
        <CardContent>
          <SectionHeading
            icon={<ShieldIcon />}
            title="最终产品建议"
            detail="按候选评分排序，并经过研究确认与风险门禁"
          />
          <Alert severity="info" icon={<InfoOutlinedIcon />} sx={{ mb: 1.5 }}>
            候选评分衡量进入研究范围的优先级；门禁后置信度才表示系统对最终建议的信心。两者不能直接比较。
          </Alert>
          <DecisionList items={decisions} />
        </CardContent>
      </Card>
      <Accordion disableGutters elevation={0} sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, '&:before': { display: 'none' } }}>
        <AccordionSummary expandIcon={<ExpandMoreIcon />}>
          <Box>
            <Typography variant="subtitle1">本轮产品 Universe</Typography>
            <Typography variant="caption" color="text.secondary">已验证并参与候选筛选的 {universe?.instruments?.length || 0} 个产品</Typography>
          </Box>
        </AccordionSummary>
        <AccordionDetails sx={{ pt: 0 }}>
          <Stack divider={<Divider />} spacing={0}>
            {!universe?.instruments?.length && <EmptyState text="暂无已验证产品。" />}
            {universe?.instruments?.map((instrument) => (
              <Box key={instrument.instrument_id} sx={{ display: 'grid', gridTemplateColumns: { xs: '34px 1fr', sm: '34px minmax(140px, 0.8fr) 1fr auto' }, gap: 1, alignItems: 'center', py: 0.9 }}>
                <Typography variant="caption" color="text.secondary">#{instrument.rank_index}</Typography>
                <Typography variant="body2" sx={{ fontWeight: 700 }}>{instrument.symbol} · {instrument.provider}</Typography>
                <Typography variant="caption" color="text.secondary">{instrument.sources.join(' / ') || '无来源标签'}</Typography>
                <Chip size="small" variant="outlined" label={instrument.contract ? '合约' : '现货'} />
              </Box>
            ))}
          </Stack>
        </AccordionDetails>
      </Accordion>
    </Stack>
  );
}

function AutonomousProcessView({ autonomous }: { autonomous: AutonomousStatusPayload | null }) {
  const latestRun = autonomous?.recent_runs?.[0] || null;
  const worker = autonomous?.worker;
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', xl: '1.25fr 0.75fr' }, gap: 2 }}>
      <Card>
        <CardContent>
          <SectionHeading
            icon={<TimelineIcon />}
            title="本轮执行步骤"
            detail={latestRun ? `${formatTime(latestRun.started_at)} 开始 · ${shortId(latestRun.loop_run_id)}` : '暂无运行记录'}
          />
          <Stack divider={<Divider />} spacing={0}>
            {!latestRun?.steps?.length && <EmptyState text="暂无执行步骤。" />}
            {latestRun?.steps?.map((step) => <DetailedStepRow key={step.step_id} step={step} />)}
          </Stack>
        </CardContent>
      </Card>
      <Card>
        <CardContent>
          <SectionHeading icon={<AutorenewIcon />} title="Worker 与队列" detail="常驻服务和最近触发请求" />
          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 1, mb: 1.5 }}>
            <SummaryMetric label="在线" value={String(worker?.workers?.filter((item) => item.active).length || 0)} />
            <SummaryMetric label="排队" value={String(worker?.queue?.queued || 0)} />
            <SummaryMetric label="运行" value={String(worker?.queue?.running || 0)} />
          </Box>
          <Stack divider={<Divider />} spacing={0}>
            {!worker?.recent_requests?.length && <EmptyState text="暂无队列请求。" />}
            {worker?.recent_requests?.slice(0, 6).map((request) => (
              <Box key={request.request_id} sx={{ py: 1 }}>
                <Stack direction="row" spacing={1} alignItems="center">
                  <Chip size="small" color={statusColor(request.status)} label={statusText(request.status)} />
                  <Typography variant="body2" sx={{ fontWeight: 700, flexGrow: 1 }}>{triggerTypeText(request.trigger_type)}</Typography>
                  <Typography variant="caption" color="text.secondary">{formatTime(request.requested_at)}</Typography>
                </Stack>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                  {shortId(request.loop_run_id || request.request_id)}{request.error ? ` · ${request.error}` : ''}
                </Typography>
              </Box>
            ))}
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}

function DebateExplorer({ debates }: { debates: AIDebateCouncil[] }) {
  const debate = debates[0];
  const [selectedStage, setSelectedStage] = useState('chair');
  useEffect(() => setSelectedStage('chair'), [debate?.debate_id]);

  if (!debate) {
    return <Card><CardContent><EmptyState text="暂无 AI 辩论记录。" /></CardContent></Card>;
  }

  const rounds = Array.from(new Set(debate.messages.map((message) => message.round_index).filter((value): value is number => typeof value === 'number' && value <= debate.rounds))).sort((a, b) => a - b);
  const selectedRound = Number(selectedStage.replace('round-', ''));
  const roundMessages = Number.isFinite(selectedRound) ? debate.messages.filter((message) => message.round_index === selectedRound) : [];
  const chair = debate.messages.find((message) => message.agent_role === 'chair_arbiter' || message.phase_id === 'chair_synthesis');

  return (
    <Card>
      <CardContent>
        <SectionHeading
          icon={<PsychologyIcon />}
          title="真实多轮辩论"
          detail={`${debate.rounds} 轮分析 · ${debate.messages.length} 条消息 · 辩论 ${shortId(debate.debate_id)}`}
          action={<Chip size="small" color={statusColor(debate.status)} label={statusText(debate.status)} />}
        />
        <Tabs
          value={selectedStage}
          onChange={(_, value: string) => setSelectedStage(value)}
          variant="scrollable"
          scrollButtons="auto"
          sx={{ borderBottom: '1px solid', borderColor: 'divider', mb: 1.5 }}
        >
          <Tab value="chair" label="主席结论" />
          {rounds.map((round) => {
            const phase = debate.messages.find((message) => message.round_index === round)?.phase_id;
            return <Tab key={round} value={`round-${round}`} label={`第 ${round} 轮 · ${councilPhaseText(phase)}`} />;
          })}
        </Tabs>
        {selectedStage === 'chair'
          ? <ChairSummary debate={debate} chair={chair} />
          : <RoundMessages debate={debate} round={selectedRound} messages={roundMessages} />}
      </CardContent>
    </Card>
  );
}

function ChairSummary({ debate, chair }: { debate: AIDebateCouncil; chair?: AIDebateMessage }) {
  if (!chair) {
    return (
      <Alert severity="info" icon={<AutorenewIcon />}>
        <Typography variant="subtitle1">辩论正在进行，主席尚未仲裁</Typography>
        <Typography variant="body2">当前已生成 {debate.messages.length} 条角色消息。完整三轮分析结束后，这里会显示主席结论、共识、分歧和缺失证据。</Typography>
      </Alert>
    );
  }
  const content = chair?.content || {};
  const summaries = stringArray(content.debate_summary).map(humanizeAiText);
  const chairDecisions = recordArray(content.decisions);
  const disagreementRows = deriveDisagreements(debate.messages);

  return (
    <Stack spacing={1.5}>
      <Alert severity="warning" icon={<ShieldIcon />}>
        <Typography variant="subtitle1">当前不建议建立方向性仓位</Typography>
        <Typography variant="body2">主席确认所有候选仍缺少研究验证，最终建议已降级为观察或观望。</Typography>
      </Alert>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'repeat(3, minmax(0, 1fr))' }, gap: 1.5 }}>
        <InsightBlock tone="success" title="共识" items={summaries.length ? summaries : ['各角色已完成三轮分析并提交主席仲裁。']} />
        <InsightBlock tone="warning" title="主要分歧" items={disagreementRows.length ? disagreementRows : ['当前未检测到结构化立场分歧。']} />
        <InsightBlock tone="info" title="尚缺证据" items={['候选缺少独立研究确认。', '需要完成补证据任务并重新运行研究门禁。']} />
      </Box>
      {chairDecisions.length > 0 && (
        <Box>
          <Typography variant="subtitle1" sx={{ mb: 0.75 }}>主席最终判断</Typography>
          <Stack divider={<Divider />} spacing={0}>
            {chairDecisions.map((decision, index) => (
              <Box key={`${decision.symbol}-${index}`} sx={{ display: 'grid', gridTemplateColumns: { xs: 'auto 1fr', sm: '80px minmax(120px, 0.6fr) 90px 90px 1fr' }, gap: 1, alignItems: 'center', py: 0.9 }}>
                <Chip size="small" color={recommendationColor(String(decision.action || ''))} label={recommendationActionText(String(decision.action || ''))} />
                <Typography variant="body2" sx={{ fontWeight: 700 }}>{String(decision.symbol || '-')}</Typography>
                <Typography variant="caption">候选 {numberText(toNumber(decision.score), 0)}</Typography>
                <Typography variant="caption">最终 {Math.round((toNumber(decision.confidence) || 0) * 100)}%</Typography>
                <Typography variant="caption" color="text.secondary">{humanizeAiText(stringArray(decision.rationale)[0])}</Typography>
              </Box>
            ))}
          </Stack>
        </Box>
      )}
      <RawDisclosure label="查看主席模型原文" value={content} />
    </Stack>
  );
}

function RoundMessages({ debate, round, messages }: { debate: AIDebateCouncil; round: number; messages: AIDebateMessage[] }) {
  const roundSummary = debate.round_summaries?.find((summary) => Number(summary.round_index) === round);
  return (
    <Stack spacing={1.25}>
      {roundSummary && (
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ xs: 'flex-start', sm: 'center' }}>
          <Chip size="small" color={Boolean(roundSummary.quorum_met) ? 'success' : 'warning'} label={Boolean(roundSummary.quorum_met) ? '法定人数已满足' : '法定人数不足'} />
          <Typography variant="caption" color="text.secondary">
            {String(roundSummary.scheduling_mode || '-') === 'round_robin' ? '轮流发言' : '同层并行'} · {String(roundSummary.completed_message_count || 0)}/{String(roundSummary.message_count || 0)} 条完成
          </Typography>
        </Stack>
      )}
      <Stack divider={<Divider />} spacing={0}>
        {messages.map((message, index) => (
          <RoleMessage key={message.message_id || `${message.agent_role}-${index}`} debate={debate} message={message} />
        ))}
      </Stack>
    </Stack>
  );
}

function RoleMessage({ debate, message }: { debate: AIDebateCouncil; message: AIDebateMessage }) {
  const [expanded, setExpanded] = useState(false);
  const content = message.content || {};
  const summary = humanizeAiText(content.overall_view || stringArray(content.debate_summary)[0] || message.error || '-');
  const previousMessage = debate.messages
    .filter((candidate) => candidate.agent_role === message.agent_role && Number(candidate.round_index || 0) < Number(message.round_index || 0))
    .sort((a, b) => Number(b.round_index || 0) - Number(a.round_index || 0))[0];
  const changed = previousMessage ? assessmentSignature(previousMessage) !== assessmentSignature(message) : null;
  const assessments = recordArray(content.candidate_assessments);
  const questions = stringArray(content.questions_for_other_agents);

  return (
    <Accordion expanded={expanded} onChange={(_, nextExpanded) => setExpanded(nextExpanded)} disableGutters elevation={0} sx={{ '&:before': { display: 'none' } }}>
      <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ px: 0, '& .MuiAccordionSummary-content': { my: 1 } }}>
        <Box sx={{ width: '100%', display: 'grid', gridTemplateColumns: { xs: '1fr', md: '140px 90px minmax(0, 1fr) 100px 160px' }, gap: 1, alignItems: 'center', pr: 1 }}>
          <Stack direction="row" spacing={0.75} alignItems="center">
            <Chip size="small" color={statusColor(message.status)} label={statusText(message.status)} />
            <Typography variant="body2" sx={{ fontWeight: 700 }}>{agentRoleText(message.agent_role)}</Typography>
          </Stack>
          <Typography variant="caption" color="text.secondary">{stanceText(message.stance)}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>{summary}</Typography>
          <Typography variant="caption" color={changed ? 'warning.main' : 'text.secondary'}>{changed === null ? '初始立场' : changed ? '判断有调整' : '维持判断'}</Typography>
          <Typography variant="caption" color="text.secondary">引用 {message.reply_to_message_ids?.length || 0} · {message.provider || '-'} / {message.model || '-'}</Typography>
        </Box>
      </AccordionSummary>
      {expanded && <AccordionDetails sx={{ px: 0, pt: 0, pb: 1.5 }}>
        <Box sx={{ pl: { xs: 0, md: 2 }, borderLeft: { md: '2px solid' }, borderColor: { md: 'divider' } }}>
          {assessments.length > 0 && (
            <Stack divider={<Divider />} spacing={0}>
              {assessments.map((assessment, index) => (
                <Box key={`${assessment.symbol}-${index}`} sx={{ py: 0.8 }}>
                  <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.35 }}>
                    <Typography variant="body2" sx={{ fontWeight: 700 }}>{String(assessment.symbol || '-')}</Typography>
                    <Chip size="small" variant="outlined" label={recommendationActionText(String(assessment.action_bias || ''))} />
                    <Typography variant="caption" color="text.secondary">原始信心 {Math.round((toNumber(assessment.confidence) || 0) * 100)}%</Typography>
                  </Stack>
                  <Typography variant="caption" color="text.secondary">支持：{humanizeAiText(stringArray(assessment.arguments)[0]) || '未提供'}</Typography>
                  <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>反证：{humanizeAiText(stringArray(assessment.counter_arguments)[0]) || '未提供'}</Typography>
                </Box>
              ))}
            </Stack>
          )}
          {questions.length > 0 && (
            <Box sx={{ mt: 1 }}>
              <Typography variant="caption" sx={{ fontWeight: 700 }}>向其他角色提出的问题</Typography>
              {questions.slice(0, 3).map((question) => <Typography key={question} variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.25 }}>· {humanizeAiText(question)}</Typography>)}
            </Box>
          )}
          <RawDisclosure label="查看该角色模型原文" value={content} />
        </Box>
      </AccordionDetails>}
    </Accordion>
  );
}

function GovernanceView({ autonomous }: { autonomous: AutonomousStatusPayload | null }) {
  const evaluation = autonomous?.latest_evaluation;
  const portfolioRisk = autonomous?.latest_portfolio_risk;
  const governance = autonomous?.latest_ai_governance;
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', xl: 'repeat(3, minmax(0, 1fr))' }, gap: 2 }}>
      <Card>
        <CardContent>
          <SectionHeading icon={<TimelineIcon />} title="建议历史评估" detail="当前样本的成熟度与校准状态" />
          <Stack spacing={1.2}>
            <KeyValue label="已评估样本" value={String(evaluation?.summary?.evaluated_count ?? 0)} />
            <KeyValue label="方向样本" value={String(evaluation?.summary?.directional_sample_count ?? 0)} />
            <KeyValue label="方向命中率" value={percentText(evaluation?.summary?.directional_hit_rate, true)} />
            <KeyValue label="平均方向收益" value={percentText(evaluation?.summary?.average_directional_return_pct)} />
            <KeyValue label="校准误差 ECE" value={percentText(evaluation?.summary?.expected_calibration_error, true)} />
          </Stack>
          {(evaluation?.summary?.directional_sample_count ?? 0) === 0 && <Alert severity="info" sx={{ mt: 1.5 }}>当前没有成熟的方向性样本，命中率和收益指标暂不计算。</Alert>}
        </CardContent>
      </Card>
      <Card>
        <CardContent>
          <SectionHeading icon={<ShieldIcon />} title="组合风险" detail="当前建议组合的风险暴露" />
          <Stack spacing={1.2}>
            <KeyValue label="风险状态" value={statusText(portfolioRisk?.summary?.risk_status)} chipStatus={portfolioRisk?.summary?.risk_status} />
            <KeyValue label="方向性暴露" value={String(portfolioRisk?.summary?.directional_exposure_count ?? 0)} />
            <KeyValue label="单产品集中度" value={percentText(portfolioRisk?.summary?.largest_product_concentration_pct)} />
            <KeyValue label="相关簇集中度" value={percentText(portfolioRisk?.summary?.largest_correlated_cluster_concentration_pct)} />
            <KeyValue label="最差压力损失" value={percentText(portfolioRisk?.summary?.worst_hypothetical_stress_loss_pct)} />
          </Stack>
        </CardContent>
      </Card>
      <Card>
        <CardContent>
          <SectionHeading icon={<PsychologyIcon />} title="AI 治理" detail="调用、成本与证据覆盖" />
          <Stack spacing={1.2}>
            <KeyValue label="治理状态" value={statusText(governance?.summary?.governance_status)} chipStatus={governance?.summary?.governance_status} />
            <KeyValue label="成功调用" value={`${governance?.summary?.successful_invocation_count ?? 0}/${governance?.summary?.invocation_count ?? 0}`} />
            <KeyValue label="Token" value={compactNumber(governance?.summary?.total_tokens ?? 0)} />
            <KeyValue label="估算成本" value={governance?.summary?.cost_status === 'known' ? `$${numberText(governance?.summary?.estimated_cost_usd)}` : '费率未知'} />
            <KeyValue label="Claim 证据覆盖" value={percentText(governance?.summary?.claim_evidence_coverage, true)} />
          </Stack>
        </CardContent>
      </Card>
    </Box>
  );
}

export function DecisionList({ items, compact = false }: { items: ProductRecommendation[]; compact?: boolean }) {
  if (items.length === 0) {
    return <EmptyState text="暂无最终产品建议。" />;
  }
  return (
    <Stack divider={<Divider />} spacing={0}>
      {items.map((item, index) => <DecisionItem key={`${item.provider}-${item.symbol}-${index}`} item={item} compact={compact} />)}
    </Stack>
  );
}

function DecisionItem({ item, compact }: { item: ProductRecommendation; compact: boolean }) {
  const narrative = recommendationNarrative(item);
  const decision = item as AITradeDecision;
  const evidenceRefs = Array.isArray(decision.evidence_refs) ? decision.evidence_refs : [];
  return (
    <Box sx={{ py: compact ? 1 : 1.35 }}>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '76px minmax(130px, 0.6fr) minmax(110px, 0.5fr) minmax(120px, 0.5fr) minmax(130px, 0.7fr)' }, gap: 1, alignItems: 'center' }}>
        <Chip size="small" color={recommendationColor(item.action, item.status)} label={recommendationActionText(item.action)} sx={{ width: 'fit-content' }} />
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" sx={{ fontWeight: 800 }}>{item.symbol || item.normalized_symbol || '-'}</Typography>
          <Typography variant="caption" color="text.secondary">{item.provider || '-'} · {item.market_type || '-'}</Typography>
        </Box>
        <ScoreCell label="候选评分" value={item.score} />
        <ScoreCell label="门禁后置信度" value={(item.confidence || 0) * 100} warning={item.score > 0 && item.confidence === 0} />
        <Stack direction="row" spacing={0.5} alignItems="center">
          <Chip size="small" variant="outlined" color={String(item.research_context?.status || '') === 'needs-followup' ? 'warning' : 'success'} label={narrative.evidenceState} />
          <Tooltip title={narrative.gateExplanation}><InfoOutlinedIcon color="action" sx={{ fontSize: 17 }} /></Tooltip>
        </Stack>
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1.35fr 1fr 1fr' }, gap: 1.25, mt: 1 }}>
        <DecisionText label="结论" value={narrative.primaryReason} />
        <DecisionText label="主要风险" value={narrative.risk} tone="warning.main" />
        <DecisionText label="下一步" value={narrative.nextAction} />
      </Box>
      {!compact && (
        <Accordion disableGutters elevation={0} sx={{ mt: 0.5, '&:before': { display: 'none' }, bgcolor: 'transparent' }}>
          <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ minHeight: 34, px: 0, '& .MuiAccordionSummary-content': { my: 0.5 } }}>
            <Typography variant="caption" color="primary.main" sx={{ fontWeight: 700 }}>查看依据与审计信息</Typography>
          </AccordionSummary>
          <AccordionDetails sx={{ px: 0, pt: 0 }}>
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' }, gap: 1.5 }}>
              <AuditList title="模型依据" values={(item.rationale || []).map(humanizeAiText)} />
              <AuditList title="风险提示" values={(item.risk_warnings || []).map(humanizeAiText)} />
              <AuditList title="证据引用" values={evidenceRefs.length ? evidenceRefs : ['未提供结构化证据引用']} />
            </Box>
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
              AI {decision.ai_site_id || '-'} / {decision.ai_model || '-'} · 入场参考 {numberText(item.entry_reference)} · 失效价 {numberText(item.invalidation_price)} · 目标价 {numberText(item.target_price)}
            </Typography>
          </AccordionDetails>
        </Accordion>
      )}
    </Box>
  );
}

function HumanReport({ reportKind, report }: { reportKind: string; report: Record<string, unknown> }) {
  const status = String(report.status || (report.latest_pipeline_run && isRecord(report.latest_pipeline_run) ? report.latest_pipeline_run.status : 'ok'));
  const generatedAt = String(report.generated_at || report.created_at || report.finished_at || '');
  const metrics = reportMetrics(reportKind, report);
  return (
    <Card>
      <CardContent>
        <SectionHeading
          icon={<ArticleIcon />}
          title={reportKindTitle(reportKind)}
          detail={generatedAt ? `生成于 ${formatTime(generatedAt)}` : '最近一次系统报告'}
          action={<Chip size="small" color={statusColor(status)} label={statusText(status)} />}
        />
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'repeat(2, minmax(0, 1fr))', md: `repeat(${Math.min(metrics.length, 5)}, minmax(0, 1fr))` }, gap: 1, mb: 2 }}>
          {metrics.map((metric) => <SummaryMetric key={metric.label} label={metric.label} value={metric.value} chipStatus={metric.chipStatus} />)}
        </Box>
        <ReportDetails reportKind={reportKind} report={report} />
        <RawDisclosure label="查看原始 JSON" value={report} />
      </CardContent>
    </Card>
  );
}

function ReportDetails({ reportKind, report }: { reportKind: string; report: Record<string, unknown> }) {
  if (reportKind === 'operator-workbench') {
    const adviceRows = recordArray(report.items).map((item) => isRecord(item.advice) ? item.advice : null).filter((item): item is Record<string, unknown> => Boolean(item));
    return (
      <Box sx={{ mb: 1.5 }}>
        <Typography variant="subtitle1" sx={{ mb: 0.75 }}>产品建议明细</Typography>
        <Stack divider={<Divider />} spacing={0}>
          {adviceRows.length === 0 && <EmptyState text="报告中没有可展示的建议明细。" />}
          {adviceRows.slice(0, 12).map((advice, index) => <OperatorAdviceRow key={`${advice.symbol}-${index}`} advice={advice} />)}
        </Stack>
      </Box>
    );
  }
  if (reportKind === 'research-pipeline') {
    const steps = recordArray(report.steps);
    return (
      <Box sx={{ mb: 1.5 }}>
        <Typography variant="subtitle1" sx={{ mb: 0.75 }}>执行步骤</Typography>
        <Stack divider={<Divider />} spacing={0}>
          {steps.map((step, index) => <ReportStepRow key={`${step.step_id}-${index}`} step={step} />)}
        </Stack>
      </Box>
    );
  }
  if (reportKind === 'autonomous-loop') {
    const recommendations = recordArray(report.recommended_products).map(toRecommendation).filter((item): item is ProductRecommendation => Boolean(item));
    return (
      <Box sx={{ mb: 1.5 }}>
        <Typography variant="subtitle1" sx={{ mb: 0.75 }}>本轮最终建议</Typography>
        <DecisionList items={recommendations} />
      </Box>
    );
  }
  if (reportKind === 'ingestion-status') {
    const sourceStatuses = isRecord(report.source_statuses) ? Object.entries(report.source_statuses) : [];
    return (
      <Box sx={{ mb: 1.5 }}>
        <Typography variant="subtitle1" sx={{ mb: 0.75 }}>信息源状态</Typography>
        <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap">
          {sourceStatuses.map(([key, value]) => <Chip key={key} size="small" variant="outlined" label={`${statusText(key)} ${String(value)}`} />)}
        </Stack>
      </Box>
    );
  }
  return (
    <Alert severity="info" sx={{ mb: 1.5 }}>
      该报告的关键指标已在上方汇总；原始结构化字段可在审计视图中查看。
    </Alert>
  );
}

function OperatorAdviceRow({ advice }: { advice: Record<string, unknown> }) {
  const levels = isRecord(advice.levels) ? advice.levels : {};
  const rationale = stringArray(advice.rationale).map(humanizeAiText);
  const warnings = stringArray(advice.risk_warnings).map(humanizeAiText);
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'auto 1fr', md: '76px minmax(130px, 0.6fr) 90px 110px 1fr' }, gap: 1, alignItems: 'center', py: 1 }}>
      <Chip size="small" color={recommendationColor(String(advice.action || ''))} label={recommendationActionText(String(advice.action || ''))} />
      <Box>
        <Typography variant="body2" sx={{ fontWeight: 700 }}>{String(advice.symbol || '-')}</Typography>
        <Typography variant="caption" color="text.secondary">{String(advice.provider || '-')}</Typography>
      </Box>
      <Typography variant="caption">信心 {Math.round((toNumber(advice.confidence) || 0) * 100)}%</Typography>
      <Typography variant="caption">参考 {numberText(toNumber(levels.entry_reference))}</Typography>
      <Box>
        <Typography variant="caption" color="text.secondary">{rationale[0] || '未提供建议依据'}</Typography>
        {warnings[0] && <Typography variant="caption" color="warning.main" sx={{ display: 'block' }}>风险：{warnings[0]}</Typography>}
      </Box>
    </Box>
  );
}

function ReportStepRow({ step }: { step: Record<string, unknown> }) {
  const stepName = String(step.step_name || step.name || '-');
  const output = isRecord(step.output) ? step.output : undefined;
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'auto 1fr', sm: '78px minmax(150px, 0.6fr) 1fr auto' }, gap: 1, alignItems: 'center', py: 0.9 }}>
      <Chip size="small" color={statusColor(String(step.status || 'unknown'))} label={statusText(String(step.status || 'unknown'))} />
      <Typography variant="body2" sx={{ fontWeight: 700 }}>{researchStepText(stepName)}</Typography>
      <Typography variant="caption" color="text.secondary">{String(step.error || stepOutputText(output))}</Typography>
      <Typography variant="caption" color="text.secondary">{durationText(toNumber(step.duration_ms))}</Typography>
    </Box>
  );
}

function reportMetrics(reportKind: string, report: Record<string, unknown>): Array<{ label: string; value: string; chipStatus?: string }> {
  const summary = isRecord(report.summary) ? report.summary : {};
  const counts = isRecord(report.counts) ? report.counts : {};
  const actions = isRecord(summary.actions) ? summary.actions : {};
  const statusCounts = isRecord(summary.current_statuses) ? summary.current_statuses : {};
  const maps: Record<string, Array<{ label: string; value: string; chipStatus?: string }>> = {
    'operator-workbench': [
      { label: '建议总数', value: String(summary.advice_count ?? 0) },
      { label: '获取失败', value: String(summary.failed_count ?? 0) },
      { label: '买入/卖出', value: `${String(actions.BUY ?? 0)}/${String(actions.SELL ?? 0)}` },
      { label: '观望', value: String(actions.HOLD ?? 0) },
      { label: '执行边界', value: Boolean(summary.execution_allowed) ? '允许执行' : '仅建议', chipStatus: Boolean(summary.execution_allowed) ? 'warning' : 'passed' },
    ],
    'research-pipeline': [
      { label: '通过步骤', value: String(Array.isArray(summary.passed_steps) ? summary.passed_steps.length : 0) },
      { label: '失败步骤', value: String(Array.isArray(summary.failed_steps) ? summary.failed_steps.length : 0) },
      { label: '跳过步骤', value: String(Array.isArray(summary.skipped_steps) ? summary.skipped_steps.length : 0) },
      { label: '总耗时', value: durationText(toNumber(summary.total_duration_ms)) },
    ],
    'autonomous-loop': [
      { label: '完成步骤', value: String(isRecord(summary.statuses) ? summary.statuses.passed ?? 0 : 0) },
      { label: '候选产品', value: String(summary.product_candidate_count ?? 0) },
      { label: 'AI 决策', value: String(summary.ai_decision_count ?? 0) },
      { label: '最终建议', value: String(summary.recommended_products_count ?? 0) },
      { label: '总耗时', value: durationText(toNumber(summary.total_duration_ms)) },
    ],
    'recommendation-evaluation': [
      { label: '历史建议', value: String(summary.decision_count ?? 0) },
      { label: '已评估', value: String(summary.evaluated_count ?? 0) },
      { label: '待到期', value: String(statusCounts.pending ?? 0) },
      { label: '方向样本', value: String(summary.directional_sample_count ?? 0) },
      { label: '命中率', value: percentText(toNumber(summary.directional_hit_rate), true) },
    ],
    'portfolio-risk': [
      { label: '风险状态', value: statusText(String(summary.risk_status || 'unknown')), chipStatus: String(summary.risk_status || 'unknown') },
      { label: '建议数量', value: String(summary.recommendation_count ?? 0) },
      { label: '方向暴露', value: String(summary.directional_exposure_count ?? 0) },
      { label: '门禁原因', value: String(summary.risk_gate_reason_count ?? 0) },
    ],
    'ai-governance': [
      { label: '成功调用', value: `${String(summary.successful_invocation_count ?? 0)}/${String(summary.invocation_count ?? 0)}` },
      { label: 'Token', value: compactNumber(toNumber(summary.total_tokens) || 0) },
      { label: '估算成本', value: summary.cost_status === 'known' ? `$${numberText(toNumber(summary.estimated_cost_usd))}` : '费率未知' },
      { label: '证据覆盖', value: percentText(toNumber(summary.claim_evidence_coverage), true) },
      { label: '治理警告', value: String(summary.warning_count ?? 0) },
    ],
    'ingestion-status': [
      { label: '原始证据', value: compactNumber(toNumber(counts.raw_evidence) || 0) },
      { label: '事件候选', value: compactNumber(toNumber(counts.event_candidates) || 0) },
      { label: '研究卡片', value: compactNumber(toNumber(counts.research_cards) || 0) },
      { label: '阻断信源', value: String(Array.isArray(report.blocked_sources) ? report.blocked_sources.length : 0) },
    ],
  };
  return maps[reportKind] || [{ label: '状态', value: statusText(String(report.status || 'ok')), chipStatus: String(report.status || 'ok') }];
}

function CompactRecommendationRow({ item }: { item: ProductRecommendation }) {
  const narrative = recommendationNarrative(item);
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'auto minmax(0, 1fr)', md: '76px minmax(120px, 0.55fr) 85px 100px minmax(0, 1.4fr) 160px' }, columnGap: 1, rowGap: { xs: 0.75, md: 1 }, alignItems: { xs: 'start', md: 'center' }, py: 1 }}>
      <Chip size="small" color={recommendationColor(item.action, item.status)} label={recommendationActionText(item.action)} />
      <Typography variant="body2" sx={{ fontWeight: 800 }}>{item.symbol || item.normalized_symbol || '-'}</Typography>
      <Typography variant="caption">候选 {numberText(item.score, 0)}</Typography>
      <Typography variant="caption" color={item.score > 0 && item.confidence === 0 ? 'warning.main' : 'text.secondary'}>最终 {Math.round((item.confidence || 0) * 100)}%</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ gridColumn: { xs: '1 / -1', md: 'auto' }, overflowWrap: 'anywhere' }}>{narrative.primaryReason}</Typography>
      <Tooltip title={narrative.evidenceState}>
        <Chip
          size="small"
          variant="outlined"
          color={String(item.research_context?.status || '') === 'needs-followup' ? 'warning' : 'success'}
          label={narrative.evidenceState}
          sx={{ gridColumn: { xs: '1 / -1', md: 'auto' }, justifySelf: 'start', maxWidth: '100%' }}
        />
      </Tooltip>
    </Box>
  );
}

function CompactStepRow({ step }: { step: AutonomousLoopStep }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: 'auto minmax(0, 1fr) auto', gap: 1, alignItems: 'center', py: 0.8 }}>
      <Chip size="small" color={statusColor(step.status)} label={statusText(step.status)} />
      <Typography variant="body2" sx={{ fontWeight: 700 }}>{autonomousStepText(step.step_name)}</Typography>
      <Typography variant="caption" color="text.secondary">{durationText(step.duration_ms)}</Typography>
    </Box>
  );
}

function DetailedStepRow({ step }: { step: AutonomousLoopStep }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'auto 1fr', sm: '76px minmax(150px, 0.7fr) minmax(0, 1fr) auto' }, gap: 1, alignItems: 'center', py: 0.9 }}>
      <Chip size="small" color={statusColor(step.status)} label={statusText(step.status)} />
      <Typography variant="body2" sx={{ fontWeight: 700 }}>{autonomousStepText(step.step_name)}</Typography>
      <Typography variant="caption" color={step.error ? 'error.main' : 'text.secondary'} sx={{ overflowWrap: 'anywhere' }}>{step.error || stepOutputText(step.output)}</Typography>
      <Typography variant="caption" color="text.secondary">{durationText(step.duration_ms)}</Typography>
    </Box>
  );
}

function RecentJobs({ jobs }: { jobs: JobRecord[] }) {
  return (
    <Card>
      <CardContent>
        <SectionHeading icon={<TimelineIcon />} title="最近后台任务" detail="手动提交任务的执行状态" />
        <Stack divider={<Divider />} spacing={0}>
          {jobs.map((job) => (
            <Box key={job.job_id} sx={{ display: 'grid', gridTemplateColumns: { xs: 'auto 1fr', sm: '78px 180px 1fr auto' }, gap: 1, alignItems: 'center', py: 0.8 }}>
              <Chip size="small" color={statusColor(job.status)} label={jobStatusText(job.status)} />
              <Typography variant="body2" sx={{ fontWeight: 700 }}>{jobKindText(job.kind)}</Typography>
              <Typography variant="caption" color="text.secondary">{shortId(job.job_id)}</Typography>
              <Typography variant="caption" color="text.secondary">{formatTime(job.created_at)}</Typography>
            </Box>
          ))}
        </Stack>
      </CardContent>
    </Card>
  );
}

function SectionHeading({
  icon,
  title,
  detail,
  action,
}: {
  icon: ReactNode;
  title: string;
  detail?: string;
  action?: ReactNode;
}) {
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ xs: 'stretch', sm: 'center' }} justifyContent="space-between" sx={{ mb: 1.5 }}>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ minWidth: 0 }}>
        <Box sx={{ color: 'primary.main', display: 'flex' }}>{icon}</Box>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="h3">{title}</Typography>
          {detail && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.15 }}>{detail}</Typography>}
        </Box>
      </Stack>
      {action && <Box sx={{ flexShrink: 0 }}>{action}</Box>}
    </Stack>
  );
}

function CompactFact({ label, value, chipStatus }: { label: string; value: string; chipStatus?: string | null }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      {chipStatus
        ? <Box sx={{ mt: 0.25 }}><Chip size="small" color={statusColor(chipStatus)} label={value} /></Box>
        : <Typography variant="body2" sx={{ fontWeight: 800, mt: 0.25, overflowWrap: 'anywhere' }}>{value}</Typography>}
    </Box>
  );
}

function SummaryMetric({ label, value, chipStatus }: { label: string; value: string; chipStatus?: string | null }) {
  return (
    <Box sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, px: 1.25, py: 1, minWidth: 0, bgcolor: '#fbfcfd' }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      {chipStatus
        ? <Box sx={{ mt: 0.5 }}><Chip size="small" color={statusColor(chipStatus)} label={value} /></Box>
        : <Typography variant="body2" sx={{ fontWeight: 800, mt: 0.35, overflowWrap: 'anywhere' }}>{value}</Typography>}
    </Box>
  );
}

function KeyValue({ label, value, chipStatus }: { label: string; value: string; chipStatus?: string | null }) {
  return (
    <Stack direction="row" spacing={1.5} alignItems="center" justifyContent="space-between">
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      {chipStatus ? <Chip size="small" color={statusColor(chipStatus)} label={value} /> : <Typography variant="body2" sx={{ fontWeight: 700, textAlign: 'right', overflowWrap: 'anywhere' }}>{value}</Typography>}
    </Stack>
  );
}

function ScoreCell({ label, value, warning = false }: { label: string; value: number; warning?: boolean }) {
  const normalized = Math.max(0, Math.min(100, value || 0));
  return (
    <Box sx={{ minWidth: 0 }}>
      <Stack direction="row" spacing={0.5} alignItems="baseline" justifyContent="space-between">
        <Typography variant="caption" color="text.secondary">{label}</Typography>
        <Typography variant="caption" color={warning ? 'warning.main' : 'text.primary'} sx={{ fontWeight: 800 }}>{Math.round(normalized)}%</Typography>
      </Stack>
      <LinearProgress variant="determinate" value={normalized} color={warning ? 'warning' : 'primary'} sx={{ mt: 0.45, height: 4, borderRadius: 0 }} />
    </Box>
  );
}

function DecisionText({ label, value, tone = 'text.secondary' }: { label: string; value: string; tone?: string }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 700 }}>{label}</Typography>
      <Typography variant="body2" color={tone} sx={{ mt: 0.25, overflowWrap: 'anywhere' }}>{value}</Typography>
    </Box>
  );
}

function AuditList({ title, values }: { title: string; values: string[] }) {
  return (
    <Box>
      <Typography variant="caption" sx={{ fontWeight: 700 }}>{title}</Typography>
      {(values.length ? values : ['无']).map((value, index) => <Typography key={`${value}-${index}`} variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.3, overflowWrap: 'anywhere' }}>· {value}</Typography>)}
    </Box>
  );
}

function InsightBlock({ tone, title, items }: { tone: 'success' | 'warning' | 'info'; title: string; items: string[] }) {
  const borderColor = tone === 'success' ? 'success.main' : tone === 'warning' ? 'warning.main' : 'primary.main';
  return (
    <Box sx={{ borderLeft: '3px solid', borderColor, pl: 1.25, py: 0.25 }}>
      <Typography variant="subtitle1">{title}</Typography>
      {items.slice(0, 4).map((item) => <Typography key={item} variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.4 }}>· {item}</Typography>)}
    </Box>
  );
}

function OptionToggle({ label, detail, checked, onChange }: { label: string; detail: string; checked: boolean; onChange: (checked: boolean) => void }) {
  return (
    <Box sx={{ border: '1px solid', borderColor: checked ? 'primary.main' : 'divider', borderRadius: 1, px: 1.25, py: 0.75 }}>
      <FormControlLabel control={<Switch checked={checked} onChange={(event) => onChange(event.target.checked)} />} label={<Typography variant="body2" sx={{ fontWeight: 700 }}>{label}</Typography>} />
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', pl: 6 }}>{detail}</Typography>
    </Box>
  );
}

function EmptyState({ text }: { text: string }) {
  return <Typography variant="body2" color="text.secondary" sx={{ py: 2, textAlign: 'center' }}>{text}</Typography>;
}

function RawDisclosure({ label, value }: { label: string; value: unknown }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <Accordion expanded={expanded} onChange={(_, nextExpanded) => setExpanded(nextExpanded)} disableGutters elevation={0} sx={{ mt: 1, '&:before': { display: 'none' }, bgcolor: 'transparent' }}>
      <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ minHeight: 34, px: 0, '& .MuiAccordionSummary-content': { my: 0.5 } }}>
        <Typography variant="caption" color="primary.main" sx={{ fontWeight: 700 }}>{label}</Typography>
      </AccordionSummary>
      {expanded && <AccordionDetails sx={{ px: 0, pt: 0 }}>
        <Box component="pre" sx={{ m: 0, maxHeight: 360, overflow: 'auto', p: 1.25, bgcolor: '#f6f8fa', border: '1px solid', borderColor: 'divider', borderRadius: 1, fontSize: 11, lineHeight: 1.5, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
          {JSON.stringify(value, null, 2)}
        </Box>
      </AccordionDetails>}
    </Accordion>
  );
}

function preferredRecommendations(autonomous: AutonomousStatusPayload | null): ProductRecommendation[] {
  if (autonomous?.latest_ai_decisions?.length) {
    return autonomous.latest_ai_decisions;
  }
  return autonomous?.latest_recommendations || [];
}

function recommendationColor(action?: string | null, fallbackStatus?: string | null): 'default' | 'primary' | 'success' | 'warning' | 'error' {
  const normalized = String(action || '').toUpperCase();
  if (normalized === 'BUY') return 'success';
  if (normalized === 'SELL') return 'error';
  if (normalized === 'WATCH' || normalized === 'HOLD') return 'warning';
  return statusColor(String(fallbackStatus || 'unknown'));
}

function assessmentSignature(message: AIDebateMessage): string {
  return recordArray(message.content?.candidate_assessments)
    .map((assessment) => `${String(assessment.symbol || '')}:${String(assessment.action_bias || '')}`)
    .sort()
    .join('|');
}

function deriveDisagreements(messages: AIDebateMessage[]): string[] {
  const positions = new Map<string, Set<string>>();
  for (const message of messages.filter((item) => item.agent_role !== 'chair_arbiter')) {
    for (const assessment of recordArray(message.content?.candidate_assessments)) {
      const symbol = String(assessment.symbol || '');
      const bias = recommendationActionText(String(assessment.action_bias || ''));
      if (!symbol || !bias) continue;
      const values = positions.get(symbol) || new Set<string>();
      values.add(bias);
      positions.set(symbol, values);
    }
  }
  return Array.from(positions.entries())
    .filter(([, values]) => values.size > 1)
    .map(([symbol, values]) => `${symbol}：角色判断包含 ${Array.from(values).join('、')}。`);
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map(String).filter(Boolean) : [];
}

function numericValue(record: Record<string, unknown> | undefined, key: string): number | undefined {
  return record ? toNumber(record[key]) : undefined;
}

function toNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
  return undefined;
}

function toRecommendation(value: Record<string, unknown>): ProductRecommendation | null {
  const action = String(value.action || '');
  if (!action) return null;
  return {
    symbol: value.symbol ? String(value.symbol) : null,
    normalized_symbol: value.normalized_symbol ? String(value.normalized_symbol) : null,
    provider: value.provider ? String(value.provider) : null,
    market_type: value.market_type ? String(value.market_type) : null,
    action,
    status: String(value.status || action.toLowerCase()),
    confidence: toNumber(value.confidence) || 0,
    score: toNumber(value.score) || 0,
    horizon: value.horizon ? String(value.horizon) : null,
    entry_reference: toNumber(value.entry_reference),
    target_price: toNumber(value.target_price),
    invalidation_price: toNumber(value.invalidation_price),
    rationale: stringArray(value.rationale),
    risk_warnings: stringArray(value.risk_warnings),
    research_context: isRecord(value.research_context) ? value.research_context : undefined,
    policy: isRecord(value.policy) ? value.policy : undefined,
  };
}

function splitCsv(value: string): string[] {
  return value.split(',').map((item) => item.trim()).filter(Boolean);
}
