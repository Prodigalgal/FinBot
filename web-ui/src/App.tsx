import {
  Alert,
  AppBar,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Collapse,
  Divider,
  Drawer,
  FormControlLabel,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  ListSubheader,
  MenuItem,
  Select,
  Stack,
  Switch,
  TextField,
  Toolbar,
  Tooltip,
  Typography,
  useMediaQuery,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet';
import ArticleIcon from '@mui/icons-material/Article';
import AutorenewIcon from '@mui/icons-material/Autorenew';
import DashboardIcon from '@mui/icons-material/Dashboard';
import DeleteIcon from '@mui/icons-material/Delete';
import ExpandLessIcon from '@mui/icons-material/ExpandLess';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import FactCheckOutlinedIcon from '@mui/icons-material/FactCheckOutlined';
import Inventory2Icon from '@mui/icons-material/Inventory2';
import LanIcon from '@mui/icons-material/Lan';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import RefreshIcon from '@mui/icons-material/Refresh';
import SaveIcon from '@mui/icons-material/Save';
import ScienceIcon from '@mui/icons-material/Science';
import QueryStatsIcon from '@mui/icons-material/QueryStats';
import SettingsIcon from '@mui/icons-material/Settings';
import ShieldIcon from '@mui/icons-material/Shield';
import TravelExploreIcon from '@mui/icons-material/TravelExplore';
import WorkIcon from '@mui/icons-material/Work';
import type { Dispatch, ReactNode, SetStateAction } from 'react';
import { lazy, Suspense, useCallback, useEffect, useMemo, useState } from 'react';
import { useTheme } from '@mui/material/styles';

import { api } from './api';
import { AIExperimentPanel } from './AIExperimentPanel';
import { CouncilConfigPanel } from './CouncilConfigPanel';
import { ExchangeAccountsPanel } from './ExchangeAccountsPanel';
import { InstantResearchPanel } from './InstantResearchPanel';
import { AutonomousPanel, OperatorPanel, OverviewPanel, ReportsPanel, ResearchPanel } from './OperationsPanels';
import { ProductCenterPanel } from './ProductCenterPanel';
import { QuantRiskPanel } from './QuantRiskPanel';
import { ResearchGovernancePanel } from './ResearchGovernancePanel';
import { SetupPanel } from './SetupPanel';
import type { AIConfigPayload, AIPromptConfig, AISiteConfig, AITaskBinding, AutonomousStatusPayload, ConfigFieldSpec, InstantResearchSeed, JobRecord, ProxyDiagnostics, StatusPayload, SystemConfigPayload } from './types';
import { statusColor, statusText } from './utils';

const drawerWidth = 232;
const CouncilWorkflowPanel = lazy(() => import('./CouncilWorkflowPanel').then((module) => ({ default: module.CouncilWorkflowPanel })));
type AIConfigForm = Pick<AIConfigPayload, 'sites' | 'task_bindings' | 'prompts' | 'council_templates' | 'experiments'>;
const configInputSx = {
  '& .MuiOutlinedInput-root': {
    minHeight: 40,
    borderRadius: 1,
    bgcolor: 'background.paper',
    alignItems: 'center',
  },
  '& .MuiOutlinedInput-root.MuiInputBase-multiline': {
    alignItems: 'flex-start',
    py: 0.75,
  },
  '& .MuiInputBase-input': {
    fontSize: 13,
    lineHeight: 1.45,
  },
  '& .MuiSelect-select': {
    display: 'flex',
    alignItems: 'center',
    minHeight: 'unset',
  },
};

const navGroups = [
  {
    id: 'workspace',
    label: null,
    items: [{ id: 'overview', label: '工作台', icon: <DashboardIcon /> }],
  },
  {
    id: 'research-decision',
    label: '研究决策',
    items: [
      { id: 'products', label: '产品与自选', icon: <Inventory2Icon /> },
      { id: 'instant', label: '发起研究', icon: <TravelExploreIcon /> },
      { id: 'autonomous', label: '自动研究', icon: <AutorenewIcon /> },
      { id: 'reviews', label: '复核与历史', icon: <FactCheckOutlinedIcon /> },
    ],
  },
  {
    id: 'analysis-trading',
    label: '分析与交易',
    items: [
      { id: 'operator', label: '市场分析', icon: <WorkIcon /> },
      { id: 'quant', label: '量化验证', icon: <QueryStatsIcon /> },
      { id: 'accounts', label: '模拟账户', icon: <AccountBalanceWalletIcon /> },
    ],
  },
  {
    id: 'tasks-records',
    label: '任务与记录',
    items: [
      { id: 'research', label: '采集与处理', icon: <ScienceIcon /> },
      { id: 'reports', label: '运行报告', icon: <ArticleIcon /> },
    ],
  },
  {
    id: 'system',
    label: '系统',
    items: [
      { id: 'config', label: '系统设置', icon: <SettingsIcon /> },
      { id: 'proxy', label: '网络诊断', icon: <LanIcon /> },
    ],
  },
];

const configSubmenuItems = [
  { id: 'setup', label: '启用向导' },
  { id: 'base', label: '运行基础' },
  { id: 'proxy', label: '网络与代理' },
  { id: 'workflow', label: '研究与交易参数' },
  { id: 'ai-sites', label: '模型服务' },
  { id: 'ai-bindings', label: '模型分工' },
  { id: 'ai-prompts', label: '提示词模板' },
  { id: 'ai-workflow', label: '辩论工作流' },
  { id: 'ai-council', label: '角色与轮次' },
  { id: 'ai-experiments', label: 'A/B 实验' },
  { id: 'macro', label: '宏观数据密钥' },
];

const pageMeta: Record<string, { title: string; subtitle: string }> = {
  overview: { title: '研究决策工作台', subtitle: '先看系统与待办，再进入产品研究、复核和模拟账户' },
  products: { title: '产品与自选', subtitle: '筛选交易产品、维护关注列表，并从产品直接发起研究' },
  accounts: { title: '模拟账户与盈亏', subtitle: '查看 Gate TestNet 与 Bybit Demo 的账户、持仓和区间盈亏' },
  instant: { title: '发起即时研究', subtitle: '输入问题并跟踪信息收集、AI 分析、多轮辩论与最终结论' },
  reviews: { title: '复核、历史与效果', subtitle: '审批方向性建议，回放运行记录并评估建议表现' },
  autonomous: { title: '自动研究', subtitle: '查看或手动触发完整的采集、分析、辩论、筛选与风控流程' },
  operator: { title: '快速市场分析', subtitle: '按标的、交易所与周期运行公共行情技术分析' },
  quant: { title: '量化验证与硬风控', subtitle: '计算安全杠杆、仓位、强平距离与账户级 Kill Switch' },
  research: { title: '手动采集与处理', subtitle: '按需运行信息采集、AI 压缩和补证据任务' },
  proxy: { title: '网络与代理诊断', subtitle: '检查交易所、信息源和外部服务的代理路由与连通性' },
  config: { title: '系统设置', subtitle: '管理运行参数、模型服务、角色与辩论工作流' },
  reports: { title: '运行报告与审计', subtitle: '查看自动研究、采集、市场分析、风险与 AI 治理结果' },
};

export function App() {
  const muiTheme = useTheme();
  const desktopNav = useMediaQuery(muiTheme.breakpoints.up('md'));
  const [tab, setTab] = useState('overview');
  const [configSection, setConfigSection] = useState('setup');
  const [configMenuOpen, setConfigMenuOpen] = useState(true);
  const [status, setStatus] = useState<StatusPayload | null>(null);
  const [autonomous, setAutonomous] = useState<AutonomousStatusPayload | null>(null);
  const [jobs, setJobs] = useState<JobRecord[]>([]);
  const [proxy, setProxy] = useState<ProxyDiagnostics | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [instantResearchSeed, setInstantResearchSeed] = useState<InstantResearchSeed | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [statusPayload, autonomousPayload, jobPayload] = await Promise.all([api.status(), api.autonomousStatus(), api.jobs()]);
      setStatus(statusPayload);
      setAutonomous(autonomousPayload);
      setJobs(jobPayload.jobs);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let lastSnapshotAt = 0;
    let lastFallbackAt = 0;
    const source = new EventSource(api.operationsStreamUrl(), { withCredentials: true });
    const onSnapshot = (event: Event) => {
      const payload = JSON.parse((event as MessageEvent<string>).data) as {
        status: StatusPayload;
        autonomous: AutonomousStatusPayload;
        jobs: JobRecord[];
      };
      lastSnapshotAt = Date.now();
      setStatus(payload.status);
      setAutonomous(payload.autonomous);
      setJobs(payload.jobs);
      setLoading(false);
      setError(null);
    };
    source.addEventListener('snapshot', onSnapshot);
    source.onerror = () => {
      const now = Date.now();
      if (now - lastSnapshotAt > 15_000 && now - lastFallbackAt > 30_000) {
        lastFallbackAt = now;
        void refresh();
      }
    };
    const fallbackTimer = window.setInterval(() => {
      const now = Date.now();
      if (now - lastSnapshotAt > 30_000 && now - lastFallbackAt > 30_000) {
        lastFallbackAt = now;
        void refresh();
      }
    }, 10_000);
    return () => {
      window.clearInterval(fallbackTimer);
      source.removeEventListener('snapshot', onSnapshot);
      source.close();
    };
  }, [refresh]);

  useEffect(() => {
    window.scrollTo({ top: 0, behavior: 'auto' });
  }, [configSection, tab]);

  const counts = status?.counts || {};
  const currentPage = pageMeta[tab] || pageMeta.overview;

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default' }}>
      <Drawer
        variant="permanent"
        sx={{
          width: drawerWidth,
          flexShrink: 0,
          display: { xs: 'none', md: 'block' },
          '& .MuiDrawer-paper': {
            width: drawerWidth,
            boxSizing: 'border-box',
            borderRight: '1px solid',
            borderColor: 'divider',
            display: 'flex',
          },
        }}
      >
        <Box sx={{ px: 2, py: 2 }}>
          <Typography variant="h2">FinBot</Typography>
          <Typography variant="caption" color="text.secondary">AI 研究与模拟交易</Typography>
        </Box>
        <Divider />
        <List sx={{ p: 1, overflowY: 'auto', flexGrow: 1, minHeight: 0 }}>
          {navGroups.map((group) => (
            <Box key={group.id} sx={{ mb: 0.75 }}>
              {group.label && (
                <Typography
                  variant="caption"
                  color="text.secondary"
                  sx={{ display: 'block', px: 1.5, pt: 0.75, pb: 0.5, fontWeight: 700 }}
                >
                  {group.label}
                </Typography>
              )}
              {group.items.map((item) => (
                <Box key={item.id}>
                  <ListItemButton
                    data-testid={`nav-${item.id}`}
                    selected={tab === item.id}
                    onClick={() => {
                      if (item.id === 'config') {
                        setTab('config');
                        setConfigMenuOpen((open) => (tab === 'config' ? !open : true));
                        return;
                      }
                      setTab(item.id);
                    }}
                    sx={{ borderRadius: 1, mb: 0.5 }}
                  >
                    <ListItemIcon sx={{ minWidth: 36 }}>{item.icon}</ListItemIcon>
                    <ListItemText primary={item.label} primaryTypographyProps={{ fontSize: 13, fontWeight: 700 }} />
                    {item.id === 'config' && (tab === 'config' && configMenuOpen ? <ExpandLessIcon fontSize="small" /> : <ExpandMoreIcon fontSize="small" />)}
                  </ListItemButton>
                  {item.id === 'config' && tab === 'config' && (
                    <Collapse in={configMenuOpen} timeout="auto" unmountOnExit>
                      <List disablePadding sx={{ pl: 4.5, pr: 0.5, pb: 0.5 }}>
                        {configSubmenuItems.map((subItem) => (
                          <ListItemButton
                            key={subItem.id}
                            selected={configSection === subItem.id}
                            onClick={() => {
                              setTab('config');
                              setConfigSection(subItem.id);
                            }}
                            sx={{ borderRadius: 1, minHeight: 32, mb: 0.25, px: 1 }}
                          >
                            <ListItemText primary={subItem.label} primaryTypographyProps={{ fontSize: 12, fontWeight: 700 }} />
                          </ListItemButton>
                        ))}
                      </List>
                    </Collapse>
                  )}
                </Box>
              ))}
            </Box>
          ))}
        </List>
        <Box sx={{ mt: 'auto', p: 2 }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <ShieldIcon color="success" fontSize="small" />
            <Typography variant="caption" color="text.secondary">
              仅模拟，不接实盘
            </Typography>
          </Stack>
        </Box>
      </Drawer>

      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        <AppBar position="sticky" color="inherit" elevation={0} sx={{ borderBottom: '1px solid', borderColor: 'divider' }}>
          <Toolbar sx={{ gap: 2 }}>
            <Box sx={{ minWidth: 0, flexGrow: 1 }}>
              <Typography variant="h1">{currentPage.title}</Typography>
              <Typography variant="body2" color="text.secondary">
                {currentPage.subtitle}
              </Typography>
            </Box>
            <Chip
              size="small"
              icon={<ShieldIcon />}
              color={statusColor(status?.status || 'unknown')}
              label={status?.status === 'ok' ? '系统正常' : statusText(status?.status)}
            />
            <Tooltip title="刷新">
              <IconButton onClick={refresh} disabled={loading}>
                {loading ? <CircularProgress size={20} /> : <RefreshIcon />}
              </IconButton>
            </Tooltip>
          </Toolbar>
          {!desktopNav && (
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1}
              sx={{ px: 1.5, py: 1, borderTop: '1px solid', borderColor: 'divider' }}
            >
              <Select
                value={tab}
                onChange={(event) => setTab(event.target.value)}
                inputProps={{ 'aria-label': '页面导航' }}
                fullWidth
              >
                {navGroups.flatMap((group) => [
                  ...(group.label ? [<ListSubheader key={`${group.id}-header`} disableSticky>{group.label}</ListSubheader>] : []),
                  ...group.items.map((item) => (
                    <MenuItem key={item.id} value={item.id}>
                      <ListItemIcon sx={{ minWidth: 34 }}>{item.icon}</ListItemIcon>
                      <ListItemText primary={item.label} />
                    </MenuItem>
                  )),
                ])}
              </Select>
              {tab === 'config' && (
                <Select
                  value={configSection}
                  onChange={(event) => setConfigSection(event.target.value)}
                  inputProps={{ 'aria-label': '配置分类' }}
                  fullWidth
                >
                  {configSubmenuItems.map((item) => <MenuItem key={item.id} value={item.id}>{item.label}</MenuItem>)}
                </Select>
              )}
            </Stack>
          )}
        </AppBar>

        <Box sx={{ p: { xs: 1.5, sm: 2, md: 3 } }}>
          {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
          {tab === 'overview' && <OverviewPanel status={status} autonomous={autonomous} jobs={jobs} counts={counts} onNavigate={setTab} />}
          {tab === 'products' && (
            <ProductCenterPanel
              onStartResearch={(request) => {
                setInstantResearchSeed(request);
                setTab('instant');
              }}
            />
          )}
          {tab === 'accounts' && <ExchangeAccountsPanel />}
          {tab === 'instant' && (
            <InstantResearchPanel
              initialRequest={instantResearchSeed}
              onInitialRequestConsumed={() => setInstantResearchSeed(null)}
            />
          )}
          {tab === 'reviews' && <ResearchGovernancePanel />}
          {tab === 'autonomous' && <AutonomousPanel autonomous={autonomous} onRefresh={refresh} />}
          {tab === 'operator' && <OperatorPanel status={status} autonomous={autonomous} jobs={jobs} onSubmitted={refresh} onNavigate={setTab} />}
          {tab === 'quant' && <QuantRiskPanel />}
          {tab === 'research' && <ResearchPanel status={status} jobs={jobs} onSubmitted={refresh} />}
          {tab === 'proxy' && <ProxyPanel proxy={proxy} setProxy={setProxy} onSubmitted={refresh} />}
          {tab === 'config' && <ConfigPanel section={configSection} />}
          {tab === 'reports' && <ReportsPanel />}
        </Box>
      </Box>
    </Box>
  );
}

