import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Divider,
  IconButton,
  LinearProgress,
  Stack,
  Tab,
  Tabs,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import CircleOutlinedIcon from '@mui/icons-material/CircleOutlined';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline';
import FactCheckOutlinedIcon from '@mui/icons-material/FactCheckOutlined';
import HourglassEmptyIcon from '@mui/icons-material/HourglassEmpty';
import InsightsIcon from '@mui/icons-material/Insights';
import PsychologyIcon from '@mui/icons-material/Psychology';
import RefreshIcon from '@mui/icons-material/Refresh';
import SearchIcon from '@mui/icons-material/Search';
import SendIcon from '@mui/icons-material/Send';
import type { KeyboardEvent, ReactNode } from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { api } from './api';
import { DecisionList } from './OperationsPanels';
import {
  agentRoleText,
  autonomousStepText,
  councilPhaseText,
  durationText,
  humanizeAiText,
  recommendationActionText,
  researchStepText,
  stanceText,
} from './humanReadable';
import type {
  AIDebateCouncil,
  AIDebateMessage,
  AutonomousLoopStep,
  InstantResearchSession,
  InstantResearchSessionSummary,
  InstantResearchSeed,
} from './types';
import { formatTime, statusColor, statusText } from './utils';

type DetailView = 'process' | 'debate' | 'result';

const TERMINAL_STATUSES = new Set(['succeeded', 'partial', 'failed', 'cancelled']);

