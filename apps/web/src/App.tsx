import AccountBalanceWalletOutlinedIcon from '@mui/icons-material/AccountBalanceWalletOutlined';
import AssessmentOutlinedIcon from '@mui/icons-material/AssessmentOutlined';
import AutorenewIcon from '@mui/icons-material/Autorenew';
import CalculateOutlinedIcon from '@mui/icons-material/CalculateOutlined';
import DashboardOutlinedIcon from '@mui/icons-material/DashboardOutlined';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import FactCheckOutlinedIcon from '@mui/icons-material/FactCheckOutlined';
import Inventory2OutlinedIcon from '@mui/icons-material/Inventory2Outlined';
import LanOutlinedIcon from '@mui/icons-material/LanOutlined';
import LogoutIcon from '@mui/icons-material/Logout';
import MenuIcon from '@mui/icons-material/Menu';
import PlayCircleOutlineIcon from '@mui/icons-material/PlayCircleOutline';
import RefreshIcon from '@mui/icons-material/Refresh';
import SchemaOutlinedIcon from '@mui/icons-material/SchemaOutlined';
import SettingsOutlinedIcon from '@mui/icons-material/SettingsOutlined';
import ShowChartOutlinedIcon from '@mui/icons-material/ShowChartOutlined';
import StorageOutlinedIcon from '@mui/icons-material/StorageOutlined';
import {
  AppBar,
  Box,
  ButtonBase,
  Chip,
  Collapse,
  Drawer,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Stack,
  Toolbar,
  Tooltip,
  Typography,
  useMediaQuery,
} from '@mui/material';
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

/** Desktop rail width (~240px). */
const drawerWidth = 240;
const commandBarHeight = { xs: 60, sm: 64 } as const;
const shellRadius = 1; // theme shape max 6px
const zeroTracking = { letterSpacing: 0 } as const;

type PageId = 'dashboard' | 'catalog' | 'research' | 'autonomous' | 'review' | 'market' | 'quant' | 'trading' | 'ingestion' | 'reports' | 'settings' | 'workflow' | 'network';
interface PageDefinition { id: PageId; group: string; label: string; title: string; icon: ReactElement }
interface NavigationGroupDefinition {
  key: string;
  label: string;
  description: string;
  initiallyExpanded: boolean;
}

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
const navigationGroups: NavigationGroupDefinition[] = [
  { key: '工作台', label: '工作台', description: '总览与待办', initiallyExpanded: true },
  { key: '研究决策', label: '研究流程', description: '从问题到结论', initiallyExpanded: true },
  { key: '研究分析与验证', label: '分析与验证', description: '行情、量化与模拟', initiallyExpanded: true },
  { key: '任务与记录', label: '数据与记录', description: '采集、证据与报告', initiallyExpanded: false },
  { key: '系统', label: '系统管理', description: '模型、工作流与网络', initiallyExpanded: false },
];
const pageIds = new Set(pages.map((item) => item.id));

