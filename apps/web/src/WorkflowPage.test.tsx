import { render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';

const apiMock = vi.hoisted(() => ({
  workflowDefinitions: vi.fn().mockRejectedValue(new Error('工作流契约加载失败')),
  workflowSchema: vi.fn().mockResolvedValue({}),
  configuration: vi.fn().mockResolvedValue({}),
  agentRoles: vi.fn().mockResolvedValue([]),
  researchHistory: vi.fn().mockResolvedValue([]),
}));

vi.mock('./api', () => ({ api: apiMock }));

import { WorkflowPage } from './WorkflowPage';

it('renders a recoverable error instead of remaining in loading state', async () => {
  render(<WorkflowPage />);

  expect(await screen.findByText('工作流契约加载失败')).toBeInTheDocument();
  expect(screen.queryByText('正在加载工作流定义')).not.toBeInTheDocument();
});
