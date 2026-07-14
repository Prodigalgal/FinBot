import AccountBalanceWalletOutlinedIcon from '@mui/icons-material/AccountBalanceWalletOutlined';
import DashboardOutlinedIcon from '@mui/icons-material/DashboardOutlined';
import HistoryOutlinedIcon from '@mui/icons-material/HistoryOutlined';
import Inventory2OutlinedIcon from '@mui/icons-material/Inventory2Outlined';
import LogoutIcon from '@mui/icons-material/Logout';
import PlayCircleOutlineIcon from '@mui/icons-material/PlayCircleOutline';
import RefreshIcon from '@mui/icons-material/Refresh';
import SchemaOutlinedIcon from '@mui/icons-material/SchemaOutlined';
import SettingsOutlinedIcon from '@mui/icons-material/SettingsOutlined';
import TuneOutlinedIcon from '@mui/icons-material/TuneOutlined';
import { AppBar, Box, Chip, Drawer, IconButton, List, ListItemButton, ListItemIcon, ListItemText, Stack, Tab, Tabs, Toolbar, Tooltip, Typography, useMediaQuery } from '@mui/material';
import type { ReactElement } from 'react';
import { useEffect, useMemo, useState } from 'react';
import { useTheme } from '@mui/material/styles';

import { api } from './api';
import { CatalogPage } from './CatalogPage';
import { DashboardPage } from './DashboardPage';
import { HistoryPage } from './HistoryPage';
import { OperationsPage } from './OperationsPage';
import { ResearchPage } from './ResearchPage';
import { SettingsPage } from './SettingsPage';
import { TradingPage } from './TradingPage';
import { WorkflowPage } from './WorkflowPage';
import type { OperationsOverview, ResearchLaunch } from './types';
import { statusColor } from './ui';

const drawerWidth = 224;
type PageId = 'dashboard' | 'research' | 'history' | 'catalog' | 'trading' | 'workflow' | 'operations' | 'settings';
const pages: Array<{ id: PageId; label: string; title: string; icon: ReactElement }> = [
  { id: 'dashboard', label: '工作台', title: '研究与交易工作台', icon: <DashboardOutlinedIcon /> },
  { id: 'research', label: '即时研究', title: '即时研究流水线', icon: <PlayCircleOutlineIcon /> },
  { id: 'history', label: '研究历史', title: '研究历史与多轮辩论', icon: <HistoryOutlinedIcon /> },
  { id: 'catalog', label: '产品与自选', title: '规范化产品库与自选', icon: <Inventory2OutlinedIcon /> },
  { id: 'trading', label: '模拟交易', title: '账户、盈亏与执行审计', icon: <AccountBalanceWalletOutlinedIcon /> },
  { id: 'workflow', label: 'AI 工作流', title: 'AI 调度小组与工作流', icon: <SchemaOutlinedIcon /> },
  { id: 'operations', label: '运行与调度', title: '常驻任务、信息源与证据', icon: <TuneOutlinedIcon /> },
  { id: 'settings', label: '系统配置', title: '模型、费率与风险配置', icon: <SettingsOutlinedIcon /> },
];

export function App() {
  const theme = useTheme();
  const desktop = useMediaQuery(theme.breakpoints.up('md'));
  const [page, setPage] = useState<PageId>('dashboard');
  const [operations, setOperations] = useState<OperationsOverview | null>(null);
  const [researchQuestion, setResearchQuestion] = useState<string | undefined>();
  const [researchLaunch, setResearchLaunch] = useState<ResearchLaunch | null>(null);
  const current = useMemo(() => pages.find((item) => item.id === page) || pages[0], [page]);
  const refreshStatus = () => api.operations().then(setOperations).catch(() => undefined);
  useEffect(() => { void refreshStatus(); const timer = window.setInterval(refreshStatus, 30000); return () => window.clearInterval(timer); }, []);
  const openResearch = (question: string) => { setResearchQuestion(question); setResearchLaunch(null); setPage('research'); };
  const openLaunch = (launch: ResearchLaunch) => { setResearchLaunch(launch); setResearchQuestion(undefined); setPage('research'); };
  const content = (() => {
    switch (page) {
      case 'dashboard': return <DashboardPage />;
      case 'research': return <ResearchPage initialQuestion={researchQuestion} initialLaunch={researchLaunch} />;
      case 'history': return <HistoryPage onOpenRun={openLaunch} />;
      case 'catalog': return <CatalogPage onResearch={openResearch} />;
      case 'trading': return <TradingPage />;
      case 'workflow': return <WorkflowPage />;
      case 'operations': return <OperationsPage />;
      case 'settings': return <SettingsPage />;
    }
  })();
  const workerOnline = operations?.workers.some((worker) => worker.status === 'RUNNING') || false;
  return <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default' }}>
    {desktop && <Drawer variant="permanent" sx={{ width: drawerWidth, flexShrink: 0, '& .MuiDrawer-paper': { width: drawerWidth, boxSizing: 'border-box', borderRightColor: 'divider' } }}>
      <Box sx={{ px: 2, py: 2.25 }}><Typography variant="h2">FinBot</Typography><Typography variant="caption" color="text.secondary">AI 研究与模拟交易</Typography></Box>
      <List sx={{ px: 1 }}>{pages.map((item) => <ListItemButton key={item.id} selected={page === item.id} onClick={() => setPage(item.id)} sx={{ borderRadius: 1, mb: .5 }}><ListItemIcon sx={{ minWidth: 36 }}>{item.icon}</ListItemIcon><ListItemText primary={item.label} primaryTypographyProps={{ fontSize: 13, fontWeight: 700 }} /></ListItemButton>)}</List>
      <Box sx={{ mt: 'auto', p: 2 }}><Chip size="small" color={workerOnline ? 'success' : 'warning'} label={workerOnline ? 'Worker 在线' : 'Worker 离线'} /></Box>
    </Drawer>}
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <AppBar position="sticky" color="inherit" elevation={0} sx={{ borderBottom: '1px solid', borderColor: 'divider' }}><Toolbar sx={{ gap: 1.5 }}><Box sx={{ flex: 1, minWidth: 0 }}><Typography variant="h1">{current.title}</Typography><Typography variant="caption" color="text.secondary">生产主系统 Java 26 · PostgreSQL · Python 量化服务</Typography></Box><Chip size="small" color={statusColor(workerOnline ? 'RUNNING' : 'FAILED')} label={workerOnline ? '常驻运行' : '需检查'} /><Tooltip title="刷新运行状态"><IconButton onClick={() => void refreshStatus()}><RefreshIcon /></IconButton></Tooltip><Tooltip title="退出登录"><IconButton onClick={() => api.logout().finally(() => window.location.reload())}><LogoutIcon /></IconButton></Tooltip></Toolbar>{!desktop && <Tabs value={page} onChange={(_event, value: PageId) => setPage(value)} variant="scrollable" scrollButtons="auto" sx={{ px: 1 }}>{pages.map((item) => <Tab key={item.id} value={item.id} icon={item.icon} iconPosition="start" label={item.label} />)}</Tabs>}</AppBar>
      <Box component="main" sx={{ p: { xs: 1.5, sm: 2, lg: 2.5 }, maxWidth: 1680, mx: 'auto' }}>{content}</Box>
    </Box>
  </Box>;
}