export function InstantResearchPanel({
  initialRequest,
  onInitialRequestConsumed,
}: {
  initialRequest?: InstantResearchSeed | null;
  onInitialRequestConsumed?: () => void;
}) {
  const [query, setQuery] = useState('');
  const [activeSeed, setActiveSeed] = useState<InstantResearchSeed | null>(null);
  const [sessions, setSessions] = useState<InstantResearchSessionSummary[]>([]);
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);
  const [session, setSession] = useState<InstantResearchSession | null>(null);
  const [view, setView] = useState<DetailView>('process');
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const refreshSequence = useRef(0);

  const refresh = useCallback(async (silent = false) => {
    const sequence = ++refreshSequence.current;
    if (!silent) {
      setLoading(true);
    }
    try {
      const payload = await api.instantResearchSessions(20);
      setSessions(payload.sessions);
      const targetId = selectedSessionId || payload.sessions[0]?.session_id || null;
      if (!selectedSessionId && targetId) {
        setSelectedSessionId(targetId);
      }
      const detail = targetId ? await api.instantResearchSession(targetId) : null;
      if (sequence !== refreshSequence.current) {
        return;
      }
      setSession(detail);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      if (!silent) {
        setLoading(false);
      }
    }
  }, [selectedSessionId]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  useEffect(() => {
    if (!initialRequest) return;
    setQuery(initialRequest.query);
    setActiveSeed(initialRequest);
  }, [initialRequest?.seed_id]);

  useEffect(() => {
    if (!session || TERMINAL_STATUSES.has(session.status)) {
      return undefined;
    }
    const timer = window.setInterval(() => void refresh(true), 2000);
    return () => window.clearInterval(timer);
  }, [refresh, session]);

  const submit = useCallback(async () => {
    const normalized = query.trim();
    if (normalized.length < 2 || submitting) {
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const response = await api.submitInstantResearch({
        query: normalized,
        symbols: activeSeed?.symbols || [],
        product_id: activeSeed?.product_id,
        preferred_instrument_id: activeSeed?.preferred_instrument_id,
        watchlist_id: activeSeed?.watchlist_id,
        provider: activeSeed?.provider,
        market_type: activeSeed?.market_type,
      });
      setSelectedSessionId(response.session.session_id);
      setSession(response.session);
      setSessions((current) => [response.session, ...current.filter((item) => item.session_id !== response.session.session_id)]);
      setView('process');
      setActiveSeed(null);
      onInitialRequestConsumed?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSubmitting(false);
    }
  }, [activeSeed, onInitialRequestConsumed, query, submitting]);

  const handleComposerKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      void submit();
    }
  };

  const progressValue = session
    ? Math.min(100, (session.progress.completed_steps / Math.max(1, session.progress.total_steps)) * 100)
    : 0;

  return (
    <Stack spacing={2} data-testid="instant-research-panel">
      <Card data-testid="instant-research-composer">
        <CardContent sx={{ p: { xs: 1.5, md: 2 }, '&:last-child': { pb: { xs: 1.5, md: 2 } } }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25} alignItems="stretch">
            <TextField
              value={query}
              onChange={(event) => setQuery(event.target.value.slice(0, 500))}
              onKeyDown={handleComposerKeyDown}
              placeholder="输入需要立即研究的问题"
              multiline
              minRows={2}
              maxRows={5}
              fullWidth
              inputProps={{ 'aria-label': '即时研究问题', maxLength: 500 }}
              sx={{
                '& .MuiOutlinedInput-root': { alignItems: 'flex-start', minHeight: 76 },
                '& textarea': { fontSize: 14, lineHeight: 1.55 },
              }}
            />
            <Button
              variant="contained"
              startIcon={submitting ? <CircularProgress color="inherit" size={18} /> : <SendIcon />}
              disabled={query.trim().length < 2 || submitting}
              onClick={() => void submit()}
              sx={{ minWidth: { sm: 128 }, minHeight: { xs: 40, sm: 76 } }}
            >
              开始研究
            </Button>
          </Stack>
          {activeSeed && (
            <Stack direction="row" spacing={0.75} sx={{ mt: 1, flexWrap: 'wrap', gap: 0.75 }}>
              <Chip size="small" color="primary" variant="outlined" label={activeSeed.display_name || activeSeed.symbols[0] || '产品研究'} />
              {activeSeed.provider && <Chip size="small" variant="outlined" label={`${activeSeed.provider} · ${activeSeed.market_type || '-'}`} />}
              <Typography variant="caption" color="text.secondary" sx={{ alignSelf: 'center' }}>已带入首选场所合约和 Watchlist 上下文</Typography>
            </Stack>
          )}
        </CardContent>
      </Card>

      {error && <Alert severity="error">{error}</Alert>}

      {loading && !session ? (
        <Card><CardContent><Stack alignItems="center" spacing={1} py={5}><CircularProgress size={28} /><Typography variant="body2" color="text.secondary">正在加载最近的研究会话</Typography></Stack></CardContent></Card>
      ) : !session ? (
        <Card><CardContent><EmptyState /></CardContent></Card>
      ) : (
        <>
          <Card data-testid="instant-research-session-header">
            <CardContent sx={{ p: { xs: 1.5, md: 2 }, '&:last-child': { pb: { xs: 1.5, md: 2 } } }}>
              <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'minmax(0, 1fr) auto auto auto' }, gap: { xs: 1, lg: 2.5 }, alignItems: 'center' }}>
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="caption" color="text.secondary">已提交问题</Typography>
                  <Typography variant="subtitle1" sx={{ overflowWrap: 'anywhere' }}>{session.query}</Typography>
                  {session.product_context?.provider && (
                    <Typography variant="caption" color="primary.main">
                      {session.product_context.provider} · {session.product_context.market_type || '-'} · 产品上下文已锁定
                    </Typography>
                  )}
                </Box>
                <SessionFact label="状态" value={<Chip size="small" color={statusColor(session.status)} label={sessionStatusText(session)} />} />
                <SessionFact label="已用时间" value={elapsedText(session)} />
                <SessionFact label="进度" value={`${session.progress.completed_steps}/${session.progress.total_steps}`} />
              </Box>
              <LinearProgress variant="determinate" value={progressValue} sx={{ mt: 1.5, height: 4, borderRadius: 0 }} />
            </CardContent>
          </Card>

          <Box data-testid="instant-research-workspace" sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', xl: '330px minmax(0, 1fr)' }, gap: 2, alignItems: 'start' }}>
            <ProcessRail session={session} sessions={sessions} onSelectSession={setSelectedSessionId} />
            <Card sx={{ minWidth: 0 }}>
              <CardContent sx={{ p: 0, '&:last-child': { pb: 0 } }}>
                <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ pr: 1, borderBottom: '1px solid', borderColor: 'divider' }}>
                  <Tabs aria-label="即时研究视图" value={view} onChange={(_, value: DetailView) => setView(value)} variant="scrollable" scrollButtons="auto">
                    <Tab value="process" label="流程详情" />
                    <Tab value="debate" label={`AI 辩论${session.debate ? ` (${session.debate.messages.length})` : ''}`} />
                    <Tab value="result" label="最终结果" />
                  </Tabs>
                  <Tooltip title="刷新">
                    <IconButton size="small" onClick={() => void refresh()} disabled={loading}>
                      {loading ? <CircularProgress size={18} /> : <RefreshIcon fontSize="small" />}
                    </IconButton>
                  </Tooltip>
                </Stack>
                <Box sx={{ p: { xs: 1.5, md: 2 } }}>
                  {view === 'process' && <ProcessDetail session={session} />}
                  {view === 'debate' && <InstantDebateView debate={session.debate || null} />}
                  {view === 'result' && <InstantResultView session={session} />}
                </Box>
              </CardContent>
            </Card>
          </Box>
        </>
      )}
    </Stack>
  );
}

