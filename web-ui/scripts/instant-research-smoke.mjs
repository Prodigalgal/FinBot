import { mkdir } from 'node:fs/promises';
import path from 'node:path';

import { chromium } from 'playwright';


const appUrl = process.env.FINBOT_URL || 'http://127.0.0.1:8780/';
const shouldSubmit = process.env.SUBMIT_SMOKE === '1';
const query = '分析 BTC 最近 24 小时市场结构、ETF 资金流与主要风险';
const outputDir = path.resolve(process.cwd(), '..', 'output', 'browser');
await mkdir(outputDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1536, height: 1024 }, deviceScaleFactor: 1 });
const logs = [];
page.on('console', (message) => {
  if (['warning', 'error'].includes(message.type())) {
    logs.push({ type: message.type(), text: message.text() });
  }
});
page.on('pageerror', (error) => logs.push({ type: 'pageerror', text: error.message }));

try {
  await page.goto(appUrl, { waitUntil: 'networkidle' });
  const nav = page.getByRole('button', { name: '即时研究', exact: true });
  if (await nav.count() !== 1) {
    throw new Error('即时研究导航未唯一呈现');
  }
  await nav.click();
  const panel = page.getByTestId('instant-research-panel');
  await panel.waitFor({ state: 'visible' });
  const sessionHeader = page.getByTestId('instant-research-session-header');
  await sessionHeader.waitFor({ state: 'visible', timeout: 15_000 });

  let submittedSessionId = null;
  if (shouldSubmit) {
    const input = page.getByLabel('即时研究问题', { exact: true });
    if (await input.count() !== 1) {
      throw new Error('即时研究输入框未唯一呈现');
    }
    await input.fill(query);
    const submit = page.getByRole('button', { name: '开始研究', exact: true });
    if (await submit.count() !== 1) {
      throw new Error('开始研究按钮未唯一呈现');
    }
    const responsePromise = page.waitForResponse(
      (response) => response.url().endsWith('/api/v1/instant-research') && response.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await submit.click();
    const response = await responsePromise;
    if (response.status() !== 202) {
      throw new Error(`即时研究提交返回 HTTP ${response.status()}`);
    }
    const payload = await response.json();
    submittedSessionId = payload.session?.session_id || null;
    await sessionHeader.getByText(query, { exact: true }).waitFor({ state: 'visible' });
  }

  const desktopOverflow = await page.evaluate(() => ({
    body: document.body.scrollWidth > document.body.clientWidth,
    document: document.documentElement.scrollWidth > document.documentElement.clientWidth,
  }));
  await page.screenshot({ path: path.join(outputDir, 'finbot-instant-research-desktop.png'), fullPage: false });

  const debateTab = page.getByRole('tablist', { name: '即时研究视图' }).getByRole('tab', { name: /^AI 辩论/ });
  if (await debateTab.count() !== 1) {
    throw new Error('AI 辩论视图未唯一呈现');
  }
  await debateTab.click();
  await page.screenshot({ path: path.join(outputDir, 'finbot-instant-research-debate-desktop.png'), fullPage: false });

  await page.setViewportSize({ width: 390, height: 844 });
  const mobileOverflow = await page.evaluate(() => ({
    body: document.body.scrollWidth > document.body.clientWidth,
    document: document.documentElement.scrollWidth > document.documentElement.clientWidth,
  }));
  await page.screenshot({ path: path.join(outputDir, 'finbot-instant-research-mobile.png'), fullPage: false });

  console.log(JSON.stringify({
    ok: true,
    submittedSessionId,
    queryVisible: await sessionHeader.getByText(query, { exact: true }).count() === 1,
    desktopOverflow,
    mobileOverflow,
    logs,
  }, null, 2));
} finally {
  await browser.close();
}
