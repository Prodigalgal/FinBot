import { useState } from 'react';
import AccountTreeIcon from '@mui/icons-material/AccountTree';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import GavelIcon from '@mui/icons-material/Gavel';
import LockOutlinedIcon from '@mui/icons-material/LockOutlined';
import { Alert, Box, Chip, LinearProgress, Paper, Stack, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, Typography } from '@mui/material';

import type { DebateProtocolTrace } from './types';
import { StatusBadge, formatTime } from './ui';

const phaseLabels: Record<DebateProtocolTrace['phases'][number]['phaseType'], string> = {
  PROPOSAL: '独立提案',
  CRITIQUE: '双盲评审',
  REVISION: '隔离修正',
  BALLOT: '匿名投票',
  AGGREGATION: '社会选择',
};

const decisionLabels: Record<NonNullable<DebateProtocolTrace['decision']>['status'], string> = {
  SELECTED: '已形成严格共识',
  TIED: '候选并列',
  LOW_QUORUM: '法定角色不足',
  ORDER_SENSITIVE: '存在顺序敏感',
  NO_VALID_BALLOTS: '没有有效成组选票',
  NO_STRICT_WINNER: '没有严格胜者',
};

const panelPurposeTitles: Record<string, string> = {
  RESEARCH: 'SDB-SCA 对称辩论 · 研究共识',
  PRINCIPAL_REVIEW: 'SDB-SCA 对称辩论 · 主审独立审计',
  EXECUTION: 'SDB-SCA 对称辩论 · 执行决策共识',
  EVIDENCE: 'SDB-SCA 对称辩论 · 证据清洗互证',
};

const panelTabLabels: Record<string, string> = {
  RESEARCH: '研究共识',
  PRINCIPAL_REVIEW: '主审独立审计',
  EXECUTION: '执行决策',
  EVIDENCE: '证据清洗',
};

export interface DebateProtocolPanelProps {
  trace?: DebateProtocolTrace | null;
  panels?: DebateProtocolTrace[];
}

