import CallSplitIcon from '@mui/icons-material/CallSplit';
import { Box, Paper, Stack, Typography } from '@mui/material';

import type { ResearchCase } from './types';
import { CopyableText, SectionTitle, StatusBadge, formatTime } from './ui';

const segmentNames: Record<ResearchCase['segments'][number]['segmentType'], string> = {
  EVIDENCE: '共享证据',
  LIVE_RESEARCH: '实盘研究',
  DEMO_AUTOTRADE: '模拟执行',
};

const dataPlaneLabels: Record<string, { label: string; color: string; bg: string }> = {
  LIVE: { label: '实盘数据域', color: '#1d4ed8', bg: 'rgba(29, 78, 216, 0.08)' },
  PAPER: { label: '模拟数据域', color: '#0891b2', bg: 'rgba(8, 145, 178, 0.08)' },
};

export function ResearchCasePanel({ researchCase }: { researchCase: ResearchCase }) {
  return (
    <Box>
      <SectionTitle
        title="研究分段"
        action={<StatusBadge status={researchCase.status} size="small" />}
      />
      <Paper variant="outlined" sx={{ overflow: 'hidden', borderRadius: '10px' }}>
        {researchCase.segments.map((segment, index) => {
          const plane = segment.dataPlane ? dataPlaneLabels[segment.dataPlane] : undefined;
          return (
            <Stack
              key={segment.segmentId}
              direction={{ xs: 'column', md: 'row' }}
              alignItems={{ md: 'center' }}
              spacing={1.75}
              sx={{
                p: 1.75,
                borderTop: index ? '1px solid' : 0,
                borderColor: 'divider',
                transition: 'background-color 0.15s ease',
                '&:hover': {
                  bgcolor: 'rgba(15, 23, 42, 0.015)',
                },
              }}
            >
              <Box
                sx={{
                  width: 32,
                  height: 32,
                  borderRadius: '6px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  bgcolor: segment.segmentType === 'EVIDENCE' ? 'surfaceMuted' : 'rgba(29, 78, 216, 0.06)',
                  color: segment.segmentType === 'EVIDENCE' ? 'text.secondary' : 'primary.main',
                  flexShrink: 0,
                }}
              >
                <CallSplitIcon fontSize="small" />
              </Box>
              <Box sx={{ width: { md: 170 }, flexShrink: 0 }}>
                <Typography fontWeight={700} sx={{ fontSize: '0.88rem' }}>
                  {segmentNames[segment.segmentType]}
                </Typography>
                <Box
                  component="span"
                  sx={{
                    display: 'inline-block',
                    mt: 0.25,
                    px: 0.75,
                    py: 0.2,
                    borderRadius: '4px',
                    fontSize: '0.7rem',
                    fontWeight: 600,
                    bgcolor: plane ? plane.bg : 'surfaceMuted',
                    color: plane ? plane.color : 'text.secondary',
                  }}
                >
                  {plane ? plane.label : '不可变共享输入'}
                </Box>
              </Box>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                {segment.workflowRunId ? (
                  <CopyableText text={segment.workflowRunId} />
                ) : (
                  <Typography variant="body2" sx={{ color: 'text.secondary', fontStyle: 'italic' }}>
                    {researchCase.evidenceArtifactId || '等待压缩快照'}
                  </Typography>
                )}
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.25 }}>
                  {segment.evidenceArtifactId ? `证据快照 #${segment.evidenceArtifactId}` : '证据准备中'} · {formatTime(segment.startedAt)} → {formatTime(segment.completedAt)}
                </Typography>
                {segment.errorMessage && (
                  <Typography variant="caption" color="error" display="block" sx={{ mt: 0.25 }}>
                    {segment.errorCode}: {segment.errorMessage}
                  </Typography>
                )}
              </Box>
              <StatusBadge status={segment.status} size="small" />
            </Stack>
          );
        })}
      </Paper>
    </Box>
  );
}
