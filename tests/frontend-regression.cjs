// npm install --no-save playwright; node --test tests/frontend-regression.cjs
// 可通过 PLAYWRIGHT_MODULE 指定已有 Playwright 模块路径。
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const front = process.env.FRONTEND_ROOT || path.resolve(__dirname, '../nginx-1.18.0/html/hmdp');
const origin = 'http://local-perks.test';
let browser;
before(async () => { browser = await chromium.launch({ channel: 'chrome', headless: true }); });
after(async () => { if (browser) await browser.close(); });

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}
function json(route, data, status = 200) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(data) });
}
function sse(event, data) { return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`; }
async function openPage(t, file, handle) {
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  t.after(() => page.close());
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  t.after(() => assert.deepEqual(errors, [], '页面不得出现未处理的 JavaScript 异常'));
  await page.route(`${origin}/**`, async route => {
    const url = new URL(route.request().url());
    if (handle && await handle(route, url)) return;
    if (url.pathname === '/cs-api/health') return json(route, { status: 'ok' });
    if (url.pathname === '/api/shop-type/list') return json(route, { success: true, data: [] });
    if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/cs-api/')) {
      return json(route, { success: true, data: null });
    }
    const filePath = path.resolve(front, '.' + decodeURIComponent(url.pathname));
    if (!filePath.startsWith(front + path.sep) || !fs.existsSync(filePath)) {
      return route.fulfill({ status: 404 });
    }
    const types = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css', '.png': 'image/png', '.woff': 'font/woff' };
    return route.fulfill({ body: fs.readFileSync(filePath), contentType: types[path.extname(filePath)] || 'application/octet-stream' });
  });
  await page.goto(`${origin}/${file}`);
  return page;
}

test('连续两次审批分别展示按钮，并使用各自的令牌', async t => {
  const calls = [];
  const page = await openPage(t, 'chat.html', async (route, url) => {
    if (url.pathname === '/cs-api/chat/agent/stream') {
      await route.fulfill({ contentType: 'text/event-stream', body:
        sse('approval', { pending_token: 'first', conversation_id: 'fixture-conv', response: '确认抢券' }) });
      return true;
    }
    if (url.pathname === '/cs-api/chat/approve') {
      calls.push(route.request().postDataJSON());
      await json(route, calls.length === 1
        ? { requires_approval: true, pending_token: 'second', conversation_id: 'fixture-conv', response: '确认支付' }
        : { requires_approval: false, response: '已取消支付' });
      return true;
    }
  });
  await page.locator('#csInput').fill('帮我抢券并支付');
  await page.locator('#csSend').click();
  await page.locator('[data-act="yes"]').click();
  await page.getByText('确认支付', { exact: true }).last().waitFor();
  assert.equal(await page.locator('[data-act="no"]').count(), 1);
  await page.locator('[data-act="no"]').click();
  await page.getByText('已取消支付', { exact: true }).waitFor();
  assert.deepEqual(calls.map(d => [d.pending_token, d.approved, d.conversation_id]),
    [['first', true, 'fixture-conv'], ['second', false, 'fixture-conv']]);
});

test('中文组词回车与 229 兼容事件不发送，普通 Enter 正常发送', async t => {
  let calls = 0;
  const page = await openPage(t, 'chat.html', async (route, url) => {
    if (url.pathname === '/cs-api/chat/agent/stream') {
      calls++;
      await route.fulfill({ contentType: 'text/event-stream', body: sse('done', { response: '收到' }) });
      return true;
    }
  });
  const input = page.locator('#csInput');
  await input.fill('中文输入');
  await input.dispatchEvent('compositionstart');
  await input.dispatchEvent('keydown', { key: 'Enter' });
  assert.equal(await input.inputValue(), '中文输入');
  await input.dispatchEvent('compositionend');
  await input.dispatchEvent('keydown', { key: 'Enter', isComposing: true });
  await input.dispatchEvent('keydown', { key: 'Enter', keyCode: 229 });
  assert.equal(calls, 0);
  await input.press('Enter');
  await page.getByText('收到', { exact: true }).waitFor();
  assert.equal(calls, 1);
  assert.equal(await page.locator('#csSend').isDisabled(), true);
});

test('新对话后旧 SSE 不再添加审批或覆盖当前会话状态', async t => {
  const page = await openPage(t, 'chat.html');
  await page.evaluate(() => {
    const original = window.fetch;
    window.fixtureStreams = [];
    window.fetch = (url, options) => {
      if (url !== '/cs-api/chat/agent/stream') return original(url, options);
      return Promise.resolve(new Response(new ReadableStream({
        start(controller) { window.fixtureStreams.push(controller); }
      }), { headers: { 'Content-Type': 'text/event-stream' } }));
    };
  });
  await page.locator('#csInput').fill('第一轮');
  await page.locator('#csSend').click();
  await page.waitForFunction(() => window.fixtureStreams.length === 1);
  await page.locator('#btnNew').click();
  await page.locator('#csInput').fill('第二轮');
  await page.locator('#csSend').click();
  await page.waitForFunction(() => window.fixtureStreams.length === 2);
  await page.evaluate(() => {
    window.fixtureStreams[0].enqueue(new TextEncoder().encode('event: approval\ndata: {"pending_token":"obsolete","response":"旧审批"}\n\n'));
  });
  await page.waitForFunction(() => document.querySelector('#statusText').textContent === '思考中');
  assert.equal(await page.locator('.approve-card').count(), 0);
  await page.evaluate(() => {
    window.fixtureStreams[1].enqueue(new TextEncoder().encode('event: done\ndata: {"response":"新会话完成"}\n\n'));
    window.fixtureStreams[1].close();
  });
  await page.getByText('新会话完成', { exact: true }).waitFor();
  assert.equal(await page.locator('.msg.user').count(), 1);
});

test('审批期间防重复提交，新对话等待审批回执', async t => {
  const release = deferred();
  t.after(() => release.resolve());
  let calls = 0;
  const page = await openPage(t, 'chat.html', async (route, url) => {
    if (url.pathname === '/cs-api/chat/agent/stream') {
      await route.fulfill({ contentType: 'text/event-stream', body: sse('approval', { pending_token: 'once', response: '确认签到' }) });
      return true;
    }
    if (url.pathname === '/cs-api/chat/approve') {
      calls++;
      await release.promise;
      await json(route, { response: '签到完成' });
      return true;
    }
  });
  await page.locator('#csInput').fill('签到');
  await page.locator('#csSend').click();
  await page.locator('[data-act="yes"]').click();
  await page.locator('#btnNew').click();
  assert.equal(await page.locator('.msg.user').count(), 1);
  await page.locator('#csInput').fill('处理中');
  assert.equal(await page.locator('#csSend').isDisabled(), true);
  release.resolve();
  await page.getByText('签到完成', { exact: true }).waitFor();
  assert.equal(calls, 1);
});

test('审批连接失败提示结果待核实，避免误导用户重复操作', async t => {
  const page = await openPage(t, 'chat.html', async (route, url) => {
    if (url.pathname === '/cs-api/chat/agent/stream') {
      await route.fulfill({ contentType: 'text/event-stream', body: sse('approval', { pending_token: 'unknown', response: '确认下单' }) });
      return true;
    }
    if (url.pathname === '/cs-api/chat/approve') { await route.abort('failed'); return true; }
  });
  await page.locator('#csInput').fill('下单');
  await page.locator('#csSend').click();
  await page.locator('[data-act="yes"]').click();
  await page.getByText(/操作结果暂未确认/).waitFor();
  assert.equal(await page.locator('[data-act="yes"]').count(), 0);
});

test('快速切换排序时迟到的旧响应不覆盖新结果', async t => {
  const release = deferred();
  t.after(() => release.resolve());
  let first = true;
  const page = await openPage(t, 'shop-list.html', async (route, url) => {
    if (url.pathname !== '/api/shop/of/type') return;
    if (first) {
      first = false;
      await release.promise;
      await json(route, { success: true, data: [{ id: 1, name: '旧距离结果' }] });
    } else {
      await json(route, { success: true, data: [{ id: 2, name: '新评分结果' }] });
    }
    return true;
  });
  await page.evaluate(() => app.changeSort('score'));
  await page.getByText('新评分结果', { exact: true }).waitFor();
  const oldResponse = page.waitForResponse(r => r.url().includes('/api/shop/of/type?') && !r.url().includes('sortBy'));
  release.resolve();
  await oldResponse;
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.equal(await page.getByText('旧距离结果', { exact: true }).count(), 0);
  assert.deepEqual(await page.evaluate(() => app.shops.map(s => s.id)), [2]);
});

test('翻页失败保留结果和页码，点击重试继续同一页', async t => {
  const requests = [];
  let failed = false;
  const page = await openPage(t, 'shop-list.html', async (route, url) => {
    if (url.pathname !== '/api/shop/of/type') return;
    const current = Number(url.searchParams.get('current'));
    requests.push(current);
    if (current === 2 && !failed) {
      failed = true;
      await json(route, { errorMsg: 'fixture-unavailable' }, 503);
    } else {
      await json(route, { success: true, data: current === 1
        ? Array.from({ length: 10 }, (_, i) => ({ id: i + 1, name: `店铺${i + 1}` }))
        : [{ id: 11, name: '重试成功店铺' }] });
    }
    return true;
  });
  await page.waitForFunction(() => !app.loading && app.shops.length === 10);
  await page.evaluate(() => app.queryShops(false));
  await page.getByRole('button', { name: '重试', exact: true }).waitFor();
  assert.deepEqual(await page.evaluate(() => [app.shops.length, app.params.current, app.noMore]), [10, 2, false]);
  await page.getByRole('button', { name: '重试', exact: true }).click();
  await page.getByText('重试成功店铺', { exact: true }).waitFor();
  assert.deepEqual(requests, [1, 2, 2]);
});

test('孤立管道行能完成 Markdown 渲染，且正文 HTML 被转义', () => {
  const html = fs.readFileSync(path.join(front, 'chat.html'), 'utf8');
  const escapeFn = html.slice(html.indexOf('  function esc('), html.indexOf('  function toast('));
  const markdownFns = html.slice(html.indexOf('  function inlineMd('), html.indexOf('  /* ================= 滚动'));
  const context = vm.createContext({});
  vm.runInContext(escapeFn + markdownFns, context);
  const output = vm.runInContext('md("| 普通文本 |\\n<script>alert(1)</script>")', context, { timeout: 1000 });
  assert.match(output, /普通文本/);
  assert.equal(output.includes('<script>'), false);
});

test('SSE 支持跨字节分片的中文和 CRLF 事件边界', async () => {
  const html = fs.readFileSync(path.join(front, 'chat.html'), 'utf8');
  const source = html.slice(html.indexOf('  function readSse('), html.indexOf('  function send('));
  const bytes = new TextEncoder().encode('event: delta\r\ndata: {"text":"你好"}\r\n\r\nevent: done\r\ndata: {}\r\n\r\n');
  let index = 0;
  const received = [];
  const resp = { body: { getReader: () => ({
    read: async () => index < bytes.length ? { value: bytes.slice(index, ++index), done: false } : { done: true }
  }) } };
  const context = vm.createContext({ TextDecoder, resp, handlers: {
    delta: d => received.push(d.text), done: () => received.push('done')
  } });
  await vm.runInContext(source + '; readSse(resp, handlers);', context);
  assert.deepEqual(received, ['你好', 'done']);
});

test('底部找店入口有可用目的页面', () => {
  let footer;
  const location = { href: '/' };
  vm.runInNewContext(fs.readFileSync(path.join(front, 'js/footer.js'), 'utf8'), {
    Vue: { component: (_, definition) => { footer = definition; } }, location
  });
  footer.methods.toPage(2);
  assert.equal(location.href, '/shop-list.html');
  assert.match(footer.template, /找店/);
});
