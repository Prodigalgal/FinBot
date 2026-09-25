import AddCommentOutlinedIcon from '@mui/icons-material/AddCommentOutlined';
import AutoAwesomeOutlinedIcon from '@mui/icons-material/AutoAwesomeOutlined';
import ChatBubbleOutlineIcon from '@mui/icons-material/ChatBubbleOutline';
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft';
import InsightsOutlinedIcon from '@mui/icons-material/InsightsOutlined';
import MenuIcon from '@mui/icons-material/Menu';
import SendIcon from '@mui/icons-material/Send';
import {
  Alert, Box, Button, Chip, CircularProgress, Divider, Drawer, IconButton,
  LinearProgress, MenuItem, Paper, Stack, TextField, Tooltip, Typography,
  useMediaQuery,
} from '@mui/material';
import { useTheme } from '@mui/material/styles';
import { useEffect, useMemo, useRef, useState } from 'react';

import { api } from './api';
import { aiOutputStreams, chatEventTypes, mergeChatEvent, mergeChatEventPages, restoreChatEvents } from './analysisChatEvents';
import type { AiOutputStream, ChatStreamEvent } from './analysisChatEvents';
import type { AnalysisChatSession, AnalysisChatTurn, ResearchHistoryDetail, WorkflowDefinitionSummary, WorkflowEvent } from './types';
import { CopyableText, ErrorBlock, StatusBadge, formatTime, statusLabel } from './ui';

const PAGE_SIZE = 50;

