import AccountTreeIcon from '@mui/icons-material/AccountTree';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import GavelIcon from '@mui/icons-material/Gavel';
import LockOutlinedIcon from '@mui/icons-material/LockOutlined';
import { Alert, Box, Chip, LinearProgress, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';

import type { DebateProtocolTrace } from './types';
import { formatTime, statusColor, statusLabel } from './ui';

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

export function DebateProtocolPanel({ trace }: { trace: DebateProtocolTrace }) {
  const decision = trace.decision;
  return <Stack spacing={1.5}>
    <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }} sx={{ px: 2, py: 1.5, borderBottom: '1px solid', borderColor: 'divider' }}>
        <AccountTreeIcon color="primary" />
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography fontWeight={800}>SDB-SCA 对称辩论</Typography>
          <Typography variant="caption" color="text.secondary">同阶段封存后统一揭示，页面只展示匿名候选和确定性社会选择结果</Typography>
        </Box>
        <Chip size="small" icon={<LockOutlinedIcon />} label="双盲隔离" variant="outlined" />
      </Stack>
      <Box sx={{ overflowX: 'auto' }}>
        <Table size="small">
          <TableHead><TableRow><TableCell>阶段</TableCell><TableCell>进度</TableCell><TableCell>任务明细</TableCell><TableCell>恢复点</TableCell><TableCell>截止时间</TableCell></TableRow></TableHead>
          <TableBody>{trace.phases.map((phase) => {
            const progress = Math.round((phase.terminalTasks / phase.requiredTasks) * 100);
            return <TableRow key={phase.phaseType}>
              <TableCell><Typography variant="body2" fontWeight={700}>{phaseLabels[phase.phaseType]}</Typography><Chip size="small" color={statusColor(phase.status)} label={statusLabel(phase.status)} sx={{ mt: .5 }} /></TableCell>
              <TableCell sx={{ minWidth: 160 }}><Stack spacing={.5}><LinearProgress variant="determinate" value={progress} /><Typography variant="caption">{phase.terminalTasks} / {phase.requiredTasks}（{progress}%）</Typography></Stack></TableCell>
              <TableCell sx={{ whiteSpace: 'nowrap' }}><Typography variant="caption">完成 {phase.completedTasks} · 处理中 {phase.claimedTasks} · 待处理 {phase.pendingTasks}</Typography>{(phase.failedTasks > 0 || phase.timedOutTasks > 0 || phase.cancelledTasks > 0) && <Typography variant="caption" color="error" display="block">失败 {phase.failedTasks} · 超时 {phase.timedOutTasks} · 已取消 {phase.cancelledTasks}</Typography>}</TableCell>
              <TableCell>{phase.recoveryPoint ? <Chip size="small" icon={<CheckCircleOutlineIcon />} color="success" label="可恢复" /> : <Typography variant="caption" color="text.secondary">尚未揭示</Typography>}</TableCell>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatTime(phase.deadline)}</TableCell>
            </TableRow>;
          })}</TableBody>
        </Table>
      </Box>
    </Paper>

    {decision ? <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack spacing={1.5}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} alignItems={{ md: 'center' }}>
          <GavelIcon color={decision.status === 'SELECTED' ? 'success' : 'warning'} />
          <Box sx={{ flex: 1 }}><Typography fontWeight={800}>{decisionLabels[decision.status]}</Typography><Typography variant="body2" color="text.secondary">{decision.explanation || '社会选择计算已完成'}</Typography></Box>
          <Chip color={decision.status === 'SELECTED' ? 'success' : 'warning'} label={decision.status === 'SELECTED' ? decision.winnerCandidateAlias || '严格胜者' : '不可自动执行'} />
        </Stack>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <Metric label="有效逻辑角色" value={String(decision.contributingRoleCount)} />
          <Metric label="匿名排名" value={compactJson(decision.rankingJson)} />
          <Metric label="决策时间" value={formatTime(decision.decidedAt)} />
        </Stack>
        <MatrixTable title="角色归一偏好矩阵" value={decision.pairwiseMatrixJson} />
        <MatrixTable title="Schulze 最强路径" value={decision.strongestPathsJson} />
      </Stack>
    </Paper> : <Alert severity="info">社会选择尚未完成；已揭示阶段可作为 Worker 重启后的确定恢复点。</Alert>}
  </Stack>;
}

function Metric({ label, value }: { label: string; value: string }) {
  return <Box sx={{ flex: 1, minWidth: 0 }}><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="body2" fontWeight={700} sx={{ wordBreak: 'break-word' }}>{value}</Typography></Box>;
}

function MatrixTable({ title, value }: { title: string; value: string }) {
  const matrix = parseMatrix(value);
  if (!matrix) return null;
  const candidates = Object.keys(matrix);
  return <Box sx={{ overflowX: 'auto' }}><Typography variant="caption" color="text.secondary">{title}</Typography><Table size="small" sx={{ mt: .5 }}><TableHead><TableRow><TableCell>候选</TableCell>{candidates.map((candidate) => <TableCell key={candidate} align="right">{candidate}</TableCell>)}</TableRow></TableHead><TableBody>{candidates.map((row) => <TableRow key={row}><TableCell>{row}</TableCell>{candidates.map((column) => <TableCell key={column} align="right">{matrix[row]?.[column] ?? '-'}</TableCell>)}</TableRow>)}</TableBody></Table></Box>;
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