function ProxyPanel({
  proxy,
  setProxy,
  onSubmitted,
}: {
  proxy: ProxyDiagnostics | null;
  setProxy: (value: ProxyDiagnostics) => void;
  onSubmitted: () => void;
}) {
  const [startBridges, setStartBridges] = useState(false);
  return (
    <Stack spacing={2}>
      <ActionPanel
        title="运行网络诊断"
        icon={<LanIcon />}
        actions={
          <Stack direction="row" spacing={1}>
            <Button
              variant="outlined"
              startIcon={<RefreshIcon />}
              onClick={async () => {
                setProxy(await api.proxyDiagnostics(startBridges));
              }}
            >
              立即检查
            </Button>
            <Button
              variant="contained"
              startIcon={<PlayArrowIcon />}
              onClick={async () => {
                await api.submitProxyDiagnostics({ start_bridges: startBridges });
                onSubmitted();
              }}
            >
              提交后台诊断
            </Button>
          </Stack>
        }
      >
        <FormControlLabel control={<Switch checked={startBridges} onChange={(event) => setStartBridges(event.target.checked)} />} label="启动临时代理桥" />
      </ActionPanel>
      {proxy && (
        <Card>
          <CardContent>
            <PanelTitle icon={<LanIcon />} title="目标路由" />
            <Stack spacing={1}>
              {proxy.targets.map((target) => (
                <RouteRow key={target.route} target={target} />
              ))}
            </Stack>
          </CardContent>
        </Card>
      )}
    </Stack>
  );
}

