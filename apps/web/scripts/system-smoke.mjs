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

try {
  await page.goto(appUrl, { waitUntil: 'domcontentloaded' });
  await page.addStyleTag({ content: '*, *::before, *::after { animation-duration: 0s !important; transition-duration: 0s !important; caret-color: transparent !important; }' });
  const mathQuestion = page.getByTestId('auth-math-question');
  await mathQuestion.waitFor({ state: 'visible' });
  await page.screenshot({ path: path.join(outputDir, 'login-desktop.png'), fullPage: true });

  await page.getByLabel('用户名').fill(username);
  await page.getByLabel('密码').fill(password);
  await page.getByLabel('验证码答案').fill(String(solveMath(await mathQuestion.textContent())));
  const operationsResponse = page.waitForResponse(
    (response) => response.url().includes('/api/v2/operations') && response.status() === 200,
    { timeout: 30_000 },
  );
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('heading', { name: '研究与交易工作台', exact: true }).waitFor();
  await operationsResponse;
  await page.getByText('账户权益', { exact: true }).first().waitFor();

  const pages = [
    ['即时研究', '即时研究流水线', '研究问题'],
    ['研究历史', '研究历史与多轮辩论', '研究问题'],
    ['产品与自选', '规范化产品库与自选', '产品库'],
    ['模拟交易', '账户、盈亏与执行审计', '账户盈亏'],
    ['AI 工作流', 'AI 调度小组与工作流', '辩论轮次'],
    ['运行与调度', '常驻任务、信息源与证据', '常驻服务与调度'],
    ['系统配置', '模型、费率与风险配置', '运行参数'],
  ];
  for (const [navigation, heading, readyText] of pages) {
    await page.getByRole('button', { name: navigation, exact: true }).click();
    await page.getByRole('heading', { name: heading, exact: true }).waitFor();
    await page.getByText(readyText, { exact: true }).first().waitFor();
  }
  await page.screenshot({ path: path.join(outputDir, 'settings-desktop.png'), fullPage: true });
  const desktopOverflow = await horizontalOverflow(page);

  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('tab', { name: '即时研究', exact: true }).click();
  await page.getByRole('heading', { name: '即时研究流水线', exact: true }).waitFor();
  const mobileOverflow = await horizontalOverflow(page);
  await page.screenshot({ path: path.join(outputDir, 'research-mobile.png'), fullPage: true });

  if (desktopOverflow || mobileOverflow) {
    throw new Error(`页面存在横向溢出: ${JSON.stringify({ desktopOverflow, mobileOverflow })}`);
  }
  if (browserProblems.length > 0) {
    throw new Error(`浏览器控制台存在错误: ${JSON.stringify(browserProblems)}`);
  }
  const sseHeartbeat = sseRunId === null
    ? null
    : await probeSseHeartbeat(page, appUrl, sseRunId);
  console.log(JSON.stringify({ ok: true, pagesChecked: pages.length + 1, desktopOverflow, mobileOverflow, sseHeartbeat }));
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

async function horizontalOverflow(targetPage) {
  return targetPage.evaluate(() =>
    document.body.scrollWidth > document.body.clientWidth
    || document.documentElement.scrollWidth > document.documentElement.clientWidth,
  );
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