export function AnalysisChatPage() {
  const theme = useTheme();
  const showHistoryColumn = useMediaQuery(theme.breakpoints.up('md'));
  const showInspectorColumn = useMediaQuery(theme.breakpoints.up('xl'));
  const [chats, setChats] = useState<AnalysisChatSession[]>([]);
  const [hasMoreChats, setHasMoreChats] = useState(false);
  const [loadingMoreChats, setLoadingMoreChats] = useState(false);
  const [workflows, setWorkflows] = useState<WorkflowDefinitionSummary[]>([]);
  const [workflowVersionId, setWorkflowVersionId] = useState('');
  const [selectedChatId, setSelectedChatId] = useState<string | null>(null);
  const [selectedChat, setSelectedChat] = useState<AnalysisChatSession | null>(null);
  const [turns, setTurns] = useState<AnalysisChatTurn[]>([]);
  const [selectedTurnId, setSelectedTurnId] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [search, setSearch] = useState('');
  const [historyOpen, setHistoryOpen] = useState(false);
  const [inspectorOpen, setInspectorOpen] = useState(false);
  const [sendBusy, setSendBusy] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [loadingChats, setLoadingChats] = useState(true);
  const [loadingSelected, setLoadingSelected] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [detail, setDetail] = useState<ResearchHistoryDetail | null>(null);
  const [events, setEvents] = useState<ChatStreamEvent[]>([]);
  const [streamConnected, setStreamConnected] = useState(false);
  const transcriptEndRef = useRef<HTMLDivElement | null>(null);
  const searchInitializedRef = useRef(false);

  useEffect(() => {
    let active = true;
    api.analysisChats(PAGE_SIZE)
      .then((savedChats) => {
        if (!active) return;
        setChats(savedChats);
        setHasMoreChats(savedChats.length === PAGE_SIZE);
        setSelectedChatId(chatIdFromHash() || savedChats[0]?.chatId || null);
      })
      .catch((cause) => { if (active) setError(cause); })
      .finally(() => { if (active) setLoadingChats(false); });
    api.workflowDefinitions()
      .then((definitions) => {
        if (!active) return;
        const published = definitions.filter((definition) => definition.publishedVersionId !== null);
        setWorkflows(published);
        setWorkflowVersionId(published.find((definition) => definition.active)?.publishedVersionId
          || published[0]?.publishedVersionId || '');
      })
      .catch((cause) => { if (active) setError(cause); });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!searchInitializedRef.current) {
      searchInitializedRef.current = true;
      return;
    }
    let active = true;
    const timer = window.setTimeout(() => {
      api.analysisChats(PAGE_SIZE, undefined, search.trim())
        .then((savedChats) => {
          if (!active) return;
          setChats(savedChats);
          setHasMoreChats(savedChats.length === PAGE_SIZE);
        })
        .catch((cause) => { if (active) setError(cause); });
    }, 250);
    return () => { active = false; window.clearTimeout(timer); };
  }, [search]);

  useEffect(() => {
    if (sendBusy) return;
    if (!selectedChatId) {
      setLoadingSelected(false);
      setSelectedChat(null);
      setTurns([]);
      setSelectedTurnId(null);
      return;
    }
    let active = true;
    setLoadingSelected(true);
    Promise.all([api.analysisChat(selectedChatId), api.analysisChatTurns(selectedChatId, undefined, PAGE_SIZE)])
      .then(([session, savedTurns]) => {
        if (!active) return;
        setSelectedChat(session);
        setTurns(savedTurns);
        setSelectedTurnId(savedTurns[savedTurns.length - 1]?.turnId || null);
      })
      .catch((cause) => { if (active) setError(cause); })
      .finally(() => { if (active) setLoadingSelected(false); });
    return () => { active = false; };
  }, [selectedChatId, sendBusy]);

  const latestTurn = turns[turns.length - 1];
  const running = Boolean(latestTurn && !finishedTurn(latestTurn));
  const selectedTurn = turns.find((turn) => turn.turnId === selectedTurnId) || latestTurn || null;
  const selectedRunId = selectedTurn?.workflowRunId || null;

  useEffect(() => {
    if (!selectedChatId || !running) return;
    let active = true;
    const refresh = () => api.analysisChatTurns(selectedChatId, undefined, PAGE_SIZE)
      .then((savedTurns) => {
        if (!active) return;
        setTurns((current) => mergeTurnPages(current, savedTurns));
      })
      .catch(() => undefined);
    const timer = window.setInterval(() => void refresh(), 3000);
    return () => { active = false; window.clearInterval(timer); };
  }, [selectedChatId, running]);

  useEffect(() => {
    setDetail(null);
    setEvents([]);
    setStreamConnected(false);
    if (!selectedRunId) return;
    let active = true;
    const refreshDetail = () => api.researchDetail(selectedRunId)
      .then((loaded) => {
        if (!active) return;
        setDetail(loaded);
        setEvents((current) => mergeChatEventPages(current, restoreChatEvents(loaded)));
      })
      .catch(() => undefined);
    void refreshDetail();
    if (selectedTurn && finishedTurn(selectedTurn)) {
      return () => { active = false; };
    }
    const source = new EventSource(api.workflowEventsUrl(selectedRunId), { withCredentials: true });
    source.onopen = () => { if (active) setStreamConnected(true); };
    source.onerror = () => { if (active) setStreamConnected(false); };
    chatEventTypes.forEach((type) => source.addEventListener(type, (raw) => {
      try {
        const event = JSON.parse((raw as MessageEvent<string>).data) as WorkflowEvent;
        if (active) setEvents((current) => mergeChatEvent(current, { type, event }));
      } catch { /* A malformed transport frame cannot replace persisted history. */ }
    }));
    const timer = window.setInterval(() => void refreshDetail(), 4000);
    return () => {
      active = false;
      window.clearInterval(timer);
      source.close();
    };
  }, [selectedRunId, selectedTurn?.taskStatus, selectedTurn?.workflowStatus]);

  useEffect(() => { transcriptEndRef.current?.scrollIntoView?.({ block: 'end' }); }, [selectedChatId, turns.length]);

  const outputs = useMemo(() => aiOutputStreams(events), [events]);
  const progress = useMemo(() => {
    if (selectedTurn && finishedTurn(selectedTurn) && selectedTurn.taskStatus === 'COMPLETED') return 100;
    return events.reduce((maximum, { event }) =>
      typeof event.percentage === 'number' ? Math.max(maximum, event.percentage) : maximum, 0);
  }, [events, selectedTurn?.taskStatus, selectedTurn?.workflowStatus]);

  const selectChat = (chatId: string) => {
    setError(null);
    setSelectedChatId(chatId);
    setSelectedChat(null);
    setTurns([]);
    setSelectedTurnId(null);
    setLoadingSelected(true);
    showChatRoute(chatId);
    setHistoryOpen(false);
  };

  const startNewChat = () => {
    setError(null);
    setSelectedChatId(null);
    showChatRoute(null);
    setSelectedChat(null);
    setTurns([]);
    setSelectedTurnId(null);
    setDraft('');
    setHistoryOpen(false);
  };

  const refreshChatList = async () => {
    const savedChats = await api.analysisChats(PAGE_SIZE, undefined, search.trim());
    setChats(savedChats);
    setHasMoreChats(savedChats.length === PAGE_SIZE);
  };

  const loadMoreChats = async () => {
    const lastChat = chats[chats.length - 1];
    if (!lastChat || !hasMoreChats || loadingMoreChats) return;
    setLoadingMoreChats(true);
    try {
      const older = await api.analysisChats(PAGE_SIZE, lastChat.chatId, search.trim());
      setChats((current) => [...current, ...older.filter((chat) => !current.some((saved) => saved.chatId === chat.chatId))]);
      setHasMoreChats(older.length === PAGE_SIZE);
    } catch (cause) {
      setError(cause);
    } finally {
      setLoadingMoreChats(false);
    }
  };

  const send = async () => {
    const message = draft.trim();
    if (!message || sendBusy || running || !workflowVersionId && !selectedChat) return;
    setSendBusy(true);
    setError(null);
    try {
      let session = selectedChat;
      if (!session) {
        session = await api.createAnalysisChat(workflowVersionId);
        setSelectedChat(session);
        setSelectedChatId(session.chatId);
        showChatRoute(session.chatId);
      }
      const accepted = await api.sendAnalysisChatMessage(session.chatId, message, crypto.randomUUID());
      setTurns((current) => mergeTurnPages(current, [accepted]));
      setSelectedTurnId(accepted.turnId);
      setDraft('');
      await refreshChatList();
      setSelectedChat(await api.analysisChat(session.chatId));
    } catch (cause) {
      setError(cause);
    } finally {
      setSendBusy(false);
    }
  };

  const retryUnlinkedTurn = async (turn: AnalysisChatTurn) => {
    if (!selectedChat || turn.taskId || sendBusy) return;
    setSendBusy(true);
    setError(null);
    try {
      const accepted = await api.sendAnalysisChatMessage(selectedChat.chatId, turn.userMessage, crypto.randomUUID());
      setTurns((current) => mergeTurnPages(current, [accepted]));
      setSelectedTurnId(accepted.turnId);
      await refreshChatList();
    } catch (cause) {
      setError(cause);
    } finally {
      setSendBusy(false);
    }
  };

  const loadOlder = async () => {
    if (!selectedChatId || !turns[0] || loadingOlder) return;
    setLoadingOlder(true);
    try {
      const older = await api.analysisChatTurns(selectedChatId, turns[0].turnNumber, PAGE_SIZE);
      setTurns((current) => mergeTurnPages(older, current));
    } catch (cause) {
      setError(cause);
    } finally {
      setLoadingOlder(false);
    }
  };

  const sidebar = (
    <ChatHistory
      chats={chats}
      selectedChatId={selectedChatId}
      search={search}
      onSearch={setSearch}
      onSelect={selectChat}
      onNew={startNewChat}
      hasMore={hasMoreChats}
      loadingMore={loadingMoreChats}
      onLoadMore={() => void loadMoreChats()}
    />
  );
  const inspector = (
    <ChatInspector
      turn={selectedTurn}
      detail={detail}
      outputs={outputs}
      progress={progress}
      streamConnected={streamConnected}
      onClose={() => setInspectorOpen(false)}
      closable={!showInspectorColumn}
    />
  );

  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: '238px minmax(0, 1fr)', xl: '238px minmax(0, 1fr) 330px' }, gap: 1.5, height: 'calc(100dvh - 160px)', minHeight: 620 }}>
      {showHistoryColumn && sidebar}
      {!showHistoryColumn && <Drawer open={historyOpen} onClose={() => setHistoryOpen(false)} PaperProps={{ sx: { width: 'min(300px, 88vw)', p: 1.25 } }}>{sidebar}</Drawer>}

      <Paper variant="outlined" sx={{ display: 'flex', flexDirection: 'column', minWidth: 0, overflow: 'hidden', borderRadius: '10px' }}>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ px: { xs: 1.5, sm: 2.25 }, py: 1.5, borderBottom: '1px solid', borderColor: 'divider' }}>
          {!showHistoryColumn && <IconButton aria-label="打开会话列表" onClick={() => setHistoryOpen(true)}><MenuIcon /></IconButton>}
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography fontWeight={800} noWrap>{selectedChat?.title || '新分析对话'}</Typography>
            <Typography variant="caption" color="text.secondary">多轮上下文 · 工作流共识 · 独立 AI 输出</Typography>
          </Box>
          <Chip size="small" color="success" variant="outlined" label="仅分析 · 不交易" sx={{ display: { xs: 'none', sm: 'inline-flex' } }} />
          {!showInspectorColumn && <Tooltip title="查看各 AI 输出"><IconButton aria-label="查看各 AI 输出" onClick={() => setInspectorOpen(true)}><InsightsOutlinedIcon /></IconButton></Tooltip>}
        </Stack>

        {error !== null && <Box sx={{ px: 2, pt: 1.5 }}><ErrorBlock error={error} /></Box>}

        <Box sx={{ flex: 1, overflowY: 'auto', px: { xs: 1.5, sm: 3 }, py: 2.5 }}>
          {loadingChats || loadingSelected ? <Stack alignItems="center" justifyContent="center" sx={{ minHeight: 220 }}><CircularProgress size={24} /></Stack> : turns.length === 0 ? <ChatWelcome /> : (
            <Stack spacing={2.5} sx={{ maxWidth: 850, mx: 'auto' }}>
              {turns[0]?.turnNumber > 1 && <Button size="small" onClick={() => void loadOlder()} disabled={loadingOlder} startIcon={<ChevronLeftIcon sx={{ transform: 'rotate(90deg)' }} />}>加载更早消息</Button>}
              {turns.map((turn) => <ChatTurnView
                key={turn.turnId}
                turn={turn}
                selected={selectedTurn?.turnId === turn.turnId}
                onInspect={() => { setSelectedTurnId(turn.turnId); if (!showInspectorColumn) setInspectorOpen(true); }}
                onRetry={turn.turnId === latestTurn?.turnId && !sendBusy ? () => void retryUnlinkedTurn(turn) : undefined}
              />)}
              <div ref={transcriptEndRef} />
            </Stack>
          )}
        </Box>

        <Box sx={{ borderTop: '1px solid', borderColor: 'divider', p: { xs: 1.5, sm: 2 }, bgcolor: 'background.paper' }}>
          {!selectedChat && (
            <TextField
              select size="small" fullWidth label="分析工作流" value={workflowVersionId}
              onChange={(event) => setWorkflowVersionId(event.target.value)} sx={{ mb: 1.25 }}
            >
              {workflows.map((workflow) => <MenuItem key={workflow.definitionId} value={workflow.publishedVersionId || ''}>
                {workflow.name} · v{workflow.publishedVersionNumber}{workflow.active ? ' · 已激活' : ''}
              </MenuItem>)}
            </TextField>
          )}
          <Stack direction="row" spacing={1} alignItems="flex-end" sx={{ maxWidth: 850, mx: 'auto' }}>
            <TextField
              autoComplete="off" fullWidth multiline minRows={1} maxRows={5}
              placeholder={running ? '等待上一条分析结束…' : '输入分析问题；Shift + Enter 换行'}
              value={draft} onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                  event.preventDefault();
                  void send();
                }
              }}
              inputProps={{ maxLength: 2000, 'aria-label': '分析消息' }}
              disabled={running || sendBusy}
            />
            <Button
              variant="contained" aria-label="发送分析消息" onClick={() => void send()}
              disabled={!draft.trim() || running || sendBusy || !selectedChat && !workflowVersionId}
              sx={{ minWidth: 48, height: 40, px: 1.5 }}
            >{sendBusy ? <CircularProgress size={19} color="inherit" /> : <SendIcon fontSize="small" />}</Button>
          </Stack>
          <Typography variant="caption" color="text.secondary" display="block" textAlign="center" sx={{ mt: 0.75 }}>
            {running ? '当前会话每次只运行一条分析消息' : '回答来自工作流共识；点击「各 AI 输出」查看模型正文与审计状态'}
          </Typography>
        </Box>
      </Paper>

      {showInspectorColumn && inspector}
      {!showInspectorColumn && <Drawer anchor="right" open={inspectorOpen} onClose={() => setInspectorOpen(false)} PaperProps={{ sx: { width: 'min(410px, 95vw)', p: 1.25 } }}>{inspector}</Drawer>}
    </Box>
  );
}