function ProcessRail({
  session,
  sessions,
  onSelectSession,
}: {
  session: InstantResearchSession;
  sessions: InstantResearchSessionSummary[];
  onSelectSession: (sessionId: string) => void;
}) {
  const researchSteps = session.research_pipeline?.steps || [];
  const loopSteps = session.loop_run?.steps || [];
  const groups = [
    {
      title: '收集信息',
      icon: <SearchIcon fontSize="small" />,
      items: [
        railItem('定向搜索', findStep(researchSteps, 'ingestion_run')),
        railItem('清理去重', findStep(researchSteps, 'process_evidence')),
        railItem('AI 压缩', findStep(researchSteps, 'ai_compression')),
        railItem('研究简报', findStep(researchSteps, 'build_phase4_brief')),
      ],
    },
    {
      title: '分析研判',
      icon: <InsightsIcon fontSize="small" />,
      items: [
        railItem('行情确认', findStep(loopSteps, 'operator_workbench')),
        railItem('产品候选', findStep(loopSteps, 'product_candidates')),
        railItem('多 Agent 辩论', findStep(loopSteps, 'ai_debate')),
        railItem('主席合成', findStep(loopSteps, 'trade_synthesis')),
      ],
    },
    {
      title: '生成结果',
      icon: <FactCheckOutlinedIcon fontSize="small" />,
      items: [
        railItem('产品建议', findStep(loopSteps, 'product_selection')),
        railItem('风险与治理', combinedStep(loopSteps, ['portfolio_risk', 'ai_governance'])),
        railItem('结果发布', findStep(loopSteps, 'publish_status')),
      ],
    },
  ];
  return (
    <Card data-testid="instant-research-process-rail">
      <CardContent sx={{ p: 0, '&:last-child': { pb: 0 } }}>
        <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ px: 1.5, py: 1.25, borderBottom: '1px solid', borderColor: 'divider' }}>
          <Typography variant="subtitle1">研究流程</Typography>
          <Typography variant="caption" color="text.secondary">{session.progress.completed_steps}/{session.progress.total_steps}</Typography>
        </Stack>
        {groups.map((group, groupIndex) => (
          <Box key={group.title} sx={{ px: 1.5, py: 1.25, borderBottom: '1px solid', borderColor: 'divider' }}>
            <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.75 }}>
              <Box sx={{ width: 26, height: 26, display: 'grid', placeItems: 'center', color: 'primary.main' }}>{group.icon}</Box>
              <Typography variant="subtitle1">{groupIndex + 1} {group.title}</Typography>
            </Stack>
            <Stack spacing={0.25}>
              {group.items.map((item) => <RailStep key={item.label} {...item} />)}
            </Stack>
          </Box>
        ))}
        <Box sx={{ px: 1.5, py: 1.25 }}>
          <Typography variant="caption" color="text.secondary" sx={{ fontWeight: 700 }}>最近研究</Typography>
          <Stack spacing={0.5} sx={{ mt: 0.75 }}>
            {sessions.slice(0, 4).map((item) => (
              <Button
                key={item.session_id}
                variant={item.session_id === session.session_id ? 'outlined' : 'text'}
                color="inherit"
                onClick={() => onSelectSession(item.session_id)}
                sx={{ justifyContent: 'flex-start', px: 1, minWidth: 0 }}
              >
                <Box sx={{ minWidth: 0, textAlign: 'left' }}>
                  <Typography variant="caption" sx={{ display: 'block', fontWeight: 700, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{item.query}</Typography>
                  <Typography variant="caption" color="text.secondary">{formatTime(item.requested_at)} · {sessionStatusText(item)}</Typography>
                </Box>
              </Button>
            ))}
          </Stack>
        </Box>
      </CardContent>
    </Card>
  );
}

