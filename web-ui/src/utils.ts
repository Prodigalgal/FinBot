import type { JobStatus } from './types';

export function compactNumber(value: number | undefined): string {
  if (typeof value !== 'number') {
    return '0';
  }
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(value);
}

export function formatTime(value?: string | null): string {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function statusColor(status: string): 'default' | 'primary' | 'success' | 'warning' | 'error' {
  if (['ok', 'passed', 'succeeded', 'enabled', 'candidate', 'completed', 'ready', 'approved'].includes(status)) {
    return 'success';
  }
  if (['running', 'queued', 'partial', 'ready_for_synthesis', 'idle', 'standby'].includes(status)) {
    return 'primary';
  }
  if (['dry-run', 'dry_run', 'unverified', 'skipped', 'warning', 'needs-followup', 'changes_requested', 'watch', 'hold', 'empty', 'disabled', 'offline', 'stopped', 'cancelled', 'abandoned', 'no_directional_exposure', 'insufficient_data', 'insufficient-data', 'pending'].includes(status)) {
    return 'warning';
  }
  if (['failed', 'blocked', 'rejected', 'blocked-by-proxy', 'provider-geo-blocked'].includes(status)) {
    return 'error';
  }
  return 'default';
}

export function jobStatusText(status: JobStatus): string {
  const map: Record<JobStatus, string> = {
    queued: '排队',
    running: '运行中',
    succeeded: '完成',
    failed: '失败',
  };
  return map[status] || status;
}

export function jobKindText(kind: string): string {
  const map: Record<string, string> = {
    'research-pipeline': '采集与处理',
    'operator-workbench': '市场分析',
    'proxy-diagnostics': '网络诊断',
  };
  return map[kind] || kind;
}

export function statusText(status: string | boolean | undefined | null): string {
  if (typeof status === 'boolean') {
    return status ? '是' : '否';
  }
  if (!status) {
    return '未知';
  }
  const map: Record<string, string> = {
    ok: '正常',
    passed: '通过',
    succeeded: '完成',
    running: '运行中',
    idle: '空闲',
    standby: '待命',
    offline: '离线',
    stopped: '已停止',
    cancelled: '已取消',
    queued: '排队',
    partial: '部分完成',
    enabled: '已启用',
    disabled: '已关闭',
    candidate: '候选',
    watch: '观察',
    hold: '观望',
    completed: '完成',
    empty: '空',
    no_directional_exposure: '无方向暴露',
    insufficient_data: '数据不足',
    pending: '待到期',
    ready_for_synthesis: '待合成',
    ready: '已就绪',
    approved: '已批准',
    rejected: '已拒绝',
    changes_requested: '需补充',
    'dry-run': '演练',
    dry_run: '演练可用',
    unverified: '待校验',
    skipped: '跳过',
    warning: '警告',
    'needs-followup': '需补证据',
    abandoned: '已放弃',
    'insufficient-data': '数据不足',
    failed: '失败',
    blocked: '已阻断',
    'blocked-by-proxy': '代理阻断',
    'provider-geo-blocked': '地区阻断',
    'provider-access-blocked': '访问阻断',
    unknown: '未知',
    true: '是',
    false: '否',
    '无流水线': '无流水线',
  };
  return map[status] || status;
}
