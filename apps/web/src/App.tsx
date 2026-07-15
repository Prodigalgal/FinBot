import AccountBalanceWalletOutlinedIcon from '@mui/icons-material/AccountBalanceWalletOutlined';
import AssessmentOutlinedIcon from '@mui/icons-material/AssessmentOutlined';
import AutorenewIcon from '@mui/icons-material/Autorenew';
import CalculateOutlinedIcon from '@mui/icons-material/CalculateOutlined';
import DashboardOutlinedIcon from '@mui/icons-material/DashboardOutlined';
import FactCheckOutlinedIcon from '@mui/icons-material/FactCheckOutlined';
import Inventory2OutlinedIcon from '@mui/icons-material/Inventory2Outlined';
import LanOutlinedIcon from '@mui/icons-material/LanOutlined';
import LogoutIcon from '@mui/icons-material/Logout';
import PlayCircleOutlineIcon from '@mui/icons-material/PlayCircleOutline';
import RefreshIcon from '@mui/icons-material/Refresh';
import SchemaOutlinedIcon from '@mui/icons-material/SchemaOutlined';
import SettingsOutlinedIcon from '@mui/icons-material/SettingsOutlined';
import ShowChartOutlinedIcon from '@mui/icons-material/ShowChartOutlined';
import StorageOutlinedIcon from '@mui/icons-material/StorageOutlined';
import { AppBar, Box, Chip, Drawer, IconButton, List, ListItemButton, ListItemIcon, ListItemText, MenuItem, Stack, TextField, Toolbar, Tooltip, Typography, useMediaQuery } from '@mui/material';
import type { ReactElement } from 'react';
import { lazy, Suspense, useEffect, useMemo, useState } from 'react';
import { useTheme } from '@mui/material/styles';

import { api } from './api';
import { DashboardPage } from './DashboardPage';
import type { OperationsOverview, ResearchLaunch } from './types';
import { LoadingBlock, formatTime, statusColor } from './ui';

const AutonomousPage = lazy(() => import('./AutonomousPage').then((module) => ({ default: module.AutonomousPage })));
const CatalogPage = lazy(() => import('./CatalogPage').then((module) => ({ default: module.CatalogPage })));
const HistoryPage = lazy(() => import('./HistoryPage').then((module) => ({ default: module.HistoryPage })));
const IngestionPage = lazy(() => import('./IngestionPage').then((module) => ({ default: module.IngestionPage })));
const MarketAnalysisPage = lazy(() => import('./MarketAnalysisPage').then((module) => ({ default: module.MarketAnalysisPage })));
const NetworkPage = lazy(() => import('./NetworkPage').then((module) => ({ default: module.NetworkPage })));
const QuantPage = lazy(() => import('./QuantPage').then((module) => ({ default: module.QuantPage })));
const ReportsPage = lazy(() => import('./ReportsPage').then((module) => ({ default: module.ReportsPage })));
const ResearchPage = lazy(() => import('./ResearchPage').then((module) => ({ default: module.ResearchPage })));
const SettingsPage = lazy(() => import('./SettingsPage').then((module) => ({ default: module.SettingsPage })));
const TradingPage = lazy(() => import('./TradingPage').then((module) => ({ default: module.TradingPage })));
const WorkflowPage = lazy(() => import('./WorkflowPage').then((module) => ({ default: module.WorkflowPage })));

