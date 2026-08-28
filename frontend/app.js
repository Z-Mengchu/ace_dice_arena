/**
 * 王牌攻守擂 · 骰子大亨 —— 主持人台（index.html）
 * 纯原生 JS，无模块；依赖 engine.js 暴露的 window.GameEngine。
 * 联机模式：fetch + EventSource(SSE) 对接 Spring Boot；比赛状态与战报由服务器持久化。
 */
(function () {
  'use strict';

  /* ==================== 0. 环境检查 ==================== */

  var GE = window.GameEngine;
  if (!GE) {
    document.body.innerHTML =
      '<div style="max-width:720px;margin:120px auto;padding:32px;font-size:18px;line-height:1.8;color:#fff;background:#1a1030;border:1px solid #f6c453;border-radius:12px">' +
      '<h1 style="color:#f6c453">engine.js 未加载</h1>' +
      '<p>请确认 game/ 目录下存在 engine.js，且 index.html 中先于 app.js 引入。</p></div>';
    return;
  }

  /* ==================== 1. 小工具 ==================== */

  function $(s) { return document.querySelector(s); }
  function $$(s) { return Array.prototype.slice.call(document.querySelectorAll(s)); }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function fmtMoney(n) { return (Number(n) || 0).toLocaleString('zh-CN'); }
  function fmt1(n) { return String(Math.round(Number(n) * 10) / 10); }
  function fmtGrowth(g) { g = Number(g) || 0; return (g > 0 ? '+' : '') + g + '%'; }
  function byId(arr, id) { for (var i = 0; i < arr.length; i++) if (arr[i].id === id) return arr[i]; return null; }

  /* ==================== 2. 程序生成音效（WebAudio，无音频文件） ==================== */

  var Snd = (function () {
    var ctx = null;
    function ac() {
      if (state && state.muted) return null;
      if (!ctx) {
        var AC = window.AudioContext || window.webkitAudioContext;
        if (!AC) return null;
        try { ctx = new AC(); } catch (e) { return null; }
      }
      if (ctx.state === 'suspended') { ctx.resume(); }
      return ctx;
    }
    function tone(freq, dur, type, gain, delay) {
      var c = ac(); if (!c) return;
      dur = dur || 0.12; type = type || 'sine'; gain = gain || 0.16; delay = delay || 0;
      var t = c.currentTime + delay;
      var o = c.createOscillator(), g = c.createGain();
      o.type = type;
      o.frequency.setValueAtTime(freq, t);
      g.gain.setValueAtTime(0.0001, t);
      g.gain.exponentialRampToValueAtTime(gain, t + 0.012);
      g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
      o.connect(g); g.connect(c.destination);
      o.start(t); o.stop(t + dur + 0.05);
    }
    function seq(notes, gap, type, gain) {
      for (var i = 0; i < notes.length; i++) tone(notes[i], 0.16, type, gain, i * (gap || 0.09));
    }
    return {
      click: function () { tone(660, 0.06, 'square', 0.07); },
      tick: function () { tone(880, 0.09, 'square', 0.15); },
      go: function () { tone(1320, 0.28, 'square', 0.2); tone(1760, 0.3, 'sine', 0.12, 0.02); },
      roll: function () { tone(300 + Math.random() * 500, 0.05, 'triangle', 0.11); },
      flip: function () { tone(520, 0.07, 'triangle', 0.13); tone(780, 0.06, 'triangle', 0.09, 0.05); },
      deny: function () { tone(180, 0.2, 'sawtooth', 0.11); },
      leopard: function () { seq([523, 659, 784, 1047, 1319], 0.07, 'sawtooth', 0.13); },
      win: function () { seq([523, 659, 784, 1047], 0.11, 'triangle', 0.17); },
      matchWin: function () { seq([392, 523, 659, 784, 1047, 1319], 0.12, 'triangle', 0.17); },
      champion: function () { seq([523, 523, 659, 784, 784, 1047, 1319, 1568], 0.14, 'triangle', 0.16); }
    };
  })();

  /* ==================== 3. 彩带（canvas 手写 confetti） ==================== */

  var Confetti = (function () {
    var cv = null, ctx = null, parts = [], raf = null;
    var colors = ['#f6c453', '#ffd700', '#ffffff', '#4da3ff', '#ff5a5a', '#3ddc97'];
    function ensure() {
      if (!cv) { cv = $('#confetti-canvas'); ctx = cv.getContext('2d'); }
      resize();
    }
    function resize() { if (cv) { cv.width = window.innerWidth; cv.height = window.innerHeight; } }
    window.addEventListener('resize', resize);
    function loop() {
      raf = requestAnimationFrame(loop);
      ctx.clearRect(0, 0, cv.width, cv.height);
      for (var i = parts.length - 1; i >= 0; i--) {
        var p = parts[i];
        p.x += p.vx; p.y += p.vy; p.rot += p.vr;
        if (p.y > cv.height + 30) { parts.splice(i, 1); continue; }
        ctx.save();
        ctx.translate(p.x, p.y);
        ctx.rotate(p.rot);
        ctx.fillStyle = p.c;
        ctx.fillRect(-p.w / 2, -p.h / 2, p.w, p.h);
        ctx.restore();
      }
      if (!parts.length) { cancelAnimationFrame(raf); raf = null; ctx.clearRect(0, 0, cv.width, cv.height); }
    }
    return {
      burst: function (n) {
        ensure();
        for (var i = 0; i < (n || 160); i++) {
          parts.push({
            x: Math.random() * cv.width,
            y: -20 - Math.random() * cv.height * 0.3,
            w: 6 + Math.random() * 8,
            h: 8 + Math.random() * 10,
            c: colors[(Math.random() * colors.length) | 0],
            vy: 2 + Math.random() * 3.5,
            vx: -1.5 + Math.random() * 3,
            rot: Math.random() * Math.PI,
            vr: -0.1 + Math.random() * 0.2
          });
        }
        if (!raf) loop();
      }
    };
  })();

  /* ==================== 4. 状态与持久化 ==================== */

  var SAVE_KEY = 'dice-arena-save-v1';

  var state = null;
  var loginUser = null;
  var serverStateReady = false;
  var serverSaveTimer = null;
  var serverVersion = 0;
  var net = { online: false, connected: false, devices: [], armedTeam: null, rolling: false, goTs: 0, es: null };
  var ui = {
    timers: [],          // setTimeout 句柄
    stateTicker: null,   // 联机槽位轮询 setInterval 句柄
    rollLive: false,     // 单机模式 GO 后的 3 秒掷骰窗口
    rollT0: 0,
    animateDice: false,  // rollReveal 是否播翻骰动画
    animateVerdict: false,
    onlinePhase: 'idle', // 联机掷骰面板阶段：idle / rolling / error
    forceLocal: false,   // 手动切回单机键盘
    prophetSel: null,
    lineupSel: null
  };

  function later(fn, ms) { var t = setTimeout(fn, ms); ui.timers.push(t); return t; }
  function clearTimers() {
    for (var i = 0; i < ui.timers.length; i++) clearTimeout(ui.timers[i]);
    ui.timers = [];
    if (ui.stateTicker) { clearInterval(ui.stateTicker); ui.stateTicker = null; }
    ui.rollLive = false;
  }

  function freshState() {
    var teams = GE.createTeams();
    var s = {
      version: 1,
      screen: 'setup',
      config: {},
      teams: teams,
      day: 1,
      quotaUsed: {},   // { teamId: { day1: n, day2: n } } 已消耗配额
      wins: {},        // { teamId: { day1: n, day2: n } } 当日胜场（场）
      bracket: null,   // { qf: [[a,b]x4], sf: [[a,b]x2], f: [[a,b]] }
      matches: {},     // { matchId: record }
      currentMatchId: null,
      live: null,      // 进行中的比赛流状态
      accumRevealed: 0,
      drawDone: false,
      log: [],
      muted: false,
      dayChampion: {},
      champion: null
    };
    for (var k in GE.DEFAULT_CONFIG) s.config[k] = GE.DEFAULT_CONFIG[k];
    for (var i = 0; i < teams.length; i++) {
      s.quotaUsed[teams[i].id] = { day1: 0, day2: 0 };
      s.wins[teams[i].id] = { day1: 0, day2: 0 };
    }
    return s;
  }

  function save() {
    try { localStorage.setItem(SAVE_KEY, JSON.stringify(state)); } catch (e) { /* 存储不可用时静默 */ }
    if (serverStateReady) {
      clearTimeout(serverSaveTimer);
      serverSaveTimer = setTimeout(saveToServer, 250);
    }
  }

  function saveToServer() {
    if (!state || !serverStateReady) return;
    serverSaveTimer = null;
    fetch('/api/game-state', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(state)
    }).then(checkLoginResponse).then(function (r) { return r.json(); }).then(function (saved) {
      if (saved && saved.version) serverVersion = Math.max(serverVersion, saved.version);
    }).catch(function () { /* 本地副本仍可支撑当前页面 */ });
  }

  function syncPlayerActions() {
    if (!serverStateReady || serverSaveTimer) return;
    fetch('/api/game-state').then(checkLoginResponse).then(function (r) { return r.status === 204 ? null : r.json(); }).then(function (saved) {
      if (!saved || !saved.state || saved.version <= serverVersion) return;
      serverVersion = saved.version;
      state = saved.state;
      try { localStorage.setItem(SAVE_KEY, JSON.stringify(state)); } catch (e) { }
      render();
    }).catch(function () { });
  }

  function checkLoginResponse(response) {
    if (response.status === 401) {
      location.replace('/login.html?next=host');
      throw new Error('login required');
    }
    return response;
  }

  function recordBattleReport(text) {
    fetch('/api/battle-reports', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: text })
    }).then(checkLoginResponse).catch(function () { /* 不阻断比赛 */ });
  }

  function load() {
    try {
      var raw = localStorage.getItem(SAVE_KEY);
      if (!raw) return null;
      var s = JSON.parse(raw);
      if (!s || s.version !== 1 || !s.teams || s.teams.length !== 8) return null;
      return s;
    } catch (e) { return null; }
  }

  /** 刷新恢复：把进行中的不稳定步骤回退到安全检查点 */
  function sanitize() {
    if (state.screen === 'match') {
      var live = state.live;
      var rec = live && state.matches[live.matchId];
      if (!live || !rec || rec.status !== 'active') {
        state.live = null;
        state.screen = state.bracket ? 'bracket' : 'setup';
        return;
      }
      if (live.step === 'roll') {
        var r = live.rolls[live.attacker];
        // 掷骰中途刷新：本次掷骰作废，回到默契确认（配额尚未消耗）
        if (!r || (r.started && !r.done)) {
          live.rolls[live.attacker] = null;
          live.step = 'sync';
        }
      }
    }
  }

  /* ==================== 5. 派生数据 ==================== */

  function dayKey() { return 'day' + state.day; }
  function teamById(id) { return byId(state.teams, id); }
  function teamName(id) { var t = teamById(id); return t ? t.name : id; }
  function playerById(team, id) { return team ? byId(team.players, id) : null; }
  function playerName(team, id) { var p = playerById(team, id); return p ? p.name : id; }
  function quotaOf(id) { var t = teamById(id); return GE.quotaFor(t.gmv[dayKey()], state.config); }
  function quotaLeft(id) { return quotaOf(id) - state.quotaUsed[id][dayKey()]; }
  function growthOf(id) { var t = teamById(id); return Number(t.growth[dayKey()]) || 0; }
  function matchLabel(rec) { return rec.round === 'qf' ? ('QF-' + (rec.index + 1)) : rec.round === 'sf' ? ('SF-' + (rec.index + 1)) : '决赛'; }
  function stageCn(r) { return r === 'qf' ? '1/4 决赛' : r === 'sf' ? '半决赛' : '总决赛'; }

  /** 王牌投手：优先副队长（后端），其次阵容中任意后端，否则阵容第一人 */
  function pitcherOf(team, lineup) {
    if (lineup.indexOf(team.viceCaptainId) >= 0) return team.viceCaptainId;
    for (var i = 0; i < lineup.length; i++) {
      var p = playerById(team, lineup[i]);
      if (p && p.role === 'back') return p.id;
    }
    return lineup[0];
  }

  function onlineMode() { return net.online && !ui.forceLocal; }

  /* ==================== 6. 战报日志 ==================== */

  function addLog(text) {
    state.log.unshift({ text: text });
    if (state.log.length > 300) state.log.length = 300;
    if (serverStateReady) recordBattleReport(text);
    save();
    renderLog();
  }

  function renderLog() {
    var box = $('#log-list');
    if (!box) return;
    var html = '';
    if (!state.log.length) html = '<div class="log-empty">暂无战报，比赛开始后自动生成。</div>';
    for (var i = 0; i < state.log.length; i++) {
      html += '<div class="log-item">' + esc(state.log[i].text) + '</div>';
    }
    box.innerHTML = html;
  }

  /* ==================== 7. 联机网络层 ==================== */

  function api(path, body) {
    return fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {})
    }).then(checkLoginResponse).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json().catch(function () { return {}; });
    });
  }

  function getState() {
    return fetch('/api/state').then(checkLoginResponse).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    });
  }

  function applyServerState(st) {
    net.devices = (st && st.devices) || [];
    net.armedTeam = (st && st.armedTeam) || null;
    net.rolling = !!(st && st.rolling);
  }

  /** 1.5 秒超时探测服务器；file:// 打开时 fetch 必失败 → 单机模式 */
  function detectServer() {
    return new Promise(function (resolve) {
      var ctrl = new AbortController();
      var to = setTimeout(function () { ctrl.abort(); }, 1500);
      fetch('/api/state', { signal: ctrl.signal })
        .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
        .then(function (st) {
          clearTimeout(to);
          net.online = true;
          applyServerState(st);
          resolve(true);
        })
        .catch(function () {
          clearTimeout(to);
          net.online = false;
          resolve(false);
        });
    });
  }

  function connectSSE() {
    if (net.es) return;
    try { net.es = new EventSource('/api/events?token=host'); } catch (e) { return; }
    net.es.onopen = function () { net.connected = true; renderTopbar(); refreshState(); };
    net.es.onerror = function () { net.connected = false; renderTopbar(); };
    net.es.onmessage = function (ev) {
      var msg = null;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      onServerEvent(msg);
    };
  }

  function onServerEvent(msg) {
    if (!msg || !msg.type) return;
    switch (msg.type) {
      case 'roster':
        refreshState();
        break;
      case 'arm':
        net.armedTeam = msg.teamId;
        break;
      case 'go':
        net.rolling = true;
        net.goTs = msg.goTs || 0;
        break;
      case 'reset':
        net.rolling = false;
        break;
      case 'reveal':
        net.rolling = false;
        handleOnlineReveal(msg);
        break;
    }
  }

  function refreshState() {
    if (!net.online) return;
    getState().then(function (st) {
      applyServerState(st);
      if (state.screen === 'match' && state.live && state.live.step === 'roll' && onlineMode()) {
        updateOnlineSlots();
      }
    }).catch(function () { /* 保持旧数据 */ });
  }

  /* ==================== 8. 顶栏 / 主渲染 ==================== */

  var SCREEN_NAMES = {
    setup: '赛前设置', accum: '积累期结算', draw: '对阵抽签', bracket: '对阵表',
    match: '比赛进行', standings: '积分榜', champion: '总冠军颁奖'
  };

  function renderTopbar() {
    var txt = '第 ' + state.day + ' 天 · ' + SCREEN_NAMES[state.screen];
    if (state.screen === 'match' && state.live) {
      var rec = state.matches[state.live.matchId];
      txt += ' · ' + matchLabel(rec) + ' 第 ' + state.live.roundNum + ' 局';
    }
    $('#tb-status').textContent = txt;
    $('#btn-mute').textContent = state.muted ? '🔇 已静音' : '🔊 音效开';
    var userEl = $('#login-user');
    if (userEl) userEl.textContent = loginUser ? ('👤 ' + loginUser.displayName) : '';
    var badge = $('#net-badge');
    if (net.online) {
      badge.className = 'net-badge online';
      badge.textContent = ui.forceLocal ? '⌨ 单机键盘（手动切换）' : ('🌐 联机模式' + (net.connected ? '' : ' · 连接中…'));
    } else {
      badge.className = 'net-badge offline';
      badge.textContent = '⌨ 未检测到联机服务器，使用单机键盘模式';
    }
  }

  function render() {
    renderTopbar();
    renderLog();
    var root = $('#screen-root');
    switch (state.screen) {
      case 'setup': renderSetup(root); break;
      case 'accum': renderAccum(root); break;
      case 'draw': renderDraw(root); break;
      case 'bracket': renderBracket(root); break;
      case 'match': renderMatch(root); break;
      case 'standings': renderStandings(root); break;
      case 'champion': renderChampion(root); break;
    }
    save();
  }

  /* ==================== 9. 倒计时遮罩 ==================== */

  function runCountdown(seqArr, done, label) {
    var ov = $('#overlay');
    ov.classList.remove('hidden');
    var i = 0;
    (function next() {
      if (i >= seqArr.length) {
        ov.classList.add('hidden');
        ov.innerHTML = '';
        if (done) done();
        return;
      }
      var txt = seqArr[i];
      ov.innerHTML =
        (label ? '<div class="cd-label">' + esc(label) + '</div>' : '') +
        '<div class="cd-num' + (txt === 'GO!' ? ' go' : '') + '">' + esc(txt) + '</div>';
      if (txt === 'GO!') Snd.go(); else Snd.tick();
      i++;
      later(next, txt === 'GO!' ? 650 : 850);
    })();
  }

  /* ==================== 10. 设置屏 ==================== */

  function renderSetup(root) {
    var rows = '';
    for (var i = 0; i < state.teams.length; i++) {
      var t = state.teams[i];
      rows += '<tr data-idx="' + i + '">' +
        '<td><input class="inp team-name" data-f="name" value="' + esc(t.name) + '"></td>' +
        '<td><input class="inp team-short" data-f="shortName" value="' + esc(t.shortName) + '"></td>' +
        '<td><input class="inp num" data-f="gmv.day1" type="number" min="0" step="1000" value="' + t.gmv.day1 + '"></td>' +
        '<td><input class="inp num" data-f="growth.day1" type="number" step="0.1" value="' + t.growth.day1 + '"></td>' +
        '<td class="quota-preview" data-q="day1"></td>' +
        '<td><input class="inp num" data-f="gmv.day2" type="number" min="0" step="1000" value="' + t.gmv.day2 + '"></td>' +
        '<td><input class="inp num" data-f="growth.day2" type="number" step="0.1" value="' + t.growth.day2 + '"></td>' +
        '<td class="quota-preview" data-q="day2"></td></tr>';
    }
    root.innerHTML =
      '<section class="screen">' +
      '<h1 class="screen-title">🏀 王牌攻守擂 · 骰子大亨</h1>' +
      '<p class="screen-sub">240 人 · 8 大战区 · 两日 8 强淘汰赛 · 按前一日 GMV 换算掷骰配额</p>' +
      '<div class="card setup-card"><div class="card-head"><span>① 参赛战区数据（可直接编辑）</span>' +
      '<div class="head-actions"><button id="btn-mock" class="btn btn-ghost">载入示例数据</button></div></div>' +
      '<table class="tbl setup-tbl"><thead><tr>' +
      '<th>队名</th><th>简称</th><th>Day1 GMV(元)</th><th>Day1 增长率(%)</th><th>Day1 配额</th>' +
      '<th>Day2 GMV(元)</th><th>Day2 增长率(%)</th><th>Day2 配额</th>' +
      '</tr></thead><tbody>' + rows + '</tbody></table>' +
      '<div class="config-row"><label>换算比例：每 <input id="inp-gmv-per-dice" class="inp num" type="number" min="1" step="1000" value="' + state.config.gmvPerDice + '"> 元 GMV = 1 次掷骰配额</label>' +
      '<span class="config-hint">每天配额独立 · 每场三局两胜 · 配额耗尽后攻击 ×0.8（疲劳作战）</span></div></div>' +
      '<div class="card rules-card"><div class="card-head"><span>② 规则速览</span></div><ul class="rules">' +
      '<li>每局双方各攻击 1 次各消耗 1 配额；攻击力 =(5 骰之和 + 当日增长率) × 同步(≤500ms ×1.5) × 豹子(×3) × 疲劳(×0.8) + 军师(+2)</li>' +
      '<li>单方豹子直接获胜；总攻击相等 → 加赛一局</li>' +
      '<li>出战阵容 5 人且至少 1 名后端；军师可在掷骰前预言对方阵容，全中 +2</li>' +
      '<li>同步掷骰：口令倒计时后 5 人同时出手（联机各点手机/电脑【掷！】，单机按键盘 1-5），首尾时差 ≤500ms 得 ×1.5</li>' +
      '<li>日冠军比当日胜场 → 当日 GMV；总冠军比两天合计胜场 → 两天增长率之和</li>' +
      '</ul></div>' +
      '<div class="footer-actions"><button id="btn-start" class="btn btn-primary btn-xl">开始比赛 →</button></div>' +
      '</section>';

    $('#btn-mock').onclick = function () {
      Snd.click();
      var fresh = GE.createTeams();
      for (var i = 0; i < fresh.length; i++) {
        var m = GE.MOCK_TEAMS[i];
        fresh[i].gmv = { day1: m.gmv.day1, day2: m.gmv.day2 };
        fresh[i].growth = { day1: m.growth.day1, day2: m.growth.day2 };
        fresh[i].name = m.name;
        fresh[i].shortName = m.shortName;
      }
      state.teams = fresh;
      save();
      render();
    };

    $('#inp-gmv-per-dice').addEventListener('input', function () {
      var v = parseInt(this.value, 10);
      if (v >= 1) { state.config.gmvPerDice = v; save(); updateSetupQuotaPreviews(); }
    });

    var tbody = root.querySelector('.setup-tbl tbody');
    tbody.addEventListener('input', function (e) {
      var tr = e.target.closest('tr');
      if (!tr) return;
      var idx = +tr.getAttribute('data-idx');
      var f = e.target.getAttribute('data-f');
      var t = state.teams[idx];
      if (f === 'name' || f === 'shortName') {
        t[f] = e.target.value;
      } else {
        var v = parseFloat(e.target.value);
        var parts = f.split('.');
        t[parts[0]][parts[1]] = isFinite(v) ? v : 0;
      }
      save();
      updateSetupQuotaPreviews();
    });

    $('#btn-start').onclick = function () {
      if (!(state.config.gmvPerDice >= 1)) { alert('换算比例必须 ≥ 1'); return; }
      for (var i = 0; i < state.teams.length; i++) {
        var t = state.teams[i];
        if (!String(t.name).trim()) t.name = '第' + (i + 1) + '战区';
        if (!String(t.shortName).trim()) t.shortName = t.name.slice(0, 2);
        if (!(t.gmv.day1 >= 0) || !(t.gmv.day2 >= 0)) { alert('GMV 不能为负数或空'); return; }
        if (!isFinite(Number(t.growth.day1)) || !isFinite(Number(t.growth.day2))) { alert('增长率必须为数字'); return; }
      }
      Snd.matchWin();
      addLog('🏀 比赛开始：8 大战区就位，进入第 1 天积累期结算');
      state.screen = 'accum';
      state.accumRevealed = 0;
      save();
      render();
    };

    updateSetupQuotaPreviews();
  }

  function updateSetupQuotaPreviews() {
    $$('#screen-root .setup-tbl tbody tr').forEach(function (tr) {
      var t = state.teams[+tr.getAttribute('data-idx')];
      tr.querySelector('[data-q="day1"]').textContent = GE.quotaFor(t.gmv.day1, state.config);
      tr.querySelector('[data-q="day2"]').textContent = GE.quotaFor(t.gmv.day2, state.config);
    });
  }

  /* ==================== 11. 积累期屏 ==================== */

  function renderAccum(root) {
    var qk = dayKey();
    var cards = '';
    for (var i = 0; i < state.teams.length; i++) {
      var t = state.teams[i];
      var rev = i < state.accumRevealed;
      cards += '<div class="card accum-card' + (rev ? ' revealed' : '') + '" data-idx="' + i + '">' +
        '<div class="accum-team">' + esc(t.name) + '</div>' +
        '<div class="accum-gmv">' + (rev ? fmtMoney(t.gmv[qk]) : '? ? ?') + '</div>' +
        '<div class="accum-grow">当日增长率 ' + fmtGrowth(t.growth[qk]) + '</div>' +
        '<div class="accum-arrow">↓ 换算掷骰配额</div>' +
        '<div class="accum-quota">' + (rev ? '🎲 × ' + GE.quotaFor(t.gmv[qk], state.config) : '待揭晓') + '</div>' +
        '</div>';
    }
    var done = state.accumRevealed >= state.teams.length;
    root.innerHTML =
      '<section class="screen">' +
      '<h1 class="screen-title">第 ' + state.day + ' 天 · 积累期结算</h1>' +
      '<p class="screen-sub">前一日已结算 GMV 逐队揭晓，换算今日掷骰配额（每 ' + fmtMoney(state.config.gmvPerDice) + ' 元 = 1 次）</p>' +
      '<div class="accum-grid">' + cards + '</div>' +
      '<div class="footer-actions">' +
      (done
        ? '<button id="btn-to-draw" class="btn btn-primary btn-xl">进入抽签 →</button>'
        : '<button id="btn-reveal-all" class="btn btn-ghost">全部揭晓</button>') +
      '</div></section>';

    if (done) {
      $('#btn-to-draw').onclick = function () { Snd.click(); state.screen = 'draw'; save(); render(); };
    } else {
      $('#btn-reveal-all').onclick = function () {
        Snd.click();
        clearTimers();
        state.accumRevealed = state.teams.length;
        save();
        render();
      };
      later(revealNextAccum, 700);
    }
  }

  function revealNextAccum() {
    if (state.screen !== 'accum') return;
    var i = state.accumRevealed;
    if (i >= state.teams.length) return;
    var card = $('#screen-root .accum-card[data-idx="' + i + '"]');
    if (!card) return;
    var t = state.teams[i], qk = dayKey();
    card.classList.add('revealed');
    Snd.flip();
    countUp(card.querySelector('.accum-gmv'), t.gmv[qk], 550);
    var q = card.querySelector('.accum-quota');
    q.textContent = '🎲 × ' + GE.quotaFor(t.gmv[qk], state.config);
    q.classList.add('pop');
    state.accumRevealed = i + 1;
    save();
    if (i + 1 < state.teams.length) later(revealNextAccum, 780);
    else later(render, 700);
  }

  function countUp(el, to, ms) {
    var t0 = performance.now();
    (function step() {
      var p = Math.min(1, (performance.now() - t0) / ms);
      el.textContent = fmtMoney(Math.round(to * p));
      if (p < 1) requestAnimationFrame(step);
    })();
  }

  /* ==================== 12. 抽签屏 ==================== */

  function renderDraw(root) {
    var html = '<section class="screen">' +
      '<h1 class="screen-title">第 ' + state.day + ' 天 · 8 强对阵抽签</h1>' +
      '<p class="screen-sub">随机生成 4 组 1/4 决赛对阵</p>';
    if (!state.drawDone) {
      html += '<div class="draw-pool card" id="draw-pool">';
      for (var i = 0; i < state.teams.length; i++) {
        html += '<div class="chip team-chip">' + esc(state.teams[i].name) + '</div>';
      }
      html += '</div><div id="draw-result" class="draw-result"></div>' +
        '<div class="footer-actions"><button id="btn-draw" class="btn btn-primary btn-xl">🎰 开始抽签</button></div>';
    } else {
      html += '<div class="draw-result">' + drawPairsHTML() + '</div>' +
        '<div class="footer-actions"><button id="btn-draw-ok" class="btn btn-primary btn-xl">确认对阵，进入对阵表 →</button></div>';
    }
    root.innerHTML = html + '</section>';

    if (!state.drawDone) {
      $('#btn-draw').onclick = function () {
        Snd.click();
        var ids = state.teams.map(function (t) { return t.id; });
        var pairs = GE.drawBracket(ids);
        state.bracket = { qf: pairs, sf: [[null, null], [null, null]], f: [[null, null]] };
        state.matches = {};
        state.currentMatchId = null;
        for (var i = 0; i < 4; i++) ensureMatchRecord('qf', i);
        state.drawDone = true;
        save();
        // 洗牌动画 + 逐组揭晓（结果已定，动画纯表演）
        var btn = $('#btn-draw');
        if (btn) btn.disabled = true;
        var chips = $$('#draw-pool .team-chip');
        var n = 0;
        var iv = setInterval(function () {
          n++;
          chips.forEach(function (c) { c.style.order = Math.floor(Math.random() * 100); });
          Snd.roll();
          if (n >= 12) { clearInterval(iv); revealPairsAnim(); }
        }, 130);
      };
    } else {
      $('#btn-draw-ok').onclick = function () { Snd.click(); state.screen = 'bracket'; save(); render(); };
    }
  }

  function drawPairsHTML() {
    var h = '<div class="pair-grid">';
    for (var i = 0; i < 4; i++) {
      var p = state.bracket.qf[i];
      h += '<div class="card pair-card">' +
        '<div class="pair-label">QF-' + (i + 1) + '</div>' +
        '<div class="pair-team">' + esc(teamName(p[0])) + '</div>' +
        '<div class="pair-vs">VS</div>' +
        '<div class="pair-team">' + esc(teamName(p[1])) + '</div></div>';
    }
    return h + '</div>';
  }

  function revealPairsAnim() {
    var res = $('#draw-result');
    if (!res) return;
    res.innerHTML = drawPairsHTML();
    var cards = $$('#draw-result .pair-card');
    cards.forEach(function (c) { c.classList.add('pair-hidden'); });
    cards.forEach(function (c, i) {
      later(function () {
        c.classList.remove('pair-hidden');
        c.classList.add('flip-in');
        Snd.flip();
        if (i === cards.length - 1) {
          later(function () {
            var foot = $('#screen-root .footer-actions');
            if (foot) {
              foot.innerHTML = '<button id="btn-draw-ok" class="btn btn-primary btn-xl">确认对阵，进入对阵表 →</button>';
              $('#btn-draw-ok').onclick = function () { Snd.click(); state.screen = 'bracket'; save(); render(); };
            }
          }, 400);
        }
      }, 500 * i + 300);
    });
  }

  /* ==================== 13. 对阵表屏 ==================== */

  function ensureMatchRecord(round, idx) {
    var id = 'd' + state.day + '-' + round + '-' + idx;
    if (state.matches[id]) return state.matches[id];
    var pair = round === 'qf' ? state.bracket.qf[idx] : round === 'sf' ? state.bracket.sf[idx] : state.bracket.f[idx];
    if (!pair || !pair[0] || !pair[1]) return null;
    state.matches[id] = {
      id: id, day: state.day, round: round, index: idx,
      a: pair[0], b: pair[1],
      winsA: 0, winsB: 0, status: 'pending', winner: null, rounds: []
    };
    return state.matches[id];
  }

  function activeMatchRec() {
    if (state.currentMatchId) {
      var r = state.matches[state.currentMatchId];
      if (r && r.status === 'active') return r;
    }
    return null;
  }

  function nextPlayableId() {
    var order = [['qf', 0], ['qf', 1], ['qf', 2], ['qf', 3], ['sf', 0], ['sf', 1], ['f', 0]];
    for (var i = 0; i < order.length; i++) {
      var rec = ensureMatchRecord(order[i][0], order[i][1]);
      if (rec && rec.status === 'pending') return rec.id;
    }
    return null;
  }

  function dayComplete() {
    var f = state.matches['d' + state.day + '-f-0'];
    return !!(f && f.status === 'done');
  }

  function renderBracket(root) {
    ensureMatchRecord('sf', 0); ensureMatchRecord('sf', 1); ensureMatchRecord('f', 0);
    var act = activeMatchRec();
    var html = '<section class="screen"><h1 class="screen-title">第 ' + state.day + ' 天 · 对阵表</h1>';

    if (dayComplete()) {
      html += '<div class="banner gold">🏁 当日赛程已全部结束</div>' +
        '<div class="footer-actions"><button id="btn-to-standings" class="btn btn-primary btn-xl">查看当日积分榜 →</button></div>';
    } else {
      html += '<div class="footer-actions top"><button id="btn-next-match" class="btn btn-primary btn-xl">' +
        (act ? '▶ 继续进行中的比赛' : '▶ 下一场比赛') + '</button></div>';
    }

    var cols = [['qf', '1/4 决赛'], ['sf', '半决赛'], ['f', '总决赛']];
    html += '<div class="bracket-grid">';
    for (var c = 0; c < cols.length; c++) {
      html += '<div class="bracket-col"><div class="bracket-col-title">' + cols[c][1] + '</div>';
      var n = cols[c][0] === 'qf' ? 4 : cols[c][0] === 'sf' ? 2 : 1;
      for (var i = 0; i < n; i++) {
        html += matchCardHTML(state.matches['d' + state.day + '-' + cols[c][0] + '-' + i], cols[c][0], i, act);
      }
      html += '</div>';
    }
    var frec = state.matches['d' + state.day + '-f-0'];
    html += '<div class="bracket-col"><div class="bracket-col-title">🏆 当日决赛胜方</div>' +
      '<div class="card match-card champ-slot">' +
      (frec && frec.status === 'done'
        ? '<div class="champ-name">' + esc(teamName(frec.winner)) + '</div><div class="champ-sub">当日总决赛胜方</div>'
        : '<div class="champ-name dim">待定</div>') +
      '</div></div></div>';

    html += '<div class="card quota-panel"><div class="card-head"><span>各队当日状态</span></div>' +
      '<table class="tbl"><thead><tr><th>队伍</th><th>剩余配额</th><th>当日胜场</th><th>累计胜场</th></tr></thead><tbody>';
    for (var t = 0; t < state.teams.length; t++) {
      var tm = state.teams[t];
      var left = quotaLeft(tm.id);
      html += '<tr><td>' + esc(tm.name) + '</td><td>' +
        (left > 0 ? left : '<span class="badge badge-red">0 · 疲劳</span>') +
        '</td><td>' + state.wins[tm.id][dayKey()] + '</td><td>' +
        (state.wins[tm.id].day1 + state.wins[tm.id].day2) + '</td></tr>';
    }
    html += '</tbody></table></div></section>';
    root.innerHTML = html;

    var nb = $('#btn-next-match');
    if (nb) nb.onclick = function () {
      Snd.click();
      var id = act ? act.id : nextPlayableId();
      if (id) startMatch(id);
    };
    var sb = $('#btn-to-standings');
    if (sb) sb.onclick = enterStandings;
    $$('#screen-root .match-card[data-mid] .match-start').forEach(function (b) {
      b.onclick = function () {
        Snd.click();
        startMatch(b.closest('.match-card').getAttribute('data-mid'));
      };
    });
  }

  function matchCardHTML(rec, round, idx, act) {
    var label = round === 'qf' ? 'QF-' + (idx + 1) : round === 'sf' ? 'SF-' + (idx + 1) : '决赛';
    if (!rec) {
      return '<div class="card match-card pending"><div class="match-label">' + label + '</div>' +
        '<div class="match-team dim">待定</div><div class="match-team dim">待定</div></div>';
    }
    var cls = rec.status === 'done' ? 'done' : rec.status === 'active' ? 'active' : '';
    var h = '<div class="card match-card ' + cls + '" data-mid="' + rec.id + '">' +
      '<div class="match-label">' + label + '<span class="match-status">' +
      (rec.status === 'done' ? '已结束' : rec.status === 'active' ? '进行中' : '未开始') + '</span></div>' +
      matchTeamRowHTML(rec.a, rec.winsA, rec.status === 'done' && rec.winner === rec.a) +
      matchTeamRowHTML(rec.b, rec.winsB, rec.status === 'done' && rec.winner === rec.b);
    if ((rec.status === 'pending' && !act) || rec.status === 'active') {
      h += '<button class="btn btn-primary match-start">' + (rec.status === 'active' ? '继续比赛' : '开始比赛') + '</button>';
    }
    return h + '</div>';
  }

  function matchTeamRowHTML(tid, wins, win) {
    return '<div class="match-team' + (win ? ' winner' : '') + '">' +
      '<span class="mt-name">' + esc(teamName(tid)) + '</span>' +
      '<span class="mt-score">' + wins + '</span>' +
      (win ? '<span class="badge badge-gold">胜</span>' : '') + '</div>';
  }

  /* ==================== 14. 比赛流 ==================== */

  function newLive(matchId) {
    return {
      matchId: matchId,
      roundNum: 1,
      step: 'vs',        // vs → prophet → lineupA → lineupB → sync → roll → rollReveal →(B 攻后)→ verdict → matchEnd
      attacker: 'A',
      sync: { captainReady: false, pitcherReady: false, skipped: false },
      prophet: { A: null, B: null },
      lineups: { A: null, B: null },
      lastLineups: { A: null, B: null },
      rolls: { A: null, B: null },
      attacks: null,
      prophetResult: { A: false, B: false },
      playerActions: {},
      roundWinner: null
    };
  }

  function startMatch(id) {
    var rec = state.matches[id];
    if (!rec) return;
    if (rec.status !== 'active' || !state.live || state.live.matchId !== id) {
      rec.status = 'active';
      state.currentMatchId = id;
      state.live = newLive(id);
      addLog('【' + matchLabel(rec) + '】' + teamName(rec.a) + ' vs ' + teamName(rec.b) + ' 开战！');
    }
    state.screen = 'match';
    save();
    render();
  }

  /** 步骤切换统一入口：清计时器 → 改步骤 → 存档 → 渲染 */
  function goStep(step) {
    clearTimers();
    state.live.step = step;
    save();
    render();
  }

  function renderMatch(root) {
    var live = state.live, rec = state.matches[live.matchId];
    var ta = teamById(rec.a), tb = teamById(rec.b);
    root.innerHTML = '<section class="screen match-screen">' + matchHeaderHTML(rec, ta, tb) + '<div id="match-body"></div></section>';
    var body = $('#match-body');
    switch (live.step) {
      case 'vs': stepVs(body, rec, ta, tb); break;
      case 'prophet': stepProphet(body, rec, ta, tb); break;
      case 'lineupA': stepLineup(body, ta, 'A'); break;
      case 'lineupB': stepLineup(body, tb, 'B'); break;
      case 'sync': stepSync(body, rec); break;
      case 'roll': stepRoll(body, rec); break;
      case 'rollReveal': stepRollReveal(body, rec); break;
      case 'verdict': stepVerdict(body, rec); break;
      case 'matchEnd': stepMatchEnd(body, rec); break;
    }
  }

  function matchHeaderHTML(rec, ta, tb) {
    var live = state.live;
    var la = quotaLeft(ta.id), lb = quotaLeft(tb.id);
    return '<div class="match-head card">' +
      '<div class="mh-team"><div class="mh-name">' + esc(ta.name) + '</div>' +
      '<div class="mh-meta">剩余配额 ' + Math.max(0, la) + (la <= 0 ? ' <span class="badge badge-red">疲劳</span>' : '') + '</div></div>' +
      '<div class="mh-center"><div class="mh-label">' + matchLabel(rec) + ' · ' + stageCn(rec.round) + ' · 第 ' + live.roundNum + ' 局</div>' +
      '<div class="mh-score">' + rec.winsA + ' : ' + rec.winsB + '</div>' +
      '<div class="mh-best">三局两胜（先胜 ' + state.config.matchWinRounds + ' 局）· 平局加赛</div></div>' +
      '<div class="mh-team right"><div class="mh-name">' + esc(tb.name) + '</div>' +
      '<div class="mh-meta">剩余配额 ' + Math.max(0, lb) + (lb <= 0 ? ' <span class="badge badge-red">疲劳</span>' : '') + '</div></div>' +
      '</div>';
  }

  /* ---------- 14.1 对阵展示 ---------- */

  function stepVs(body, rec, ta, tb) {
    body.innerHTML =
      '<div class="vs-row">' + vsTeamCard(ta, rec) + '<div class="vs-badge">VS</div>' + vsTeamCard(tb, rec) + '</div>' +
      '<div class="footer-actions"><button id="btn-vs-go" class="btn btn-primary btn-xl">开始第 ' + state.live.roundNum + ' 局 →</button></div>';
    $('#btn-vs-go').onclick = function () { Snd.click(); goStep('prophet'); };
  }

  function vsTeamCard(t) {
    return '<div class="card vs-team">' +
      '<div class="vs-team-name">' + esc(t.name) + '</div>' +
      '<div class="vs-team-sub">' + esc(t.shortName) + '</div>' +
      '<div class="vs-team-stats">当日 GMV ' + fmtMoney(t.gmv[dayKey()]) + '</div>' +
      '<div class="vs-team-stats">当日增长率 ' + fmtGrowth(t.growth[dayKey()]) + '</div>' +
      '<div class="vs-team-stats">掷骰配额 ' + quotaOf(t.id) + ' 次</div></div>';
  }

  /* ---------- 14.2 军师预言 ---------- */

  function stepProphet(body, rec, ta, tb) {
    var pa = state.live.playerActions || {};
    body.innerHTML = '<div class="card player-wait-card"><div class="role-ribbon">玩家操作阶段</div>' +
      '<h2>等待双方军师提交预言</h2><p>预言内容由队伍玩家在自己的队伍大厅选择并密封提交，主持人无法查看或代填。</p>' +
      '<div class="player-action-status"><span class="' + (pa.prophetA ? 'done' : '') + '">' + esc(ta.name) + ' · ' + (pa.prophetA ? '已提交' : '等待中') + '</span>' +
      '<span class="' + (pa.prophetB ? 'done' : '') + '">' + esc(tb.name) + ' · ' + (pa.prophetB ? '已提交' : '等待中') + '</span></div></div>';
    return;
    ui.prophetSel = { A: [], B: [], skipA: false, skipB: false };
    body.innerHTML =
      '<div class="card"><div class="card-head"><span>🕵️ 军师预言（可选）—— 猜对方本局出战 5 人，全中本队 +' + state.config.prophetBonus + ' 攻击</span>' +
      '<span class="head-hint">须在阵容录入前完成 · 开盅判分时揭晓</span></div>' +
      '<div class="prophet-cols">' +
      prophetPanelHTML('A', ta, tb) +
      prophetPanelHTML('B', tb, ta) +
      '</div>' +
      '<div class="footer-actions">' +
      '<button id="btn-prophet-skip" class="btn btn-ghost">两队都不预言，直接跳过</button>' +
      '<button id="btn-prophet-ok" class="btn btn-primary btn-xl" disabled>确认预言，进入阵容选择 →</button>' +
      '</div></div>';
    bindProphetPanel('A');
    bindProphetPanel('B');
    $('#btn-prophet-skip').onclick = function () {
      Snd.click();
      state.live.prophet = { A: null, B: null };
      addLog('【' + matchLabel(rec) + ' 第' + state.live.roundNum + '局】双方军师均放弃预言');
      goStep('lineupA');
    };
    $('#btn-prophet-ok').onclick = function () {
      var ps = ui.prophetSel;
      var gA = ps.skipA ? null : ps.A.slice();
      var gB = ps.skipB ? null : ps.B.slice();
      state.live.prophet = { A: gA, B: gB };
      var msg = '【' + matchLabel(rec) + ' 第' + state.live.roundNum + '局】军师预言已封存：' +
        (gA ? ta.name + '军师猜 ' + gA.length + ' 人' : ta.name + '军师放弃') + '；' +
        (gB ? tb.name + '军师猜 ' + gB.length + ' 人' : tb.name + '军师放弃');
      addLog(msg);
      Snd.click();
      goStep('lineupA');
    };
  }

  function prophetPanelHTML(side, mine, opp) {
    var h = '<div class="prophet-panel" data-side="' + side + '">' +
      '<div class="pp-title">' + esc(mine.name) + ' 军师 → 猜 <b class="gold-text">' + esc(opp.name) + '</b> 出战阵容</div>' +
      '<div class="pp-tools"><label class="pp-skip"><input type="checkbox" class="pp-skip-cb"> 放弃预言</label>' +
      '<span class="pp-count">已选 0 / 5</span></div>' +
      '<div class="player-grid">';
    for (var i = 0; i < opp.players.length; i++) {
      var p = opp.players[i];
      h += '<div class="chip player-chip" data-pid="' + p.id + '">' +
        '<span class="pc-name">' + esc(p.name) + '</span>' +
        '<span class="pc-tag ' + (p.role === 'back' ? 'tag-back' : 'tag-front') + '">' + (p.role === 'back' ? '后端' : '前端') + '</span>' +
        '</div>';
    }
    return h + '</div></div>';
  }

  function bindProphetPanel(side) {
    var panel = $('#match-body .prophet-panel[data-side="' + side + '"]');
    var ps = ui.prophetSel;
    var cb = panel.querySelector('.pp-skip-cb');
    Array.prototype.slice.call(panel.querySelectorAll('.player-chip')).forEach(function (chip) {
      chip.onclick = function () {
        if (cb.checked) return;
        var pid = chip.getAttribute('data-pid');
        var arr = ps[side];
        var ix = arr.indexOf(pid);
        if (ix >= 0) { arr.splice(ix, 1); chip.classList.remove('selected'); }
        else {
          if (arr.length >= 5) { Snd.deny(); return; }
          arr.push(pid);
          chip.classList.add('selected');
          Snd.click();
        }
        updateProphetPanel(side);
      };
    });
    cb.onchange = function () {
      ps[side === 'A' ? 'skipA' : 'skipB'] = cb.checked;
      panel.classList.toggle('skipped', cb.checked);
      updateProphetPanel(side);
    };
  }

  function updateProphetPanel(side) {
    var panel = $('#match-body .prophet-panel[data-side="' + side + '"]');
    var ps = ui.prophetSel;
    panel.querySelector('.pp-count').textContent = '已选 ' + ps[side].length + ' / 5';
    var okA = ps.skipA || ps.A.length === 5;
    var okB = ps.skipB || ps.B.length === 5;
    $('#btn-prophet-ok').disabled = !(okA && okB);
  }

  /* ---------- 14.3 出战阵容选择 ---------- */

  function stepLineup(body, team, side) {
    body.innerHTML = '<div class="card player-wait-card"><div class="role-ribbon">队长操作阶段</div>' +
      '<h2>等待 ' + esc(team.name) + ' 提交出战阵容</h2><p>本局 5 人阵容由该队玩家在队伍大厅选择，至少包含 1 名后端队员。主持人只负责核对赛程。</p>' +
      '<div class="signal-pulse"><i></i>等待队伍提交</div></div>';
    return;
    ui.lineupSel = [];
    var last = state.live.lastLineups[side];
    var h = '<div class="card"><div class="card-head"><span>⚔️ ' + esc(team.name) + ' 选择本局出战阵容（5 人，至少 1 名后端）</span>' +
      '<span class="head-hint">第 ' + state.live.roundNum + ' 局 · 队长现场点将，主持人录入</span></div>' +
      '<div class="lineup-tools">' +
      '<span class="pp-count" id="lineup-count">已选 0 / 5</span>' +
      '<span id="lineup-need" class="badge badge-red">还需至少 1 名后端</span>' +
      (last ? '<button id="btn-last-lineup" class="btn btn-ghost">沿用上局阵容</button>' : '') +
      '<button id="btn-lineup-clear" class="btn btn-ghost">清空</button></div>' +
      '<div class="player-grid lineup-grid">';
    for (var i = 0; i < team.players.length; i++) {
      var p = team.players[i];
      var extra = '';
      if (p.id === team.captainId) extra = '<span class="pc-tag tag-cap">队长</span>';
      else if (p.id === team.viceCaptainId) extra = '<span class="pc-tag tag-vice">副队</span>';
      h += '<div class="chip player-chip" data-pid="' + p.id + '">' +
        '<span class="pc-name">' + esc(p.name) + '</span>' +
        '<span class="pc-tag ' + (p.role === 'back' ? 'tag-back' : 'tag-front') + '">' + (p.role === 'back' ? '后端' : '前端') + '</span>' +
        extra + '</div>';
    }
    h += '</div><div class="footer-actions">' +
      '<button id="btn-lineup-ok" class="btn btn-primary btn-xl" disabled>确认 ' + esc(team.shortName) + ' 队阵容 →</button>' +
      '</div></div>';
    body.innerHTML = h;

    Array.prototype.slice.call(body.querySelectorAll('.player-chip')).forEach(function (chip) {
      chip.onclick = function () {
        var pid = chip.getAttribute('data-pid');
        var arr = ui.lineupSel;
        var ix = arr.indexOf(pid);
        if (ix >= 0) { arr.splice(ix, 1); chip.classList.remove('selected'); }
        else {
          if (arr.length >= 5) { Snd.deny(); return; }
          arr.push(pid);
          chip.classList.add('selected');
          Snd.click();
        }
        updateLineupUI(team);
      };
    });
    $('#btn-lineup-clear').onclick = function () {
      ui.lineupSel = [];
      Array.prototype.slice.call(body.querySelectorAll('.player-chip')).forEach(function (c) { c.classList.remove('selected'); });
      updateLineupUI(team);
    };
    var lb = $('#btn-last-lineup');
    if (lb) lb.onclick = function () {
      ui.lineupSel = last.slice();
      Array.prototype.slice.call(body.querySelectorAll('.player-chip')).forEach(function (c) {
        c.classList.toggle('selected', last.indexOf(c.getAttribute('data-pid')) >= 0);
      });
      Snd.click();
      updateLineupUI(team);
    };
    $('#btn-lineup-ok').onclick = function () {
      var sel = ui.lineupSel.slice();
      state.live.lineups[side] = sel;
      state.live.lastLineups[side] = sel;
      Snd.click();
      if (side === 'A') { goStep('lineupB'); }
      else {
        state.live.attacker = 'A';
        state.live.sync = { captainReady: false, pitcherReady: false, skipped: false };
        goStep('sync');
      }
    };
  }

  function updateLineupUI(team) {
    var sel = ui.lineupSel;
    $('#lineup-count').textContent = '已选 ' + sel.length + ' / 5';
    var hasBack = sel.some(function (pid) { var p = playerById(team, pid); return p && p.role === 'back'; });
    var need = $('#lineup-need');
    if (hasBack) { need.className = 'badge badge-green'; need.textContent = '已含后端'; }
    else { need.className = 'badge badge-red'; need.textContent = '还需至少 1 名后端'; }
    $('#btn-lineup-ok').disabled = !(sel.length === 5 && hasBack);
  }

  /* ---------- 14.4 默契确认 ---------- */

  function stepSync(body, rec) {
    var live = state.live, side = live.attacker;
    var team = teamById(side === 'A' ? rec.a : rec.b);
    var cap = playerById(team, team.captainId);
    var pitId = pitcherOf(team, live.lineups[side]);
    var s = live.sync;
    body.innerHTML = '<div class="card player-wait-card"><div class="role-ribbon">队伍确认阶段</div>' +
      '<h2>' + esc(team.name) + ' 正在完成进攻确认</h2><p>队长发出“准备进攻”，投手确认“收到”后，主持区才会进入发令环节。</p>' +
      '<div class="player-action-status"><span class="' + (s.captainReady ? 'done' : '') + '">队长 · ' + (s.captainReady ? '已准备' : '等待确认') + '</span>' +
      '<span class="' + (s.pitcherReady ? 'done' : '') + '">投手 · ' + (s.pitcherReady ? '已收到' : '等待确认') + '</span></div></div>';
    return;
    body.innerHTML =
      '<div class="card sync-card">' +
      '<div class="sync-title">🤝 默契确认 · 当前进攻方：<b class="gold-text">' + esc(team.name) + '</b>' +
      '<span class="head-hint">（第 ' + live.roundNum + ' 局 · ' + (side === 'A' ? '先攻' : '后攻') + '）</span></div>' +
      '<div class="sync-flow">' +
      '<div class="sync-node' + (s.captainReady ? ' done' : '') + '"><div class="sn-role">队长 ' + esc(cap ? cap.name : '') + '</div><div class="sn-act">① 准备进攻</div></div>' +
      '<div class="sync-arrow">→</div>' +
      '<div class="sync-node' + (s.pitcherReady ? ' done' : '') + '"><div class="sn-role">王牌投手 ' + esc(playerName(team, pitId)) + '</div><div class="sn-act">② 收到</div></div>' +
      '<div class="sync-arrow">→</div>' +
      '<div class="sync-node"><div class="sn-role">掷骰区</div><div class="sn-act">③ 解锁</div></div>' +
      '</div>' +
      '<div class="footer-actions">' +
      '<button id="btn-cap-ready" class="btn btn-primary" ' + (s.captainReady ? 'disabled' : '') + '>【队长】准备进攻</button>' +
      '<button id="btn-pit-ack" class="btn btn-primary" ' + (s.captainReady && !s.pitcherReady ? '' : 'disabled') + '>【投手】收到</button>' +
      '<button id="btn-sync-skip" class="btn btn-ghost">跳过确认（等待 3 秒）</button>' +
      '</div>' +
      '<div class="hint-bar">依次点击队长与投手按钮完成默契确认即解锁掷骰；跳过确认将强制倒计时 3 秒后解锁。</div></div>';

    $('#btn-cap-ready').onclick = function () { Snd.click(); s.captainReady = true; save(); render(); };
    $('#btn-pit-ack').onclick = function () { Snd.go(); s.pitcherReady = true; goStep('roll'); };
    $('#btn-sync-skip').onclick = function () {
      Snd.click();
      Array.prototype.slice.call(body.querySelectorAll('.footer-actions .btn')).forEach(function (b) { b.disabled = true; });
      runCountdown(['3', '2', '1'], function () {
        s.skipped = true;
        s.pitcherReady = true;
        goStep('roll');
      }, '跳过默契确认');
    };
  }

  /* ---------- 14.5 同步掷骰（双模式） ---------- */

  function stepRoll(body, rec) {
    var live = state.live, side = live.attacker;
    var tid = side === 'A' ? rec.a : rec.b;
    var team = teamById(tid);
    stepRollOnline(body, rec, team, tid);
  }

  function dieHTML(cls, value) {
    var pips = '';
    for (var i = 0; i < 9; i++) pips += '<i></i>';
    return '<span class="die ' + (cls || '') + '" data-v="' + (value || 0) + '">' + pips + '<span class="die-q">?</span></span>';
  }

  /* ----- 单机键盘模式 ----- */

  function stepRollLocal(body, rec, team) {
    var live = state.live, side = live.attacker;
    var lineup = live.lineups[side];
    var pitId = pitcherOf(team, lineup);
    var h = '<div class="card roll-card">' +
      '<div class="sync-title">🎲 同步掷骰 · <span class="badge badge-blue">单机键盘模式</span> 当前进攻方：<b class="gold-text">' + esc(team.name) + '</b></div>' +
      '<div class="hint-bar">王牌投手 <b>' + esc(playerName(team, pitId)) + '</b> 点击【下达口令】→ 大屏 3-2-1-GO → 5 名队员各按键盘 <b>1 / 2 / 3 / 4 / 5</b>（或点击大骰子）各掷 1 骰；3 秒内全部掷出，首尾时差 ≤' + state.config.syncWindowMs + 'ms 得同步 ×' + state.config.syncMultiplier + '；超时未掷的骰子自动补掷且同步失败。</div>' +
      '<div class="roll-zone" id="roll-zone">';
    for (var i = 0; i < 5; i++) {
      var p = playerById(team, lineup[i]);
      h += '<div class="roll-slot" data-idx="' + i + '">' +
        '<div class="rs-player"><span class="rs-key">' + (i + 1) + '</span>' + esc(p ? p.name : '') + '</div>' +
        '<button class="die big roll-die" data-v="0" data-idx="' + i + '" disabled><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><i></i><span class="die-q">' + (i + 1) + '</span></button>' +
        '<div class="rs-state">待掷</div></div>';
    }
    h += '</div>' +
      '<div class="roll-timer"><i id="roll-timer-bar"></i></div>' +
      '<div class="online-bar"><span id="roll-status" class="online-status">等待投手下令</span></div>' +
      '<div class="footer-actions">' +
      '<button id="btn-order" class="btn btn-primary btn-xl">📣 下达口令</button>' +
      (net.online ? '<button id="btn-mode-toggle" class="btn btn-ghost">切换为联机模式</button>' : '') +
      '</div></div>';
    body.innerHTML = h;

    Array.prototype.slice.call(body.querySelectorAll('.roll-die')).forEach(function (d) {
      d.onclick = function () { rollOneLocal(+d.getAttribute('data-idx')); };
    });
    var mt = $('#btn-mode-toggle');
    if (mt) mt.onclick = function () { Snd.click(); ui.forceLocal = false; render(); };
    $('#btn-order').onclick = function () {
      Snd.click();
      this.disabled = true;
      runCountdown(['3', '2', '1', 'GO!'], function () {
        live.rolls[side] = { mode: 'local', dice: [null, null, null, null, null], times: [null, null, null, null, null], started: true, done: false, syncOk: false };
        save();
        ui.rollLive = true;
        ui.rollT0 = performance.now();
        Array.prototype.slice.call(body.querySelectorAll('.roll-die')).forEach(function (d) { d.disabled = false; d.classList.add('armed'); });
        var bar = $('#roll-timer-bar');
        if (bar) { bar.classList.remove('go'); void bar.offsetWidth; bar.classList.add('go'); }
        var st = $('#roll-status');
        if (st) st.textContent = 'GO！请 5 名队员同时出手（3 秒窗口）';
        later(finalizeLocalRoll, 3000);
      });
    };
  }

  function rollOneLocal(idx) {
    if (!ui.rollLive) return;
    var live = state.live;
    if (!live || live.step !== 'roll') return;
    var roll = live.rolls[live.attacker];
    if (!roll || roll.mode !== 'local' || roll.done) return;
    if (roll.dice[idx] != null) return;
    var v = GE.rollDie();
    roll.dice[idx] = v;
    roll.times[idx] = Math.round(performance.now() - ui.rollT0);
    Snd.roll();
    var slot = $('#roll-zone .roll-slot[data-idx="' + idx + '"]');
    if (slot) {
      tumbleDie(slot.querySelector('.die'), v);
      slot.classList.add('done');
      var st = slot.querySelector('.rs-state');
      if (st) st.textContent = '已掷';
    }
    var all = true;
    for (var i = 0; i < 5; i++) if (roll.dice[i] == null) { all = false; break; }
    if (all) { ui.rollLive = false; later(finalizeLocalRoll, 500); }
  }

  function finalizeLocalRoll() {
    var live = state.live;
    if (!live || live.step !== 'roll') return;
    var side = live.attacker;
    var roll = live.rolls[side];
    if (!roll || roll.mode !== 'local' || roll.done) return;
    ui.rollLive = false;
    var auto = false;
    for (var i = 0; i < 5; i++) {
      if (roll.dice[i] == null) { roll.dice[i] = GE.rollDie(); roll.times[i] = null; auto = true; }
    }
    roll.autoFilled = auto;
    if (!auto) roll.spreadMs = Math.max.apply(null, roll.times) - Math.min.apply(null, roll.times);
    else roll.spreadMs = 999999;
    roll.syncOk = !auto && roll.spreadMs <= state.config.syncWindowMs;
    finalizeRollCommon(side, roll);
  }

  function tumbleDie(die, finalV) {
    if (!die) return;
    die.disabled = true;
    die.classList.remove('armed');
    die.classList.add('tumbling');
    var n = 0;
    var iv = setInterval(function () {
      n++;
      die.setAttribute('data-v', String(1 + Math.floor(Math.random() * 6)));
      if (n >= 6) {
        clearInterval(iv);
        die.setAttribute('data-v', String(finalV));
        die.classList.remove('tumbling');
      }
    }, 80);
  }

  /* ----- 联机模式 ----- */

  function stepRollOnline(body, rec, team, tid) {
    var h = '<div class="card roll-card">' +
      '<div class="sync-title">🎲 同步掷骰 · <span class="badge badge-green">联机模式 · 时钟已校准</span> 当前进攻方：<b class="gold-text">' + esc(team.name) + '</b></div>' +
      '<div class="hint-bar">① 点击【通知就位】→ 该队 5 台设备自动复校时钟并就位；② 确认槽位状态后点击【下达口令】→ 大屏 3-2-1 倒计时 → 队员听队长口令各自点击【掷！】→ 服务器按校准后的时间轴判定时差并开盅。缺口骰子将自动补掷且同步失败。</div>' +
      '<div class="online-slots" id="online-slots">' + onlineSlotsHTML(tid, null) + '</div>' +
      '<div class="online-bar"><span id="online-count" class="badge badge-green">' + onlineCalibText(tid) + '</span>' +
      '<span id="online-status" class="online-status">等待主持人操作</span></div>' +
      '<div class="roll-timer"><i id="roll-timer-bar"></i></div>' +
      '<div class="footer-actions">' +
      '<button id="btn-arm" class="btn btn-primary">📣 通知就位</button>' +
      '<button id="btn-host-go" class="btn btn-primary btn-xl">⚡ 下达口令</button>' +
      '<button id="btn-online-reset" class="btn btn-ghost">重置联机掷骰</button>' +
      '</div></div>';
    body.innerHTML = h;
    ui.onlinePhase = 'idle';
    refreshState();
    if (ui.stateTicker) clearInterval(ui.stateTicker);
    ui.stateTicker = setInterval(refreshState, 2500);

    $('#btn-arm').onclick = function () {
      Snd.click();
      var btn = this;
      btn.disabled = true;
      api('/api/arm', { teamId: tid }).then(function () {
        var st = $('#online-status');
        if (st) st.textContent = '已通知 ' + team.name + ' 就位，等待设备校准…';
        refreshState();
      }).catch(function () {
        var st = $('#online-status');
        if (st) st.innerHTML = '<span class="badge badge-red">通知失败，请检查服务器</span>';
      }).then(function () { btn.disabled = false; });
    };
    $('#btn-host-go').onclick = function () { hostGoOnline(tid); };
    $('#btn-online-reset').onclick = function () {
      Snd.click();
      api('/api/reset', {}).then(function () {
        ui.onlinePhase = 'idle';
        var st = $('#online-status');
        if (st) st.textContent = '已重置，可重新下达口令';
        var g = $('#btn-host-go');
        if (g) g.disabled = false;
      }).catch(function () { Snd.deny(); });
    };
  }

  function slotMapFor(tid) {
    var m = {};
    for (var i = 0; i < net.devices.length; i++) {
      var d = net.devices[i];
      if (d.teamId === tid && d.slot >= 1 && d.slot <= 5) m[d.slot] = d;
    }
    return m;
  }

  function onlineSlotsHTML(tid, rolledSlots) {
    var m = slotMapFor(tid);
    var h = '';
    for (var s = 1; s <= 5; s++) {
      var d = m[s];
      var cls = 'offline', st = '未加入', nm = '槽位 ' + s;
      if (d) {
        nm = d.name || nm;
        if (rolledSlots && rolledSlots[s]) { cls = 'rolled'; st = '已出手 🎲'; }
        else if (d.calibrated) { cls = 'calibrated'; st = '已校准' + (d.rtt != null ? ' · ' + d.rtt + 'ms' : ''); }
        else { cls = 'joined'; st = '已加入 · 校准中…'; }
      }
      h += '<div class="slot-card ' + cls + '">' +
        '<div class="slot-num">' + s + '</div>' +
        '<div class="slot-name">' + esc(nm) + '</div>' +
        '<div class="slot-status">' + esc(st) + '</div></div>';
    }
    return h;
  }

  function onlineCalibText(tid) {
    var m = slotMapFor(tid), n = 0;
    for (var s = 1; s <= 5; s++) if (m[s] && m[s].calibrated) n++;
    return n + ' / 5 已校准';
  }

  function updateOnlineSlots() {
    var grid = $('#online-slots');
    if (!grid || !state.live) return;
    var rec = state.matches[state.live.matchId];
    var tid = state.live.attacker === 'A' ? rec.a : rec.b;
    grid.innerHTML = onlineSlotsHTML(tid, null);
    var cnt = $('#online-count');
    if (cnt) cnt.textContent = onlineCalibText(tid);
  }

  function hostGoOnline(tid) {
    var btn = $('#btn-host-go');
    if (btn) btn.disabled = true;
    Snd.click();
    runCountdown(['3', '2', '1'], function () {
      api('/api/go', {}).then(function (res) {
        ui.onlinePhase = 'rolling';
        net.goTs = (res && res.goTs) || Date.now();
        var st = $('#online-status');
        if (st) st.textContent = 'GO！队员掷骰中…等待开盅（3 秒）';
        var bar = $('#roll-timer-bar');
        if (bar) { bar.classList.remove('go'); void bar.offsetWidth; bar.classList.add('go'); }
        later(function () {
          // 6 秒兜底：未收到 reveal 广播
          if (state.screen === 'match' && state.live && state.live.step === 'roll' && ui.onlinePhase === 'rolling') {
            ui.onlinePhase = 'error';
            var st2 = $('#online-status');
            if (st2) st2.innerHTML = '<span class="badge badge-red">未收到开盅结果</span> 可点【重置联机掷骰】重试，或切换单机模式';
            var g = $('#btn-host-go');
            if (g) g.disabled = false;
            Snd.deny();
          }
        }, 6000);
      }).catch(function () {
        ui.onlinePhase = 'error';
        var st = $('#online-status');
        if (st) st.innerHTML = '<span class="badge badge-red">口令发送失败，请检查服务器</span>';
        var g = $('#btn-host-go');
        if (g) g.disabled = false;
        Snd.deny();
      });
    }, '同步掷骰 · ' + teamName(tid));
  }

  function handleOnlineReveal(msg) {
    var live = state.live;
    if (!live || live.step !== 'roll') return;   // 非等待状态（如刷新后）忽略
    var side = live.attacker;
    var rec = state.matches[live.matchId];
    var tid = side === 'A' ? rec.a : rec.b;
    if (msg.teamId !== tid) return;              // 不是当前进攻方的开盅
    var dice = [null, null, null, null, null];
    var ts = [null, null, null, null, null];
    var early = [false, false, false, false, false];
    var rolledSlots = {};
    var list = msg.dice || [];
    for (var i = 0; i < list.length; i++) {
      var d = list[i];
      var ix = d.slot - 1;
      if (ix >= 0 && ix < 5) {
        dice[ix] = d.die;
        ts[ix] = d.ts;
        early[ix] = !!d.early;
        rolledSlots[d.slot] = true;
      }
    }
    var missing = false;
    for (i = 0; i < 5; i++) {
      if (dice[i] == null || ts[i] == null) {
        if (dice[i] == null) dice[i] = GE.rollDie();   // 缺口自动补掷
        ts[i] = null;
        missing = true;
      }
    }
    var spreadMs = (!missing && typeof msg.spreadMs === 'number') ? msg.spreadMs : 999999;
    var roll = {
      mode: 'online',
      dice: dice,
      ts: ts,
      early: early,
      missing: missing,
      spreadMs: spreadMs,
      syncOk: !missing && !!msg.syncOk,
      started: true,
      done: false
    };
    finalizeRollCommon(side, roll);
  }

  /* ----- 掷骰收尾（两模式共用）：消耗配额 + 疲劳判定 + 存档 ----- */

  function finalizeRollCommon(side, roll) {
    var live = state.live;
    if (!live) return;
    var rec = state.matches[live.matchId];
    var tid = side === 'A' ? rec.a : rec.b;
    roll.fatigued = quotaLeft(tid) <= 0;
    state.quotaUsed[tid][dayKey()] += 1;
    roll.done = true;
    live.rolls[side] = roll;
    ui.animateDice = true;
    clearTimers();
    live.step = 'rollReveal';
    save();
    render();
  }

  /* ---------- 14.6 单方开盅展示 ---------- */

  function stepRollReveal(body, rec) {
    var live = state.live, side = live.attacker;
    var team = teamById(side === 'A' ? rec.a : rec.b);
    var roll = live.rolls[side];
    var leopard = GE.isLeopard(roll.dice);
    var animate = ui.animateDice;
    ui.animateDice = false;

    var h = '<div class="card reveal-side-card">' +
      '<div class="rs-title">🎲 开盅 · ' + esc(team.name) + '</div>' +
      '<div class="reveal-dice">';
    for (var i = 0; i < 5; i++) {
      var filled = roll.mode === 'local'
        ? (roll.autoFilled && roll.times[i] == null)
        : (roll.missing && (!roll.ts || roll.ts[i] == null));
      var early = roll.mode === 'online' && roll.early && roll.early[i];
      h += '<div class="reveal-slot">' +
        dieHTML('big reveal-die', animate ? 0 : roll.dice[i]) +
        '<div class="rs-badges">' +
        (filled ? '<span class="badge badge-gray">补掷</span>' : '') +
        (early ? '<span class="badge badge-red">抢跑</span>' : '') +
        '</div></div>';
    }
    h += '</div><div class="reveal-meta">' +
      (roll.syncOk
        ? '<span class="badge badge-green big">同步成功 ×' + fmt1(state.config.syncMultiplier) + '</span>'
        : '<span class="badge badge-red big">同步失败</span>') +
      '<span class="meta-item">首尾时差：' + (roll.spreadMs >= 999999 ? '—（超时/补掷）' : roll.spreadMs + ' ms') + '</span>' +
      (leopard ? '<span class="badge badge-gold big">🐆 豹子！×' + state.config.leopardMultiplier + '</span>' : '') +
      (roll.fatigued ? '<span class="badge badge-red big">疲劳作战 ×' + state.config.fatigueMultiplier + '</span>' : '') +
      (roll.mode === 'online'
        ? '<span class="badge badge-blue">联机掷骰 · 时钟已校准</span>'
        : '<span class="badge badge-blue">单机键盘</span>') +
      '</div>' +
      '<div class="footer-actions"><button id="btn-roll-next" class="btn btn-primary btn-xl">' +
      (side === 'A' ? '对方队伍进攻 →' : '⚖ 开盅判分 →') +
      '</button></div></div>';
    body.innerHTML = h;

    if (animate) {
      var dice = $$('#match-body .reveal-die');
      dice.forEach(function (d, i) {
        later(function () {
          d.setAttribute('data-v', String(roll.dice[i]));
          d.classList.add('flip-in');
          Snd.roll();
          if (i === dice.length - 1 && leopard) later(Snd.leopard, 250);
        }, 260 * i + 250);
      });
    } else if (leopard) { Snd.leopard(); }

    $('#btn-roll-next').onclick = function () {
      Snd.click();
      if (side === 'A') {
        live.attacker = 'B';
        live.sync = { captainReady: false, pitcherReady: false, skipped: false };
        goStep('sync');
      } else {
        computeAttacks();
        ui.animateVerdict = true;
        goStep('verdict');
      }
    };
  }

  /* ---------- 14.7 开盅判分（双方） ---------- */

  function computeAttacks() {
    var live = state.live;
    if (live.attacks) return;   // 已算过（刷新恢复）
    var rec = state.matches[live.matchId];
    var hitA = live.prophet.A ? GE.checkProphetGuess(live.prophet.A, live.lineups.B) : false;
    var hitB = live.prophet.B ? GE.checkProphetGuess(live.prophet.B, live.lineups.A) : false;
    live.prophetResult = { A: hitA, B: hitB };
    var atkA = GE.computeAttack({
      dice: live.rolls.A.dice, growthCoef: growthOf(rec.a),
      spreadMs: live.rolls.A.spreadMs, prophetHit: hitA, fatigued: live.rolls.A.fatigued
    }, state.config);
    var atkB = GE.computeAttack({
      dice: live.rolls.B.dice, growthCoef: growthOf(rec.b),
      spreadMs: live.rolls.B.spreadMs, prophetHit: hitB, fatigued: live.rolls.B.fatigued
    }, state.config);
    live.attacks = { A: atkA, B: atkB };
    var w = GE.decideRound(atkA, atkB);
    live.roundWinner = w;
    if (w === 'A') rec.winsA++;
    else if (w === 'B') rec.winsB++;
    rec.rounds.push({
      n: live.roundNum,
      diceA: live.rolls.A.dice.slice(), diceB: live.rolls.B.dice.slice(),
      totalA: atkA.total, totalB: atkB.total, winner: w
    });
    addLog(roundLogText(rec));
    save();
  }

  function roundLogText(rec) {
    var live = state.live;
    var lbl = '【' + matchLabel(rec) + ' 第' + live.roundNum + '局】';
    var ta = teamById(rec.a), tb = teamById(rec.b);
    var A = live.attacks.A, B = live.attacks.B;
    function desc(team, atk, roll) {
      var s = team.name + ' 掷出 ' + roll.dice.join(',');
      if (atk.isLeopard) s += ' 豹子！';
      s += roll.syncOk ? ' 同步成功 ×' + fmt1(atk.syncMult) : ' 同步失败';
      if (!roll.syncOk && roll.spreadMs < 999999) s += '（时差 ' + roll.spreadMs + 'ms）';
      if (roll.autoFilled || roll.missing) s += '（含补掷）';
      if (atk.fatigueMult < 1) s += '（疲劳 ×' + fmt1(atk.fatigueMult) + '）';
      s += '，总攻击 ' + fmt1(atk.total);
      return s;
    }
    if (live.roundWinner === 'tie') {
      return lbl + desc(ta, A, live.rolls.A) + '；' + desc(tb, B, live.rolls.B) + ' —— 战平，加赛一局！';
    }
    var wT = live.roundWinner === 'A' ? ta : tb;
    var lT = live.roundWinner === 'A' ? tb : ta;
    var wA = live.roundWinner === 'A' ? A : B;
    var lA = live.roundWinner === 'A' ? B : A;
    var wR = live.roundWinner === 'A' ? live.rolls.A : live.rolls.B;
    var s = lbl + desc(wT, wA, wR) + '，击败 ' + lT.name;
    if (wA.isLeopard && !lA.isLeopard) s += '（单方豹子直接胜）';
    return s;
  }

  function stepVerdict(body, rec) {
    var live = state.live;
    if (!live.attacks) computeAttacks();
    var ta = teamById(rec.a), tb = teamById(rec.b);
    var A = live.attacks.A, B = live.attacks.B;
    var w = live.roundWinner;
    var animate = ui.animateVerdict;
    ui.animateVerdict = false;

    var h = '<div class="verdict-wrap"><div class="verdict-cols">' +
      verdictColHTML(ta, live.rolls.A, A, live.prophet.A, live.prophetResult.A, w === 'A') +
      '<div class="verdict-mid"><div class="vs-badge small">VS</div><div class="verdict-round">第 ' + live.roundNum + ' 局 · 判分</div></div>' +
      verdictColHTML(tb, live.rolls.B, B, live.prophet.B, live.prophetResult.B, w === 'B') +
      '</div>' +
      '<div id="verdict-banner" class="verdict-banner' + (animate ? '' : ' show') + '">' +
      (w === 'tie' ? '⚖ 平局！加赛一局' : esc((w === 'A' ? ta : tb).name) + ' 拿下本局！') +
      (w !== 'tie' && ((A.isLeopard && !B.isLeopard) || (B.isLeopard && !A.isLeopard))
        ? '<div class="vb-sub">单方豹子，直接获胜！</div>' : '') +
      '</div>' +
      '<div class="footer-actions">' +
      (animate ? '<button id="btn-verdict-skip" class="btn btn-ghost">跳过动画</button>' : '') +
      '<button id="btn-verdict-next" class="btn btn-primary btn-xl' + (animate ? ' hidden' : '') + '">' +
      verdictNextLabel(rec) + '</button></div></div>';
    body.innerHTML = h;

    if (animate) playVerdictAnim();
    else if (w === 'tie') Snd.flip(); else Snd.win();

    var skip = $('#btn-verdict-skip');
    if (skip) skip.onclick = function () { finishVerdictAnim(true); };
    $('#btn-verdict-next').onclick = function () { Snd.click(); nextRoundOrEnd(); };
  }

  function verdictColHTML(team, roll, atk, pguess, phit, isWin) {
    var rows = [
      ['骰子点数之和', String(atk.diceSum)],
      ['当日增长率', '+' + fmtGrowth(atk.growthCoef)],
      ['基础攻击', fmt1(atk.diceSum + atk.growthCoef)],
      ['同步增益', '×' + fmt1(atk.syncMult) + (roll.syncOk ? '（时差 ' + roll.spreadMs + 'ms）' : '（未达成）')],
      ['豹子暴击', atk.isLeopard ? ('×' + fmt1(atk.leopardMult) + ' 🐆') : '—'],
      ['疲劳作战', atk.fatigueMult < 1 ? ('×' + fmt1(atk.fatigueMult) + '（疲劳）') : '—'],
      ['军师预言', atk.prophetBonus > 0 ? ('+' + atk.prophetBonus + '（全中！）') : (pguess ? '未猜中' : '未预言')]
    ];
    var h = '<div class="card verdict-col' + (isWin ? ' win' : '') + '">' +
      '<div class="vc-head"><span class="vc-name">' + esc(team.name) + '</span>' +
      (isWin ? '<span class="badge badge-gold big">胜</span>' : '') + '</div>' +
      '<div class="vc-prophet">' +
      (pguess
        ? '军师预言：' + pguess.map(function (id) { return esc(id.slice(-3)); }).join('、') + (phit ? ' ✓ 全中' : ' ✗')
        : '军师未登记预言') +
      '</div>' +
      '<div class="vc-dice">';
    for (var i = 0; i < 5; i++) h += dieHTML('small', roll.dice[i]);
    h += '</div>';
    for (var r = 0; r < rows.length; r++) {
      h += '<div class="bd-row' + (r === rows.length - 1 ? '' : '') + '"><span>' + rows[r][0] + '</span><span class="bd-v">' + esc(rows[r][1]) + '</span></div>';
    }
    h += '<div class="bd-row total"><span>总攻击力</span><span class="bd-v">' + fmt1(atk.total) + '</span></div></div>';
    return h;
  }

  function verdictNextLabel(rec) {
    if (rec.winsA >= state.config.matchWinRounds || rec.winsB >= state.config.matchWinRounds) return '本场结束，宣布结果 →';
    if (state.live.roundWinner === 'tie') return '加赛一局 →';
    return '下一局 →';
  }

  function playVerdictAnim() {
    var rows = $$('#match-body .bd-row');
    rows.forEach(function (r, i) {
      later(function () { r.classList.add('lit'); Snd.flip(); }, 190 * i + 300);
    });
    later(function () { finishVerdictAnim(false); }, 190 * rows.length + 650);
  }

  function finishVerdictAnim(withSound) {
    $$('#match-body .bd-row').forEach(function (r) { r.classList.add('lit'); });
    var b = $('#verdict-banner');
    if (b && !b.classList.contains('show')) {
      b.classList.add('show');
      if (withSound !== false) {
        if (state.live.roundWinner === 'tie') Snd.flip(); else Snd.win();
      }
    }
    var nb = $('#btn-verdict-next');
    if (nb) nb.classList.remove('hidden');
    var sb = $('#btn-verdict-skip');
    if (sb) sb.classList.add('hidden');
  }

  /* ---------- 14.8 局推进 / 场结束 ---------- */

  function resetRound(live) {
    live.attacker = 'A';
    live.sync = { captainReady: false, pitcherReady: false, skipped: false };
    live.prophet = { A: null, B: null };
    live.lineups = { A: null, B: null };
    live.rolls = { A: null, B: null };
    live.attacks = null;
    live.prophetResult = { A: false, B: false };
    live.playerActions = {};
    live.roundWinner = null;
  }

  function nextRoundOrEnd() {
    var live = state.live, rec = state.matches[live.matchId];
    if (rec.winsA >= state.config.matchWinRounds || rec.winsB >= state.config.matchWinRounds) {
      finalizeMatch();
      goStep('matchEnd');
    } else {
      live.roundNum++;
      resetRound(live);
      goStep('prophet');
    }
  }

  function finalizeMatch() {
    var live = state.live, rec = state.matches[live.matchId];
    rec.status = 'done';
    rec.winner = rec.winsA > rec.winsB ? rec.a : rec.b;
    state.wins[rec.winner][dayKey()] += 1;
    advanceBracket(rec);
    var w = teamById(rec.winner), l = teamById(rec.winner === rec.a ? rec.b : rec.a);
    addLog('【' + matchLabel(rec) + '】' + w.name + ' ' + rec.winsA + ':' + rec.winsB + ' 击败 ' + l.name +
      '，' + (rec.round === 'f' ? '夺得当日总决赛冠军！' : '晋级' + (rec.round === 'qf' ? '半决赛' : '总决赛') + '！'));
    Snd.matchWin();
    state.currentMatchId = null;
  }

  function advanceBracket(rec) {
    if (rec.round === 'qf') {
      state.bracket.sf[Math.floor(rec.index / 2)][rec.index % 2] = rec.winner;
      ensureMatchRecord('sf', Math.floor(rec.index / 2));
    } else if (rec.round === 'sf') {
      state.bracket.f[0][rec.index] = rec.winner;
      ensureMatchRecord('f', 0);
    }
  }

  function stepMatchEnd(body, rec) {
    var w = teamById(rec.winner), l = teamById(rec.winner === rec.a ? rec.b : rec.a);
    body.innerHTML =
      '<div class="card matchend-card">' +
      '<div class="me-trophy">🏆</div>' +
      '<div class="me-title">' + esc(w.name) + '</div>' +
      '<div class="me-sub">' + matchLabel(rec) + ' 获胜 · ' + rec.winsA + ' : ' + rec.winsB + ' 击败 ' + esc(l.name) + '</div>' +
      '<div class="me-advance">' + (rec.round === 'f' ? '🎉 夺得当日总决赛冠军！' : '晋级 ' + (rec.round === 'qf' ? '半决赛' : '总决赛') + '！') + '</div>' +
      '<div class="footer-actions"><button id="btn-back-bracket" class="btn btn-primary btn-xl">返回对阵表 →</button></div></div>';
    Confetti.burst(180);
    $('#btn-back-bracket').onclick = function () {
      Snd.click();
      state.live = null;
      state.screen = 'bracket';
      save();
      render();
    };
  }

  /* ==================== 15. 积分榜 / 颁奖 ==================== */

  function standingsRows() {
    return state.teams.map(function (t) {
      return {
        teamId: t.id,
        winsDay1: state.wins[t.id].day1, winsDay2: state.wins[t.id].day2,
        gmvDay1: t.gmv.day1, gmvDay2: t.gmv.day2,
        growthDay1: t.growth.day1, growthDay2: t.growth.day2
      };
    });
  }

  /** 日冠军：当日胜场最多，并列比当日 GMV */
  function dayChampRow() {
    var k = 'winsDay' + state.day, g = 'gmvDay' + state.day;
    return standingsRows().sort(function (a, b) { return (b[k] - a[k]) || (b[g] - a[g]); })[0];
  }

  function enterStandings() {
    Snd.click();
    var first = !state.dayChampion[state.day];
    if (first) state.dayChampion[state.day] = dayChampRow().teamId;
    state.screen = 'standings';
    save();
    render();
    if (first) {
      Confetti.burst(220);
      Snd.champion();
      addLog('🏆 第 ' + state.day + ' 天日冠军：' + teamName(state.dayChampion[state.day]) + '！');
    }
  }

  function renderStandings(root) {
    var rows = standingsRows();
    var ranked = GE.standings(rows, state.config);
    var dayRows = rows.slice().sort(function (a, b) {
      var k = 'winsDay' + state.day, g = 'gmvDay' + state.day;
      return (b[k] - a[k]) || (b[g] - a[g]);
    });
    var champId = state.dayChampion[state.day];

    var dayTbl = '<table class="tbl"><thead><tr><th>名次</th><th>队伍</th><th>当日胜场</th><th>当日 GMV</th><th>当日增长率</th></tr></thead><tbody>';
    for (var i = 0; i < dayRows.length; i++) {
      var r = dayRows[i];
      dayTbl += '<tr class="' + (i === 0 ? 'rank-1' : '') + '"><td>' + (i + 1) + '</td><td>' + esc(teamName(r.teamId)) +
        (r.teamId === champId ? ' <span class="badge badge-gold">日冠军</span>' : '') + '</td>' +
        '<td>' + r['winsDay' + state.day] + '</td><td class="num-cell">' + fmtMoney(r['gmvDay' + state.day]) + '</td>' +
        '<td>' + fmtGrowth(r['growthDay' + state.day]) + '</td></tr>';
    }
    dayTbl += '</tbody></table>';

    var totalTbl = '<table class="tbl"><thead><tr><th>总排名</th><th>队伍</th><th>两天合计胜场</th><th>两天增长率和</th><th>两天 GMV 和</th></tr></thead><tbody>';
    for (i = 0; i < ranked.length; i++) {
      r = ranked[i];
      totalTbl += '<tr class="' + (r.rank === 1 ? 'rank-1' : '') + '"><td>' + r.rank + '</td><td>' + esc(teamName(r.teamId)) + '</td>' +
        '<td>' + r.totalWins + '</td><td>' + fmtGrowth(r.growthSum) + '</td>' +
        '<td class="num-cell">' + fmtMoney(r.gmvDay1 + r.gmvDay2) + '</td></tr>';
    }
    totalTbl += '</tbody></table>';

    root.innerHTML =
      '<section class="screen">' +
      '<h1 class="screen-title">第 ' + state.day + ' 天 · 积分榜</h1>' +
      '<div class="banner gold">👑 第 ' + state.day + ' 天日冠军：' + esc(teamName(champId)) + '</div>' +
      '<div class="standings-cols">' +
      '<div class="card"><div class="card-head"><span>当日积分榜（日冠军口径：当日胜场 → 当日 GMV）</span></div>' + dayTbl + '</div>' +
      '<div class="card"><div class="card-head"><span>总冠军累计榜（合计胜场 → 增长率和）</span></div>' + totalTbl + '</div>' +
      '</div>' +
      '<div class="footer-actions">' +
      '<button id="btn-back-bracket2" class="btn btn-ghost">返回对阵表</button>' +
      (state.day === 1
        ? '<button id="btn-day2" class="btn btn-primary btn-xl">进入第二天 →</button>'
        : '<button id="btn-final" class="btn btn-primary btn-xl">👑 进入总冠军颁奖 →</button>') +
      '</div></section>';

    $('#btn-back-bracket2').onclick = function () { Snd.click(); state.screen = 'bracket'; save(); render(); };
    var d2 = $('#btn-day2');
    if (d2) d2.onclick = function () {
      Snd.matchWin();
      state.day = 2;
      state.bracket = null;
      state.matches = {};
      state.live = null;
      state.currentMatchId = null;
      state.accumRevealed = 0;
      state.drawDone = false;
      state.screen = 'accum';
      addLog('—— 第 2 天开赛：切换 day2 GMV / 增长率 / 配额，重新抽签（累计胜场保留）——');
      save();
      render();
    };
    var bf = $('#btn-final');
    if (bf) bf.onclick = function () {
      Snd.champion();
      var r2 = GE.standings(standingsRows(), state.config);
      state.champion = r2[0].teamId;
      state.screen = 'champion';
      addLog('👑 总冠军诞生：' + teamName(state.champion) + '（两天合计胜场 ' + r2[0].totalWins + '，增长率和 ' + fmtGrowth(r2[0].growthSum) + '）！');
      save();
      render();
      Confetti.burst(300);
    };
  }

  function renderChampion(root) {
    var t = teamById(state.champion);
    var ranked = GE.standings(standingsRows(), state.config);
    var w = state.wins[t.id];
    var tbl = '<table class="tbl"><thead><tr><th>总排名</th><th>队伍</th><th>两天合计胜场</th><th>两天增长率和</th><th>两天 GMV 和</th></tr></thead><tbody>';
    for (var i = 0; i < ranked.length; i++) {
      var r = ranked[i];
      tbl += '<tr class="' + (r.rank === 1 ? 'rank-1' : '') + '"><td>' + r.rank + '</td><td>' + esc(teamName(r.teamId)) +
        (r.teamId === t.id ? ' <span class="badge badge-gold">总冠军</span>' : '') + '</td>' +
        '<td>' + r.totalWins + '</td><td>' + fmtGrowth(r.growthSum) + '</td>' +
        '<td class="num-cell">' + fmtMoney(r.gmvDay1 + r.gmvDay2) + '</td></tr>';
    }
    tbl += '</tbody></table>';

    root.innerHTML =
      '<section class="screen">' +
      '<div class="card champion-card">' +
      '<div class="champion-trophy">🏆</div>' +
      '<div class="champion-title">「王牌攻守擂 · 骰子大亨」总冠军</div>' +
      '<div class="champion-name">' + esc(t.name) + '</div>' +
      '<div class="champion-stats">' +
      '<span>两天合计胜场 <b class="gold-text">' + (w.day1 + w.day2) + '</b></span>' +
      '<span>两天增长率和 <b class="gold-text">' + fmtGrowth(t.growth.day1 + t.growth.day2) + '</b></span>' +
      '<span>两天 GMV <b class="gold-text">' + fmtMoney(t.gmv.day1 + t.gmv.day2) + '</b></span>' +
      '</div>' +
      '<div class="photo-placeholder"><span class="cam">📷</span>总冠军全队合影留念区 —— 请 ' + esc(t.name) + ' 全体队员上台合影！</div>' +
      '<div class="footer-actions">' +
      '<button id="btn-confetti" class="btn btn-primary">🎉 再撒一波花</button>' +
      '<button id="btn-restart2" class="btn btn-ghost">↺ 重新开始（清空全部进度）</button>' +
      '</div></div>' +
      '<div class="card"><div class="card-head"><span>最终总排名</span></div>' + tbl + '</div>' +
      '</section>';

    Confetti.burst(260);
    $('#btn-confetti').onclick = function () { Confetti.burst(260); Snd.champion(); };
    $('#btn-restart2').onclick = doRestart;
  }

  /* ==================== 16. 全局交互 ==================== */

  function doRestart() {
    if (!confirm('确定要清除全部进度并重新开始吗？（两天累计积分都会清空）')) return;
    clearTimers();
    try { localStorage.removeItem(SAVE_KEY); } catch (e) { }
    state = freshState();
    save();
    render();
  }

  document.addEventListener('keydown', function (e) {
    var tag = (e.target && e.target.tagName) || '';
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
    // 单机模式掷骰：数字键 1-5
    if (e.key >= '1' && e.key <= '5' && !e.repeat &&
      state.screen === 'match' && state.live && state.live.step === 'roll' && !onlineMode()) {
      e.preventDefault();
      rollOneLocal(+e.key - 1);
      return;
    }
    // Enter 触发当前屏主按钮
    if (e.key === 'Enter') {
      var b = document.querySelector('#screen-root .btn-primary:not([disabled]):not(.hidden)');
      if (b) { e.preventDefault(); b.click(); }
    }
  });

  /* ==================== 17. 启动 ==================== */

  state = load() || freshState();   // 服务端首次启动前保留本地副本作为快速兜底

  $('#btn-mute').onclick = function () {
    state.muted = !state.muted;
    save();
    renderTopbar();
    if (!state.muted) Snd.click();
  };
  $('#btn-restart').onclick = doRestart;
  $('#btn-logout').onclick = function () {
    fetch('/api/auth/logout', { method: 'POST' }).finally(function () {
      location.replace('/login.html?next=host');
    });
  };
  $('#btn-log').onclick = function () { $('#log-drawer').classList.toggle('open'); Snd.click(); };
  $('#btn-log-close').onclick = function () { $('#log-drawer').classList.remove('open'); };

  // 登录用户与完整赛程状态均从服务器读取；无服务端存档时才采用本地/初始状态。
  fetch('/api/auth/me').then(checkLoginResponse).then(function (r) { return r.json(); }).then(function (user) {
    loginUser = user;
    return fetch('/api/game-state').then(checkLoginResponse);
  }).then(function (r) {
    if (r.status === 204) return null;
    if (!r.ok) throw new Error('game state unavailable');
    return r.json();
  }).then(function (saved) {
    if (saved && saved.state && saved.state.version === 1 && saved.state.teams && saved.state.teams.length === 8) {
      state = saved.state;
      serverVersion = saved.version || 0;
    }
    sanitize();
    serverStateReady = true;
    save();
    render();
    return detectServer();
  }).then(function (ok) {
    if (ok) connectSSE();
    render();
  }).catch(function (err) {
    if (err && err.message === 'login required') return;
    sanitize();
    render();
    alert('服务器状态暂时不可用，当前显示浏览器中的最近副本。请检查服务端后刷新。');
  });
  setInterval(syncPlayerActions, 1200);

})();