function ConfigPanel({ section }: { section: string }) {
  const [snapshot, setSnapshot] = useState<SystemConfigPayload | null>(null);
  const [aiSnapshot, setAiSnapshot] = useState<AIConfigPayload | null>(null);
  const [formValues, setFormValues] = useState<Record<string, unknown>>({});
  const [aiForm, setAiForm] = useState<AIConfigForm | null>(null);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadConfig = useCallback(async () => {
    setError(null);
    const [payload, aiPayload] = await Promise.all([api.config(), api.aiConfig()]);
    setSnapshot(payload);
    setAiSnapshot(aiPayload);
    setFormValues(configFormValues(payload));
    setAiForm(aiConfigForm(aiPayload));
  }, []);

  useEffect(() => {
    loadConfig().catch((err) => setError(err instanceof Error ? err.message : String(err)));
  }, [loadConfig]);

  const activeConfigGroups = useMemo(() => {
    if (!snapshot) {
      return [];
    }
    const groups = configSectionGroups(section);
    const grouped: Record<string, ConfigFieldSpec[]> = {};
    for (const field of snapshot.schema) {
      if (!groups.includes(field.group)) {
        continue;
      }
      grouped[field.group] = grouped[field.group] || [];
      grouped[field.group].push(field);
    }
    return Object.entries(grouped);
  }, [section, snapshot]);

  const isAiSection = section.startsWith('ai-');
  const isSetupSection = section === 'setup';
  const activeSectionTitle = configSubmenuItems.find((item) => item.id === section)?.label || '系统设置';

  const saveConfig = async () => {
    if ((!snapshot && !isAiSection) || (!aiForm && isAiSection)) {
      return;
    }
    setSaving(true);
    setError(null);
    setMessage(null);
    try {
      if (isAiSection && aiForm) {
        const nextPayload = await api.updateAiConfig(aiForm);
        setAiSnapshot(nextPayload);
        setAiForm(aiConfigForm(nextPayload));
        setMessage('AI 配置已保存，下一次 AI 调用会读取最新站点、模型和提示词。');
        return;
      }
      const updates: Record<string, unknown> = {};
      for (const field of snapshot!.schema) {
        if (!configSectionGroups(section).includes(field.group)) {
          continue;
        }
        const value = formValues[field.key];
        if (field.sensitive && (value === undefined || String(value).trim() === '')) {
          continue;
        }
        if (!field.sensitive && sameConfigValue(field, value, snapshot!.values[field.key]?.value)) {
          continue;
        }
        updates[field.key] = normalizeConfigInput(field, value);
      }
      const nextPayload = await api.updateConfig({ values: updates, clear_keys: [] });
      setSnapshot(nextPayload);
      setFormValues(configFormValues(nextPayload));
      setMessage('配置已保存，下一次请求和新后台任务会读取最新值。');
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Stack spacing={2}>
      {section !== 'ai-workflow' && (
        <ActionPanel
          title={activeSectionTitle}
          icon={<SettingsIcon />}
          actions={
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
              <Button variant="outlined" startIcon={<RefreshIcon />} onClick={loadConfig} disabled={saving}>
                重新载入
              </Button>
              {!isSetupSection && (
                <Button variant="contained" startIcon={<SaveIcon />} onClick={saveConfig} disabled={saving || !snapshot}>
                  保存设置
                </Button>
              )}
            </Stack>
          }
        >
          <Stack spacing={1}>
            <Typography variant="body2" color="text.secondary">
              修改只影响后续请求和新任务；正在运行的任务不会被中途改写。
            </Typography>
            <Box component="details" sx={{ color: 'text.secondary' }}>
              <Box component="summary" sx={{ cursor: 'pointer', width: 'fit-content', fontSize: 12, fontWeight: 700 }}>
                配置文件位置
              </Box>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75, overflowWrap: 'anywhere' }}>
                运行时配置：{snapshot?.runtime_config_path || '-'}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', overflowWrap: 'anywhere' }}>
                代理策略：{snapshot?.proxy_policy_path || '-'}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', overflowWrap: 'anywhere' }}>
                AI 配置：{aiSnapshot?.config_path || '-'}
              </Typography>
            </Box>
          </Stack>
        </ActionPanel>
      )}
      {message && <Alert severity="success">{message}</Alert>}
      {error && <Alert severity="error">{error}</Alert>}
      {(!snapshot || !aiSnapshot) && !error && (
        <Card>
          <CardContent>
            <Stack direction="row" spacing={1.5} alignItems="center">
              <CircularProgress size={20} />
              <Typography variant="body2" color="text.secondary">正在载入配置</Typography>
            </Stack>
          </CardContent>
        </Card>
      )}
      {snapshot && aiSnapshot && aiForm && (
        <Box sx={{ minWidth: 0 }}>
          {!isAiSection && !isSetupSection && (
            <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', xl: 'repeat(2, minmax(0, 1fr))' }, gap: 2 }}>
              {activeConfigGroups.map(([group, fields]) => (
                <Card key={group}>
                  <CardContent>
                    <PanelTitle icon={<SettingsIcon />} title={configGroupTitle(group)} />
                    <Stack spacing={1.25}>
                      {fields.map((field) => (
                        <ConfigFieldEditor
                          key={field.key}
                          field={field}
                          value={formValues[field.key]}
                          state={snapshot.values[field.key]}
                          onChange={(value) => setFormValues((current) => ({ ...current, [field.key]: value }))}
                        />
                      ))}
                    </Stack>
                  </CardContent>
                </Card>
              ))}
            </Box>
          )}
          {isSetupSection && <SetupPanel onApplied={loadConfig} />}
          {section === 'ai-sites' && <AISitesPanel aiForm={aiForm} setAiForm={setAiForm} setAiSnapshot={setAiSnapshot} setMessage={setMessage} setError={setError} />}
          {section === 'ai-bindings' && <AIBindingsPanel aiSnapshot={aiSnapshot} aiForm={aiForm} setAiForm={setAiForm} />}
          {section === 'ai-prompts' && <AIPromptsPanel aiSnapshot={aiSnapshot} aiForm={aiForm} setAiForm={setAiForm} />}
          {section === 'ai-workflow' && (
            <Suspense fallback={<Stack direction="row" spacing={1.5} alignItems="center"><CircularProgress size={20} /><Typography variant="body2" color="text.secondary">正在载入工作流编辑器</Typography></Stack>}>
              <CouncilWorkflowPanel aiForm={aiForm} setAiForm={setAiForm} rolePresets={aiSnapshot.role_presets || []} />
            </Suspense>
          )}
          {section === 'ai-council' && <CouncilConfigPanel aiForm={aiForm} setAiForm={setAiForm} rolePresets={aiSnapshot.role_presets || []} />}
          {section === 'ai-experiments' && <AIExperimentPanel aiForm={aiForm} setAiForm={setAiForm} tasks={aiSnapshot.tasks} />}
        </Box>
      )}
    </Stack>
  );
}

