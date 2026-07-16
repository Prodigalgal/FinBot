import { beforeEach, describe, expect, it, vi } from 'vitest';

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
});

function jsonResponse(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