function ProcessDetail({ session }: { session: InstantResearchSession }) {
  const researchSteps = session.research_pipeline?.steps || [];
  const loopSteps = session.loop_run?.steps || [];
  return (
    <Stack spacing={2}>
      <StepSection
        title="信息收集与处理"
        detail={session.research_pipeline ? `${researchSteps.length} 个环节 · ${statusText(session.research_pipeline.status)}` : '等待 Worker 建立信息处理任务'}
        steps={researchSteps}
        labelFor={researchStepText}
      />
      <Divider />
      <StepSection
        title="分析、辩论与结果"
        detail={`${loopSteps.length}/${session.progress.total_steps} 个环节已建立`}
        steps={loopSteps}
        labelFor={autonomousStepText}
      />
    </Stack>
  );
}

function StepSection({
  title,
  detail,
  steps,
  labelFor,
}: {
  title: string;
  detail: string;
  steps: AutonomousLoopStep[];
  labelFor: (name: string) => string;
}) {
  return (
    <Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={0.5} sx={{ mb: 0.75 }}>
        <Typography variant="subtitle1">{title}</Typography>
        <Typography variant="caption" color="text.secondary">{detail}</Typography>
      </Stack>
      {steps.length === 0 ? (
        <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>等待运行。</Typography>
      ) : (
        <Stack divider={<Divider />} spacing={0}>
          {steps.map((step) => (
            <Box key={step.step_id} sx={{ display: 'grid', gridTemplateColumns: { xs: '24px minmax(0, 1fr)', md: '24px minmax(150px, 0.6fr) minmax(0, 1fr) 80px' }, columnGap: 1, rowGap: 0.25, alignItems: 'center', py: 0.85 }}>
              <StepStatusIcon status={step.status} />
              <Typography variant="body2" sx={{ fontWeight: 700 }}>{labelFor(step.step_name)}</Typography>
              <Typography variant="caption" color={step.error ? 'error.main' : 'text.secondary'} sx={{ gridColumn: { xs: '2', md: 'auto' }, overflowWrap: 'anywhere' }}>
                {step.error || stepDetail(step)}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ gridColumn: { xs: '2', md: 'auto' } }}>{durationText(step.duration_ms)}</Typography>
            </Box>
          ))}
        </Stack>
      )}
    </Box>
  );
}