const drawerWidth = 236;
type PageId = 'dashboard' | 'catalog' | 'research' | 'autonomous' | 'review' | 'market' | 'quant' | 'trading' | 'ingestion' | 'reports' | 'settings' | 'workflow' | 'network';
interface PageDefinition { id: PageId; group: string; label: string; title: string; icon: ReactElement }
const pages: PageDefinition[] = [
  { id: 'dashboard', group: '工作台', label: '研究决策工作台', title: '研究决策工作台', icon: <DashboardOutlinedIcon /> },
  { id: 'catalog', group: '研究决策', label: '产品与自选', title: '产品库与自选列表', icon: <Inventory2OutlinedIcon /> },
  { id: 'research', group: '研究决策', label: '发起研究', title: '即时研究流水线', icon: <PlayCircleOutlineIcon /> },
  { id: 'autonomous', group: '研究决策', label: '自动研究', title: '自动研究循环', icon: <AutorenewIcon /> },
  { id: 'review', group: '研究决策', label: '复核与效果', title: '研究复核与效果反馈', icon: <FactCheckOutlinedIcon /> },
  { id: 'market', group: '研究分析与验证', label: '走势预测', title: '实盘行情走势预测', icon: <ShowChartOutlinedIcon /> },
  { id: 'quant', group: '研究分析与验证', label: '量化验证', title: '量化研究与预测验证', icon: <CalculateOutlinedIcon /> },
  { id: 'trading', group: '研究分析与验证', label: '模拟验证', title: '模拟交易验证与永久审计', icon: <AccountBalanceWalletOutlinedIcon /> },
  { id: 'ingestion', group: '任务与记录', label: '采集与处理', title: '信息采集、清理与证据', icon: <StorageOutlinedIcon /> },
  { id: 'reports', group: '任务与记录', label: '运行报告', title: '结构化运行报告', icon: <AssessmentOutlinedIcon /> },
  { id: 'settings', group: '系统', label: '系统设置', title: '系统、模型与交易配置', icon: <SettingsOutlinedIcon /> },
  { id: 'workflow', group: '系统', label: 'AI 工作流', title: 'AI 调度小组与自由工作流', icon: <SchemaOutlinedIcon /> },
  { id: 'network', group: '系统', label: '网络诊断', title: '代理路由与网络诊断', icon: <LanOutlinedIcon /> },
];
const pageIds = new Set(pages.map((item) => item.id));

