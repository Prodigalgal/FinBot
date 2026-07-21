import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { AiProvider } from './types';

describe('control-plane API transport', () => {
  beforeEach(() => {
    vi.resetModules();
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/';
  });

  it('adds the cookie CSRF token to mutating authenticated requests', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-cookie; Path=/';
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ authenticated: false }, 200));
    vi.stubGlobal('fetch', fetchMock);
    const { api } = await import('./api');

    await api.logout();

    expect(fetchMock).toHaveBeenCalledWith('/api/v2/auth/logout', expect.objectContaining({
      credentials: 'include',
      headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-cookie' }),
    }));
  });

  it('does not send a CSRF header on the login bootstrap request', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-cookie; Path=/';
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ authenticated: true }, 200));
    vi.stubGlobal('fetch', fetchMock);
    const { api } = await import('./api');

    await api.login({
      username: 'admin',
      password: 'secret',
      challengeId: 'challenge-1',
      proofOfWorkSolution: 'solution',
      mathAnswer: 4,
    });

    const headers = fetchMock.mock.calls[0][1].headers as Record<string, string>;
    expect(headers['X-XSRF-TOKEN']).toBeUndefined();
  });

  it('dispatches the authentication event and preserves Problem Detail on 401', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ detail: '会话已过期' }, 401)));
    const authenticationRequired = vi.fn();
    const { api, ApiError, AUTH_REQUIRED_EVENT } = await import('./api');
    window.addEventListener(AUTH_REQUIRED_EVENT, authenticationRequired, { once: true });

    await expect(api.authStatus()).rejects.toEqual(new ApiError('会话已过期', 401));
    expect(authenticationRequired).toHaveBeenCalledOnce();
  });

  it('returns undefined for successful 204 responses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    const { api } = await import('./api');

    await expect(api.deleteWatchlist('watchlist_default')).resolves.toBeUndefined();
  });

  it('submits source tests as idempotent asynchronous commands', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-cookie; Path=/';
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      taskId: 'task_source_test01',
      status: 'PENDING',
    }, 202));
    vi.stubGlobal('fetch', fetchMock);
    const { api } = await import('./api');

    await api.testSource('source_searxng_news_search', 'market news', 'source-test-key');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v2/sources/source_searxng_news_search/test',
      expect.objectContaining({
        method: 'POST',
        credentials: 'include',
        headers: expect.objectContaining({
          'Idempotency-Key': 'source-test-key',
          'X-XSRF-TOKEN': 'csrf-cookie',
        }),
      }),
    );
  });

  it('submits provider capacity and timeout hot configuration', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-cookie; Path=/';
    const provider: AiProvider = {
      profileId: 'provider_runtime_test',
      displayName: 'Runtime provider',
      protocol: 'RESPONSES',
      reasoningParameterStyle: 'NESTED',
      baseUrl: 'https://provider.example/v1',
      baseUrlConfigured: true,
      apiKeyConfigured: true,
      credentialSource: 'DATABASE_OVERRIDE',
      credentialFingerprint: '0123456789abcdef',
      credentialVersion: 2,
      credentialUpdatedAt: '2026-07-21T00:00:00Z',
      enabled: true,
      connectTimeoutSeconds: 10,
      requestTimeoutSeconds: 3600,
      maximumConcurrentRequests: 7,
      acquireTimeoutSeconds: 5400,
      workflowNodeUsageCount: 1,
      roleTemplateUsageCount: 0,
      executionStageUsageCount: 0,
      totalUsageCount: 1,
      version: 3,
      updatedAt: '2026-07-21T00:00:00Z',
    };
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(provider, 200));
    vi.stubGlobal('fetch', fetchMock);
    const { api } = await import('./api');

    await api.updateProvider(provider);

    const options = fetchMock.mock.calls[0][1] as RequestInit;
    expect(JSON.parse(options.body as string)).toEqual(expect.objectContaining({
      requestTimeoutSeconds: 3600,
      maximumConcurrentRequests: 7,
      acquireTimeoutSeconds: 5400,
      expectedVersion: 3,
    }));
  });
});

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
