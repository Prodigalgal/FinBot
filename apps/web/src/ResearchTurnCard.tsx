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
  return <Paper variant="outlined" sx={{ p: 2, borderLeft: chair ? '4px solid' : undefined, borderLeftColor: 'primary.main' }}>
    <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}>
      <Box><Typography fontWeight={800}>{turn.roleName}</Typography><Typography variant="caption" color="text.secondary">{chair ? '主席最终裁决' : `第 ${turn.round} 轮 · 发言顺序 ${turn.turnIndex}`}</Typography></Box>
      {turn.confidence !== null && <Chip size="small" label={`置信度 ${(Number(turn.confidence) * 100).toFixed(0)}%`} />}
    </Stack>
    <Typography sx={{ mt: 1 }} fontWeight={chair ? 700 : 500}>{turn.summary}</Typography>
    {turn.argument && turn.argument !== turn.summary && <Typography variant="body2" color="text.secondary" sx={{ mt: .75, whiteSpace: 'pre-wrap' }}>{turn.argument}</Typography>}
    <InsightList title="关键依据" values={evidence} />
    <InsightList title="反方观点与挑战" values={challenges} />
    <InsightList title="本轮修订" values={revisions} />
  </Paper>;
}

function InsightList({ title, values }: { title: string; values: string[] }) {
  if (values.length === 0) return null;
  return <Box sx={{ mt: 1.25 }}><Typography variant="caption" color="text.secondary">{title}</Typography><Stack spacing={.25} sx={{ mt: .25 }}>{values.map((value, index) => <Typography key={`${title}-${index}`} variant="body2">{index + 1}. {value}</Typography>)}</Stack></Box>;
}