export function App() {
  const theme = useTheme();
  const desktop = useMediaQuery(theme.breakpoints.up('md'));
  const [page, setPageState] = useState<PageId>(() => pageFromHash());
  const [operations, setOperations] = useState<OperationsOverview | null>(null);
  const [researchQuestion, setResearchQuestion] = useState<string | undefined>();
  const [researchLaunch, setResearchLaunch] = useState<ResearchLaunch | null>(null);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [expandedGroups, setExpandedGroups] = useState<Record<string, boolean>>(() =>
    Object.fromEntries(navigationGroups.map((group) => [group.key, group.initiallyExpanded])),
  );
  const current = useMemo(() => pages.find((item) => item.id === page) || pages[0], [page]);
  const currentGroupLabel = navigationGroups.find((group) => group.key === current.group)?.label ?? current.group;
  const refreshStatus = () => api.operations().then(setOperations).catch(() => undefined);
  const navigate = (target: string) => {
    if (!pageIds.has(target as PageId)) return;
    const next = target as PageId;
    setPageState(next);
    window.history.replaceState(null, '', `${window.location.pathname}${window.location.search}#${next}`);
    window.scrollTo({ top: 0, behavior: 'auto' });
  };
  const navigateFromMenu = (target: PageId) => {
    navigate(target);
    setMobileNavOpen(false);
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
        source?.close();
        source = null;
        void refreshStatus();
        if (!disposed) timer = window.setTimeout(connect, retryMilliseconds);
        retryMilliseconds = Math.min(30000, retryMilliseconds * 2);
      };
    };
    void refreshStatus();
    connect();
    const hashListener = () => setPageState(pageFromHash());
    window.addEventListener('hashchange', hashListener);
    return () => {
      disposed = true;
      source?.close();
      if (timer !== undefined) window.clearTimeout(timer);
      window.removeEventListener('hashchange', hashListener);
    };
  }, []);

  useEffect(() => {
    setExpandedGroups((groups) => (groups[current.group] ? groups : { ...groups, [current.group]: true }));
  }, [current.group]);

  useEffect(() => {
    if (desktop) setMobileNavOpen(false);
  }, [desktop]);

  const openResearch = (question: string) => {
    setResearchQuestion(question);
    setResearchLaunch(null);
    navigate('research');
  };
  const openLaunch = (launch: ResearchLaunch) => {
    setResearchLaunch(launch);
    setResearchQuestion(undefined);
    navigate('research');
  };
  const toggleGroup = (groupKey: string) =>
    setExpandedGroups((groups) => ({ ...groups, [groupKey]: !groups[groupKey] }));

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

  const navigationBody = (
    <>
      <Box
        sx={{
          px: 2,
          py: 2,
          borderBottom: '1px solid',
          borderColor: 'divider',
          flexShrink: 0,
        }}
      >
        <Typography variant="h2" sx={{ ...zeroTracking, fontSize: 18, lineHeight: 1.25 }}>
          FinBot
        </Typography>
        <Typography variant="caption" color="text.secondary" sx={zeroTracking}>
          AI 研究与走势预测
        </Typography>
      </Box>
      <Box sx={{ overflowY: 'auto', flex: 1, minHeight: 0, px: 1, py: 1.25 }}>
        {navigationGroups.map((group) => {
          const expanded = expandedGroups[group.key] ?? group.initiallyExpanded;
          const groupPages = pages.filter((item) => item.group === group.key);
          return (
            <Box key={group.key} sx={{ mb: 1 }}>
              <ButtonBase
                data-testid={`nav-group-${group.key}`}
                aria-expanded={expanded}
                onClick={() => toggleGroup(group.key)}
                sx={{
                  width: '100%',
                  borderRadius: shellRadius,
                  px: 1.25,
                  py: 0.6,
                  textAlign: 'left',
                  ...zeroTracking,
                  '&:hover': { bgcolor: 'action.hover' },
                }}
              >
                <Box sx={{ flex: 1, minWidth: 0 }}>
                  <Typography
                    variant="overline"
                    color="text.secondary"
                    display="block"
                    lineHeight={1.2}
                    sx={zeroTracking}
                  >
                    {group.label}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" noWrap sx={zeroTracking}>
                    {group.description}
                  </Typography>
                </Box>
                <ExpandMoreIcon
                  fontSize="small"
                  sx={{
                    color: 'text.secondary',
                    transform: expanded ? 'rotate(180deg)' : 'none',
                    transition: 'transform 160ms ease',
                  }}
                />
              </ButtonBase>
              <Collapse in={expanded} timeout="auto" unmountOnExit>
                <List disablePadding sx={{ mt: 0.35 }}>
                  {groupPages.map((item) => {
                    const selected = page === item.id;
                    return (
                      <ListItemButton
                        key={item.id}
                        selected={selected}
                        aria-current={selected ? 'page' : undefined}
                        onClick={() => (desktop ? navigate(item.id) : navigateFromMenu(item.id))}
                        sx={{
                          borderRadius: shellRadius,
                          mb: 0.25,
                          minHeight: 40,
                          pl: 1.25,
                          pr: 1,
                          borderLeft: '3px solid',
                          borderLeftColor: selected ? 'primary.main' : 'transparent',
                          bgcolor: selected ? 'action.selected' : 'transparent',
                          '&.Mui-selected': {
                            bgcolor: 'action.selected',
                            color: 'primary.dark',
                            '&:hover': { bgcolor: 'action.selected' },
                            '& .MuiListItemIcon-root': { color: 'primary.main' },
                          },
                          '&:hover': {
                            bgcolor: selected ? 'action.selected' : 'action.hover',
                          },
                        }}
                      >
                        <ListItemIcon sx={{ minWidth: 34, color: selected ? 'primary.main' : 'text.secondary' }}>
                          {item.icon}
                        </ListItemIcon>
                        <ListItemText
                          primary={item.label}
                          primaryTypographyProps={{
                            fontSize: 13,
                            fontWeight: selected ? 700 : 600,
                            letterSpacing: 0,
                            noWrap: true,
                          }}
                        />
                      </ListItemButton>
                    );
                  })}
                </List>
              </Collapse>
            </Box>
          );
        })}
      </Box>
      <Box
        sx={{
          mt: 'auto',
          p: 1.5,
          borderTop: '1px solid',
          borderColor: 'divider',
          flexShrink: 0,
        }}
      >
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
          <Chip size="small" color={workerOnline ? 'success' : 'warning'} label={workerOnline ? 'Worker 在线' : 'Worker 离线'} />
          <Typography variant="caption" color="text.secondary" sx={zeroTracking}>
            常驻调度
          </Typography>
        </Stack>
      </Box>
    </>
  );

  const drawerPaperSx = {
    width: drawerWidth,
    boxSizing: 'border-box' as const,
    borderRightColor: 'divider',
    bgcolor: 'background.paper',
    display: 'flex',
    flexDirection: 'column' as const,
  };

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default', overflowX: 'hidden' }}>
      {desktop && (
        <Drawer
          component="nav"
          aria-label="主导航"
          variant="permanent"
          open
          sx={{
            width: drawerWidth,
            flexShrink: 0,
            '& .MuiDrawer-paper': drawerPaperSx,
          }}
        >
          {navigationBody}
        </Drawer>
      )}

      {!desktop && (
        <Drawer
          id="mobile-navigation-drawer"
          component="nav"
          aria-label="主导航"
          variant="temporary"
          open={mobileNavOpen}
          onClose={() => setMobileNavOpen(false)}
          ModalProps={{ keepMounted: true }}
          sx={{
            display: { xs: 'block', md: 'none' },
            '& .MuiDrawer-paper': {
              ...drawerPaperSx,
              maxWidth: 'min(240px, 86vw)',
            },
          }}
        >
          {navigationBody}
        </Drawer>
      )}

      <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <AppBar
          position="sticky"
          color="inherit"
          elevation={0}
          sx={{
            borderBottom: '1px solid',
            borderColor: 'divider',
            bgcolor: 'background.paper',
            backgroundImage: 'none',
          }}
        >
          <Toolbar
            disableGutters
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: { xs: 0.75, sm: 1.25 },
              px: { xs: 1.5, sm: 2 },
              minHeight: commandBarHeight,
              height: commandBarHeight,
              boxSizing: 'border-box',
            }}
          >
            {!desktop && (
              <IconButton
                data-testid="mobile-navigation"
                aria-label="打开导航菜单"
                aria-controls="mobile-navigation-drawer"
                aria-expanded={mobileNavOpen}
                edge="start"
                onClick={() => setMobileNavOpen(true)}
                sx={{
                  width: 44,
                  height: 44,
                  flexShrink: 0,
                  borderRadius: shellRadius,
                }}
              >
                <MenuIcon />
              </IconButton>
            )}

            <Box sx={{ flex: 1, minWidth: 0, overflow: 'hidden', py: 0.5 }}>
              <Typography
                variant="caption"
                color="text.secondary"
                noWrap
                sx={{ ...zeroTracking, display: 'block', lineHeight: 1.2 }}
              >
                {currentGroupLabel} / {current.label}
              </Typography>
              <Typography
                data-testid="app-page-title"
                component="h1"
                variant="h1"
                noWrap
                sx={{
                  ...zeroTracking,
                  fontSize: { xs: 16, sm: 18, md: 20 },
                  fontWeight: 700,
                  lineHeight: 1.25,
                  color: 'text.primary',
                }}
              >
                {current.title}
              </Typography>
              <Typography
                variant="caption"
                color="text.secondary"
                noWrap
                sx={{ ...zeroTracking, display: { xs: 'none', sm: 'block' }, lineHeight: 1.25 }}
              >
                Java 26 · PostgreSQL · Python Quant
                {operations && (
                  <Box component="span" sx={{ display: { xs: 'none', lg: 'inline' } }}>
                    {' '}
                    · 状态更新 {formatTime(operations.generatedAt)}
                  </Box>
                )}
              </Typography>
            </Box>

            <Chip
              size="small"
              color={statusColor(workerOnline ? 'RUNNING' : 'FAILED')}
              label={workerOnline ? '常驻运行' : '需检查'}
              sx={{ display: { xs: 'none', sm: 'inline-flex' }, flexShrink: 0 }}
            />
            <Tooltip title="刷新运行状态">
              <IconButton
                aria-label="刷新运行状态"
                onClick={() => void refreshStatus()}
                sx={{ width: 40, height: 40, flexShrink: 0, borderRadius: shellRadius }}
              >
                <RefreshIcon />
              </IconButton>
            </Tooltip>
            <Tooltip title="退出登录">
              <IconButton
                aria-label="退出登录"
                onClick={() => api.logout().finally(() => window.location.reload())}
                sx={{ width: 40, height: 40, flexShrink: 0, borderRadius: shellRadius }}
              >
                <LogoutIcon />
              </IconButton>
            </Tooltip>
          </Toolbar>
        </AppBar>

        <Box
          component="main"
          sx={{
            flex: 1,
            p: { xs: 2, sm: 2, lg: 2.5 },
            maxWidth: 1680,
            width: '100%',
            mx: 'auto',
            boxSizing: 'border-box',
            minWidth: 0,
          }}
        >
          <Suspense fallback={<LoadingBlock label="正在加载工作区" />}>{content}</Suspense>
        </Box>
      </Box>
    </Box>
  );
}

function pageFromHash(): PageId {
  const value = window.location.hash.replace(/^#/, '').split('/', 1)[0];
  return pageIds.has(value as PageId) ? (value as PageId) : 'dashboard';
}
