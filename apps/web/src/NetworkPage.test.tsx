import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';

import type { NetworkWorkspace, ProxyGatewayRuntimeStatus } from './types';

const workspace: NetworkWorkspace = {
  routes: [],
  proxyGateways: [{
    gatewayId: 'proxygateway_firecrawl', displayName: 'Firecrawl 代理池', enabled: true,
    engine: 'SING_BOX',
    preferredNames: '', maximumNodes: 32, refreshSeconds: 1800, allowInsecureTls: false,
    subscriptionSupported: true, subscriptionSource: 'ENVIRONMENT_FALLBACK',
    subscriptionFingerprint: 'sha256:test', subscriptionVersion: 0,
    inlineNodesSupported: false, inlineNodesSource: 'NOT_SUPPORTED',
    inlineNodesFingerprint: null, inlineNodesVersion: 0,
    status: 'READY', version: 1, updatedAt: '2026-07-17T00:00:00Z',
  }],
  generatedAt: '2026-07-17T00:00:00Z',
};

const runtimeStatus: ProxyGatewayRuntimeStatus = {
  gatewayId: 'proxygateway_firecrawl', engine: 'SING_BOX', serviceReady: true, egressReady: true,
  nodeCount: 32, healthyNodeCount: 5, unhealthyNodeCount: 27,
  healthyNodeIndices: [4, 16, 18, 20, 23], probeFailureCounts: { HTTP_403: 27 },
  validationEnabled: true, validationTarget: 'api.firecrawl.dev', generation: 4,
  refreshAttempt: 4, lastRefreshAt: '2026-07-17T00:01:00Z', error: null,
};

const apiMock = vi.hoisted(() => ({
  network: vi.fn(),
  networkDiagnostics: vi.fn(),
  proxyGatewayStatus: vi.fn(),
}));

vi.mock('./api', () => ({ api: apiMock }));

import { NetworkPage } from './NetworkPage';

beforeEach(() => {
  apiMock.network.mockResolvedValue(workspace);
  apiMock.networkDiagnostics.mockResolvedValue([]);
  apiMock.proxyGatewayStatus.mockResolvedValue(runtimeStatus);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

it('shows target-aware proxy node health separately from saved configuration', async () => {
  render(<NetworkPage />);

  expect(await screen.findByText('出口可用')).toBeInTheDocument();
  expect(screen.getByText('运行内核 sing-box')).toBeInTheDocument();
  expect(screen.getByText('健康节点 5/32')).toBeInTheDocument();
  expect(screen.getByText('探测目标 api.firecrawl.dev')).toBeInTheDocument();
  expect(screen.getByText('节点索引 4, 16, 18, 20, 23')).toBeInTheDocument();
  expect(screen.getByText('上游风控 403 × 27')).toBeInTheDocument();
});

it('renders known proxy runtime failures as operator-friendly Chinese', async () => {
  apiMock.proxyGatewayStatus.mockResolvedValue({
    ...runtimeStatus,
    egressReady: false,
    healthyNodeCount: 0,
    unhealthyNodeCount: 32,
    healthyNodeIndices: [],
    error: 'Proxy target validation found no healthy nodes',
  });

  render(<NetworkPage />);

  expect(await screen.findByText('无可用出口')).toBeInTheDocument();
  expect(screen.getByText('目标探测未发现可用节点')).toBeInTheDocument();
  expect(screen.queryByText('Proxy target validation found no healthy nodes')).not.toBeInTheDocument();
});