function AISitesPanel({
  aiForm,
  setAiForm,
  setAiSnapshot,
  setMessage,
  setError,
}: {
  aiForm: AIConfigForm;
  setAiForm: Dispatch<SetStateAction<AIConfigForm | null>>;
  setAiSnapshot: Dispatch<SetStateAction<AIConfigPayload | null>>;
  setMessage: (value: string | null) => void;
  setError: (value: string | null) => void;
}) {
  const [refreshingModelKey, setRefreshingModelKey] = useState<string | null>(null);
  const [expandedSiteId, setExpandedSiteId] = useState<string | null>(aiForm.sites[0]?.site_id || null);
  const updateSite = (index: number, patch: Partial<AISiteConfig>) => {
    setAiForm((current) => {
      if (!current) {
        return current;
      }
      const sites = current.sites.map((site, itemIndex) => (itemIndex === index ? { ...site, ...patch } : site));
      return { ...current, sites };
    });
  };
  const removeSite = (index: number) => {
    const removedSiteId = aiForm.sites[index]?.site_id;
    const remainingSites = aiForm.sites.filter((_, itemIndex) => itemIndex !== index);
    setAiForm((current) => {
      if (!current) {
        return current;
      }
      return { ...current, sites: current.sites.filter((_, itemIndex) => itemIndex !== index) };
    });
    if (expandedSiteId === removedSiteId) {
      setExpandedSiteId(remainingSites[0]?.site_id || null);
    }
  };
  const addSite = () => {
    const siteId = `site_${aiForm.sites.length + 1}`;
    setAiForm((current) => {
      if (!current) {
        return current;
      }
      return {
        ...current,
        sites: [
          ...current.sites,
          {
            site_id: siteId,
            display_name: '新站点',
            enabled: true,
            base_url: '',
            api_key: '',
            api_key_configured: false,
            chat_models: [],
            responses_models: [],
            default_chat_model: '',
            default_responses_model: '',
            timeout_seconds: 60,
            input_cost_per_million_tokens: null,
            output_cost_per_million_tokens: null,
            pricing_model: '',
            pricing_currency: 'USD',
            pricing_basis: 'cache_miss',
            pricing_source_url: null,
            pricing_checked_at: null,
          },
        ],
      };
    });
    setExpandedSiteId(siteId);
  };
  const refreshModels = async (siteId: string, protocol: string) => {
    setRefreshingModelKey(`${siteId}:${protocol}`);
    setError(null);
    setMessage(null);
    try {
      const result = await api.refreshAiModels({ site_id: siteId, protocol });
      setAiSnapshot(result.config);
      setAiForm(aiConfigForm(result.config));
      setMessage(`${siteId} 的 ${protocol === 'chat' ? 'Chat' : 'Responses'} 模型列表已刷新，共 ${result.models.length} 个。`);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setRefreshingModelKey(null);
    }
  };
  return (
    <Stack spacing={2}>
      <Card>
        <CardContent>
          <Stack direction={{ xs: 'column', sm: 'row' }} alignItems={{ xs: 'stretch', sm: 'center' }} justifyContent="space-between" spacing={1.5}>
            <PanelTitle icon={<SettingsIcon />} title="AI 站点" mb={0} />
            <Button variant="outlined" startIcon={<AddIcon />} onClick={addSite}>新增站点</Button>
          </Stack>
        </CardContent>
      </Card>
      {aiForm.sites.map((site, index) => (
        <Card key={`${site.site_id}-${index}`}>
          <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} alignItems={{ xs: 'stretch', sm: 'center' }} justifyContent="space-between" spacing={1.25}>
              <Box sx={{ minWidth: 0 }}>
                <PanelTitle icon={<SettingsIcon />} title={site.display_name || site.site_id || 'AI 站点'} mb={0.25} />
                <Typography variant="caption" color="text.secondary">
                  {site.base_url || '尚未配置 API 地址'} · Chat {site.chat_models.length} 个 · Responses {site.responses_models.length} 个
                </Typography>
              </Box>
              <Stack direction="row" spacing={0.75} alignItems="center" justifyContent="flex-end" flexWrap="wrap" useFlexGap>
                <Chip size="small" color={site.enabled ? 'success' : 'default'} label={site.enabled ? '已启用' : '已关闭'} />
                <Chip size="small" color={site.api_key_configured ? 'success' : 'warning'} label={site.api_key_configured ? '密钥已配置' : '未配置密钥'} />
                <Chip size="small" variant="outlined" label={site.input_cost_per_million_tokens != null && site.output_cost_per_million_tokens != null ? '费率已配置' : '费率未知'} />
                <Tooltip title="删除站点">
                  <IconButton size="small" aria-label={`删除 ${site.display_name || site.site_id}`} onClick={() => removeSite(index)}>
                    <DeleteIcon fontSize="small" />
                  </IconButton>
                </Tooltip>
                <Tooltip title={expandedSiteId === site.site_id ? '收起站点配置' : '展开站点配置'}>
                  <IconButton size="small" aria-label={expandedSiteId === site.site_id ? '收起站点配置' : '展开站点配置'} onClick={() => setExpandedSiteId((current) => current === site.site_id ? null : site.site_id)}>
                    {expandedSiteId === site.site_id ? <ExpandLessIcon fontSize="small" /> : <ExpandMoreIcon fontSize="small" />}
                  </IconButton>
                </Tooltip>
              </Stack>
            </Stack>
          </CardContent>
          <Collapse in={expandedSiteId === site.site_id} timeout="auto" unmountOnExit={false}>
            <Divider />
            <CardContent>
            <Stack spacing={1.25}>
              <SimpleConfigField label="启用" helper="关闭后新 AI 调用不会选择该站点">
                <Switch checked={site.enabled} onChange={(event) => updateSite(index, { enabled: event.target.checked })} />
              </SimpleConfigField>
              <SimpleConfigField label="站点 ID" helper="稳定标识，环节绑定会引用它">
                <TextField fullWidth value={site.site_id} onChange={(event) => updateSite(index, { site_id: event.target.value })} sx={configInputSx} />
              </SimpleConfigField>
              <SimpleConfigField label="显示名称" helper="仅用于界面显示">
                <TextField fullWidth value={site.display_name} onChange={(event) => updateSite(index, { display_name: event.target.value })} sx={configInputSx} />
              </SimpleConfigField>
              <SimpleConfigField label="API 地址" helper="OpenAI chat/responses 兼容 base URL">
                <TextField fullWidth value={site.base_url} onChange={(event) => updateSite(index, { base_url: event.target.value })} sx={configInputSx} />
              </SimpleConfigField>
              <SimpleConfigField label="API Key" helper={site.api_key_configured ? '留空保持原密钥不变' : '请输入站点密钥'}>
                <TextField fullWidth type="password" value={site.api_key || ''} onChange={(event) => updateSite(index, { api_key: event.target.value })} placeholder={site.api_key_configured ? '留空保持不变' : undefined} sx={configInputSx} />
              </SimpleConfigField>
              <SimpleConfigField label="Chat 模型" helper="多个模型用逗号分隔">
                <TextField fullWidth value={site.chat_models.join(',')} onChange={(event) => updateSite(index, { chat_models: splitCsv(event.target.value) })} sx={configInputSx} />
              </SimpleConfigField>
              <SimpleConfigField label="刷新 Chat 模型" helper="从该站点 /models 拉取；请先保存站点地址和密钥">
                <Button
                  variant="outlined"
                  startIcon={refreshingModelKey === `${site.site_id}:chat` ? <CircularProgress size={16} /> : <RefreshIcon />}
                  disabled={Boolean(refreshingModelKey) || !site.site_id}
                  onClick={() => refreshModels(site.site_id, 'chat')}
                >
                  刷新
                </Button>
              </SimpleConfigField>
              <SimpleConfigField label="Responses 模型" helper="多个模型用逗号分隔">
                <TextField fullWidth value={site.responses_models.join(',')} onChange={(event) => updateSite(index, { responses_models: splitCsv(event.target.value) })} sx={configInputSx} />
              </SimpleConfigField>
              <SimpleConfigField label="刷新 Responses 模型" helper="只在站点支持 Responses 协议时使用">
                <Button
                  variant="outlined"
                  startIcon={refreshingModelKey === `${site.site_id}:responses` ? <CircularProgress size={16} /> : <RefreshIcon />}
                  disabled={Boolean(refreshingModelKey) || !site.site_id}
                  onClick={() => refreshModels(site.site_id, 'responses')}
                >
                  刷新
                </Button>
              </SimpleConfigField>
              <SimpleConfigField label="默认 Chat 模型" helper="Chat 协议默认使用">
                <TextField fullWidth value={site.default_chat_model || ''} onChange={(event) => updateSite(index, { default_chat_model: event.target.value })} sx={configInputSx} />
              </SimpleConfigField>
              <SimpleConfigField label="默认 Responses 模型" helper="Responses 协议默认使用">
                <TextField fullWidth value={site.default_responses_model || ''} onChange={(event) => updateSite(index, { default_responses_model: event.target.value })} sx={configInputSx} />
              </SimpleConfigField>
              <SimpleConfigField label="超时秒数" helper="单次 AI 请求超时时间">
                <TextField fullWidth type="number" value={site.timeout_seconds} onChange={(event) => updateSite(index, { timeout_seconds: Number(event.target.value) })} sx={configInputSx} />
              </SimpleConfigField>
              <SimpleConfigField label="计费模型" helper="只有调用模型与此处一致时才使用下方费率">
                <TextField fullWidth value={site.pricing_model || ''} onChange={(event) => updateSite(index, { pricing_model: event.target.value || null })} sx={configInputSx} />
              </SimpleConfigField>
              <SimpleConfigField label="输入 Token 单价" helper="USD / 1M tokens，按 cache miss 保守口径；留空表示成本未知">
                <TextField
                  fullWidth
                  type="number"
                  inputProps={{ min: 0, step: 0.0001 }}
                  value={site.input_cost_per_million_tokens ?? ''}
                  onChange={(event) => updateSite(index, { input_cost_per_million_tokens: event.target.value === '' ? null : Number(event.target.value) })}
                  sx={configInputSx}
                />
              </SimpleConfigField>
              <SimpleConfigField label="输出 Token 单价" helper="USD / 1M tokens；留空表示成本未知">
                <TextField
                  fullWidth
                  type="number"
                  inputProps={{ min: 0, step: 0.0001 }}
                  value={site.output_cost_per_million_tokens ?? ''}
                  onChange={(event) => updateSite(index, { output_cost_per_million_tokens: event.target.value === '' ? null : Number(event.target.value) })}
                  sx={configInputSx}
                />
              </SimpleConfigField>
              {site.pricing_source_url && (
                <SimpleConfigField label="费率来源" helper={`${site.pricing_currency || 'USD'} · ${site.pricing_basis === 'cache_miss' ? 'cache miss' : site.pricing_basis || '-'} · 核对 ${site.pricing_checked_at || '-'}`}>
                  <Button component="a" href={site.pricing_source_url} target="_blank" rel="noreferrer" variant="outlined" size="small">
                    官方定价
                  </Button>
                </SimpleConfigField>
              )}
            </Stack>
            </CardContent>
          </Collapse>
        </Card>
      ))}
    </Stack>
  );
}

