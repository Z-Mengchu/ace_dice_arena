#!/usr/bin/env node
/**
 * 「王牌攻守擂·骰子大亨」联机服务器
 * 纯 Node.js（仅用内置模块 http/fs/path/url/os/crypto，无第三方依赖）
 * 用法：node server.js [port]   默认端口 8080
 *
 * 角色：
 *  - 主持人电脑运行本服务器，投屏打开 http://localhost:<port>/ 作为大屏
 *  - 每队 5 名出战队员在各自电脑打开 http://<主持人IP>:<port>/player 参与同步掷骰
 *
 * 核心机制：
 *  - 队员点击时间按各设备本地时间轴校准（NTP 式偏移 offset）后再比较，消除设备间时钟/网速差异
 *  - 骰子点数由服务器在揭示（reveal）时掷出，客户端无法预知
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const GameEngine = require('./frontend/engine.js');

/* ------------------------------ 常量 ------------------------------ */

const TEAM_IDS = ['t1', 't2', 't3', 't4', 't5', 't6', 't7', 't8']; // 合法队伍 id
const SLOT_COUNT = 5;                 // 每队出战槽位数
const REVEAL_TIMEOUT_MS = 3000;       // go 后等待收齐 roll 的超时（毫秒）
const HEARTBEAT_MS = 15000;           // SSE 心跳间隔（毫秒）
const MAX_BODY_BYTES = 1024 * 1024;   // POST body 上限
const SYNC_WINDOW_MS = GameEngine.DEFAULT_CONFIG.syncWindowMs; // 同步窗口阈值 500ms

/* ------------------- 引擎工具函数（缺省时本地兜底） ------------------- */
/* 说明：reveal 计算委托给 GameEngine 的同名函数。引擎现已提供
 * normalizeTime / computeSpread / checkSync，下面的 || 右支永远不会走到；
 * 本地兜底按产品规则给出等价实现，仅作防御（如旧版 engine.js 被误部署时）。 */

// NTP 式时间校准：offset = 服务器时间 - 客户端时间（由 ping 往返估算），
// 归一化即把设备本地时间平移到服务器时间轴
const normalizeTime = GameEngine.normalizeTime || function normalizeTime(clientTs, offset) {
  return clientTs + (offset || 0);
};

// 首尾时差（极差）：max - min；无数据返回 { spreadMs: null }
const computeSpread = GameEngine.computeSpread || function computeSpread(timestamps) {
  if (!Array.isArray(timestamps) || timestamps.length === 0) return { spreadMs: null };
  let min = Infinity;
  let max = -Infinity;
  for (const t of timestamps) {
    if (t < min) min = t;
    if (t > max) max = t;
  }
  return { spreadMs: max - min };
};

// 同步判定：必须集齐 5 个时间戳、无人抢跑、首尾时差不超过 syncWindowMs
// （缺人自动补掷但同步失败的产品规则即由「必须 5 个时间戳」保证）
const checkSync = GameEngine.checkSync || function checkSync(timestamps, goTs) {
  let earlyCount = 0;
  for (const t of timestamps) {
    if (t < goTs) earlyCount += 1;
  }
  const { spreadMs } = computeSpread(timestamps);
  const syncOk =
    timestamps.length === SLOT_COUNT &&
    earlyCount === 0 &&
    spreadMs !== null &&
    spreadMs <= SYNC_WINDOW_MS;
  return { syncOk, earlyCount };
};

/* ------------------------------ MIME 表 ------------------------------ */

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2'
};

/* --------------------------- 通用小工具 --------------------------- */

/** 以 JSON 响应并结束 */
function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store'
  });
  res.end(body);
}

/** 读取并解析 POST 的 JSON body（空 body 视为 {}） */
function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error('body too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (size === 0) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch (e) {
        reject(e);
      }
    });
    req.on('error', reject);
  });
}

/* --------------------------- 服务器工厂 --------------------------- */

/**
 * 创建 HTTP 服务器（不自动监听）。
 * options.root 指定静态文件根目录，默认为本文件所在目录（即 game/）。
 * 测试可传入临时目录以避免污染项目目录。
 */