export function DebateProtocolPanel({ trace, panels }: DebateProtocolPanelProps) {
  const panelList = panels && panels.length > 0 ? panels : trace ? [trace] : [];
  const initialIndex = trace && panelList.length > 0
    ? Math.max(0, panelList.findIndex(p => p.debateId === trace.debateId || (trace.panelKey && p.panelKey === trace.panelKey)))
    : 0;

  const [selectedIndex, setSelectedIndex] = useState<number>(initialIndex);

  if (panelList.length === 0) {
    return null;
  }

  const safeIndex = selectedIndex < panelList.length ? selectedIndex : 0;
  const currentTrace = panelList[safeIndex];
  const decision = currentTrace.decision;
  const purposeTitle = currentTrace.panelPurpose
    ? (panelPurposeTitles[currentTrace.panelPurpose] || `SDB-SCA 对称辩论 · ${currentTrace.panelPurpose}`)
    : 'SDB-SCA 对称辩论';

  return (
    <Stack spacing={1.5}>
      {panelList.length > 1 && (
        <Paper variant="outlined" sx={{ borderRadius: '10px', bgcolor: 'surfaceMuted', px: 1, py: 0.5 }}>
          <Tabs
            value={safeIndex}
            onChange={(_, newIndex) => setSelectedIndex(newIndex)}
            variant="scrollable"
            scrollButtons="auto"
            sx={{
              minHeight: 38,
              '& .MuiTab-root': {
                minHeight: 38,
                fontSize: '0.82rem',
                fontWeight: 700,
                textTransform: 'none',
                py: 0.75,
                px: 2,
              },
            }}
          >
            {panelList.map((p, idx) => {
              const tabLabel = p.panelPurpose
                ? (panelTabLabels[p.panelPurpose] || p.panelPurpose)
                : p.panelKey
                  ? p.panelKey
                  : `面板 ${idx + 1}`;
              return <Tab key={p.debateId || idx} label={tabLabel} />;
            })}
          </Tabs>
        </Paper>
      )}

      <Paper variant="outlined" sx={{ overflow: 'hidden', borderRadius: '10px' }}>
        <Stack
          direction={{ xs: 'column', md: 'row' }}
          spacing={1.5}
          alignItems={{ md: 'center' }}
          sx={{ px: 2, py: 1.5, borderBottom: '1px solid', borderColor: 'divider', bgcolor: 'surfaceMuted' }}
        >
          <AccountTreeIcon color="primary" />
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography fontWeight={800} sx={{ fontSize: '0.92rem' }}>{purposeTitle}</Typography>
            <Typography variant="caption" color="text.secondary">
              同阶段封存后统一揭示，页面只展示匿名候选和确定性社会选择结果
            </Typography>
          </Box>
          <Stack direction="row" spacing={1} alignItems="center">
            {currentTrace.panelKey && (
              <Chip
                size="small"
                label={currentTrace.panelKey}
                sx={{
                  height: 24,
                  fontSize: '0.72rem',
                  fontWeight: 600,
                  bgcolor: 'background.paper',
                  border: '1px solid',
                  borderColor: 'divider',
                  fontFamily: 'monospace',
                }}
              />
            )}
            <Chip
              size="small"
              icon={<LockOutlinedIcon sx={{ fontSize: '0.9rem !important' }} />}
              label="双盲隔离"
              sx={{
                height: 24,
                fontSize: '0.72rem',
                fontWeight: 700,
                bgcolor: 'background.paper',
                border: '1px solid',
                borderColor: 'divider',
              }}
            />
          </Stack>
        </Stack>
        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>阶段</TableCell>
                <TableCell>进度</TableCell>
                <TableCell>任务明细</TableCell>
                <TableCell>恢复点</TableCell>
                <TableCell>截止时间</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {currentTrace.phases.map((phase) => {
                const progress = phase.requiredTasks > 0
                  ? Math.round((phase.terminalTasks / phase.requiredTasks) * 100)
                  : 0;
                return (
                  <TableRow
                    key={phase.phaseType}
                    sx={{
                      '&:hover': { bgcolor: 'rgba(15, 23, 42, 0.015)' },
                    }}
                  >
                    <TableCell>
                      <Typography variant="body2" fontWeight={700}>
                        {phaseLabels[phase.phaseType]}
                      </Typography>
                      <Box sx={{ mt: 0.5 }}>
                        <StatusBadge status={phase.status} size="small" />
                      </Box>
                    </TableCell>
                    <TableCell sx={{ minWidth: 160 }}>
                      <Stack spacing={0.5}>
                        <LinearProgress
                          variant="determinate"
                          value={progress}
                          sx={{
                            height: 6,
                            borderRadius: '3px',
                            bgcolor: 'rgba(15, 23, 42, 0.06)',
                            '& .MuiLinearProgress-bar': {
                              borderRadius: '3px',
                              bgcolor: progress === 100 ? '#16a34a' : 'primary.main',
                            },
                          }}
                        />
                        <Typography variant="caption" sx={{ fontFeatureSettings: '"tnum"' }}>
                          {phase.terminalTasks} / {phase.requiredTasks}（{progress}%）
                        </Typography>
                      </Stack>
                    </TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>
                      <Typography variant="caption" sx={{ fontFeatureSettings: '"tnum"' }}>
                        完成 {phase.completedTasks} · 处理中 {phase.claimedTasks} · 待处理 {phase.pendingTasks}
                      </Typography>
                      {(phase.failedTasks > 0 || phase.timedOutTasks > 0 || phase.cancelledTasks > 0) && (
                        <Typography variant="caption" color="error" display="block" sx={{ mt: 0.25, fontFeatureSettings: '"tnum"' }}>
                          失败 {phase.failedTasks} · 超时 {phase.timedOutTasks} · 已取消 {phase.cancelledTasks}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell>
                      {phase.recoveryPoint ? (
                        <Chip
                          size="small"
                          icon={<CheckCircleOutlineIcon sx={{ fontSize: '0.9rem !important' }} />}
                          label="可恢复"
                          sx={{
                            height: 22,
                            fontSize: '0.72rem',
                            fontWeight: 700,
                            bgcolor: 'rgba(22, 163, 74, 0.08)',
                            color: '#15803d',
                            borderColor: 'transparent',
                          }}
                        />
                      ) : (
                        <Typography variant="caption" color="text.secondary">尚未揭示</Typography>
                      )}
                    </TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap', fontFeatureSettings: '"tnum"' }}>
                      {formatTime(phase.deadline)}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Box>
      </Paper>

      {decision ? (
        <Paper
          variant="outlined"
          sx={{
            p: 2.25,
            borderRadius: '10px',
            borderLeft: decision.status === 'SELECTED' ? '4px solid #16a34a' : '4px solid #f59e0b',
            bgcolor: decision.status === 'SELECTED' ? 'rgba(22, 163, 74, 0.015)' : 'rgba(245, 158, 11, 0.015)',
          }}
        >
          <Stack spacing={1.75}>
            <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.25} alignItems={{ md: 'center' }}>
              <GavelIcon color={decision.status === 'SELECTED' ? 'success' : 'warning'} />
              <Box sx={{ flex: 1 }}>
                <Typography fontWeight={800} sx={{ fontSize: '0.95rem' }}>{decisionLabels[decision.status]}</Typography>
                <Typography variant="body2" color="text.secondary">
                  {decision.explanation || '社会选择计算已完成'}
                </Typography>
              </Box>
              <Chip
                color={decision.status === 'SELECTED' ? 'success' : 'warning'}
                label={decision.status === 'SELECTED' ? decision.winnerCandidateAlias || '严格胜者' : '不可自动执行'}
                sx={{ fontWeight: 800, letterSpacing: '0.02em' }}
              />
            </Stack>
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={1.5}
              sx={{
                p: 1.5,
                bgcolor: 'surfaceMuted',
                borderRadius: '8px',
                border: '1px solid',
                borderColor: 'divider',
              }}
            >
              <Metric label="有效逻辑角色" value={String(decision.contributingRoleCount)} />
              <Metric label="匿名排名" value={compactJson(decision.rankingJson)} />
              <Metric label="决策时间" value={formatTime(decision.decidedAt)} />
            </Stack>
            <MatrixTable title="角色归一偏好矩阵" value={decision.pairwiseMatrixJson} />
            <MatrixTable title="Schulze 最强路径" value={decision.strongestPathsJson} />
          </Stack>
        </Paper>
      ) : (
        <Alert severity="info" sx={{ borderRadius: '8px' }}>
          社会选择尚未完成；已揭示阶段可作为 Worker 重启后的确定恢复点。
        </Alert>
      )}
    </Stack>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.25 }}>
        {label}
      </Typography>
      <Typography variant="body2" fontWeight={800} sx={{ wordBreak: 'break-word', fontFeatureSettings: '"tnum"' }}>
        {value}
      </Typography>
    </Box>
  );
}

function MatrixTable({ title, value }: { title: string; value: string }) {
  const matrix = parseMatrix(value);
  if (!matrix) return null;
  const candidates = Object.keys(matrix);
  return (
    <Box sx={{ overflowX: 'auto', mt: 1 }}>
      <Typography variant="caption" sx={{ fontWeight: 700, color: 'text.secondary', display: 'block', mb: 0.5 }}>
        {title}
      </Typography>
      <Table size="small" sx={{ border: '1px solid', borderColor: 'divider', borderRadius: '6px' }}>
        <TableHead>
          <TableRow sx={{ bgcolor: 'surfaceMuted' }}>
            <TableCell sx={{ fontWeight: 700 }}>候选</TableCell>
            {candidates.map((candidate) => (
              <TableCell key={candidate} align="right" sx={{ fontWeight: 700 }}>
                {candidate}
              </TableCell>
            ))}
          </TableRow>
        </TableHead>
        <TableBody>
          {candidates.map((row) => (
            <TableRow key={row} sx={{ '&:hover': { bgcolor: 'rgba(15, 23, 42, 0.015)' } }}>
              <TableCell sx={{ fontWeight: 600 }}>{row}</TableCell>
              {candidates.map((column) => (
                <TableCell key={column} align="right" sx={{ fontFeatureSettings: '"tnum"', fontFamily: 'Roboto Mono, monospace', fontSize: '0.82rem' }}>
                  {matrix[row]?.[column] ?? '-'}
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Box>
  );
}

function parseMatrix(value: string): Record<string, Record<string, number>> | null {
  try {
    const parsed: unknown = JSON.parse(value);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;
    return parsed as Record<string, Record<string, number>>;
  } catch {
    return null;
  }
}

function compactJson(value: string): string {
  try {
    const parsed: unknown = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.join(' → ') : value;
  } catch {
    return value;
  }
}