function AIBindingsPanel({
  aiSnapshot,
  aiForm,
  setAiForm,
}: {
  aiSnapshot: AIConfigPayload;
  aiForm: AIConfigForm;
  setAiForm: Dispatch<SetStateAction<AIConfigForm | null>>;
}) {
  const siteIds = aiForm.sites.map((site) => site.site_id).filter(Boolean);
  const updateBinding = (taskId: string, patch: Partial<AITaskBinding>) => {
    setAiForm((current) => {
      if (!current) {
        return current;
      }
      const currentBinding = current.task_bindings[taskId] || { enabled: true, site_id: '', protocol: 'chat', model: '', reasoning_effort: 'high', fallback_site_ids: [] };
      return {
        ...current,
        task_bindings: {
          ...current.task_bindings,
          [taskId]: { ...currentBinding, ...patch },
        },
      };
    });
  };
  return (
    <Stack spacing={2}>
      {aiSnapshot.tasks.map((task) => {
        const binding = aiForm.task_bindings[task.task_id] || { enabled: true, site_id: '', protocol: 'chat', model: '', reasoning_effort: 'high', fallback_site_ids: [] };
        const selectedSite = aiForm.sites.find((site) => site.site_id === binding.site_id);
        const models = binding.protocol === 'responses' ? selectedSite?.responses_models || [] : selectedSite?.chat_models || [];
        return (
          <Card key={task.task_id}>
            <CardContent>
              <PanelTitle icon={<SettingsIcon />} title={task.label} />
              <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>{task.description}</Typography>
              <Stack spacing={1.25}>
                <SimpleConfigField label="启用环节" helper="关闭后该环节不主动调用 AI">
                  <Switch checked={binding.enabled} onChange={(event) => updateBinding(task.task_id, { enabled: event.target.checked })} />
                </SimpleConfigField>
                <SimpleConfigField label="站点" helper="选择该环节优先使用的 AI 站点">
                  <TextField select fullWidth value={binding.site_id || ''} onChange={(event) => updateBinding(task.task_id, { site_id: event.target.value })} sx={configInputSx}>
                    {siteIds.map((siteId) => <MenuItem key={siteId} value={siteId}>{siteId}</MenuItem>)}
                  </TextField>
                </SimpleConfigField>
                <SimpleConfigField label="协议" helper="OpenAI Chat 或 Responses 协议簇">
                  <TextField select fullWidth value={binding.protocol} onChange={(event) => updateBinding(task.task_id, { protocol: event.target.value, model: '' })} sx={configInputSx}>
                    <MenuItem value="chat">chat</MenuItem>
                    <MenuItem value="responses">responses</MenuItem>
                  </TextField>
                </SimpleConfigField>
                <SimpleConfigField label="模型" helper="可直接输入，也可从站点模型中选择">
                  <TextField fullWidth value={binding.model || ''} onChange={(event) => updateBinding(task.task_id, { model: event.target.value })} sx={configInputSx} select={models.length > 0}>
                    {models.map((model) => <MenuItem key={model} value={model}>{model}</MenuItem>)}
                  </TextField>
                </SimpleConfigField>
                <SimpleConfigField label="思考等级" helper="显式传递给模型；关闭时不请求深度思考">
                  <TextField select fullWidth value={binding.reasoning_effort || 'provider_default'} onChange={(event) => updateBinding(task.task_id, { reasoning_effort: event.target.value as AITaskBinding['reasoning_effort'] })} sx={configInputSx}>
                    <MenuItem value="provider_default">厂商默认</MenuItem>
                    <MenuItem value="none">关闭</MenuItem>
                    <MenuItem value="minimal">极低</MenuItem>
                    <MenuItem value="low">低</MenuItem>
                    <MenuItem value="medium">中</MenuItem>
                    <MenuItem value="high">高</MenuItem>
                    <MenuItem value="xhigh">极高</MenuItem>
                    <MenuItem value="max">最高</MenuItem>
                  </TextField>
                </SimpleConfigField>
                <SimpleConfigField label="备用站点" helper="多个站点用逗号分隔，主站点失败后依次尝试">
                  <TextField fullWidth value={(binding.fallback_site_ids || []).join(',')} onChange={(event) => updateBinding(task.task_id, { fallback_site_ids: splitCsv(event.target.value) })} sx={configInputSx} />
                </SimpleConfigField>
              </Stack>
            </CardContent>
          </Card>
        );
      })}
    </Stack>
  );
}

