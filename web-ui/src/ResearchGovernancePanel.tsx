import { Box, Tab, Tabs } from '@mui/material';
import { useState } from 'react';

import { FeedbackPanel } from './FeedbackPanel';
import { ResearchHistoryPanel } from './ResearchHistoryPanel';
import { ReviewInboxPanel } from './ReviewInboxPanel';

type GovernanceView = 'reviews' | 'history' | 'feedback';

export function ResearchGovernancePanel() {
  const [view, setView] = useState<GovernanceView>('reviews');
  return (
    <Box>
      <Tabs value={view} onChange={(_, value: GovernanceView) => setView(value)} variant="scrollable" scrollButtons="auto" allowScrollButtonsMobile sx={{ mb: 2, borderBottom: '1px solid', borderColor: 'divider' }}>
        <Tab value="reviews" label="待复核建议" />
        <Tab value="history" label="运行历史" />
        <Tab value="feedback" label="效果评估" />
      </Tabs>
      {view === 'reviews' && <ReviewInboxPanel />}
      {view === 'history' && <ResearchHistoryPanel />}
      {view === 'feedback' && <FeedbackPanel />}
    </Box>
  );
}
