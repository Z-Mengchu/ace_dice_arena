'use strict';
/**
 * server.js 集成测试
 * 运行：node server.test.js   全部通过时退出码为 0
 *
 * 真实启动服务器（端口 0 随机），用全局 fetch 调 JSON API，
 * 用 http 模块手动解析 SSE 流。静态根目录使用临时目录，避免污染项目目录。
 */

const assert = require('assert');
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { createServer } = require('./server.js');

/* --------------------------- 测试小框架 --------------------------- */

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

let BASE = ''; // http://127.0.0.1:<port>

/** POST JSON，返回 {status, json} */
async function jpost(pathname, body) {
  const res = await fetch(BASE + pathname, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body === undefined ? {} : body)
  });
  let json = null;
  try { json = await res.json(); } catch (e) { /* 忽略非 JSON 响应 */ }
  return { status: res.status, json };
}

/** 用原始 path 发 GET（http.request 不会对 path 做点段归一化），返回状态码 */
function rawStatus(pathname) {
  return new Promise((resolve, reject) => {
    const u = new URL(BASE);
    const req = http.request(
      { hostname: u.hostname, port: u.port, path: pathname, method: 'GET' },
      (res) => {
        res.resume();
        res.on('end', () => resolve(res.statusCode));
      }
    );
    req.on('error', reject);
    req.end();
  });
}

/** 建立 SSE 连接并手动解析 data: 行 */
function connectSSE(url) {
  return new Promise((resolve, reject) => {
    const req = http.get(url, (res) => {
      res.setEncoding('utf8');
      const sse = { events: [], close() { req.destroy(); res.destroy(); } };
      let buf = '';
      res.on('data', (chunk) => {
        buf += chunk;
        let idx;
        while ((idx = buf.indexOf('\n\n')) >= 0) {
          const block = buf.slice(0, idx);
          buf = buf.slice(idx + 2);
          for (const line of block.split('\n')) {
            if (line.startsWith('data: ')) {
              try { sse.events.push(JSON.parse(line.slice(6))); } catch (e) { /* 忽略坏行 */ }
            }
          }
        }
      });
      resolve(sse);
    });
    req.on('error', reject);
  });
}

/** 轮询等待某类 SSE 事件出现 */
async function waitEvent(sse, type, timeoutMs = 6000) {
  const start = Date.now();
  for (;;) {
    const e = sse.events.find((x) => x.type === type);
    if (e) return e;
    if (Date.now() - start > timeoutMs) throw new Error('等待 SSE 事件超时: ' + type);
    await new Promise((r) => setTimeout(r, 10));
  }
}

/* --------------------- 共享状态（流程类用例依赖顺序执行） --------------------- */

const OFFSETS = [50, -30, 80, 10, -60]; // 各设备伪造的 NTP 偏移
const TOKENS = []; // t1 槽位 1-5 的 token
let hostSse = null;

/**
 * 打一轮：reset → arm t1 → go → 按目标归一化时间构造 clientTs 并 roll → 等 reveal
 * targetDeltas：各槽位期望的「归一化后相对 goTs 的偏移」，clientTs = goTs + delta - offset
 * rollCount：实际发起 roll 的台数（缺人场景小于 5）
 */
async function playRound(targetDeltas, rollCount) {
  await jpost('/api/reset', {});
  hostSse.events.length = 0; // 清空上一轮残留事件
  await jpost('/api/arm', { teamId: 't1' });
  await waitEvent(hostSse, 'arm');
  const goRes = await jpost('/api/go', {});
  assert.strictEqual(goRes.status, 200);
  const goTs = goRes.json.goTs;
  assert.ok(typeof goTs === 'number' && goTs > 0);
  const t0 = Date.now();
  const n = rollCount === undefined ? targetDeltas.length : rollCount;
  for (let i = 0; i < n; i += 1) {
    const clientTs = goTs + targetDeltas[i] - OFFSETS[i];
    const r = await jpost('/api/roll', { token: TOKENS[i], clientTs });
    assert.strictEqual(r.status, 200, `槽位 ${i + 1} roll 应成功`);
  }
  const reveal = await waitEvent(hostSse, 'reveal');
  return { reveal, goTs, elapsed: Date.now() - t0 };
}

/** 校验 reveal 事件骰子的公共结构 */
function assertDiceShape(reveal) {
  assert.strictEqual(reveal.teamId, 't1');
  assert.strictEqual(reveal.dice.length, 5, 'dice 应为 5 个');
  reveal.dice.forEach((d, i) => {
    assert.strictEqual(d.slot, i + 1);
    assert.ok(Number.isInteger(d.die) && d.die >= 1 && d.die <= 6, `die 应为 1-6 整数，实际 ${d.die}`);
    assert.strictEqual(typeof d.early, 'boolean');
  });
}