function createServer(options = {}) {
  const staticRoot = path.resolve(options.root || path.join(__dirname, 'frontend'));

  /* 全局状态 */
  const state = {
    devices: new Map(),   // token -> { teamId, slot, name, offset, rtt, calibrated }
    armedTeam: null,      // 当前被 arm 的队伍 id
    rolling: false,       // 是否处于一轮掷骰中（arm 后 true，reveal/reset 后 false）
    rolls: new Map(),     // slot -> { slot, clientTs }（本轮 armedTeam 的点击记录）
    goTs: null,           // 本轮 go 的服务器时间戳
    revealTimer: null     // 3 秒揭示定时器
  };
  const sseClients = new Set(); // 所有 SSE 连接的 res 对象

  /** 输出给客户端的设备列表（与 /api/state 的 devices 一致，不含 token/offset） */
  function devicesView() {
    const list = [];
    for (const dev of state.devices.values()) {
      list.push({
        teamId: dev.teamId,
        slot: dev.slot,
        name: dev.name,
        rtt: dev.rtt,
        calibrated: dev.calibrated
      });
    }
    return list;
  }

  /** 按队伍+槽位查找设备（用于 reveal 时取该设备的校准偏移） */
  function findDevice(teamId, slot) {
    for (const dev of state.devices.values()) {
      if (dev.teamId === teamId && dev.slot === slot) return dev;
    }
    return null;
  }

  /** 向单个 SSE 连接写一条事件 */
  function sendEvent(res, obj) {
    res.write('data: ' + JSON.stringify(obj) + '\n\n');
  }

  /** 广播事件给所有 SSE 连接 */
  function broadcast(obj) {
    for (const res of sseClients) sendEvent(res, obj);
  }

  /** 广播最新 roster */
  function broadcastRoster() {
    broadcast({ type: 'roster', devices: devicesView() });
  }

  /** 清除揭示定时器 */
  function clearRevealTimer() {
    if (state.revealTimer) {
      clearTimeout(state.revealTimer);
      state.revealTimer = null;
    }
  }

  /**
   * 揭示（reveal）：收齐 5 个 roll 立即触发，或 3 秒超时触发（二者先到为准）
   * 骰子由服务器掷出；有 roll 记录的槽位按设备 offset 归一化时间并判定抢跑；
   * 无记录的槽位 ts=null、early=false（缺人自动补掷但同步失败）
   */
  function doReveal() {
    clearRevealTimer();
    if (!state.rolling || !state.armedTeam) return; // 已揭示或未在局中，防重入
    state.rolling = false;
    const teamId = state.armedTeam;
    const dice = [];
    const timestamps = [];
    for (let slot = 1; slot <= SLOT_COUNT; slot += 1) {
      const die = GameEngine.rollDie(); // 服务器掷骰，客户端无法预知
      const roll = state.rolls.get(slot);
      let ts = null;
      let early = false;
      if (roll) {
        const dev = findDevice(teamId, slot);
        ts = normalizeTime(roll.clientTs, dev ? dev.offset : 0);
        early = ts < state.goTs; // 归一化后仍早于 go 即抢跑
        timestamps.push(ts);
      }
      dice.push({ slot, die, ts, early });
    }
    const { spreadMs } = computeSpread(timestamps);
    const { syncOk } = checkSync(timestamps, state.goTs);
    broadcast({ type: 'reveal', teamId, dice, spreadMs, syncOk });
  }

  /* --------------------------- 静态文件 --------------------------- */

  function serveStatic(req, res, pathname) {
    let rel;
    try {
      rel = decodeURIComponent(pathname);
    } catch (e) {
      return sendJson(res, 400, { error: 'bad path' });
    }
    if (rel.indexOf('\0') !== -1) return sendJson(res, 403, { error: 'forbidden' });
    // 路由别名：/ -> index.html，/player -> player.html
    if (rel === '/') rel = '/index.html';
    else if (rel === '/player') rel = '/player.html';
    // 解析到根目录内，拒绝路径穿越
    const filePath = path.resolve(staticRoot, '.' + rel);
    if (filePath !== staticRoot && !filePath.startsWith(staticRoot + path.sep)) {
      return sendJson(res, 403, { error: 'forbidden' });
    }
    fs.stat(filePath, (err, st) => {
      if (err || !st.isFile()) return sendJson(res, 404, { error: 'not found' });
      const type = MIME_TYPES[path.extname(filePath).toLowerCase()] || 'application/octet-stream';
      res.writeHead(200, {
        'Content-Type': type,
        'Content-Length': st.size,
        'Cache-Control': 'no-cache'
      });
      fs.createReadStream(filePath).pipe(res);
    });
  }

  /* --------------------------- SSE --------------------------- */

  function handleEvents(req, res, url) {
    const token = url.searchParams.get('token') || '';
    // 主持人用 token=host，队员必须用自己的 token
    if (token !== 'host' && !state.devices.has(token)) {
      return sendJson(res, 401, { error: 'unknown token' });
    }
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no'
    });
    // 新连接立即推送一次 roster 与（若存在）当前 arm 状态
    sendEvent(res, { type: 'roster', devices: devicesView() });
    if (state.armedTeam) sendEvent(res, { type: 'arm', teamId: state.armedTeam });
    sseClients.add(res);
    req.on('close', () => sseClients.delete(res));
  }

  /* --------------------------- JSON API --------------------------- */

  async function handleApi(req, res, url, pathname) {
    const enterTs = Date.now(); // 进入 handler 的服务器时间（ping 需要零延迟回应它）

    // GET 类接口
    if (req.method === 'GET') {
      if (pathname === '/api/state') {
        return sendJson(res, 200, {
          devices: devicesView(),
          armedTeam: state.armedTeam,
          rolling: state.rolling
        });
      }
      if (pathname === '/api/events') return handleEvents(req, res, url);
      return sendJson(res, 404, { error: 'not found' });
    }
    if (req.method !== 'POST') return sendJson(res, 405, { error: 'method not allowed' });

    // POST 类接口：统一先解析 JSON body
    let body;
    try {
      body = await readJsonBody(req);
    } catch (e) {
      return sendJson(res, 400, { error: 'invalid json' });
    }
    if (body === null || typeof body !== 'object') body = {};

    switch (pathname) {
      // NTP 式对时：回显客户端发出的 c0，并附进入 handler 时的服务器时间 s
      case '/api/ping':
        return sendJson(res, 200, { c0: body.c0, s: enterTs });

      // 队员加入：同队同槽重复 join 时新 token 顶替旧设备（掉线重进友好）
      case '/api/join': {
        const { teamId, slot, name } = body;
        if (!TEAM_IDS.includes(teamId) || !Number.isInteger(slot) || slot < 1 || slot > SLOT_COUNT) {
          return sendJson(res, 400, { error: 'invalid teamId or slot' });
        }
        for (const [tok, dev] of state.devices) {
          if (dev.teamId === teamId && dev.slot === slot) state.devices.delete(tok);
        }
        const token = crypto.randomUUID();
        state.devices.set(token, {
          teamId,
          slot,
          name: typeof name === 'string' ? name.slice(0, 32) : '',
          offset: 0,
          rtt: null,
          calibrated: false
        });
        broadcastRoster();
        return sendJson(res, 200, { token, teamId, slot });
      }

      // 校准：按 token 记录 {offset, rtt, calibrated:true}
      case '/api/calibrate': {
        const dev = state.devices.get(body.token);
        if (!dev) return sendJson(res, 401, { error: 'invalid token' });
        if (typeof body.offset !== 'number' || !isFinite(body.offset) ||
            typeof body.rtt !== 'number' || !isFinite(body.rtt)) {
          return sendJson(res, 400, { error: 'invalid offset or rtt' });
        }
        dev.offset = body.offset;
        dev.rtt = body.rtt;
        dev.calibrated = true;
        broadcastRoster();
        return sendJson(res, 200, { ok: true });
      }

      // 主持人 arm：指定攻擂队伍，清空本轮记录，进入待掷状态
      case '/api/arm': {
        if (!TEAM_IDS.includes(body.teamId)) return sendJson(res, 400, { error: 'invalid teamId' });
        state.armedTeam = body.teamId;
        state.rolls.clear();
        state.rolling = true;
        broadcast({ type: 'arm', teamId: body.teamId });
        return sendJson(res, 200, { ok: true });
      }

      // 主持人 go：记录 goTs 并广播，启动 3 秒揭示定时器（重复 go 先清旧定时器）
      case '/api/go': {
        clearRevealTimer();
        state.goTs = Date.now();
        broadcast({ type: 'go', goTs: state.goTs });
        state.revealTimer = setTimeout(doReveal, REVEAL_TIMEOUT_MS);
        state.revealTimer.unref();
        return sendJson(res, 200, { ok: true, goTs: state.goTs });
      }

      // 队员掷骰（实际只记录点击时间，骰子由服务器在 reveal 时掷出）
      case '/api/roll': {
        const dev = state.devices.get(body.token);
        if (!dev) return sendJson(res, 401, { error: 'invalid token' });
        if (!state.rolling || !state.armedTeam || dev.teamId !== state.armedTeam) {
          return sendJson(res, 409, { error: 'not armed' }); // 当前无 arm 或不属于 armedTeam
        }
        if (typeof body.clientTs !== 'number' || !isFinite(body.clientTs)) {
          return sendJson(res, 400, { error: 'invalid clientTs' });
        }
        if (!state.rolls.has(dev.slot)) {
          // 每槽只取第一次，重复忽略
          state.rolls.set(dev.slot, { slot: dev.slot, clientTs: body.clientTs });
          if (state.rolls.size >= SLOT_COUNT) doReveal(); // 收齐 5 个槽位立即揭示
        }
        return sendJson(res, 200, { ok: true });
      }

      // 重置：清空本轮所有进行中的状态
      case '/api/reset': {
        clearRevealTimer();
        state.rolls.clear();
        state.armedTeam = null;
        state.rolling = false;
        state.goTs = null;
        broadcast({ type: 'reset' });
        return sendJson(res, 200, { ok: true });
      }

      default:
        return sendJson(res, 404, { error: 'not found' });
    }
  }

  /* --------------------------- 请求入口 --------------------------- */

  const server = http.createServer((req, res) => {
    let url;
    try {
      url = new URL(req.url, 'http://localhost');
    } catch (e) {
      return sendJson(res, 400, { error: 'bad request' });
    }
    const pathname = url.pathname;
    if (pathname.startsWith('/api/')) {
      handleApi(req, res, url, pathname).catch((err) => {
        // 兜底：handler 异常不应拖垮服务器
        console.error('API 处理异常:', err);
        if (!res.headersSent) sendJson(res, 500, { error: 'internal error' });
      });
      return;
    }
    if (req.method !== 'GET') return sendJson(res, 405, { error: 'method not allowed' });
    serveStatic(req, res, pathname);
  });

  // SSE 心跳：每 15 秒发一行注释，防止代理/浏览器超时断连
  const heartbeat = setInterval(() => {
    for (const res of sseClients) res.write(': hb\n\n');
  }, HEARTBEAT_MS);
  heartbeat.unref();
  server.on('close', () => clearInterval(heartbeat));

  server.gameState = state; // 暴露状态便于调试与测试
  return server;
}

/* --------------------------- 直接启动 --------------------------- */

if (require.main === module) {
  const port = Number(process.argv[2]) || 8080;
  const server = createServer();
  server.listen(port, () => {
    const actualPort = server.address().port;
    console.log('「王牌攻守擂·骰子大亨」联机服务器已启动');
    console.log(`主持入口（大屏）：http://localhost:${actualPort}/`);
    // 打印本机所有局域网 IPv4 地址的队员入口，方便队员扫码/输网址
    const nets = os.networkInterfaces();
    for (const name of Object.keys(nets)) {
      for (const ni of nets[name] || []) {
        if (ni.family === 'IPv4' && !ni.internal) {
          console.log(`队员入口：http://${ni.address}:${actualPort}/player`);
        }
      }
    }
  });
}

module.exports = { createServer };
