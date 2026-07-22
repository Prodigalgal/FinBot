import { mkdir } from 'node:fs/promises';
import path from 'node:path';

import { chromium } from 'playwright';

const appUrl = required('FINBOT_URL');
const username = required('FINBOT_ADMIN_USERNAME');
const password = required('FINBOT_ADMIN_PASSWORD');
const sseRunId = process.env.FINBOT_SSE_RUN_ID?.trim() || null;
const outputDir = path.resolve(process.cwd(), '..', '..', 'output', 'playwright');
await mkdir(outputDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1536, height: 1024 } });
page.setDefaultTimeout(60_000);
const browserProblems = [];
page.on('console', (message) => {
  if (['warning', 'error'].includes(message.type())) {
    browserProblems.push({ type: message.type(), text: message.text() });
  }
});
page.on('pageerror', (error) => browserProblems.push({ type: 'pageerror', text: error.message }));
page.on('requestfailed', (request) => browserProblems.push({
  type: 'requestfailed',
  text: `${request.failure()?.errorText || 'request failed'} ${request.url()}`,
}));
page.on('response', (response) => {
  if (response.status() >= 500) {
    browserProblems.push({
      type: 'http',
      text: `HTTP ${response.status()} ${response.request().method()} ${response.url()}`,
    });
  }
});

try {
  await page.goto(appUrl, { waitUntil: 'domcontentloaded' });
  await page.addStyleTag({ content: '*, *::before, *::after { animation-duration: 0s !important; transition-duration: 0s !important; caret-color: transparent !important; }' });
  const mathQuestion = page.getByTestId('auth-math-question');
  await mathQuestion.waitFor({ state: 'visible' });
  await page.waitForFunction(() => /^\d+\s+[+-]\s+\d+\s+=\s+\?$/.test(
    document.querySelector('[data-testid="auth-math-question"]')?.textContent || '',
  ));
  await page.screenshot({ path: path.join(outputDir, 'login-desktop.png'), fullPage: true });

  await page.getByLabel('用户名').fill(username);
  await page.getByLabel('密码').fill(password);
  await page.getByLabel('验证码答案').fill(String(solveMath(await mathQuestion.textContent())));
  const operationsResponse = page.waitForResponse(
    (response) => response.url().includes('/api/v2/operations') && response.status() === 200,
    { timeout: 30_000 },
  );
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('heading', { name: '研究决策工作台', exact: true }).waitFor();
  await operationsResponse;
  await page.getByText('账户权益', { exact: true }).first().waitFor();

  const pages = [
    ['研究决策工作台', '研究决策工作台', '系统就绪度', '工作台'],
    ['产品与自选', '产品库与自选列表', '产品库', '研究决策'],
    ['发起研究', '即时研究流水线', '研究问题', '研究决策'],
    ['自动研究', '自动研究循环', '自动循环', '研究决策'],
    ['复核与效果', '研究复核与效果反馈', '基准运行', '研究决策'],
    ['走势预测', '实盘行情走势预测', '分析目标', '研究分析与验证'],
    ['量化验证', '量化研究与预测验证', '仓位与风控预览', '研究分析与验证'],
    ['模拟验证', '模拟交易验证与永久审计', '账户概览', '研究分析与验证'],
    ['采集与处理', '信息采集、清理与证据', '信息源状态', '任务与记录'],
    ['运行报告', '结构化运行报告', '生成报告', '任务与记录'],
    ['系统设置', '系统、模型与交易配置', '快速启用', '系统'],
    ['AI 工作流', 'AI 调度小组与自由工作流', '辩论协议', '系统'],
    ['网络诊断', '代理路由与网络诊断', '出站路由', '系统'],
  ];
  const mainNavigation = page.getByRole('navigation', { name: '主导航' });
  for (const [navigation, heading, readyText, group] of pages) {
    const navigationButton = mainNavigation.getByRole('button', { name: navigation, exact: true });
    if (!await navigationButton.isVisible()) {
      await mainNavigation.getByTestId(`nav-group-${group}`).click();
    }
    await navigationButton.click();
    await page.getByRole('heading', { name: heading, exact: true }).waitFor();
    await page.getByText(readyText, { exact: false }).first().waitFor();
    if (navigation === '网络诊断') {
      const runtimeStatuses = page.locator('[data-testid^="proxy-runtime-"]');
      await runtimeStatuses.first().waitFor();
      await page.waitForFunction(() => Array.from(
        document.querySelectorAll('[data-testid^="proxy-runtime-"]'),
      ).every((element) => element.textContent?.trim() !== '检测中'));
    }
  }
  await page.screenshot({ path: path.join(outputDir, 'all-workspaces-desktop.png'), fullPage: true });
  const desktopOverflowElements = await horizontalOverflowElements(page);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByTestId('mobile-navigation').click();
  await page.getByRole('navigation', { name: '主导航' }).getByRole('button', { name: '发起研究', exact: true }).click();
  await page.getByRole('heading', { name: '即时研究流水线', exact: true }).waitFor();
  const mobileOverflowElements = await horizontalOverflowElements(page);
  await page.screenshot({ path: path.join(outputDir, 'research-mobile.png'), fullPage: true });

  if (desktopOverflowElements.length > 0 || mobileOverflowElements.length > 0) {
    throw new Error(`页面存在横向溢出: ${JSON.stringify({ desktopOverflowElements, mobileOverflowElements })}`);
  }
  if (browserProblems.length > 0) {
    throw new Error(`浏览器控制台存在错误: ${JSON.stringify(browserProblems)}`);
  }
  const csrfConflictStatus = await probeAuthenticatedCsrfWrite(page, appUrl);
  const operationsSse = await probeOperationsSse(page, appUrl);
  const workflowSseHeartbeat = sseRunId === null
    ? null
    : await probeSseHeartbeat(page, appUrl, sseRunId);
  console.log(JSON.stringify({ ok: true, pagesChecked: pages.length, desktopOverflow: false, mobileOverflow: false, csrfConflictStatus, operationsSse, workflowSseHeartbeat }));
} catch (error) {
  await page.screenshot({ path: path.join(outputDir, 'system-smoke-failure.png'), fullPage: true });
  throw error;
} finally {
  await browser.close();
}