function InstantDebateView({ debate }: { debate: AIDebateCouncil | null }) {
  const rounds = useMemo(() => debateRounds(debate), [debate]);
  const chair = debate?.messages.find((message) => message.agent_role === 'chair_arbiter' || message.phase_id === 'chair_synthesis');
  const latestRound = rounds[rounds.length - 1] || 1;
  const [selectedStage, setSelectedStage] = useState<string>(`round-${latestRound}`);

  useEffect(() => {
    setSelectedStage(chair ? 'chair' : `round-${latestRound}`);
  }, [chair?.message_id, debate?.debate_id, debate?.messages.length, latestRound]);

  if (!debate) {
    return <Alert severity="info" icon={<PsychologyIcon />}>尚未进入多 Agent 辩论。</Alert>;
  }
  const selectedRound = Number(selectedStage.replace('round-', ''));
  const messages = Number.isFinite(selectedRound)
    ? debate.messages.filter((message) => message.round_index === selectedRound)
    : [];
  return (
    <Stack spacing={1.5}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}>
        <Box>
          <Typography variant="subtitle1">真实多轮辩论</Typography>
          <Typography variant="caption" color="text.secondary">{debate.rounds} 轮 · {debate.messages.length} 条已返回消息</Typography>
        </Box>
        <Chip size="small" color={statusColor(debate.status)} label={statusText(debate.status)} />
      </Stack>
      <Tabs value={selectedStage} onChange={(_, value: string) => setSelectedStage(value)} variant="scrollable" scrollButtons="auto" sx={{ borderBottom: '1px solid', borderColor: 'divider' }}>
        {rounds.map((round) => {
          const phase = debate.messages.find((message) => message.round_index === round)?.phase_id;
          return <Tab key={round} value={`round-${round}`} label={`第 ${round} 轮 · ${councilPhaseText(phase)}`} />;
        })}
        <Tab value="chair" label="主席结论" />
      </Tabs>
      {selectedStage === 'chair' ? (
        chair ? <ChairView chair={chair} /> : <Alert severity="info">主席尚未完成结论。</Alert>
      ) : messages.length ? (
        <Stack divider={<Divider />} spacing={0}>{messages.map((message) => <DebateMessageRow key={message.message_id || `${message.agent_role}-${message.turn_index}`} message={message} />)}</Stack>
      ) : (
        <Typography variant="body2" color="text.secondary">本轮消息尚未返回。</Typography>
      )}
    </Stack>
  );
}

function DebateMessageRow({ message }: { message: AIDebateMessage }) {
  const content = message.content || {};
  const assessments = recordArray(content.candidate_assessments);
  const evidenceCount = assessments.reduce((total, assessment) => total + stringArray(assessment.evidence_refs).length, 0);
  const summary = humanizeAiText(content.overall_view || stringArray(content.debate_summary)[0] || message.error || '等待结构化观点');
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '150px 88px minmax(0, 1fr) 150px' }, gap: 1, alignItems: 'center', py: 1.1 }}>
      <Stack direction="row" spacing={0.75} alignItems="center">
        <Chip size="small" color={statusColor(message.status)} label={statusText(message.status)} />
        <Typography variant="body2" sx={{ fontWeight: 700 }}>{agentRoleText(message.agent_role)}</Typography>
      </Stack>
      <Typography variant="caption" color="text.secondary">{stanceText(message.stance)}</Typography>
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="body2" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>{summary}</Typography>
        {assessments[0] && <Typography variant="caption" color="text.secondary">{humanizeAiText(stringArray(assessments[0].arguments)[0])}</Typography>}
      </Box>
      <Typography variant="caption" color="text.secondary">引用 {message.reply_to_message_ids?.length || 0} · 证据 {evidenceCount}<br />{message.provider || '-'} / {message.model || '-'}</Typography>
    </Box>
  );
}

function ChairView({ chair }: { chair: AIDebateMessage }) {
  const content = chair.content || {};
  const summaries = stringArray(content.debate_summary).map(humanizeAiText);
  const decisions = recordArray(content.decisions);
  return (
    <Stack spacing={1.25}>
      <InsightColumns
        consensus={summaries.length ? summaries : ['主席已完成合成。']}
        disagreements={stringArray(content.major_disagreements).map(humanizeAiText)}
        missing={stringArray(content.missing_evidence).map(humanizeAiText)}
      />
      {decisions.length > 0 && <Stack divider={<Divider />} spacing={0}>{decisions.map((decision, index) => <DecisionRow key={`${decision.symbol}-${index}`} decision={decision} />)}</Stack>}
    </Stack>
  );
}

