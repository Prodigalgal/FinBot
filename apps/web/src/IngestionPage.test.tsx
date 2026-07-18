import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import type { IngestionWorkspace, SourceRecord } from './types';

const source: SourceRecord = {
  sourceId: 'source_test_rss01',
  displayName: '测试 RSS',
  mode: 'RSS',
  tier: 'T2',
  category: 'market_news',
  provider: null,
  trustWeight: 0.7,
  pollIntervalSeconds: 900,
  priority: 'P2',
  assetScope: ['BTCUSDT'],
  feedUrls: ['https://example.com/feed.xml'],
  seedUrls: [],
  searchQueries: [],
  endpointBaseUrl: null,
  credentialSupported: false,
  outboundRoute: 'PUBLIC_DATA',
  maximumResults: 10,
  maximumScrapeTargets: 0,
  enabled: true,
  version: 0,
  aiWebSearchBinding: null,
};

const createdSource: SourceRecord = {
  ...source,
  sourceId: 'source_created_rss01',
  displayName: '用户 RSS',
  feedUrls: ['https://news.example.com/feed.xml'],
};

function workspaceSource(record: SourceRecord): IngestionWorkspace['sources'][number] {
  return {
    sourceId: record.sourceId,
    displayName: record.displayName,
    mode: record.mode,
    tier: record.tier,
    category: record.category,
    outboundRoute: record.outboundRoute,
    credentialSupported: record.credentialSupported,
    credentialConfigured: true,
    credentialSource: 'NOT_REQUIRED',
    credentialFingerprint: null,
    credentialVersion: 0,
    enabled: record.enabled,
    version: record.version,
    latestStatus: null,
    fetchedCount: 0,
    insertedCount: 0,
    duplicateCount: 0,
    errorCode: null,
    errorMessage: null,
    lastCollectedAt: null,
  };
}

const workspace: IngestionWorkspace = {
  rawEvidenceCount: 0,
  normalizedDocumentCount: 0,
  compressionCount: 0,
  aiReviewCount: 0,
  sources: [workspaceSource(source)],
  recentRuns: [],
  recentAiReviews: [],
  sourceCatalogVersion: 'v3',
  sourceCatalogManifestHash: 'e1abe028cf7e97ac51b40b2485a37a4bbcf48d969ad31143b379bcf537abed22',
  sourceCatalogSize: 61,
  generatedAt: '2026-07-16T14:00:00Z',
};

const workspaceAfterCreate: IngestionWorkspace = {
  ...workspace,
  sources: [...workspace.sources, workspaceSource(createdSource)],
};

const apiMock = vi.hoisted(() => ({
  ingestionWorkspace: vi.fn(),
  tasks: vi.fn(),
  sources: vi.fn(),
  configuration: vi.fn(),
  sourceHealth: vi.fn(),
  documents: vi.fn(),
  createSource: vi.fn(),
  updateSource: vi.fn(),
  deleteSource: vi.fn(),
  setSourceEnabled: vi.fn(),
  testSource: vi.fn(),
  collectSource: vi.fn(),
  putRuntimeSecret: vi.fn(),
  clearRuntimeSecret: vi.fn(),
}));

vi.mock('./api', () => ({ api: apiMock }));

import { IngestionPage } from './IngestionPage';

beforeEach(() => {
  apiMock.ingestionWorkspace.mockResolvedValue(workspace);
  apiMock.tasks.mockResolvedValue([]);
  apiMock.sources.mockResolvedValue([source]);
  apiMock.configuration.mockResolvedValue({ settings: [], providers: [], models: [] });
  apiMock.documents.mockResolvedValue([]);
  apiMock.sourceHealth.mockResolvedValue({
    sourceId: source.sourceId, serviceReady: true, egressReady: true,
    routeType: 'PUBLIC_DATA', routeEndpoint: 'direct', channelStatus: 'READY',
    firecrawlChannelStatus: 'NOT_APPLICABLE', rateLimitStatus: 'READY; hosts=0',
    lastSuccessAt: '2026-07-16T14:00:00Z', lastBlockedAt: null,
    lastAttemptAt: '2026-07-16T14:00:00Z', latestOutcome: 'PREPARED',
    latestStatusCode: 200, latestErrorCode: null, safeMessage: null,
  });
  apiMock.createSource.mockResolvedValue(createdSource);
  apiMock.testSource.mockResolvedValue({
    collectionId: 'collection_test01', sourceId: source.sourceId, status: 'COMPLETED',
    fetchedCount: 1, insertedCount: 1, duplicateCount: 0, errorCode: null, errorMessage: null,
  });
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

it('creates a user-managed RSS source from the management dialog', async () => {
  apiMock.ingestionWorkspace
    .mockResolvedValueOnce(workspace)
    .mockResolvedValue(workspaceAfterCreate);
  apiMock.sources
    .mockResolvedValueOnce([source])
    .mockResolvedValue([source, createdSource]);
  const user = userEvent.setup();
  render(<IngestionPage />);
  expect(await screen.findByText(/默认信源目录 v3 · 61 项/)).toBeInTheDocument();
  await user.click(await screen.findByRole('button', { name: '新增信源' }));
  await user.type(screen.getByLabelText(/^名称/), '用户 RSS');
  await user.type(screen.getByLabelText(/^RSS Feed URL/), 'https://news.example.com/feed.xml');
  await user.click(screen.getByRole('button', { name: '保存' }));

  await waitFor(() => expect(apiMock.createSource).toHaveBeenCalledWith(expect.objectContaining({
    displayName: '用户 RSS',
    mode: 'RSS',
    feedUrls: ['https://news.example.com/feed.xml'],
    outboundRoute: 'PUBLIC_DATA',
  })));
  await waitFor(() => expect(screen.getByLabelText('信息源')).toHaveTextContent('用户 RSS'));
}, 10_000);

it('runs an immediate source test through the selected source configuration', async () => {
  const user = userEvent.setup();
  render(<IngestionPage />);
  await user.click(await screen.findByRole('button', { name: '在线测试' }));

  await waitFor(() => expect(apiMock.testSource).toHaveBeenCalledWith(
    source.sourceId,
    '最新市场、宏观、监管和交易所事件',
  ));
  expect(await screen.findByText(/在线测试已完成/)).toBeInTheDocument();
});

it('shows the selected source runtime channel and egress state', async () => {
  render(<IngestionPage />);

  expect(await screen.findByText('采集器可用')).toBeInTheDocument();
  expect(screen.getByText('PUBLIC_DATA · 出口可用')).toBeInTheDocument();
  expect(apiMock.sourceHealth).toHaveBeenCalledWith(source.sourceId);
});
