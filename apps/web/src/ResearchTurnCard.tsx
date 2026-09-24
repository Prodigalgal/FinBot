import { Box, Chip, Paper, Stack, Typography } from '@mui/material';

import type { ResearchHistoryDetail } from './types';
import { jsonList } from './ui';

type AgentTurn = ResearchHistoryDetail['agentTurns'][number];

export function orderedResearchTurns(turns: AgentTurn[]): AgentTurn[] {
  return [...turns].sort((left, right) => {
    const chairOrder = Number(right.messageType === 'CHAIR_VERDICT') - Number(left.messageType === 'CHAIR_VERDICT');
    return chairOrder || left.round - right.round || left.turnIndex - right.turnIndex;
  });
}

export function ResearchTurnCard({ turn }: { turn: AgentTurn }) {
  const chair = turn.messageType === 'CHAIR_VERDICT';
  const evidence = [...jsonList(turn.claimsJson), ...jsonList(turn.evidenceReferencesJson)];
  const challenges = jsonList(turn.challengesJson);
  const revisions = jsonList(turn.revisionNotesJson);
  const confidencePercent = turn.confidence !== null ? Math.round(Number(turn.confidence) * 100) : null;

  return (
    <Paper
      variant="outlined"
      sx={{
        p: 2.25,
        borderRadius: '10px',
        bgcolor: chair ? 'rgba(29, 78, 216, 0.015)' : 'background.paper',
        borderLeft: chair ? '4px solid #1d4ed8' : '1px solid',
        borderColor: chair ? undefined : 'divider',
        transition: 'box-shadow 0.2s ease, border-color 0.2s ease',
        '&:hover': {
          borderColor: chair ? '#1d4ed8' : 'rgba(15, 23, 42, 0.2)',
          boxShadow: '0 2px 8px -2px rgba(15, 23, 42, 0.06)',
        },
      }}
    >
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1}>
        <Box>
          <Stack direction="row" alignItems="center" spacing={1}>
            <Typography fontWeight={800} sx={{ fontSize: '0.95rem' }}>{turn.roleName}</Typography>
            {chair && (
              <Chip
                size="small"
                label="CHAIR"
                sx={{
                  height: 18,
                  fontSize: '0.65rem',
                  fontWeight: 800,
                  letterSpacing: '0.05em',
                  bgcolor: '#1d4ed8',
                  color: '#ffffff',
                  borderRadius: '4px',
                }}
              />
            )}
          </Stack>
          <Typography variant="caption" color="text.secondary" sx={{ letterSpacing: '-0.01em' }}>
            {chair ? '主席最终裁决 · 审计已归档' : `第 ${turn.round} 轮博弈 · 发言序列 #${turn.turnIndex}`}
          </Typography>
        </Box>
        {confidencePercent !== null && (
          <Chip
            size="small"
            variant="outlined"
            label={`置信度 ${confidencePercent}%`}
            sx={{
              fontWeight: 700,
              fontSize: '0.72rem',
              fontFeatureSettings: '"tnum"',
              borderColor: confidencePercent >= 70 ? 'rgba(22, 163, 74, 0.3)' : 'divider',
              bgcolor: confidencePercent >= 70 ? 'rgba(22, 163, 74, 0.06)' : 'surfaceMuted',
              color: confidencePercent >= 70 ? '#15803d' : 'text.primary',
            }}
          />
        )}
      </Stack>

      <Typography sx={{ mt: 1.25, fontSize: '0.92rem', lineHeight: 1.6 }} fontWeight={chair ? 700 : 500}>
        {turn.summary}
      </Typography>

      {turn.argument && turn.argument !== turn.summary && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.75, whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>
          {turn.argument}
        </Typography>
      )}

      {evidence.length > 0 && (
        <InsightList
          title="关键依据"
          values={evidence}
          bgColor="rgba(241, 245, 249, 0.7)"
          borderColor="rgba(226, 232, 240, 0.8)"
          accentColor="#2563eb"
        />
      )}
      {challenges.length > 0 && (
        <InsightList
          title="反方观点与风险挑战"
          values={challenges}
          bgColor="rgba(254, 242, 242, 0.6)"
          borderColor="rgba(254, 202, 202, 0.7)"
          accentColor="#dc2626"
        />
      )}
      {revisions.length > 0 && (
        <InsightList
          title="本轮修订与折衷"
          values={revisions}
          bgColor="rgba(240, 253, 244, 0.6)"
          borderColor="rgba(187, 247, 208, 0.7)"
          accentColor="#16a34a"
        />
      )}
    </Paper>
  );
}

function InsightList({
  title,
  values,
  bgColor = 'surfaceMuted',
  borderColor = 'divider',
  accentColor = '#475569',
}: {
  title: string;
  values: string[];
  bgColor?: string;
  borderColor?: string;
  accentColor?: string;
}) {
  if (values.length === 0) return null;
  return (
    <Box
      sx={{
        mt: 1.25,
        p: 1.25,
        borderRadius: '8px',
        bgcolor: bgColor,
        border: '1px solid',
        borderColor: borderColor,
      }}
    >
      <Typography
        variant="caption"
        sx={{
          fontWeight: 700,
          color: accentColor,
          display: 'block',
          letterSpacing: '-0.01em',
        }}
      >
        {title}
      </Typography>
      <Stack spacing={0.4} sx={{ mt: 0.5 }}>
        {values.map((value, index) => (
          <Typography key={`${title}-${index}`} variant="body2" sx={{ fontSize: '0.82rem', lineHeight: 1.5, color: '#334155' }}>
            <Box component="span" sx={{ fontWeight: 700, mr: 0.5, color: accentColor }}>
              {index + 1}.
            </Box>
            {value}
          </Typography>
        ))}
      </Stack>
    </Box>
  );
}
