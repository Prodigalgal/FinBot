import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';

import { DebateProtocolPanel } from './DebateProtocolPanel';
import type { DebateProtocolTrace } from './types';

afterEach(cleanup);

it('shows barrier progress and a non-executable social-choice result without model identities', () => {
  const trace: DebateProtocolTrace = {
    debateId: 'debate_anonymous_test',
    protocol: 'SDB_SCA_V1',
    phases: [{
      phaseType: 'BALLOT',
      status: 'REVEALED',
      requiredTasks: 6,
      terminalTasks: 6,
      pendingTasks: 0,
      claimedTasks: 0,
      completedTasks: 4,
      failedTasks: 1,
      timedOutTasks: 1,
      cancelledTasks: 0,
      deadline: '2026-07-22T14:30:00Z',
      openedAt: '2026-07-22T14:00:00Z',
      revealedAt: '2026-07-22T14:10:00Z',
      completedAt: null,
      recoveryPoint: true,
    }],
    artifacts: [],
    ballots: [],
    decision: {
      status: 'ORDER_SENSITIVE',
      winnerCandidateAlias: null,
      contributingRoleCount: 3,
      undefeatedCandidatesJson: '["candidate_alpha","candidate_beta"]',
      pairwiseMatrixJson: '{"candidate_alpha":{"candidate_alpha":0,"candidate_beta":2},"candidate_beta":{"candidate_alpha":1,"candidate_beta":0}}',
      strongestPathsJson: '{"candidate_alpha":{"candidate_alpha":0,"candidate_beta":2},"candidate_beta":{"candidate_alpha":1,"candidate_beta":0}}',
      rankingJson: '["candidate_alpha","candidate_beta"]',
      forecastJson: null,
      explanation: '正序与逆序展示产生不同胜者。',
      decisionHash: 'a'.repeat(64),
      decidedAt: '2026-07-22T14:11:00Z',
    },
  };

  render(<DebateProtocolPanel trace={trace} />);

  expect(screen.getByText('匿名投票')).toBeInTheDocument();
  expect(screen.getByText('6 / 6（100%）')).toBeInTheDocument();
  expect(screen.getByText('存在顺序敏感')).toBeInTheDocument();
  expect(screen.getByText('不可自动执行')).toBeInTheDocument();
  expect(screen.getAllByText('candidate_alpha').length).toBeGreaterThan(0);
  expect(screen.queryByText(/grok|gemini|provider_/i)).not.toBeInTheDocument();
});
