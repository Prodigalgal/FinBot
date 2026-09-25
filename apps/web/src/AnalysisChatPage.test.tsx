import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { AnalysisChatSession, AnalysisChatTurn, ResearchHistoryDetail } from './types';

const session: AnalysisChatSession = {
  chatId: 'chat_test001', title: '行情怎么看', workflowVersionId: 'version_test001',
  createdAt: '2026-09-25T00:00:00Z', updatedAt: '2026-09-25T00:00:00Z',
};

const firstTurn: AnalysisChatTurn = {
  turnId: 'chatturn_test001', chatId: session.chatId, turnNumber: 1,
  userMessage: '行情怎么看', workflowRunId: 'run_test001', taskId: 'task_test001',
  workflowStatus: 'COMPLETED', taskStatus: 'COMPLETED',
  answerSummary: null, answer: '第一个共识', createdAt: '2026-09-25T00:00:00Z',
};

const secondTurn: AnalysisChatTurn = {
  ...firstTurn, turnId: 'chatturn_test002', turnNumber: 2, userMessage: '还有什么风险',
  workflowRunId: 'run_test002', taskId: 'task_test002', answer: '第二个共识',
  createdAt: '2026-09-25T00:01:00Z',
};

const apiMock = vi.hoisted(() => ({
  analysisChats: vi.fn(),
  workflowDefinitions: vi.fn(),
  analysisChat: vi.fn(),
  analysisChatTurns: vi.fn(),
  sendAnalysisChatMessage: vi.fn(),
  researchDetail: vi.fn(),
  workflowEventsUrl: vi.fn(),
}));

vi.mock('./api', () => ({ api: apiMock }));

import { AnalysisChatPage } from './AnalysisChatPage';

afterEach(() => vi.clearAllMocks());

it('restores a saved answer and sends a follow-up through the analysis chat API', async () => {
  let savedTurns = [firstTurn];
  apiMock.analysisChats.mockResolvedValue([session]);
  apiMock.workflowDefinitions.mockResolvedValue([{
    definitionId: 'definition_test001', name: '研究工作流', active: true,
    publishedVersionId: session.workflowVersionId, publishedVersionNumber: 1,
  }]);
  apiMock.analysisChat.mockResolvedValue(session);
  apiMock.analysisChatTurns.mockImplementation(async () => savedTurns);
  apiMock.sendAnalysisChatMessage.mockImplementation(async () => {
    savedTurns = [firstTurn, secondTurn];
    return secondTurn;
  });
  apiMock.researchDetail.mockResolvedValue({
    events: [], checkpoints: [], aiInvocations: [], debateProtocol: null,
  } as unknown as ResearchHistoryDetail);

  render(<AnalysisChatPage />);

  expect(await screen.findByText('第一个共识')).toBeInTheDocument();
  fireEvent.change(screen.getByRole('textbox', { name: '分析消息' }), { target: { value: '还有什么风险' } });
  fireEvent.click(screen.getByRole('button', { name: '发送分析消息' }));

  expect(await screen.findByText('第二个共识')).toBeInTheDocument();
  await waitFor(() => expect(apiMock.sendAnalysisChatMessage).toHaveBeenCalledWith(
    session.chatId, '还有什么风险', expect.any(String),
  ));
  expect(screen.getByText('仅分析 · 不交易')).toBeInTheDocument();
});

it('keeps saved conversations readable when workflow definitions are unavailable', async () => {
  apiMock.analysisChats.mockResolvedValue([session]);
  apiMock.workflowDefinitions.mockRejectedValue(new Error('工作流目录暂不可用'));
  apiMock.analysisChat.mockResolvedValue(session);
  apiMock.analysisChatTurns.mockResolvedValue([firstTurn]);
  apiMock.researchDetail.mockResolvedValue({
    events: [], checkpoints: [], aiInvocations: [], debateProtocol: null,
  } as unknown as ResearchHistoryDetail);

  render(<AnalysisChatPage />);

  expect(await screen.findByText('第一个共识')).toBeInTheDocument();
  expect(await screen.findByText('工作流目录暂不可用')).toBeInTheDocument();
});