function AIPromptsPanel({
  aiSnapshot,
  aiForm,
  setAiForm,
}: {
  aiSnapshot: AIConfigPayload;
  aiForm: AIConfigForm;
  setAiForm: Dispatch<SetStateAction<AIConfigForm | null>>;
}) {
  const updatePrompt = (taskId: string, patch: Partial<AIPromptConfig>) => {
    setAiForm((current) => {
      if (!current) {
        return current;
      }
      const currentPrompt = current.prompts[taskId] || { system_prompt: '', user_prompt_template: '{payload_json}' };
      return {
        ...current,
        prompts: {
          ...current.prompts,
          [taskId]: { ...currentPrompt, ...patch },
        },
      };
    });
  };
  return (
    <Stack spacing={2}>
      {aiSnapshot.tasks.map((task) => {
        const prompt = aiForm.prompts[task.task_id] || { system_prompt: task.default_system_prompt, user_prompt_template: task.default_user_prompt_template };
        return (
          <Card key={task.task_id}>
            <CardContent>
              <PanelTitle icon={<SettingsIcon />} title={`${task.label}提示词`} />
              <Stack spacing={1.25}>
                <SimpleConfigField label="系统提示词" helper="约束角色、输出格式、安全边界和事实引用规则">
                  <TextField fullWidth multiline minRows={10} value={prompt.system_prompt} onChange={(event) => updatePrompt(task.task_id, { system_prompt: event.target.value })} sx={configInputSx} />
                </SimpleConfigField>
                <SimpleConfigField label="用户提示词模板" helper="支持 {payload_json}、{target_type}、{target_id}">
                  <TextField fullWidth multiline minRows={6} value={prompt.user_prompt_template} onChange={(event) => updatePrompt(task.task_id, { user_prompt_template: event.target.value })} sx={configInputSx} />
                </SimpleConfigField>
              </Stack>
            </CardContent>
          </Card>
        );
      })}
    </Stack>
  );
}