function required(name) {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function solveMath(value) {
  const match = (value || '').match(/^(\d+)\s+([+-])\s+(\d+)\s+=\s+\?$/);
  if (!match) throw new Error(`无法解析数学验证码: ${value}`);
  return match[2] === '+' ? Number(match[1]) + Number(match[3]) : Number(match[1]) - Number(match[3]);
}

async function horizontalOverflowElements(targetPage) {
  return targetPage.evaluate(() => {
    const documentOverflows = document.body.scrollWidth > document.body.clientWidth
      || document.documentElement.scrollWidth > document.documentElement.clientWidth;
    if (!documentOverflows) return [];
    const viewportWidth = document.documentElement.clientWidth;
    return Array.from(document.querySelectorAll('body *'))
      .map((element) => {
        const bounds = element.getBoundingClientRect();
        return {
          tag: element.tagName.toLowerCase(),
          className: typeof element.className === 'string' ? element.className.slice(0, 120) : '',
          text: (element.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 100),
          left: Math.round(bounds.left),
          right: Math.round(bounds.right),
          width: Math.round(bounds.width),
          scrollWidth: element.scrollWidth,
          clientWidth: element.clientWidth,
        };
      })
      .filter((element) => element.right > viewportWidth + 1 || element.left < -1)
      .slice(0, 12);
  });
}

async function probeSseHeartbeat(targetPage, baseUrl, runId) {
  const streamUrl = new URL(`/api/v2/workflows/${encodeURIComponent(runId)}/events`, baseUrl).toString();
  return targetPage.evaluate(async ({ url, timeoutMs }) => {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), timeoutMs);
    let reader;
    try {
      const response = await fetch(url, {
        credentials: 'include',
        headers: { Accept: 'text/event-stream' },
        signal: controller.signal,
      });
      if (!response.ok || response.body === null) {
        throw new Error(`SSE 连接失败: HTTP ${response.status}`);
      }
      if (!response.headers.get('content-type')?.startsWith('text/event-stream')) {
        throw new Error(`SSE Content-Type 无效: ${response.headers.get('content-type') || '<missing>'}`);
      }
      reader = response.body.getReader();
      const decoder = new TextDecoder();
      let received = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done) throw new Error('SSE 在收到 heartbeat 前结束');
        received += decoder.decode(value, { stream: true });
        if (received.includes(':heartbeat') || received.includes(': heartbeat')) {
          return true;
        }
        if (received.length > 65_536) received = received.slice(-32_768);
      }
    } finally {
      window.clearTimeout(timeout);
      controller.abort();
      await reader?.cancel().catch(() => undefined);
    }
  }, { url: streamUrl, timeoutMs: 35_000 });
}

async function probeAuthenticatedCsrfWrite(targetPage, baseUrl) {
  return targetPage.evaluate(async (url) => {
    const sourcesResponse = await fetch(new URL('/api/v2/sources', url), { credentials: 'include' });
    if (!sourcesResponse.ok) throw new Error(`读取信息源失败: HTTP ${sourcesResponse.status}`);
    const sources = await sourcesResponse.json();
    if (!Array.isArray(sources) || sources.length === 0) throw new Error('没有可用于 CSRF smoke 的信息源');
    const source = sources[0];
    const csrf = document.cookie.split('; ').find((item) => item.startsWith('XSRF-TOKEN='))?.split('=', 2)[1];
    if (!csrf) throw new Error('登录后缺少 XSRF-TOKEN cookie');
    const response = await fetch(new URL(`/api/v2/sources/${encodeURIComponent(source.sourceId)}/status`, url), {
      method: 'PUT',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': decodeURIComponent(csrf) },
      body: JSON.stringify({ enabled: source.enabled, expectedVersion: source.version + 1_000_000 }),
    });
    if (response.status !== 409) throw new Error(`CSRF 写链路未进入业务冲突层: HTTP ${response.status}`);
    return response.status;
  }, baseUrl);
}

async function probeOperationsSse(targetPage, baseUrl) {
  const streamUrl = new URL('/api/v2/operations/events', baseUrl).toString();
  return targetPage.evaluate(async ({ url, timeoutMs }) => {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), timeoutMs);
    let reader;
    try {
      const response = await fetch(url, { credentials: 'include', headers: { Accept: 'text/event-stream' }, signal: controller.signal });
      if (!response.ok || response.body === null) throw new Error(`Operations SSE 连接失败: HTTP ${response.status}`);
      reader = response.body.getReader();
      const decoder = new TextDecoder();
      let received = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done) throw new Error('Operations SSE 在快照前结束');
        received += decoder.decode(value, { stream: true });
        if (received.includes('event:operations.snapshot') || received.includes('event: operations.snapshot')) return true;
      }
    } finally {
      window.clearTimeout(timeout);
      controller.abort();
      await reader?.cancel().catch(() => undefined);
    }
  }, { url: streamUrl, timeoutMs: 20_000 });
}
