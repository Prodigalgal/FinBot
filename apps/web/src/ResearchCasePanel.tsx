import CallSplitIcon from '@mui/icons-material/CallSplit';
import { Box, Chip, Paper, Stack, Typography } from '@mui/material';

import type { ResearchCase } from './types';
import { SectionTitle, formatTime, statusColor, statusLabel } from './ui';

const segmentNames: Record<ResearchCase['segments'][number]['segmentType'], string> = {
  EVIDENCE: '共享证据',
  LIVE_RESEARCH: '实盘研究',
  DEMO_AUTOTRADE: '模拟执行',
};

export function ResearchCasePanel({ researchCase }: { researchCase: ResearchCase }) {
  return <Box>
    <SectionTitle title="研究分段" action={<Chip size="small" color={statusColor(researchCase.status)} label={statusLabel(researchCase.status)} />} />
    <Paper variant="outlined" sx={{ overflow: 'hidden' }}>
      {researchCase.segments.map((segment, index) => <Stack
        key={segment.segmentId}
        direction={{ xs: 'column', md: 'row' }}
        alignItems={{ md: 'center' }}
        spacing={1.5}
        sx={{ p: 1.5, borderTop: index ? '1px solid' : 0, borderColor: 'divider' }}
      >
        <CallSplitIcon fontSize="small" color={segment.segmentType === 'EVIDENCE' ? 'disabled' : 'action'} />
        <Box sx={{ width: { md: 150 } }}><Typography fontWeight={700}>{segmentNames[segment.segmentType]}</Typography><Typography variant="caption" color="text.secondary">{segment.dataPlane === 'LIVE' ? '实盘数据域' : segment.dataPlane === 'PAPER' ? '模拟数据域' : '不可变共享输入'}</Typography></Box>
        <Box sx={{ flex: 1, minWidth: 0 }}><Typography variant="body2" sx={{ wordBreak: 'break-all' }}>{segment.workflowRunId || researchCase.evidenceArtifactId || '等待压缩快照'}</Typography><Typography variant="caption" color="text.secondary">{segment.evidenceArtifactId ? `证据 ${segment.evidenceArtifactId}` : '证据准备中'} · {formatTime(segment.startedAt)} → {formatTime(segment.completedAt)}</Typography>{segment.errorMessage && <Typography variant="caption" color="error" display="block">{segment.errorCode}: {segment.errorMessage}</Typography>}</Box>
        <Chip size="small" color={statusColor(segment.status)} label={statusLabel(segment.status)} sx={{ alignSelf: { xs: 'flex-start', md: 'center' } }} />
      </Stack>)}
    </Paper>
  </Box>;
}
