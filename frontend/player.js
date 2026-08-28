/**
 * 王牌攻守擂 · 骰子大亨 —— 队员掷骰端（player.html）
 * 纯原生 JS，无模块；依赖 engine.js 暴露的 window.GameEngine。
 * 流程：选战区 → 选位置 → 自动加入 + NTP 式时钟校准（后台无感）→ 就位等待 → 听口令点【掷！】→ 开盅展示。
 */
(function () {
  'use strict';

  /* ==================== 0. 环境检查 ==================== */

  var GE = window.GameEngine;
  if (!GE) {
    document.body.innerHTML =
      '<div style="max-width:640px;margin:100px auto;padding:28px;font-size:17px;line-height:1.8;color:#fff;background:#1a1030;border:1px solid #f6c453;border-radius:12px">' +
      '<h1 style="color:#f6c453">engine.js 未加载</h1><p>请确认 game/ 目录下存在 engine.js。</p></div>';
    return;
  }

  /** 往返延迟估算：偏移改由服务端计算，这里只取 rtt 最小的样本上报给服务端做单程延迟补偿 */
  var estimateOffset = GE.estimateClockOffset
    ? function (samples) { return GE.estimateClockOffset(samples); }
    : function (samples) {
        // NTP 式：offset = s - (c0 + c1) / 2，rtt = c1 - c0，取 rtt 最小的样本
        var best = null;
        for (var i = 0; i < samples.length; i++) {
          var sm = samples[i];
          var rtt = sm.c1 - sm.c0;
          var off = sm.s - (sm.c0 + sm.c1) / 2;
          if (!best || rtt < best.rtt) best = { offset: Math.round(off), rtt: rtt };
        }
        return best || { offset: 0, rtt: 0 };
      };

  /* ==================== 1. 小工具 ==================== */

  function $(s) { return document.querySelector(s); }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }

  function api(path, body) {
    return fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {})
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (responseBody) {
        if (!r.ok) {
          var e = new Error(responseBody.error || ('HTTP ' + r.status));
          e.status = r.status;
          throw e;
        }
        return responseBody;
      });
    });
  }

  function getState() {
    return fetch('/api/state').then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    });
  }

  /* ==================== 2. 状态 ==================== */

  var SKEY = 'dice-arena-player-v1';

  var my = loadMy();          // { teamId, slot, name, token }，sessionStorage 恢复掉线重进
  var loginUser = null;
  var rollAssignment = null;
  var teamCatalog = GE.MOCK_TEAMS;
  var ui = {
    screen: my ? 'main' : 'team',   // team / slot / main / error
    sub: 'standby',                 // main 内：calibrating / standby / countdown / go / rolled / reveal / kick
    teamId: my ? my.teamId : null,
    slot: my ? my.slot : 0,
    name: '',
    devices: [],
    armedTeam: null,
    goStarted: false,
    calibText: '',
    calibWarn: false,
    reveal: null,
    errText: ''
  };
  var es = null;

  function announceRule(rule, phase) {
    if (!window.GameRules || !rollAssignment) return;
    window.GameRules.announce(rule, [rollAssignment.gameId || 'game', rollAssignment.matchId || my.teamId, rollAssignment.round || 1, phase].join('-'));
  }

  function loadMy() {
    try {
      var raw = sessionStorage.getItem(SKEY);
      if (!raw) return null;
      var m = JSON.parse(raw);
      if (!m || !m.teamId || !m.slot || !m.token) return null;
      return m;
    } catch (e) { return null; }
  }
  function saveMy() {
    try { sessionStorage.setItem(SKEY, JSON.stringify(my)); } catch (e) { }
  }

  function teamInfo(tid) {
    var list = teamCatalog;
    for (var i = 0; i < list.length; i++) if (list[i].id === tid) return list[i];
    return { id: tid, name: tid, shortName: tid };
  }
  function myTeamName() { return my ? teamInfo(my.teamId).name : ''; }
  function defaultName(tid, slot) { return teamInfo(tid).shortName + '-' + pad2(slot); }

  /* ==================== 3. 网络：SSE + 校准 ==================== */

  function connectSSE() {
    if (es) { try { es.close(); } catch (e) { } es = null; }
    try { es = new EventSource('/api/events?token=' + encodeURIComponent(my.token)); } catch (e) { return; }
    es.onopen = function () { setNet('已连接服务器', false); refreshState(); };
    es.onerror = function () { setNet('连接中断，重连中…', true); };
    es.onmessage = function (ev) {
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
      case 'prepare':
        if (my && msg.teamId === my.teamId && ui.screen === 'main' && ui.sub !== 'calibrating') {
          ui.sub = 'standby'; render();
        }
        break;
      case 'countdown':
        if (my && msg.teamId === my.teamId && ui.screen === 'main') {
          ui.countdownAt = Number(msg.goTs); ui.sub = 'countdown'; render();
        }
        break;
      case 'arm':
        ui.armedTeam = msg.teamId;
        if (my && msg.teamId === my.teamId && ui.screen === 'main') {
          announceRule('TIMING', 'ROLL-' + my.teamId);
          ui.sub = 'armed';
          render();
          // 收到就位通知：后台自动快速复校 3 次并重新上报（队员无感）
          ui.calibText = '正在自动复校时钟…';
          renderStatus();
          calibrate(3, function () {
            ui.calibText = calibOkText();
            renderStatus();
          });
        }
        break;
      case 'go':
        if (msg.teamId && my && msg.teamId !== my.teamId) break;
        ui.goStarted = true;
        if (ui.screen === 'main' && (ui.sub === 'countdown' || ui.sub === 'standby' || ui.sub === 'rolled')) {
          ui.sub = 'go';
          render();
        }
        break;
      case 'reveal':
        if (ui.screen === 'main' && my && msg.teamId === my.teamId) {
          announceRule('RESULT', 'RESULT-' + my.teamId);
          ui.reveal = msg;
          ui.sub = 'reveal';
          render();
        }
        break;
      case 'timing-ready':
        if (ui.screen === 'main' && my && msg.teamId === my.teamId) {
          announceRule('PITCHER_ROLL', 'PITCHER_ROLL-' + my.teamId);
          ui.timing = msg;
          ui.sub = 'timing';
          render();
        }
        break;
      case 'reset':
        if (ui.screen === 'main' && ui.sub !== 'calibrating') {
          ui.reveal = null;
          ui.sub = 'standby';
          render();
        }
        break;
    }
  }

  function refreshState() {
    getState().then(function (st) {
      ui.devices = st.devices || [];
      ui.armedTeam = st.armedTeams && my && st.armedTeams.indexOf(my.teamId) >= 0 ? my.teamId : (st.armedTeam || null);
      ui.goStarted = !!(st.armedTeams && my && st.armedTeams.indexOf(my.teamId) >= 0);
      ui.countdownAt = st.countdowns && my ? st.countdowns[my.teamId] : null;
      if (ui.countdownAt && ui.countdownAt > Date.now() && ui.sub !== 'go' && ui.sub !== 'rolled') ui.sub = 'countdown';
      if (ui.goStarted && ui.sub === 'standby') ui.sub = 'go';
      if (ui.screen === 'main') render();
    }).catch(function () { });
  }

  /**
   * 连续 n 次 ping（间隔约 120ms）→ 上报往返延迟 → 由服务端计算并返回时钟偏移。
   * 偏移不再由本页面决定：ping 时服务端已记录自己的收包时刻，这里只提供 rtt 供其补偿单程延迟。
   */
  function calibrate(n, done) {
    var samples = [];
    (function one(i) {
      if (i >= n) {
        var est = estimateOffset(samples);
        if (samples.length) {
          api('/api/calibrate', { token: my.token, rtt: est.rtt })
            .then(function (res) {
              ui.lastRtt = res && res.rtt != null ? Math.round(res.rtt) : est.rtt;
              ui.calibWarn = ui.lastRtt > 300;
              if (done) done(est);
            })
            .catch(function (err) {
              handleAuthError(err);
              ui.calibText = '校准失败，请稍后重试';
              ui.calibWarn = true;
              renderStatus();
              if (done) done(null);
            });
        } else {
          ui.calibText = '校准失败：无法连接服务器';
          ui.calibWarn = true;
          renderStatus();
          if (done) done(null);
        }
        return;
      }
      var c0 = Date.now();
      api('/api/ping', { token: my.token, c0: c0 }).then(function (res) {
        var c1 = Date.now();
        samples.push({ c0: c0, s: res.s, c1: c1 });
      }).catch(function () { /* 丢样本，继续 */ }).then(function () {
        setTimeout(function () { one(i + 1); }, 120);
      });
    })(0);
  }

  function calibOkText() {
    return '✓ 已校准 · 延迟 ' + (ui.lastRtt != null ? ui.lastRtt : '?') + ' ms' +
      (ui.calibWarn ? '（网络较差，建议切换网络/靠近路由器）' : '');
  }

  function finishCalibration(est) {
    if (ui.sub !== 'calibrating') return;
    if (!est) {
      ui.sub = 'calibration-error';
      render();
      return;
    }
    var phase = rollAssignment && rollAssignment.phase || '';
    ui.sub = ui.goStarted || phase.indexOf('ROLL_') === 0
      ? 'go'
      : ui.countdownAt && ui.countdownAt > Date.now() ? 'countdown' : 'standby';
    ui.calibText = calibOkText();
    render();
  }

  function retryCalibration() {
    ui.sub = 'calibrating';
    ui.calibText = '正在重新校准设备时钟…';
    ui.calibWarn = false;
    render();
    calibrate(5, finishCalibration);
  }

  function handleAuthError(err) {
    if (err && err.status && err.status >= 400 && err.status < 500) {
      // token 失效（如服务器重启）：引导重新加入
      ui.sub = 'kick';
      render();
    }
  }

  function setNet(txt, warn) {
    var el = $('#pl-net');
    if (!el) return;
    el.textContent = txt;
    el.style.color = warn ? '#ff5a5a' : '';
  }

  /* ==================== 4. 渲染 ==================== */

  function render() {
    var root = $('#player-root');
    switch (ui.screen) {
      case 'team': renderTeam(root); break;
      case 'slot': renderSlot(root); break;
      case 'main': renderMain(root); break;
      case 'bench': renderBench(root); break;
      case 'error': renderError(root); break;
    }
  }

  function renderStatus() {
    var el = $('#pl-calib-status');
    if (!el) return;
    el.textContent = ui.calibText;
    el.className = 'pl-status' + (ui.calibWarn ? ' warn' : '');
  }

  /* ---------- 4.1 选战区 ---------- */

  function renderTeam(root) {
    var h = '<div class="pl-title">选择你的战区</div>' +
      '<div class="pl-sub">8 大战区 · 每人选择本队后进入出战位置选择</div>' +
      '<div class="pl-team-grid">';
    var list = teamCatalog;
    for (var i = 0; i < list.length; i++) {
      h += '<button class="btn pl-team-btn" data-tid="' + list[i].id + '">' + esc(list[i].name) + '</button>';
    }
    h += '</div>';
    root.innerHTML = h;
    Array.prototype.slice.call(root.querySelectorAll('.pl-team-btn')).forEach(function (b) {
      b.onclick = function () {
        ui.teamId = b.getAttribute('data-tid');
        ui.screen = 'slot';
        render();
      };
    });
  }

  /* ---------- 4.2 选出战位置 ---------- */

  function renderSlot(root) {
    var tid = ui.teamId;
    var h = '<div class="pl-title">' + esc(teamInfo(tid).name) + '</div>' +
      '<div class="pl-sub">选择你的出战位置（1-5 号位）· 已被占用的位置可点击顶替（原设备将失效）</div>' +
      '<div class="pl-slot-grid" id="pl-slot-grid"></div>' +
      '<div class="pl-name-row"><span class="dim">昵称（可留空）：</span>' +
      '<input id="pl-name" class="inp" maxlength="12" placeholder="' + esc(defaultName(tid, ui.slot || 1)) + '"></div>' +
      '<div class="pl-foot">' +
      '<button id="pl-back-team" class="btn btn-ghost">← 重选战区</button>' +
      '<button id="pl-refresh" class="btn btn-ghost">刷新占用</button>' +
      '<button id="pl-join" class="btn btn-primary btn-xl" disabled>加入并校准 →</button>' +
      '</div>';
    root.innerHTML = h;
    ui.slot = 0;

    function paintSlots() {
      getState().then(function (st) {
        ui.devices = st.devices || [];
        var occ = {};
        for (var i = 0; i < ui.devices.length; i++) {
          var d = ui.devices[i];
          if (d.teamId === tid) occ[d.slot] = d;
        }
        var grid = $('#pl-slot-grid');
        if (!grid) return;
        var sh = '';
        for (var s = 1; s <= 5; s++) {
          var o = occ[s];
          sh += '<button class="pl-slot-btn' + (o ? ' occupied' : '') + (ui.slot === s ? ' selected' : '') + '" data-slot="' + s + '">' + s +
            '<small>' + (o ? '已被 ' + esc(o.name || '队友') + ' 占用' : '空闲') + '</small></button>';
        }
        grid.innerHTML = sh;
        Array.prototype.slice.call(grid.querySelectorAll('.pl-slot-btn')).forEach(function (b) {
          b.onclick = function () {
            var s2 = +b.getAttribute('data-slot');
            if (occ[s2] && !confirm('该位置已被「' + (occ[s2].name || '队友') + '」占用，确定顶替吗？（原设备将失效，适合掉线重进）')) return;
            ui.slot = s2;
            $('#pl-join').disabled = false;
            var inp = $('#pl-name');
            if (inp && !inp.value) inp.placeholder = defaultName(tid, s2);
            paintSlotsSelected();
          };
        });
      }).catch(function () {
        var grid = $('#pl-slot-grid');
        if (grid) grid.innerHTML = '<div class="pl-status err" style="grid-column:1/-1">无法获取占用情况，请检查网络后点【刷新占用】</div>';
      });
    }
    function paintSlotsSelected() {
      Array.prototype.slice.call($('#pl-slot-grid').querySelectorAll('.pl-slot-btn')).forEach(function (b) {
        b.classList.toggle('selected', +b.getAttribute('data-slot') === ui.slot);
      });
    }

    $('#pl-back-team').onclick = function () { ui.screen = 'team'; render(); };
    $('#pl-refresh').onclick = paintSlots;
    $('#pl-join').onclick = function () {
      if (!ui.slot) return;
      var nm = ($('#pl-name').value || '').trim() || defaultName(tid, ui.slot);
      this.disabled = true;
      this.textContent = '加入中…';
      api('/api/join', { teamId: tid, slot: ui.slot, name: nm }).then(function (res) {
        my = { teamId: tid, slot: ui.slot, name: nm, token: res.token };
        saveMy();
        connectSSE();
        ui.screen = 'main';
        ui.sub = 'calibrating';
        ui.calibText = '正在自动校准时钟（5 次 ping）…';
        render();
        calibrate(5, function (est) {
          if (ui.sub !== 'calibrating') return;
          ui.sub = 'standby';
          ui.calibText = est ? calibOkText() : ui.calibText;
          render();
        });
      }).catch(function () {
        alert('加入失败，请检查网络后重试');
        var b = $('#pl-join');
        if (b) { b.disabled = false; b.textContent = '加入并校准 →'; }
      });
    };
    paintSlots();
  }

  /* ---------- 4.3 主界面（就位/掷骰/开盅） ---------- */

  function lineupHTML() {
    var lineup = (rollAssignment && rollAssignment.lineup) || [];
    return '<div class="pl-assigned-lineup"><div class="pl-title">本局五人出战阵容</div><div class="pl-lineup-grid">' + lineup.map(function (player) {
      var device = ui.devices.find(function (item) { return item.teamId === rollAssignment.teamId && item.slot === player.slot; });
      return '<div class="pl-lineup-seat ' + (player.slot === my.slot ? 'mine ' : '') + (device && device.ready ? 'ready' : '') + '"><i>' + player.slot + '</i><b>' + esc(player.name) + '</b><small>' + (device && device.ready ? '已准备' : device&&device.calibrated?'等待准备':'正在进入') + (player.slot === my.slot ? ' · 你' : '') + '</small></div>';
    }).join('') + '</div><p class="pl-sub">五人分别点击准备；全员准备后由队长发令，3 秒倒计时结束即可同时进攻。</p><div class="pl-sync-rule"><b>同步操作规则</b><span>系统记录五名出战队员的点击时间，若最大误差在 0.5 秒以内，本局攻击力 ×1.5；否则使用原始攻击力，无惩罚。最终五枚骰子由王牌投手投出。</span></div></div>';
  }

  function renderBench(root) {
    var lineup = (rollAssignment && rollAssignment.lineup) || [];
    root.innerHTML = '<div class="pl-title">本局观战席</div><div class="pl-wait"><span class="big-ico">👁</span>你不在本局五人出战阵容中<br><small>本局出战：' + lineup.map(function (player) { return esc(player.name); }).join('、') + '</small></div><div class="pl-foot"><a class="btn btn-primary" href="/lobby">返回队伍大厅</a></div>';
  }

  function renderMain(root) {
    var h = '<div class="pl-me">' +
      '<div class="pl-me-team">' + esc(myTeamName()) + '</div>' +
      '<div class="pl-me-slot">' + my.slot + ' 号位 · ' + esc(my.name) + '</div></div>' +
      '<div id="pl-calib-status" class="pl-status' + (ui.calibWarn ? ' warn' : '') + '">' + esc(ui.calibText || '✓ 已校准') + '</div>' +
      lineupHTML() + '<div id="pl-stage"></div>' +
      '<div class="pl-foot"><a class="btn btn-ghost" href="/lobby">返回队伍大厅</a></div>';
    root.innerHTML = h;
    paintStage();
  }

  function paintStage() {
    var stage = $('#pl-stage');
    if (!stage) return;
    var h = '';
    if (ui.sub === 'calibrating') {
      h = '<div class="pl-wait"><span class="big-ico">⏱️</span>正在自动校准设备时钟…<br><small>无需任何操作</small></div>';
    } else if (ui.sub === 'calibration-error') {
      h = '<div class="pl-status err">' + esc(ui.calibText || '设备时钟校准失败，暂时不能准备') + '</div>' +
        '<div class="pl-foot"><button id="pl-retry-calibration" class="btn btn-primary btn-xl">重新校准</button></div>';
    } else if (ui.sub === 'standby') {
      var mineDevice=ui.devices.find(function(device){return my&&device.teamId===my.teamId&&device.slot===my.slot;}),isReady=!!(mineDevice&&mineDevice.ready),readyCount=ui.devices.filter(function(device){return my&&device.teamId===my.teamId&&device.ready;}).length;
      h = '<div class="pl-wait"><span class="big-ico">🪑</span>'+(isReady?'你已准备':'你已进入备战席')+'<br><small>本队 '+readyCount+' / 5 人已准备；全部准备后由队长发号施令</small></div><div class="pl-foot"><button id="pl-ready" class="btn '+(isReady?'btn-ghost':'btn-primary')+' btn-xl">'+(isReady?'取消准备':'准备')+'</button>'+(rollAssignment&&rollAssignment.captain?'<button id="pl-command" class="btn btn-primary btn-xl" '+(readyCount===5?'':'disabled')+'>队长发号施令</button>':'')+'</div>';
    } else if (ui.sub === 'countdown') {
      var seconds=Math.max(0,Math.ceil((Number(ui.countdownAt||Date.now())-Date.now())/1000));
      h = '<div class="pl-command-countdown"><small>队长已经发令</small><strong>'+seconds+'</strong><p>倒计时结束后立即点击进攻</p></div>';
    } else if (ui.sub === 'go') {
      h = '<button id="pl-roll" class="pl-roll-btn">点击！</button>' +
        '<div class="pl-sub">听队长口令，口令一落立即点击！</div>';
    } else if (ui.sub === 'rolled') {
      h = '<div class="pl-wait"><span class="big-ico">✅</span>点击时间已上报<br><small>等待其余出战队员完成点击</small></div>';
    } else if (ui.sub === 'timing') {
      h = '<div class="pl-wait"><span class="big-ico">⏱️</span>五人同步点击已完成<br><small>' + (ui.timing && ui.timing.syncOk ? '最大误差在 0.5 秒内，攻击力 ×1.5' : '未达到同步加成，使用原始攻击力') + ' · 等待王牌投手最终投骰</small></div>';
    } else if (ui.sub === 'reveal') {
      h = revealHTML();
    } else if (ui.sub === 'kick') {
      h = '<div class="pl-status err">连接凭证已失效（可能服务器重启或被顶替）。</div>' +
        '<div class="pl-foot"><button id="pl-rejoin" class="btn btn-primary btn-xl">重新加入 →</button></div>';
    }
    stage.innerHTML = h;

    var rollBtn = $('#pl-roll');
    if (rollBtn) rollBtn.onclick = doRoll;
    var readyBtn=$('#pl-ready');
    if(readyBtn)readyBtn.onclick=function(){var mineDevice=ui.devices.find(function(device){return my&&device.teamId===my.teamId&&device.slot===my.slot;}),next=!(mineDevice&&mineDevice.ready);readyBtn.disabled=true;api('/api/player-ready',{token:my.token,ready:next}).then(refreshState).catch(function(err){if(err&&err.status===409){boot();return;}readyBtn.disabled=false;});};
    var retryCalibrationBtn = $('#pl-retry-calibration');
    if (retryCalibrationBtn) retryCalibrationBtn.onclick = retryCalibration;
    var commandBtn=$('#pl-command');
    if(commandBtn)commandBtn.onclick=function(){commandBtn.disabled=true;fetch('/api/lobby/player-action',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({type:'captain-command',selections:[]})}).then(function(r){if(!r.ok)throw new Error();}).catch(function(){commandBtn.disabled=false;});};
    var rejoin = $('#pl-rejoin');
    if (rejoin) rejoin.onclick = function () {
      try { sessionStorage.removeItem(SKEY); } catch (e) { }
      my = null;
      ui.sub = 'standby';
      boot();
    };
    var cont = $('#pl-continue');
    if (cont) cont.onclick = function () {
      location.replace('/lobby');
    };
  }

  function doRoll() {
    if (ui.sub !== 'go') return;
    var clientTs = Date.now();
    ui.sub = 'rolled';
    paintStage();
    api('/api/roll', { token: my.token, clientTs: clientTs }).catch(function (err) {
      handleAuthError(err);
      if (ui.sub === 'rolled') {
        var stage = $('#pl-stage');
        if (stage) stage.innerHTML = '<div class="pl-status err">出手上报失败，请检查网络！若大屏未开始倒计时可忽略本提示。</div>';
      }
    });
  }

  function revealHTML() {
    var msg = ui.reveal || {};
    var list = (msg.dice || []).slice().sort(function (a, b) { return a.slot - b.slot; });
    var values = list.map(function (d) { return d.die; });
    var leopard = values.length === 5 && GE.isLeopard(values);
    var h = '<div class="pl-title" style="font-size:24px">🎲 开盅结果</div><div class="pl-reveal-dice">';
    for (var i = 0; i < list.length; i++) {
      var d = list[i];
      h += '<div class="pl-reveal-slot' + (d.slot === my.slot ? ' mine' : '') + '">' +
        dieHTML('big', d.die) +
        '<div class="prs-tag">' + d.slot + ' 号位' + (d.slot === my.slot ? '（你）' : '') + (d.early ? ' · 抢跑' : '') + '</div></div>';
    }
    h += '</div><div class="pl-result-line">' +
      '<span class="badge ' + (msg.syncOk ? 'badge-green' : 'badge-red') + ' big">' + (msg.syncOk ? '同步成功 ×1.5' : '同步失败') + '</span>' +
      '<span class="badge badge-blue big">时差 ' + (typeof msg.spreadMs === 'number' ? msg.spreadMs + ' ms' : '—') + '</span>' +
      (leopard ? '<span class="badge badge-gold big">🐆 豹子！</span>' : '') +
      '</div>' +
      '<div class="pl-foot"><button id="pl-continue" class="btn btn-primary btn-xl">继续 · 等待后续赛程</button></div>';
    return h;
  }

  function dieHTML(cls, value) {
    var pips = '';
    for (var i = 0; i < 9; i++) pips += '<i></i>';
    return '<span class="die ' + (cls || '') + '" data-v="' + (value || 0) + '">' + pips + '<span class="die-q">?</span></span>';
  }

  /* ---------- 4.4 无服务器错误页 ---------- */

  function renderError(root) {
    root.innerHTML =
      '<div class="pl-title">⚠ 未连接到联机服务器</div>' +
      '<div class="pl-status err">' + esc(ui.errText || '无法访问服务器') + '</div>' +
      '<div class="pl-sub">请确认：① 主持人已启动服务器；② 本机与主持人电脑在同一局域网；③ 通过服务器地址访问本页（形如 http://服务器IP:8080/player ）。</div>' +
      '<div class="pl-foot"><button id="pl-retry" class="btn btn-primary btn-xl">重试连接</button></div>';
    $('#pl-retry').onclick = boot;
  }

  /* ==================== 5. 启动 ==================== */

  function boot() {
    Promise.all([
      getState(),
      fetch('/api/auth/me').then(function (r) {
        if (r.status === 401) { location.replace('/login.html?next=player'); throw new Error('login required'); }
        return r.json();
      }),
      fetch('/api/game-state').then(function (r) { return r.status === 204 ? null : r.json(); }),
      fetch('/api/lobby').then(function (r) { return r.json(); }),
      fetch('/api/roll-assignment').then(function (r) { return r.json().then(function (body) { if (!r.ok) throw new Error(body.error || '无法读取本局阵容'); return body; }); })
    ]).then(function (result) {
      var st = result[0];
      loginUser = result[1];
      var saved = result[2];
      var lobby = result[3];
      rollAssignment = result[4];
      if (saved && saved.state && Array.isArray(saved.state.teams)) teamCatalog = saved.state.teams;
      var assignedTeam = lobby && lobby.me && lobby.me.teamId;
      if (!assignedTeam) throw new Error('你不在本轮参赛队伍中');
      if (rollAssignment.phase && rollAssignment.phase.indexOf('ROLL_') === 0) announceRule('TIMING', 'ROLL-' + assignedTeam);
      if (rollAssignment.phase && rollAssignment.phase.indexOf('PITCHER_ROLL_') === 0) announceRule('PITCHER_ROLL', 'PITCHER_ROLL-' + assignedTeam);
      teamCatalog = teamCatalog.filter(function (team) { return team.id === assignedTeam; });
      if (my && (my.teamId !== assignedTeam || my.slot !== rollAssignment.slot || my.username !== loginUser.username)) {
        try { sessionStorage.removeItem(SKEY); } catch (e) { }
        my = null;
      }
      var userEl = $('#pl-user');
      if (userEl) userEl.textContent = '👤 ' + loginUser.displayName;
      ui.devices = st.devices || [];
      ui.armedTeam = st.armedTeams && assignedTeam && st.armedTeams.indexOf(assignedTeam) >= 0 ? assignedTeam : (st.armedTeam || null);
      ui.goStarted = !!(st.armedTeams && assignedTeam && st.armedTeams.indexOf(assignedTeam) >= 0);
      ui.countdownAt = st.countdowns && assignedTeam ? st.countdowns[assignedTeam] : null;
      setNet('已连接服务器', false);
      if (!rollAssignment.eligible) {
        if (my) try { sessionStorage.removeItem(SKEY); } catch (e) { }
        my = null; ui.screen = 'bench'; render();
        setTimeout(boot, 3000);
        return;
      }
      if (my) {
        // 恢复会话：重连 SSE + 后台复校
        connectSSE();
        ui.screen = 'main';
        ui.sub = 'calibrating';
        ui.calibText = '正在自动校准时钟…';
        render();
        calibrate(5, finishCalibration);
      } else {
        ui.teamId = assignedTeam; ui.slot = rollAssignment.slot;
        api('/api/join', { teamId: assignedTeam, slot: rollAssignment.slot, name: loginUser.displayName }).then(function (res) {
          my = { teamId: assignedTeam, slot: res.slot, name: loginUser.displayName, username: loginUser.username, token: res.token };
          saveMy(); connectSSE(); ui.screen = 'main'; ui.sub = 'calibrating'; ui.calibText = '正在自动校准时钟（5 次 ping）…'; render();
          calibrate(5, finishCalibration);
        }).catch(function (error) { ui.errText = error.message || '无法加入本局投骰席位'; ui.screen = 'error'; render(); });
      }
    }).catch(function () {
      setNet('未连接', true);
      ui.errText = '无法访问 /api/state';
      ui.screen = 'error';
      render();
    });
  }

  $('#pl-logout').onclick = function () {
    fetch('/api/auth/logout', { method: 'POST' }).finally(function () {
      location.replace('/login.html?next=player');
    });
  };

  if (window.GameRules) window.GameRules.init();
  boot();
  setInterval(function(){if(ui.screen==='main'&&ui.sub==='countdown')paintStage();},200);

})();