function InstantResultView({ session }: { session: InstantResearchSession }) {
  if (!session.decisions.length && !session.recommendations.length) {
    return <Alert severity={session.status === 'failed' ? 'error' : 'info'}>{session.error || '最终结果尚未生成。'}</Alert>;
  }
  return (
    <Stack spacing={2}>
      <Box>
        <Typography variant="subtitle1" sx={{ mb: 0.75 }}>AI 决策</Typography>
        <DecisionList items={session.decisions} />
      </Box>
      {session.recommendations.length > 0 && (
        <Box>
          <Typography variant="subtitle1" sx={{ mb: 0.75 }}>产品建议</Typography>
          <DecisionList items={session.recommendations} />
        </Box>
      )}
      <Alert severity="info" icon={<FactCheckOutlinedIcon />}>即时研究仅生成研究建议，不提交模拟或真实订单。</Alert>
    </Stack>
  );
}

function DecisionRow({ decision }: { decision: Record<string, unknown> }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'auto 1fr', md: '74px 120px 90px minmax(0, 1fr)' }, gap: 1, py: 0.9, alignItems: 'center' }}>
      <Chip size="small" color={actionColor(String(decision.action || 'WATCH'))} label={recommendationActionText(String(decision.action || 'WATCH'))} />
      <Typography variant="body2" sx={{ fontWeight: 700 }}>{String(decision.symbol || '-')}</Typography>
      <Typography variant="caption">{Math.round(Number(decision.confidence || 0) * 100)}%</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ gridColumn: { xs: '1 / -1', md: 'auto' } }}>{humanizeAiText(stringArray(decision.rationale)[0]) || '未提供主要依据。'}</Typography>
    </Box>
  );
}

function InsightColumns({ consensus, disagreements, missing }: { consensus: string[]; disagreements: string[]; missing: string[] }) {
  const columns = [
    { title: '共识', color: 'success.main', items: consensus },
    { title: '主要分歧', color: 'warning.main', items: disagreements.length ? disagreements : ['暂无结构化分歧。'] },
    { title: '待补证据', color: 'primary.main', items: missing.length ? missing : ['暂无新增缺失证据。'] },
  ];
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: 'repeat(3, minmax(0, 1fr))' }, border: '1px solid', borderColor: 'divider' }}>
      {columns.map((column, index) => (
        <Box key={column.title} sx={{ p: 1.25, borderRight: { lg: index < columns.length - 1 ? '1px solid' : 0 }, borderBottom: { xs: index < columns.length - 1 ? '1px solid' : 0, lg: 0 }, borderColor: 'divider' }}>
          <Typography variant="subtitle1" sx={{ color: column.color, mb: 0.5 }}>{column.title}</Typography>
          {column.items.slice(0, 3).map((item) => <Typography key={item} variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.35 }}>· {item}</Typography>)}
        </Box>
      ))}
    </Box>
  );
}

function RailStep({ label, status, duration }: { label: string; status: string; duration?: number | null }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: '22px minmax(0, 1fr) auto', gap: 0.75, alignItems: 'center', minHeight: 30 }}>
      <StepStatusIcon status={status} />
      <Typography variant="body2" color={status === 'pending' ? 'text.secondary' : 'text.primary'}>{label}</Typography>
      <Typography variant="caption" color="text.secondary">{durationText(duration)}</Typography>
    </Box>
  );
}

function StepStatusIcon({ status }: { status: string }) {
  if (['passed', 'succeeded', 'completed', 'skipped'].includes(status)) {
    return <CheckCircleIcon color="success" sx={{ fontSize: 18 }} />;
  }
  if (status === 'running') {
    return <CircularProgress size={16} thickness={5} />;
  }
  if (status === 'failed') {
    return <ErrorOutlineIcon color="error" sx={{ fontSize: 18 }} />;
  }
  if (status === 'queued') {
    return <HourglassEmptyIcon color="primary" sx={{ fontSize: 17 }} />;
  }
  return <CircleOutlinedIcon color="disabled" sx={{ fontSize: 17 }} />;
}