function ChatHistory({ chats, selectedChatId, search, onSearch, onSelect, onNew, hasMore, loadingMore, onLoadMore }: {
  chats: AnalysisChatSession[];
  selectedChatId: string | null;
  search: string;
  onSearch: (value: string) => void;
  onSelect: (chatId: string) => void;
  onNew: () => void;
  hasMore: boolean;
  loadingMore: boolean;
  onLoadMore: () => void;
}) {
  return <Paper variant="outlined" sx={{ display: 'flex', flexDirection: 'column', overflow: 'hidden', borderRadius: '10px' }}>
    <Stack spacing={1.25} sx={{ p: 1.5 }}>
      <Button variant="contained" startIcon={<AddCommentOutlinedIcon />} fullWidth onClick={onNew}>新对话</Button>
      <TextField size="small" placeholder="搜索会话标题" value={search} onChange={(event) => onSearch(event.target.value)} inputProps={{ 'aria-label': '搜索会话' }} />
    </Stack>
    <Divider />
    <Box sx={{ overflowY: 'auto', flex: 1, p: 1 }}>
      {chats.map((chat) => <Button
        key={chat.chatId} fullWidth onClick={() => onSelect(chat.chatId)}
        sx={{ display: 'block', textAlign: 'left', textTransform: 'none', px: 1.25, py: 1, mb: 0.4, borderRadius: '7px', bgcolor: chat.chatId === selectedChatId ? 'rgba(29, 78, 216, 0.08)' : 'transparent', color: 'text.primary' }}
      >
        <Typography variant="body2" fontWeight={chat.chatId === selectedChatId ? 800 : 600} noWrap>{chat.title}</Typography>
        <Typography variant="caption" color="text.secondary">{formatTime(chat.updatedAt)}</Typography>
      </Button>)}
      {chats.length === 0 && <Typography variant="body2" color="text.secondary" textAlign="center" sx={{ p: 2 }}>暂无匹配会话</Typography>}
      {hasMore && <Button fullWidth size="small" disabled={loadingMore} onClick={onLoadMore}>加载更多会话</Button>}
    </Box>
  </Paper>;
}

