export function workspaceSubview<T extends string>(
  workspace: string,
  allowed: readonly T[],
  fallback: T,
): T {
  const [currentWorkspace, subview] = window.location.hash.replace(/^#/, '').split('/', 2);
  return currentWorkspace === workspace && allowed.includes(subview as T) ? subview as T : fallback;
}

export function replaceWorkspaceLocation(workspace: string, subview?: string): void {
  const suffix = subview ? `/${encodeURIComponent(subview)}` : '';
  window.history.replaceState(
    null,
    '',
    `${window.location.pathname}${window.location.search}#${workspace}${suffix}`,
  );
}