function SimpleConfigField({ label, helper, children }: { label: string; helper: string; children: ReactNode }) {
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: '190px minmax(0, 1fr)' },
        gap: { xs: 0.75, sm: 1.5 },
        alignItems: 'center',
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
        px: 1.25,
        py: 1,
        bgcolor: '#fbfcfd',
      }}
    >
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="body2" sx={{ fontWeight: 700, lineHeight: 1.35 }}>{label}</Typography>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.25 }}>{helper}</Typography>
      </Box>
      <Box sx={{ minWidth: 0 }}>{children}</Box>
    </Box>
  );
}

function ConfigFieldEditor({
  field,
  value,
  state,
  onChange,
}: {
  field: ConfigFieldSpec;
  value: unknown;
  state?: SystemConfigPayload['values'][string];
  onChange: (value: unknown) => void;
}) {
  if (field.kind === 'boolean') {
    return (
      <ConfigFieldShell field={field} state={state}>
        <Stack direction="row" alignItems="center" justifyContent="flex-end" sx={{ minHeight: 40 }}>
          <Switch checked={Boolean(value)} onChange={(event) => onChange(event.target.checked)} />
        </Stack>
      </ConfigFieldShell>
    );
  }
  if (field.kind === 'select') {
    return (
      <ConfigFieldShell field={field} state={state}>
        <TextField select fullWidth value={String(value ?? '')} onChange={(event) => onChange(event.target.value)} sx={configInputSx}>
          {field.options.map((option) => (
            <MenuItem key={option} value={option}>{option}</MenuItem>
          ))}
        </TextField>
      </ConfigFieldShell>
    );
  }
  return (
    <ConfigFieldShell field={field} state={state}>
      <TextField
        fullWidth
        type={field.kind === 'secret' ? 'password' : field.kind === 'integer' || field.kind === 'number' ? 'number' : 'text'}
        value={String(value ?? '')}
        onChange={(event) => onChange(event.target.value)}
        placeholder={field.sensitive && state?.configured ? '留空保持不变' : undefined}
        multiline={field.kind === 'multiline' || field.multiline}
        minRows={field.kind === 'multiline' || field.multiline ? 3 : undefined}
        sx={configInputSx}
      />
    </ConfigFieldShell>
  );
}

function ConfigFieldShell({
  field,
  state,
  children,
}: {
  field: ConfigFieldSpec;
  state?: SystemConfigPayload['values'][string];
  children: ReactNode;
}) {
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', sm: '190px minmax(0, 1fr)' },
        gap: { xs: 0.75, sm: 1.5 },
        alignItems: 'center',
        border: '1px solid',
        borderColor: 'divider',
        borderRadius: 1,
        px: 1.25,
        py: 1,
        bgcolor: '#fbfcfd',
      }}
    >
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="body2" sx={{ fontWeight: 700, lineHeight: 1.35 }}>{field.label}</Typography>
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.25 }}>
          {configHelperText(field, state)}
        </Typography>
      </Box>
      <Box sx={{ minWidth: 0 }}>{children}</Box>
    </Box>
  );
}

