import { mkdir } from 'node:fs/promises';
import path from 'node:path';

import { chromium } from 'playwright';


const appUrl = process.env.FINBOT_URL;
const username = process.env.FINBOT_ADMIN_USERNAME;
const password = process.env.FINBOT_ADMIN_PASSWORD;
if (!appUrl || !username || !password) {
  throw new Error('FINBOT_URL、FINBOT_ADMIN_USERNAME 和 FINBOT_ADMIN_PASSWORD 必须通过环境变量提供');
}

const outputDir = path.resolve(process.cwd(), '..', 'output', 'playwright');
await mkdir(outputDir, { recursive: true });

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1536, height: 1024 }, deviceScaleFactor: 1 });
const browserProblems = [];
page.on('console', (message) => {
  if (['warning', 'error'].includes(message.type())) {
    browserProblems.push({ type: message.type(), text: message.text() });
  }
});
page.on('pageerror', (error) => browserProblems.push({ type: 'pageerror', text: error.message }));

try {
  await page.goto(appUrl, { waitUntil: 'domcontentloaded' });
  await page.getByLabel('用户名', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill(password);
  const mathQuestion = await page.getByTestId('auth-math-question').textContent();
  await page.getByLabel('验证码答案', { exact: true }).fill(String(solveMathQuestion(mathQuestion || '')));
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByTestId('nav-quant').waitFor({ state: 'visible', timeout: 15_000 });
  await page.getByTestId('nav-quant').click();
  await page.getByTestId('quant-risk-panel').waitFor({ state: 'visible' });

  const responsePromise = page.waitForResponse(
    (response) => response.url().endsWith('/api/v1/quant/risk/execution-gate') && response.request().method() === 'POST',
    { timeout: 15_000 },
  );
  await page.getByRole('button', { name: '计算并检查', exact: true }).click();
  const response = await responsePromise;
  if (response.status() !== 200) {
    throw new Error(`执行风险 API 返回 HTTP ${response.status()}`);
  }
  await page.getByText('杠杆 阻断', { exact: true }).waitFor({ state: 'visible' });
  await page.getByText('AI 不可覆盖硬风控结果', { exact: true }).waitFor({ state: 'visible' });

  const desktopOverflow = await horizontalOverflow(page);
  await page.screenshot({ path: path.join(outputDir, 'quant-desktop.png'), fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByTestId('quant-risk-panel').waitFor({ state: 'visible' });
  const mobileOverflow = await horizontalOverflow(page);
  await page.screenshot({ path: path.join(outputDir, 'quant-mobile.png'), fullPage: true });

  if (desktopOverflow.body || desktopOverflow.document || mobileOverflow.body || mobileOverflow.document) {
    throw new Error(`页面存在横向溢出: ${JSON.stringify({ desktopOverflow, mobileOverflow })}`);
  }
  if (browserProblems.length) {
    throw new Error(`浏览器控制台存在 warning/error: ${JSON.stringify(browserProblems)}`);
  }

  console.log(JSON.stringify({
    ok: true,
    leverageBlocked: true,
    hardRiskOverrideDisabled: true,
    desktopOverflow,
    mobileOverflow,
    browserProblems,
  }, null, 2));
} catch (error) {
  await page.screenshot({ path: path.join(outputDir, 'quant-smoke-failure.png'), fullPage: true });
  console.error(JSON.stringify({
    ok: false,
    url: page.url(),
    title: await page.title(),
    visibleText: (await page.locator('body').innerText()).slice(0, 500),
  }, null, 2));
  throw error;
} finally {
  await browser.close();
}

function solveMathQuestion(question) {
  const match = question.match(/^(\d+)\s+([+\-x])\s+(\d+)\s+=\s+\?$/);
  if (!match) throw new Error(`无法解析数学验证码: ${question}`);
  const left = Number(match[1]);
  const right = Number(match[3]);
  if (match[2] === '+') return left + right;
  if (match[2] === '-') return left - right;
  return left * right;
}

async function horizontalOverflow(targetPage) {
  return targetPage.evaluate(() => ({
    body: document.body.scrollWidth > document.body.clientWidth,
    document: document.documentElement.scrollWidth > document.documentElement.clientWidth,
  }));
}
