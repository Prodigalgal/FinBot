import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import type { IngestionWorkspace, SourceRecord, TaskRecord } from './types';

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
  crawlerHeaderProfileId: 'header_default',
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

const sourceTestTask: TaskRecord = {
  taskId: 'task_source_test01',
  taskType: 'INGESTION',
  status: 'PENDING',
  priority: 80,
  payloadSummary: `${source.sourceId}: 最新市场、宏观、监管和交易所事件`,
  attemptCount: 0,
  maximumAttempts: 3,
  availableAt: '2026-07-16T14:01:00Z',
  claimedAt: null,
  leaseExpiresAt: null,
  claimOwner: null,
  heartbeatAt: null,
  completedAt: null,
  errorCode: null,
  errorMessage: null,
  createdAt: '2026-07-16T14:01:00Z',
  updatedAt: '2026-07-16T14:01:00Z',
};

const workspaceAfterTest: IngestionWorkspace = {
  ...workspace,
  recentRuns: [{
    collectionId: 'collection_test01',
    workflowRunId: null,
    sourceId: source.sourceId,
    sourceName: source.displayName,
    query: '最新市场、宏观、监管和交易所事件',
    status: 'COMPLETED',
    fetchedCount: 1,
    insertedCount: 1,
    duplicateCount: 0,
    errorCode: null,
    errorMessage: null,
    startedAt: '2026-07-16T14:01:01Z',
    completedAt: '2026-07-16T14:01:02Z',
  }],
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
  task: vi.fn(),
  collectSource: vi.fn(),
  putRuntimeSecret: vi.fn(),
  clearRuntimeSecret: vi.fn(),
  crawlerHeaderProfiles: vi.fn(),
  createCrawlerHeaderProfile: vi.fn(),
  updateCrawlerHeaderProfile: vi.fn(),
  deleteCrawlerHeaderProfile: vi.fn(),
}));

vi.mock('./api', () => ({ api: apiMock }));

import { IngestionPage } from './IngestionPage';

beforeEach(() => {
  apiMock.ingestionWorkspace.mockResolvedValue(workspace);
  apiMock.tasks.mockResolvedValue([]);
  apiMock.sources.mockResolvedValue([source]);
  apiMock.configuration.mockResolvedValue({ settings: [], providers: [], models: [] });
  apiMock.crawlerHeaderProfiles.mockResolvedValue([{
    profileId: 'header_default', displayName: 'FinBot 默认爬虫请求头',
    userAgent: 'FinBot/2.0 (contact: finbot@omnnu.xyz)', accept: null,
    acceptLanguage: 'zh-CN,zh;q=0.9,en;q=0.8', additionalHeaders: {},
    browserTemplate: 'NONE', retainSensitiveHeadersOnCrossOriginRedirect: false,
    crossOriginRetainHeaders: [], captchaBypassEnabled: false, captchaBypassProvider: 'NONE',
    enabled: true, usageCount: 1, version: 0, updatedAt: '2026-07-16T14:00:00Z',
  }]);
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
  apiMock.createCrawlerHeaderProfile.mockResolvedValue({
    profileId: 'header_test01', displayName: '新闻请求头',
    userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
    accept: null, acceptLanguage: 'zh-CN,zh;q=0.9,en;q=0.8', additionalHeaders: {},
    browserTemplate: 'CHROME_WINDOWS', retainSensitiveHeadersOnCrossOriginRedirect: false,
    crossOriginRetainHeaders: [], captchaBypassEnabled: false, captchaBypassProvider: 'NONE',
    enabled: true, usageCount: 0, version: 0, updatedAt: '2026-07-16T14:00:00Z',
  });
  apiMock.testSource.mockResolvedValue(sourceTestTask);
  apiMock.task.mockResolvedValue({
    ...sourceTestTask,
    status: 'COMPLETED',
    attemptCount: 1,
    completedAt: '2026-07-16T14:01:02Z',
    updatedAt: '2026-07-16T14:01:02Z',
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
  apiMock.ingestionWorkspace
    .mockResolvedValueOnce(workspace)
    .mockResolvedValue(workspaceAfterTest);
  const user = userEvent.setup();
  render(<IngestionPage />);
  await user.click(await screen.findByRole('button', { name: '在线测试' }));

  await waitFor(() => expect(apiMock.testSource).toHaveBeenCalledWith(
    source.sourceId,
    '最新市场、宏观、监管和交易所事件',
    expect.any(String),
  ));
  await waitFor(() => expect(apiMock.task).toHaveBeenCalledWith(sourceTestTask.taskId));
  expect(await screen.findByText(/在线测试已完成/)).toBeInTheDocument();
  expect(screen.getByText(/获取 1，新增 1，重复 0/)).toBeInTheDocument();
});

it('creates a reusable crawler header profile from the ingestion control plane', async () => {
  const user = userEvent.setup();
  render(<IngestionPage />);
  await user.click(await screen.findByRole('button', { name: '新增配置' }));
  const dialog = await screen.findByRole('dialog');
  await user.type(within(dialog).getByRole('textbox', { name: '配置名称' }), '新闻请求头');
  await user.click(within(dialog).getByRole('button', { name: '保存并热更新' }));

  await waitFor(() => expect(apiMock.createCrawlerHeaderProfile).toHaveBeenCalledWith(expect.objectContaining({
    displayName: '新闻请求头',
    browserTemplate: 'CHROME_WINDOWS',
    captchaBypassEnabled: false,
    captchaBypassProvider: 'NONE',
    retainSensitiveHeadersOnCrossOriginRedirect: false,
    additionalHeaders: {},
    enabled: true,
  })));
});

it('shows the persistent task failure without reporting a successful source test', async () => {
  apiMock.task.mockResolvedValue({
    ...sourceTestTask,
    status: 'FAILED',
    attemptCount: 3,
    completedAt: '2026-07-16T14:01:05Z',
    errorCode: 'SEARXNG_UPSTREAM_TIMEOUT',
    errorMessage: '搜索上游超时',
    updatedAt: '2026-07-16T14:01:05Z',
  });
  const user = userEvent.setup();
  render(<IngestionPage />);
  await user.click(await screen.findByRole('button', { name: '在线测试' }));

  expect(await screen.findByText(/SEARXNG_UPSTREAM_TIMEOUT：搜索上游超时/)).toBeInTheDocument();
  expect(screen.queryByText(/在线测试已完成/)).not.toBeInTheDocument();
});

it('shows the selected source runtime channel and egress state', async () => {
  render(<IngestionPage />);

  expect(await screen.findByText('采集器可用')).toBeInTheDocument();
  expect(screen.getByText('PUBLIC_DATA · 出口可用')).toBeInTheDocument();
  expect(apiMock.sourceHealth).toHaveBeenCalledWith(source.sourceId);
});