function PanelTitle({ icon, title, mb = 1.5 }: { icon: ReactNode; title: string; mb?: number }) {
  return (
    <Stack direction="row" spacing={1} alignItems="center" sx={{ mb }}>
      <Box sx={{ color: 'primary.main', display: 'flex' }}>{icon}</Box>
      <Typography variant="h3">{title}</Typography>
    </Stack>
  );
}

function ActionPanel({
  title,
  icon,
  actions,
  children,
}: {
  title: string;
  icon: ReactNode;
  actions: ReactNode;
  children: ReactNode;
}) {
  return (
    <Card>
      <CardContent>
        <Stack
          direction={{ xs: 'column', sm: 'row' }}
          alignItems={{ xs: 'stretch', sm: 'center' }}
          justifyContent="space-between"
          spacing={1.5}
          sx={{ mb: 2 }}
        >
          <PanelTitle icon={icon} title={title} mb={0} />
          <Box
            sx={{
              display: 'flex',
              justifyContent: { xs: 'stretch', sm: 'flex-end' },
              '& > *': { width: { xs: '100%', sm: 'auto' } },
              '& .MuiStack-root': { width: { xs: '100%', sm: 'auto' } },
              '& .MuiButton-root': { whiteSpace: 'nowrap' },
            }}
          >
            {actions}
          </Box>
        </Stack>
        {children}
      </CardContent>
    </Card>
  );
}

function RouteRow({ target }: { target: ProxyDiagnostics['targets'][number] }) {
  return (
    <Stack direction="row" alignItems="center" spacing={1.5} sx={{ py: 0.75 }}>
      <Chip size="small" color={statusColor(target.decision.status)} label={statusText(target.decision.status)} />
      <Typography variant="body2" sx={{ width: 160, fontWeight: 700 }}>{target.route}</Typography>
      <Typography variant="body2" sx={{ width: 210 }} noWrap>{target.decision.proxy}</Typography>
      <Typography variant="caption" color="text.secondary" sx={{ flexGrow: 1 }} noWrap>{target.decision.reason || target.url}</Typography>
    </Stack>
  );
}

function splitCsv(value: string): string[] {
  return value.split(',').map((item) => item.trim()).filter(Boolean);
}

function configSectionGroups(section: string): string[] {
  const map: Record<string, string[]> = {
    base: ['基础'],
    proxy: ['Firecrawl', '交易所代理', '代理策略'],
    workflow: ['研究流水线', '交易建议', '自动循环', 'P1 建议评估', 'P1 组合风险', 'P1 AI 治理', '模拟交易', '常驻 Worker'],
    macro: ['宏观数据'],
  };
  return map[section] || [];
}

function configGroupTitle(group: string): string {
  const titles: Record<string, string> = {
    基础: '运行基础',
    Firecrawl: '信息采集服务',
    交易所代理: '交易所网络代理',
    代理策略: '代理路由策略',
    研究流水线: '采集与处理',
    交易建议: '快速市场分析',
    自动循环: '自动研究',
    'P1 建议评估': '建议效果评估',
    'P1 组合风险': '组合风险',
    'P1 AI 治理': 'AI 治理',
    模拟交易: '模拟交易',
    '常驻 Worker': '常驻 Worker',
    宏观数据: '宏观数据',
  };
  return titles[group] || group;
}

function configFormValues(payload: SystemConfigPayload): Record<string, unknown> {
  const values: Record<string, unknown> = {};
  for (const field of payload.schema) {
    const current = payload.values[field.key]?.value;
    if (field.sensitive) {
      values[field.key] = '';
    } else if (field.kind === 'string_list') {
      values[field.key] = Array.isArray(current) ? current.join(',') : String(current ?? '');
    } else if (field.kind === 'boolean') {
      values[field.key] = Boolean(current);
    } else {
      values[field.key] = current ?? '';
    }
  }
  return values;
}

function aiConfigForm(payload: AIConfigPayload): AIConfigForm {
  return {
    sites: payload.sites.map((site) => ({ ...site, api_key: '' })),
    task_bindings: { ...payload.task_bindings },
    prompts: { ...payload.prompts },
    council_templates: payload.council_templates.map((template) => ({
      ...template,
      roles: template.roles.map((role) => ({ ...role, fallback_site_ids: [...role.fallback_site_ids] })),
      phases: template.phases.map((phase) => ({ ...phase })),
      chair: { ...template.chair, fallback_site_ids: [...template.chair.fallback_site_ids] },
      workflow: {
        ...template.workflow,
        nodes: template.workflow.nodes.map((node) => ({ ...node, position: { ...node.position } })),
        edges: template.workflow.edges.map((edge) => ({ ...edge })),
      },
    })),
    experiments: (payload.experiments || []).map((experiment) => ({
      ...experiment,
      variants: experiment.variants.map((variant) => ({ ...variant })),
    })),
  };
}

function normalizeConfigInput(field: ConfigFieldSpec, value: unknown): unknown {
  if (field.kind === 'boolean') {
    return Boolean(value);
  }
  if (field.kind === 'integer') {
    return value === '' || value === undefined ? null : Number.parseInt(String(value), 10);
  }
  if (field.kind === 'number') {
    return value === '' || value === undefined ? null : Number.parseFloat(String(value));
  }
  if (field.kind === 'string_list') {
    return splitCsv(String(value ?? ''));
  }
  return value;
}

function sameConfigValue(field: ConfigFieldSpec, formValue: unknown, snapshotValue: unknown): boolean {
  if (field.kind === 'string_list') {
    const formList = splitCsv(String(formValue ?? ''));
    const snapshotList = Array.isArray(snapshotValue) ? snapshotValue.map((item) => String(item)) : splitCsv(String(snapshotValue ?? ''));
    return formList.join(',') === snapshotList.join(',');
  }
  if (field.kind === 'boolean') {
    return Boolean(formValue) === Boolean(snapshotValue);
  }
  return String(formValue ?? '') === String(snapshotValue ?? '');
}

function configHelperText(field: ConfigFieldSpec, state?: SystemConfigPayload['values'][string]): string {
  const sourceMap: Record<string, string> = {
    runtime: '运行时配置',
    proxy_policy: '代理策略',
    env: '环境变量',
    default: '默认值',
  };
  const source = sourceMap[state?.source || 'default'] || state?.source || '默认值';
  const lifecycle = field.restart_required ? '需重启生效' : field.hot_reload ? '热更生效' : '下次启动生效';
  const sensitive = field.sensitive ? (state?.configured ? '已配置，留空不修改' : '未配置') : '';
  return [field.help, source, lifecycle, sensitive].filter(Boolean).join(' · ');
}