function ChatWelcome() {
  return <Stack alignItems="center" spacing={1.25} sx={{ maxWidth: 520, mx: 'auto', mt: { xs: 4, md: 10 }, textAlign: 'center' }}>
    <Box sx={{ width: 52, height: 52, display: 'grid', placeItems: 'center', bgcolor: 'rgba(29, 78, 216, 0.08)', color: 'primary.main', borderRadius: '14px' }}><AutoAwesomeOutlinedIcon /></Box>
    <Typography variant="h5" fontWeight={800}>从一个分析问题开始</Typography>
    <Typography variant="body2" color="text.secondary">FinBot 会运行所选研究工作流，并将共识回答与各 AI 的输出分别呈现。会话会保存，后续提问可沿用前文。</Typography>
    <Chip size="small" variant="outlined" color="success" label="此聊天不会创建交易决策或订单" />
  </Stack>;
}

function ChatTurnView({ turn, selected, onInspect, onRetry }: { turn: AnalysisChatTurn; selected: boolean; onInspect: () => void; onRetry?: () => void }) {
  const complete = turn.taskStatus === 'COMPLETED' && finishedTurn(turn);
  const failed = turn.taskStatus === 'FAILED' || turn.taskStatus === 'CANCELLED';
  const waitingHuman = turn.workflowStatus === 'WAITING_HUMAN';
  return <Stack spacing={1.1} sx={{ borderRadius: '9px', outline: selected ? '1px solid rgba(29, 78, 216, 0.12)' : 'none', p: 1 }}>
    <Stack direction="row" justifyContent="flex-end">
      <Box sx={{ maxWidth: '85%', p: 1.5, bgcolor: 'rgba(29, 78, 216, 0.09)', borderRadius: '12px 12px 3px 12px' }}>
        <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', lineHeight: 1.65 }}>{turn.userMessage}</Typography>
      </Box>
    </Stack>
    <Paper variant="outlined" sx={{ p: 1.75, borderRadius: '3px 12px 12px 12px', maxWidth: '95%', borderColor: failed ? 'error.light' : 'divider' }}>
      <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1 }}>
        <ChatBubbleOutlineIcon color="primary" fontSize="small" />
        <Typography variant="subtitle2" fontWeight={800} sx={{ flex: 1 }}>FinBot 工作流</Typography>
        {(waitingHuman || turn.taskStatus) && <StatusBadge status={waitingHuman ? 'WAITING_HUMAN' : turn.taskStatus || ''} size="small" />}
      </Stack>
      {complete && turn.answer ? <>
        {turn.answerSummary && <Typography variant="body2" fontWeight={700} sx={{ mb: 0.7 }}>{turn.answerSummary}</Typography>}
        <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', lineHeight: 1.75 }}>{turn.answer}</Typography>
      </> : failed ? <Alert severity="warning" sx={{ mb: 1 }}>本次分析未完成，未生成最终回答。可查看各 AI 输出与失败记录。</Alert>
        : waitingHuman ? <Alert severity="info" sx={{ mb: 1 }}>工作流正在等待人工处理，完成后会更新回答。</Alert>
        : complete ? <Alert severity="info" sx={{ mb: 1 }}>工作流已结束，但没有可展示的共识回答。请查看运行详情。</Alert>
          : <Stack spacing={1}><Typography variant="body2" color="text.secondary">正在运行分析工作流…</Typography><LinearProgress /></Stack>}
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mt: 1.25 }}>
        <Typography variant="caption" color="text.secondary">{formatTime(turn.createdAt)} · 第 {turn.turnNumber} 轮</Typography>
        {turn.workflowRunId && <Button size="small" onClick={onInspect} startIcon={<InsightsOutlinedIcon fontSize="small" />}>各 AI 输出</Button>}
        {!turn.workflowRunId && onRetry && <Button size="small" onClick={onRetry}>重试启动分析</Button>}
      </Stack>
    </Paper>
  </Stack>;
}