/* ------------------------------ 测试用例 ------------------------------ */

test('静态文件：/ 与 /player 返回 200，路径穿越被拒', async () => {
  let res = await fetch(BASE + '/');
  assert.strictEqual(res.status, 200);
  assert.match(res.headers.get('content-type') || '', /text\/html/);
  assert.match(await res.text(), /</);

  res = await fetch(BASE + '/player');
  assert.strictEqual(res.status, 200);
  assert.match(await res.text(), /</);

  res = await fetch(BASE + '/no-such-file.js');
  assert.strictEqual(res.status, 404);

  // 原始点段 / 编码点段 / 编码斜杠 三种穿越写法都必须被拒（403 或 404）
  for (const p of ['/../etc/passwd', '/%2e%2e/%2e%2e/etc/passwd', '/..%2f..%2fetc%2fpasswd']) {
    const st = await rawStatus(p);
    assert.ok(st === 403 || st === 404, `路径穿越应被拒: ${p} -> ${st}`);
  }
});

test('ping：回显 c0，s 为合理当前时间', async () => {
  const c0 = Date.now() - 123;
  const before = Date.now();
  const r = await jpost('/api/ping', { c0 });
  const after = Date.now();
  assert.strictEqual(r.status, 200);
  assert.strictEqual(r.json.c0, c0);
  assert.ok(r.json.s >= before - 1000 && r.json.s <= after + 1000, `s=${r.json.s} 不在合理范围`);
});

test('非法输入校验：join 400 / calibrate 401 / roll 401', async () => {
  const badJoins = [
    { teamId: 't9', slot: 1 },
    { teamId: 't0', slot: 1 },
    { teamId: 't1', slot: 0 },
    { teamId: 't1', slot: 6 },
    { teamId: 't1', slot: '1' },
    { teamId: 1, slot: 1 }
  ];
  for (const body of badJoins) {
    const r = await jpost('/api/join', body);
    assert.strictEqual(r.status, 400, `应 400: ${JSON.stringify(body)}`);
  }
  const badCal = await jpost('/api/calibrate', { token: 'not-exist', offset: 0, rtt: 10 });
  assert.strictEqual(badCal.status, 401);
  const badRoll = await jpost('/api/roll', { token: 'not-exist', clientTs: 1 });
  assert.strictEqual(badRoll.status, 401);
});

test('建立流程：5 设备 join + calibrate，host SSE 首屏收到 roster', async () => {
  for (let slot = 1; slot <= 5; slot += 1) {
    const r = await jpost('/api/join', { teamId: 't1', slot, name: `雷霆-0${slot}` });
    assert.strictEqual(r.status, 200);
    assert.strictEqual(r.json.teamId, 't1');
    assert.strictEqual(r.json.slot, slot);
    assert.ok(typeof r.json.token === 'string' && r.json.token.length > 0);
    TOKENS.push(r.json.token);
  }
  for (let i = 0; i < 5; i += 1) {
    const r = await jpost('/api/calibrate', { token: TOKENS[i], offset: OFFSETS[i], rtt: 20 + i });
    assert.strictEqual(r.status, 200);
    assert.deepStrictEqual(r.json, { ok: true });
  }

  hostSse = await connectSSE(BASE + '/api/events?token=host');
  const roster = await waitEvent(hostSse, 'roster');
  const t1Devs = roster.devices.filter((d) => d.teamId === 't1');
  assert.strictEqual(t1Devs.length, 5);
  t1Devs.forEach((d, i) => {
    assert.strictEqual(d.calibrated, true);
    assert.strictEqual(d.rtt, 20 + i);
    assert.strictEqual(d.name, `雷霆-0${i + 1}`);
  });

  // /api/state 应与 roster 一致
  const res = await fetch(BASE + '/api/state');
  assert.strictEqual(res.status, 200);
  const st = await res.json();
  assert.strictEqual(st.devices.filter((d) => d.teamId === 't1').length, 5);
  assert.strictEqual(st.armedTeam, null);
  assert.strictEqual(st.rolling, false);
});

test('完整流程：归一化极差 200ms → syncOk=true 且立即揭示', async () => {
  const { reveal, goTs, elapsed } = await playRound([100, 150, 200, 250, 300]);
  assertDiceShape(reveal);
  assert.strictEqual(reveal.syncOk, true);
  assert.ok(Math.abs(reveal.spreadMs - 200) <= 10, `spreadMs=${reveal.spreadMs} 应约等于 200`);
  reveal.dice.forEach((d) => {
    assert.ok(typeof d.ts === 'number' && d.ts >= goTs, '不应有抢跑');
    assert.strictEqual(d.early, false);
  });
  assert.ok(elapsed < 2000, `收齐应立即揭示，实际耗时 ${elapsed}ms`);
});