function SessionFact({ label, value }: { label: string; value: ReactNode }) {
  return <Stack direction="row" spacing={0.75} alignItems="center"><Typography variant="caption" color="text.secondary">{label}</Typography>{typeof value === 'string' ? <Typography variant="body2" sx={{ fontWeight: 700 }}>{value}</Typography> : value}</Stack>;
}

function EmptyState() {
  return (
    <Stack alignItems="center" spacing={1} sx={{ py: 6, color: 'text.secondary' }}>
      <SearchIcon sx={{ fontSize: 32 }} />
      <Typography variant="subtitle1">暂无即时研究会话</Typography>
    </Stack>
  );
}

function railItem(label: string, step?: AutonomousLoopStep | null) {
  return { label, status: step?.status || 'pending', duration: step?.duration_ms };
}

function findStep(steps: AutonomousLoopStep[], stepName: string): AutonomousLoopStep | null {
  return steps.find((step) => step.step_name === stepName) || null;
}

function combinedStep(steps: AutonomousLoopStep[], names: string[]): AutonomousLoopStep | null {
  const matches = names.map((name) => findStep(steps, name)).filter((step): step is AutonomousLoopStep => Boolean(step));
  if (!matches.length) {
    return null;
  }
  const status = matches.some((step) => step.status === 'failed')
    ? 'failed'
    : matches.some((step) => step.status === 'running')
      ? 'running'
      : matches.every((step) => ['passed', 'skipped'].includes(step.status))
        ? 'passed'
        : 'pending';
  return { ...matches[0], status, duration_ms: matches.reduce((total, step) => total + Number(step.duration_ms || 0), 0) };
}

function stepDetail(step: AutonomousLoopStep): string {
  const output = step.output || {};
  const fragments = [
    countDetail(output.results, '结果'),
    countDetail(output.items, '项目'),
    countDetail(output.candidates, '候选'),
    countDetail(output.ai_decisions, '决策'),
    countDetail(output.recommended_products, '建议'),
  ].filter(Boolean);
  return fragments.join(' · ') || statusText(step.status);
}

function countDetail(value: unknown, label: string): string {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return '';
  }
  const count = Number((value as { count?: number }).count);
  return Number.isFinite(count) ? `${label} ${count}` : '';
}

function sessionStatusText(session: InstantResearchSessionSummary): string {
  if (session.status === 'running') {
    const map: Record<string, string> = { preparing: '正在准备', collect: '正在收集', analyze: '正在分析', result: '正在生成结果' };
    return map[session.stage] || '运行中';
  }
  return statusText(session.status);
}

function elapsedText(session: InstantResearchSession): string {
  const start = new Date(session.started_at || session.requested_at).getTime();
  const end = session.finished_at ? new Date(session.finished_at).getTime() : Date.now();
  if (!Number.isFinite(start) || !Number.isFinite(end)) {
    return '-';
  }
  return durationText(Math.max(0, end - start));
}

function debateRounds(debate: AIDebateCouncil | null): number[] {
  if (!debate) {
    return [];
  }
  return Array.from(new Set(debate.messages.map((message) => message.round_index).filter((value): value is number => typeof value === 'number' && value <= debate.rounds))).sort((a, b) => a - b);
}

function recordArray(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? value.filter((item): item is Record<string, unknown> => typeof item === 'object' && item !== null && !Array.isArray(item)) : [];
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map((item) => String(item)).filter(Boolean) : [];
}

function actionColor(action: string): 'default' | 'success' | 'error' | 'warning' {
  if (action === 'BUY') return 'success';
  if (action === 'SELL') return 'error';
  if (action === 'WATCH') return 'warning';
  return 'default';
}