function ChatInspector({ turn, detail, outputs, progress, streamConnected, onClose, closable }: {
  turn: AnalysisChatTurn | null;
  detail: ResearchHistoryDetail | null;
  outputs: AiOutputStream[];
  progress: number;
  streamConnected: boolean;
  onClose: () => void;
  closable: boolean;
}) {
  const invocations = detail?.aiInvocations || [];
  const agentTurns = detail?.agentTurns || [];
  const streamsById = new Map(outputs.map((output) => [output.invocationId, output]));
  const invocationIds = [...new Set([...outputs.map((output) => output.invocationId), ...invocations.map((invocation) => invocation.invocationId)])];
  const displayName = (nodeId: string) => detail?.checkpoints.find((checkpoint) => checkpoint.nodeId === nodeId)?.displayName || nodeId;
  return <Paper variant="outlined" sx={{ display: 'flex', flexDirection: 'column', overflow: 'hidden', borderRadius: '10px', height: '100%' }}>
    <Stack direction="row" alignItems="center" spacing={1} sx={{ px: 1.75, py: 1.5 }}>
      <InsightsOutlinedIcon color="primary" fontSize="small" />
      <Box sx={{ flex: 1 }}><Typography fontWeight={800}>工作流与 AI 输出</Typography><Typography variant="caption" color="text.secondary">各次调用独立展示，仅含正文输出</Typography></Box>
      {closable && <IconButton aria-label="关闭输出详情" onClick={onClose}><ChevronLeftIcon sx={{ transform: 'rotate(180deg)' }} /></IconButton>}
    </Stack>
    <Divider />
    <Box sx={{ overflowY: 'auto', flex: 1, p: 1.5 }}>
      {!turn?.workflowRunId ? <Typography variant="body2" color="text.secondary" sx={{ p: 1 }}>选择一条消息查看对应工作流。</Typography> : <Stack spacing={1.5}>
        <Paper variant="outlined" sx={{ p: 1.5, bgcolor: 'surfaceMuted' }}>
          <Stack direction="row" alignItems="center" justifyContent="space-between" spacing={1}>
            <Typography variant="body2" fontWeight={700}>第 {turn.turnNumber} 轮分析</Typography>
            {(turn.workflowStatus === 'WAITING_HUMAN' || turn.taskStatus) && <StatusBadge status={turn.workflowStatus === 'WAITING_HUMAN' ? 'WAITING_HUMAN' : turn.taskStatus || ''} size="small" />}
          </Stack>
          <Box sx={{ mt: 0.8 }}><CopyableText text={turn.workflowRunId} /></Box>
          <Stack direction="row" justifyContent="space-between" sx={{ mt: 1 }}><Typography variant="caption" color="text.secondary">工作流进度</Typography><Typography variant="caption">{Math.round(progress)}%</Typography></Stack>
          <LinearProgress variant="determinate" value={progress} sx={{ mt: 0.4, height: 5, borderRadius: 3 }} />
          {!finishedTurn(turn) && <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.8 }}>{streamConnected ? '实时连接中' : '连接恢复中，持久化事件会自动补齐'}</Typography>}
        </Paper>
        {detail?.debateProtocol && <Alert severity={detail.debateProtocol.decision?.status === 'SELECTED' ? 'success' : 'info'}>
          研究共识：{statusLabel(detail.debateProtocol.decision?.status || 'RUNNING')}
        </Alert>}
        <Typography variant="subtitle2" fontWeight={800}>AI 调用 · {invocationIds.length}</Typography>
        {invocationIds.map((invocationId) => {
          const output = streamsById.get(invocationId);
          const invocation = invocations.find((candidate) => candidate.invocationId === invocationId);
          const nodeId = output?.nodeId || invocation?.nodeId || '';
          return <Paper key={invocationId} variant="outlined" sx={{ p: 1.35, borderRadius: '8px' }}>
            <Stack direction="row" alignItems="center" spacing={0.7} flexWrap="wrap" useFlexGap>
              <Typography variant="body2" fontWeight={800} sx={{ flex: 1, minWidth: 120 }}>{displayName(nodeId)}</Typography>
              {invocation?.status && <StatusBadge status={invocation.status} size="small" />}
            </Stack>
            <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.4, overflowWrap: 'anywhere' }}>
              {invocation ? `${invocation.providerProfileId} · ${invocation.modelName}` : '等待模型审计信息'}
            </Typography>
            {output?.text ? <Typography component="pre" variant="body2" sx={{ mt: 1, mb: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontFamily: 'inherit', maxHeight: 420, overflowY: 'auto', lineHeight: 1.65 }}>{output.text}</Typography>
              : <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>暂无正文片段</Typography>}
            {invocation?.errorMessage && <Alert severity="error" sx={{ mt: 1 }}>{invocation.errorCode}: {invocation.errorMessage}</Alert>}
          </Paper>;
        })}
        {invocationIds.length === 0 && <Typography variant="body2" color="text.secondary">等待 AI 节点开始运行…</Typography>}
        {agentTurns.length > 0 && <>
          <Typography variant="subtitle2" fontWeight={800}>席位记录 · {agentTurns.length}</Typography>
          {agentTurns.map((agentTurn) => <Paper key={agentTurn.messageId} variant="outlined" sx={{ p: 1.35, borderRadius: '8px' }}>
            <Stack direction="row" spacing={0.7} alignItems="center">
              <Typography variant="body2" fontWeight={800} sx={{ flex: 1 }}>{agentTurn.roleName} · 第 {agentTurn.round} 轮</Typography>
              <StatusBadge status={agentTurn.status} size="small" />
            </Stack>
            <Typography variant="caption" color="text.secondary">{agentTurn.messageType} · {displayName(agentTurn.nodeId)}</Typography>
            <Typography component="pre" variant="body2" sx={{ mt: 0.8, mb: 0, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontFamily: 'inherit', maxHeight: 300, overflowY: 'auto', lineHeight: 1.65 }}>
              {agentTurn.argument || agentTurn.summary}
            </Typography>
          </Paper>)}
        </>}
      </Stack>}
    </Box>
  </Paper>;
}

function finishedTurn(turn: AnalysisChatTurn): boolean {
  if (turn.taskStatus === 'FAILED' || turn.taskStatus === 'CANCELLED') return true;
  return turn.taskStatus === 'COMPLETED' && (
    turn.workflowStatus === 'PARTIAL' || turn.workflowStatus === 'COMPLETED'
    || turn.workflowStatus === 'FAILED' || turn.workflowStatus === 'CANCELLED'
  );
}

function mergeTurnPages(left: AnalysisChatTurn[], right: AnalysisChatTurn[]): AnalysisChatTurn[] {
  const merged = new Map<number, AnalysisChatTurn>();
  [...left, ...right].forEach((turn) => merged.set(turn.turnNumber, turn));
  return [...merged.values()].sort((first, second) => first.turnNumber - second.turnNumber);
}

function chatIdFromHash(): string | null {
  const match = /^#chat\/([^/?#]+)$/.exec(window.location.hash);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return null;
  }
}

function showChatRoute(chatId: string | null): void {
  const fragment = chatId ? `#chat/${encodeURIComponent(chatId)}` : '#chat';
  window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}${fragment}`);
}