test('同步失败：归一化极差 900ms → syncOk=false', async () => {
  const { reveal } = await playRound([100, 250, 400, 700, 1000]);
  assertDiceShape(reveal);
  assert.strictEqual(reveal.syncOk, false);
  assert.ok(Math.abs(reveal.spreadMs - 900) <= 10, `spreadMs=${reveal.spreadMs} 应约等于 900`);
});

test('缺人补掷：只 4 台 roll，3 秒超时揭示，syncOk=false', async () => {
  const { reveal, elapsed } = await playRound([100, 150, 200, 250], 4);
  assertDiceShape(reveal);
  assert.ok(elapsed >= 2500 && elapsed < 8000, `应走 3 秒超时路径，实际 ${elapsed}ms`);
  assert.strictEqual(reveal.syncOk, false);
  // 槽位 5 无记录：服务器补掷骰子但 ts=null
  const missing = reveal.dice.filter((d) => d.ts === null);
  assert.strictEqual(missing.length, 1);
  assert.strictEqual(missing[0].slot, 5);
  assert.strictEqual(missing[0].early, false);
  assert.ok(Number.isInteger(missing[0].die) && missing[0].die >= 1 && missing[0].die <= 6);
  // 其余 4 个槽位时间戳正常
  reveal.dice.filter((d) => d.ts !== null).forEach((d) => assert.strictEqual(d.early, false));
});

test('抢跑：1 个 roll 归一化 ts < goTs → early=true 且 syncOk=false', async () => {
  // 极差 270ms 在窗口内，syncOk=false 只能来自抢跑判定
  const { reveal } = await playRound([-50, 100, 140, 180, 220]);
  assertDiceShape(reveal);
  assert.strictEqual(reveal.dice[0].early, true, '槽位 1 应判定抢跑');
  reveal.dice.slice(1).forEach((d) => assert.strictEqual(d.early, false));
  assert.strictEqual(reveal.syncOk, false);
  assert.ok(Math.abs(reveal.spreadMs - 270) <= 10, `spreadMs=${reveal.spreadMs} 应约等于 270`);
});

test('顶替：同队同槽再次 join 后旧 token roll 返回 401', async () => {
  await jpost('/api/reset', {});
  const a = await jpost('/api/join', { teamId: 't2', slot: 1, name: '旧设备' });
  assert.strictEqual(a.status, 200);
  const b = await jpost('/api/join', { teamId: 't2', slot: 1, name: '新设备' });
  assert.strictEqual(b.status, 200);
  assert.notStrictEqual(a.json.token, b.json.token);

  await jpost('/api/arm', { teamId: 't2' });
  await jpost('/api/go', {});
  const oldRoll = await jpost('/api/roll', { token: a.json.token, clientTs: Date.now() });
  assert.strictEqual(oldRoll.status, 401, '旧 token 应失效');
  const newRoll = await jpost('/api/roll', { token: b.json.token, clientTs: Date.now() });
  assert.strictEqual(newRoll.status, 200, '新 token 应可用');

  // 顶替后 /api/state 里 t2 槽位 1 只剩新设备
  const res = await fetch(BASE + '/api/state');
  const st = await res.json();
  const t2s1 = st.devices.filter((d) => d.teamId === 't2' && d.slot === 1);
  assert.strictEqual(t2s1.length, 1);
  assert.strictEqual(t2s1[0].name, '新设备');

  await jpost('/api/reset', {});
});

/* ------------------------------ 运行入口 ------------------------------ */

(async () => {
  // 临时静态根目录（含最小 index.html / player.html），不污染项目目录
  const tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'dice-tycoon-'));
  fs.writeFileSync(path.join(tmpRoot, 'index.html'), '<!doctype html><html><body>大屏</body></html>');
  fs.writeFileSync(path.join(tmpRoot, 'player.html'), '<!doctype html><html><body>队员</body></html>');

  const server = createServer({ root: tmpRoot });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  BASE = `http://127.0.0.1:${server.address().port}`;
  console.log(`测试服务器已启动: ${BASE}`);

  let failed = 0;
  for (const t of tests) {
    try {
      await t.fn();
      console.log(`✓ ${t.name}`);
    } catch (e) {
      failed += 1;
      console.error(`✗ ${t.name}`);
      console.error(e && e.stack ? e.stack : e);
    }
  }

  if (hostSse) hostSse.close();
  try { server.close(); } catch (e) { /* 忽略 */ }
  try { fs.rmSync(tmpRoot, { recursive: true, force: true }); } catch (e) { /* 忽略 */ }

  console.log(failed === 0 ? `\n全部 ${tests.length} 项测试通过` : `\n${failed}/${tests.length} 项测试失败`);
  process.exit(failed === 0 ? 0 : 1);
})();