export function App() {
  const theme = useTheme();
  const desktop = useMediaQuery(theme.breakpoints.up('md'));
  const [page, setPageState] = useState<PageId>(() => pageFromHash());
  const [operations, setOperations] = useState<OperationsOverview | null>(null);
  const [researchQuestion, setResearchQuestion] = useState<string | undefined>();
  const [researchLaunch, setResearchLaunch] = useState<ResearchLaunch | null>(null);
  const current = useMemo(() => pages.find((item) => item.id === page) || pages[0], [page]);
  const refreshStatus = () => api.operations().then(setOperations).catch(() => undefined);
  const navigate = (target: string) => {
    if (!pageIds.has(target as PageId)) return;
    const next = target as PageId;
    setPageState(next);
    window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}#${next}`);
    window.scrollTo({ top: 0, behavior: 'auto' });
  };

  useEffect(() => {
    let source: EventSource | null = null;
    let timer: number | undefined;
    let retryMilliseconds = 3000;
    let disposed = false;
    const connect = () => {
      if (disposed) return;
      source = new EventSource(api.operationsEventsUrl(), { withCredentials: true });
      source.addEventListener('operations.snapshot', (event) => {
        setOperations(JSON.parse((event as MessageEvent<string>).data) as OperationsOverview);
        retryMilliseconds = 3000;
      });
      source.onerror = () => {
        source?.close(); source = null;
        void refreshStatus();
        if (!disposed) timer = window.setTimeout(connect, retryMilliseconds);
        retryMilliseconds = Math.min(30000, retryMilliseconds * 2);
      };
    };
    void refreshStatus(); connect();
    const hashListener = () => setPageState(pageFromHash());
    window.addEventListener('hashchange', hashListener);
    return () => { disposed = true; source?.close(); if (timer !== undefined) window.clearTimeout(timer); window.removeEventListener('hashchange', hashListener); };
  }, []);

  const openResearch = (question: string) => { setResearchQuestion(question); setResearchLaunch(null); navigate('research'); };
  const openLaunch = (launch: ResearchLaunch) => { setResearchLaunch(launch); setResearchQuestion(undefined); navigate('research'); };
  const content = (() => {
    switch (page) {
      case 'dashboard': return <DashboardPage onNavigate={navigate} />;
      case 'catalog': return <CatalogPage onResearch={openResearch} />;
      case 'research': return <ResearchPage initialQuestion={researchQuestion} initialLaunch={researchLaunch} />;
      case 'autonomous': return <AutonomousPage />;
      case 'review': return <HistoryPage onOpenRun={openLaunch} />;
      case 'market': return <MarketAnalysisPage onOpenRun={openLaunch} />;
      case 'quant': return <QuantPage />;
      case 'trading': return <TradingPage />;
      case 'ingestion': return <IngestionPage />;
      case 'reports': return <ReportsPage />;
      case 'settings': return <SettingsPage />;
      case 'workflow': return <WorkflowPage />;
      case 'network': return <NetworkPage />;
    }
  })();
  const workerOnline = operations?.workers.some((worker) => worker.status === 'RUNNING') || false;
  const groups = [...new Set(pages.map((item) => item.group))];
  return <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default' }}>
    {desktop && <Drawer component="nav" aria-label="主导航" variant="permanent" sx={{ width: drawerWidth, flexShrink: 0, '& .MuiDrawer-paper': { width: drawerWidth, boxSizing: 'border-box', borderRightColor: 'divider' } }}>
      <Box sx={{ px: 2, py: 2.25 }}><Typography variant="h2">FinBot</Typography><Typography variant="caption" color="text.secondary">AI 研究与走势预测</Typography></Box>
      <Box sx={{ overflowY: 'auto', px: 1 }}>{groups.map((group) => <Box key={group} sx={{ mb: 1 }}><Typography variant="overline" color="text.secondary" sx={{ px: 1.25 }}>{group}</Typography><List disablePadding>{pages.filter((item) => item.group === group).map((item) => <ListItemButton key={item.id} selected={page === item.id} onClick={() => navigate(item.id)} sx={{ borderRadius: 1, mb: .25, minHeight: 38 }}><ListItemIcon sx={{ minWidth: 34 }}>{item.icon}</ListItemIcon><ListItemText primary={item.label} primaryTypographyProps={{ fontSize: 13, fontWeight: 700 }} /></ListItemButton>)}</List></Box>)}</Box>
      <Box sx={{ mt: 'auto', p: 2 }}><Chip size="small" color={workerOnline ? 'success' : 'warning'} label={workerOnline ? 'Worker 在线' : 'Worker 离线'} /></Box>
    </Drawer>}
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <AppBar position="sticky" color="inherit" elevation={0} sx={{ borderBottom: '1px solid', borderColor: 'divider' }}><Toolbar sx={{ gap: 1.25, minHeight: { xs: 64, md: 68 } }}>{!desktop && <TextField select size="small" value={page} onChange={(event) => navigate(event.target.value)} sx={{ width: 170 }}>{groups.map((group) => pages.filter((item) => item.group === group).map((item) => <MenuItem key={item.id} value={item.id}>{item.label}</MenuItem>))}</TextField>}<Box sx={{ flex: 1, minWidth: 0 }}><Typography variant="h1" noWrap>{current.title}</Typography><Typography variant="caption" color="text.secondary" noWrap>Java 26 · PostgreSQL · Python Quant{operations && <Box component="span" sx={{ display: { xs: 'none', lg: 'inline' } }}> · 状态更新 {formatTime(operations.generatedAt)}</Box>}</Typography></Box><Chip size="small" color={statusColor(workerOnline ? 'RUNNING' : 'FAILED')} label={workerOnline ? '常驻运行' : '需检查'} sx={{ display: { xs: 'none', sm: 'inline-flex' } }} /><Tooltip title="刷新运行状态"><IconButton onClick={() => void refreshStatus()}><RefreshIcon /></IconButton></Tooltip><Tooltip title="退出登录"><IconButton onClick={() => api.logout().finally(() => window.location.reload())}><LogoutIcon /></IconButton></Tooltip></Toolbar></AppBar>
      <Box component="main" sx={{ p: { xs: 1.25, sm: 2, lg: 2.5 }, maxWidth: 1680, mx: 'auto' }}><Suspense fallback={<LoadingBlock label="正在加载工作区" />}>{content}</Suspense></Box>
    </Box>
  </Box>;
}

function pageFromHash(): PageId {
  const value = window.location.hash.replace(/^#/, '').split('/', 1)[0];
  return pageIds.has(value as PageId) ? value as PageId : 'dashboard';
}
