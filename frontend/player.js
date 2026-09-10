/**
 * 骰子擂台 · 田忌赛马 —— 队员端（player.html）
 * 纯原生 JS，无模块。
 * 流程：查询掷骰资格 → 领取令牌 + NTP 式时钟校准（后台无感）→ 本地渲染 321 倒计时
 * → 30 秒窗口内点击【掷！】→ 展示个人点数并等待全队 → 开盲盒（每人一次）
 * → 对局阶段逐轮猜阵（本轮出战小队成员）→ 本队赛程结束后引导回队伍大厅。
 * 阶段倒计时与动态状态统一由右侧阶段面板（stage-panel.js）承载；队长重投结果以弹窗通知本队队员；
 * 顶部队名默认收起为按钮，点击才展开战队信息弹层（纯前端，不发请求）。
 */
(function () {
  'use strict';

  /* ==================== 1. 小工具 ==================== */

  function $(s) { return document.querySelector(s); }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function fetchWithTimeout(path, options, timeoutMs) {
    var controller = typeof AbortController === 'function' ? new AbortController() : null;
    var timer = controller ? setTimeout(function () { controller.abort(); }, timeoutMs || 8000) : null;
    var requestOptions = options || {};
    if (controller) requestOptions.signal = controller.signal;
    return fetch(path, requestOptions).catch(function (error) {
      if (error && error.name === 'AbortError') {
        var timeoutError = new Error('请求超时，请检查网络后重试');
        timeoutError.endpoint = path;
        throw timeoutError;
      }
      if (error) error.endpoint = path;
      throw error;
    }).finally(function () { if (timer) clearTimeout(timer); });
  }

  function api(path, body) {
    return fetchWithTimeout(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body || {})
    }, 8000).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (responseBody) {
        if (!r.ok) {
          var e = new Error(responseBody.error || ('HTTP ' + r.status));
          e.status = r.status;
          e.endpoint = path;
          throw e;
        }
        return responseBody;
      });
    });
  }

  function bootJson(path, allowNoContent) {
    return fetchWithTimeout(path, {}, 8000).then(function (response) {
      if (allowNoContent && response.status === 204) return null;
      return response.json().catch(function () { return {}; }).then(function (body) {
        if (!response.ok) {
          var error = new Error(body.error || ('访问 ' + path + ' 失败（HTTP ' + response.status + '）'));
          error.status = response.status;
          error.endpoint = path;
          throw error;
        }
        return body;
      });
    });
  }

  function loadGameState() {
    var headers = {};
    if (gameStateEtag) headers['If-None-Match'] = gameStateEtag;
    return fetchWithTimeout('/api/game-state?scope=player', { headers: headers }, 8000).then(function (response) {
      if (response.status === 304) return { notModified: true };
      if (response.status === 204) return { notModified: false, state: null, version: null };
      return response.json().catch(function () { return {}; }).then(function (body) {
        if (!response.ok) {
          var error = new Error(body.error || ('访问玩家状态失败（HTTP ' + response.status + '）'));
          error.status = response.status;
          error.endpoint = '/api/game-state?scope=player';
          throw error;
        }
        gameStateEtag = response.headers.get('ETag');
        gameStateVersion = body.version == null ? null : String(body.version);
        return { notModified: false, state: body.state || null, version: gameStateVersion };
      });
    });
  }

  function applyGameState(result) {
    if (!result || result.notModified) return;
    gameState = result.state;
    if (result.version == null) {
      gameStateVersion = null;
      gameStateEtag = null;
    }
  }

  /* ==================== 2. 状态 ==================== */

  var SKEY = 'dice-arena-player-v2';

  var my = loadMy();            // { teamId, username, playerId, token, die, rollTs }，sessionStorage 恢复掉线重进
  var loginUser = null;
  var assignment = null;        // /api/roll-assignment 响应 {eligible, stage, rollGoAt, stageDeadlineAt, alreadyRolled, teamId}
  var gameState = null;         // /api/game-state 的 state 部分（取队名与已掷点数用）
  var gameStateVersion = null;
  var gameStateEtag = null;
  var ui = {
    screen: 'loading',          // loading / idle / main / ended / error
    sub: 'calibrating',         // main 内：countdown / go / rolled / kick / blindbox / battle / waiting（calibrating 仅作 join 期间的瞬时占位）
    offset: 0,                  // 服务端时钟 - 本地时钟（后台校准返回值，用于本地渲染倒计时）
    lastRtt: null,
    calibText: '',
    calibWarn: false,
    die: null,
    rolling: false,             // 掷骰请求在途：在途期间忽略重复点击并保持按钮禁用
    rollAnimating: false,       // 投骰动画播放中：期间禁止 paintStage 重绘阶段区冲掉动画 DOM
    renderedNotice: '',         // 阶段区当前已渲染的提示文案：未变化时走秒不重建按钮 DOM
    myBox: null,                // 开盲盒响应里的本地结果：回源到达前先于 gameState 渲染，新一轮掷骰时清空
    boxAnimating: false,        // 开盒动画播放中：期间禁止 paintStage 重绘盲盒视图冲掉动画 DOM
    notice: '',
    errText: '',
    guessSel: [],             // 猜阵已选中的敌方球员 id（最多 5 个）
    guessKey: '',             // 当前猜阵所属 matchId:round 或 matchId:pre:round，变化时清空 guessSel
    preEdit: false,           // 已提前提交猜阵后点"改投"进入的重选模式
    battleKey: ''             // 对局视图结构指纹：未变化时只就地刷新人数/倒计时，避免打断点选
  };
  var es = null;
  var refreshTimer = null;
  var refreshExpectedVersion = null, refreshForced = false;
  var assignmentLoading = false, assignmentPending = false, assignmentPendingVersion = null;
  var matchDetails = {}, matchDetailPending = {};
  var rerollBaseline = -1;      // 首次见到本队重掷日志时只建基线，不弹历史记录；日志重置（下一 bracket）时重建
  var rerollQueue = [];
  var rerollShowing = false;

  function loadMy() {
    try {
      var raw = sessionStorage.getItem(SKEY);
      if (!raw) return null;
      var m = JSON.parse(raw);
      if (!m || !m.token || !m.username) return null;
      return m;
    } catch (e) { return null; }
  }
  function saveMy() {
    try { sessionStorage.setItem(SKEY, JSON.stringify(my)); } catch (e) { }
  }
  function clearMy() {
    my = null;
    try { sessionStorage.removeItem(SKEY); } catch (e) { }
  }

  /** 服务端当前时刻的本地估算：倒计时与窗口判断都以服务端时间轴为准 */
  function serverNow() { return Date.now() + (ui.offset || 0); }

  function myPlayerId() { return loginUser ? 'u' + loginUser.id : ''; }

  /** 本队 id：优先取资格接口返回值，缺失时按本人球员 id 在整局状态里反查 */
  function myTeamId() {
    if (assignment && assignment.teamId) return assignment.teamId;
    var pid = myPlayerId();
    var teams = (gameState && gameState.teams) || [];
    for (var i = 0; i < teams.length; i++) {
      var players = teams[i].players || [];
      for (var j = 0; j < players.length; j++) if (String(players[j].id) === pid) return teams[i].id;
    }
    return null;
  }
  function findTeamById(id) {
    var teams = (gameState && gameState.teams) || [];
    for (var i = 0; i < teams.length; i++) if (teams[i].id === id) return teams[i];
    return null;
  }
  function myTeam() { return findTeamById(myTeamId()); }
  function teamNameOf(id) {
    var team = findTeamById(id);
    if (team && team.name) return team.name;
    if (gameState && id === gameState.champion && gameState.championName) return gameState.championName;
    return id || '';
  }
  function myTeamName() {
    var name = teamNameOf(myTeamId());
    return name || (assignment && assignment.teamId) || '';
  }

  /** 本人在本队 players 里的节点（含 dice/diceFinal/blindBox 等轮次数据，仅本队可见） */
  function findMyPlayer() {
    var team = myTeam();
    var pid = myPlayerId();
    var players = (team && team.players) || [];
    for (var i = 0; i < players.length; i++) {
      if (String(players[i].id) === pid) return players[i];
    }
    return null;
  }

  /** 本队当前进行中（含待开赛）的比赛；没有则返回 null */
  function myActiveMatch() {
    var tid = myTeamId();
    var matches = (gameState && gameState.matches) || {};
    if (!tid) return null;
    for (var key in matches) {
      var m = matches[key];
      if (m && m.status === 'active' && (m.a === tid || m.b === tid)) return m;
    }
    return null;
  }

  /** 本队应展示的比赛：进行中的优先，否则取最近一场已完结的（刚打完、等待晋级判定或已被淘汰） */
  function myCurrentMatch() {
    var active = myActiveMatch();
    if (active) return active;
    var tid = myTeamId();
    var matches = (gameState && gameState.matches) || {};
    if (!tid) return null;
    var latest = null, latestRank = -1;
    for (var key in matches) {
      var m = matches[key];
      if (!m || (m.a !== tid && m.b !== tid)) continue;
      // 按 bracket 进度排序：决赛 > 半决赛 > 1/4 决赛，取本队最近一场
      var rank = key.charAt(0) === 'f' ? 3 : key.charAt(0) === 's' ? 2 : 1;
      if (rank >= latestRank) { latest = m; latestRank = rank; }
    }
    return latest;
  }

  /** 本队是否已无任何比赛：冠军已产生，或本队输过一场且没有进行中的比赛（被淘汰） */
  function gameOverForMe() {
    if (!gameState || !gameState.matches) return false;
    if (gameState.champion) return true;
    var tid = myTeamId();
    if (!tid) return false;
    if (myActiveMatch()) return false;
    var matches = gameState.matches;
    var played = false;
    for (var key in matches) {
      var m = matches[key];
      if (!m || (m.a !== tid && m.b !== tid)) continue;
      played = true;
      // 单败淘汰：任何一场已完结比赛不是本队获胜，即已出局
      if (m.status === 'done' && m.winner !== tid) return true;
    }
    return !played;   // 从未出现在任何场次里
  }

  /** 非掷骰阶段的页面路由：按当前阶段与本队参赛状态决定渲染哪个视图 */
  function stageRoute() {
    var stage = assignment && assignment.stage;
    if (gameOverForMe()) return 'ended';
    if (!gameState || !myTeamId()) return 'idle';
    if (stage === 'BLIND_BOX') return myActiveMatch() ? 'blindbox' : 'idle';
    if (stage === 'BATTLE') return myCurrentMatch() ? 'battle' : 'idle';
    if (stage === 'TACTICS') return myActiveMatch() ? 'waiting' : 'idle';
    return 'idle';
  }

  /** 从整局状态里取回自己的骰子点数（已掷后刷新/换设备恢复用） */
  function findMyDie() {
    var team = myTeam();
    var pid = loginUser ? 'u' + loginUser.id : '';
    var players = (team && team.players) || [];
    for (var i = 0; i < players.length; i++) {
      if (String(players[i].id) === String(pid) && players[i].dice != null) return Number(players[i].dice);
    }
    return null;
  }

  /* ==================== 3. 网络：校准 + 大厅事件 ==================== */

  /**
   * 连续 n 次 ping（间隔约 120ms）→ 上报最小往返延迟 → 由服务端计算并返回时钟偏移。
   * 偏移只用于本地渲染 321 倒计时；掷骰时刻的归一化同样由服务端按此偏移完成。
   * done(est, tokenExpired)：tokenExpired=true 表示令牌已失效，需重新领取后再校准。
   */
  function calibrate(n, done) {
    var samples = [];
    var authFailed = false;
    (function one(i) {
      if (i >= n) {
        if (authFailed) { if (done) done(null, true); return; }
        if (!samples.length) {
          ui.calibText = '校准失败：无法连接服务器';
          ui.calibWarn = true;
          renderStatus();
          if (done) done(null);
          return;
        }
        var rtt = null;
        for (var k = 0; k < samples.length; k++) {
          var sampleRtt = samples[k].c1 - samples[k].c0;
          if (rtt == null || sampleRtt < rtt) rtt = sampleRtt;
        }
        api('/api/calibrate', { token: my.token, rtt: rtt })
          .then(function (res) {
            ui.offset = res && res.offset != null ? Number(res.offset) : 0;
            ui.lastRtt = res && res.rtt != null ? Math.round(res.rtt) : rtt;
            ui.calibWarn = ui.lastRtt > 300;
            if (done) done({ offset: ui.offset, rtt: ui.lastRtt });
          })
          .catch(function (err) {
            if (err && (err.status === 401 || err.status === 403)) {
              // 令牌失效（服务器重启或被重新领取轮换）：由调用方清会话重领
              if (done) done(null, true);
              return;
            }
            ui.calibText = '校准失败，请稍后重试';
            ui.calibWarn = true;
            renderStatus();
            if (done) done(null);
          });
        return;
      }
      var c0 = Date.now();
      api('/api/ping', { token: my.token, c0: c0 }).then(function (res) {
        var c1 = Date.now();
        samples.push({ c0: c0, c1: c1 });
      }).catch(function (err) {
        if (err && (err.status === 401 || err.status === 403)) authFailed = true;
        // 其余失败：丢弃该样本，继续
      }).then(function () {
        setTimeout(function () { one(i + 1); }, 120);
      });
    })(0);
  }

  function calibOkText() {
    return '✓ 已校准 · 延迟 ' + (ui.lastRtt != null ? ui.lastRtt : '?') + ' ms' +
      (ui.calibWarn ? '（网络较差，建议切换网络/靠近路由器）' : '');
  }

  /**
   * 后台校准完成：只更新时钟偏移与状态栏，并纠正倒计时显示。
   * 失败不阻塞掷骰——掷骰时刻由服务端按收包时间钳制，与本地时钟无关。
   */
  function finishCalibration(est) {
    if (!est) {
      ui.calibText = '时钟校准失败，倒计时按本机时间显示';
      ui.calibWarn = true;
      renderStatus();
      return;
    }
    ui.calibText = calibOkText();
    renderStatus();
    if (ui.screen === 'main' && (ui.sub === 'countdown' || ui.sub === 'go')) {
      var openAt = rollOpenAt();
      var target = openAt && serverNow() < openAt ? 'countdown' : 'go';
      if (target !== ui.sub) ui.sub = target;
      paintStage();
    }
  }

  function retryCalibration() {
    ui.calibText = '正在重新校准设备时钟…';
    ui.calibWarn = false;
    renderStatus();
    calibrate(5, function (est, tokenExpired) {
      if (tokenExpired) { rejoinAndCalibrate(); return; }
      finishCalibration(est);
    });
  }

  /** 令牌失效后的后台恢复：清会话 → 重新领取令牌 → 后台校准，不打断当前界面 */
  function rejoinAndCalibrate() {
    clearMy();
    api('/api/join', {}).then(function (res) {
      my = {
        teamId: assignment.teamId,
        username: loginUser.username,
        playerId: 'u' + loginUser.id,
        token: res.token,
        die: null,
        rollTs: null
      };
      saveMy();
      calibrate(5, function (est, tokenExpired) {
        if (tokenExpired) { ui.sub = 'kick'; render(); return; }
        finishCalibration(est);
      });
    }).catch(joinFailed);
  }

  function joinFailed(err) {
    if (err && err.status === 409) {
      // 资格在等待期间失效（阶段已推进）：回源后按最新资格渲染
      refreshAssignment();
      return;
    }
    ui.errText = err && err.message ? err.message : '无法领取掷骰令牌';
    ui.screen = 'error';
    render();
  }

  function connectEvents() {
    if (es) return;
    try { es = new EventSource('/api/lobby/events'); } catch (e) { return; }
    // 建连后的 sync 事件只带版本：相同版本不请求，延迟连接/重连时才按需追平。
    es.onopen = function () { setNet('已连接服务器', false); };
    es.onerror = function () { setNet('连接中断，重连中…', true); };
    es.onmessage = function (ev) {
      var msg = null;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      var gameChanged = msg && msg.type === 'game' &&
        (msg.version == null || gameStateVersion == null || String(msg.version) !== gameStateVersion);
      var syncChanged = msg && msg.type === 'sync' && msg.version != null &&
        (gameStateVersion == null || String(msg.version) !== gameStateVersion);
      if (gameChanged || syncChanged) {
        scheduleRefresh(msg.version);
      }
    };
  }

  /** 阶段变化通知可能连发，短去抖后统一回源资格与整局状态（保留随机抖动削峰） */
  function scheduleRefresh(expectedVersion) {
    if (expectedVersion != null) {
      expectedVersion = String(expectedVersion);
      if (gameStateVersion != null && expectedVersion === gameStateVersion) return;
      refreshExpectedVersion = expectedVersion;
    } else {
      refreshForced = true;
    }
    if (refreshTimer) return;
    if (assignmentLoading) {
      if (refreshForced) assignmentPending = true;
      else assignmentPendingVersion = refreshExpectedVersion;
      refreshForced = false;
      refreshExpectedVersion = null;
      return;
    }
    refreshTimer = setTimeout(function () {
      refreshTimer = null;
      var forced = refreshForced;
      var version = refreshExpectedVersion;
      refreshForced = false;
      refreshExpectedVersion = null;
      if (!forced && version != null && version === gameStateVersion) return;
      refreshAssignment(forced ? null : version);
    }, 120 + Math.floor(Math.random() * 120));
  }

  /** 资格与整局状态回源：in-flight 去重，并发触发合并为一次，期间到达的请求在完成后补一轮 */
  function refreshAssignment(expectedVersion) {
    if (assignmentLoading) {
      if (expectedVersion == null) assignmentPending = true;
      else assignmentPendingVersion = String(expectedVersion);
      return;
    }
    assignmentLoading = true;
    Promise.all([
      bootJson('/api/roll-assignment'),
      loadGameState()
    ]).then(function (result) {
      assignment = result[0];
      applyGameState(result[1]);
      checkRerollPopups();
      onAssignmentChange();
    }).catch(function () { /* 网络抖动：等下一条通知 */ })
      .finally(function () {
        assignmentLoading = false;
        var forcedPending = assignmentPending;
        var pendingVersion = assignmentPendingVersion;
        var retry = forcedPending || (pendingVersion != null && pendingVersion !== gameStateVersion);
        assignmentPending = false;
        assignmentPendingVersion = null;
        if (retry) refreshAssignment(forcedPending ? null : pendingVersion);
      });
  }

  /* ---------- 单场战况详情：按需加载（/api/game-state 摘要的 rounds 只有局号与胜负） ---------- */
  function matchDetailKey(match) { return match.id + ':' + (match.rounds || []).length; }
  function loadMatchDetail(match, onDone) {
    var key = matchDetailKey(match);
    if (matchDetails[key]) { if (onDone) onDone(matchDetails[key]); return; }
    var pending = matchDetailPending[key];
    if (!pending) {
      pending = bootJson('/api/game-state/matches/' + encodeURIComponent(match.id), true)
        .then(function (d) { if (d) matchDetails[key] = d; return d; })
        .catch(function () { return null; })
        .finally(function () { delete matchDetailPending[key]; });
      matchDetailPending[key] = pending;
    }
    if (onDone) pending.then(function (d) { if (d) onDone(d); });
  }

  /** 掷骰流程内的子状态：处于这些状态时资格刷新不需重建页面 */
  var ROLL_SUBS = ['calibrating', 'calibration-error', 'countdown', 'go', 'rolled', 'kick'];

  function onAssignmentChange() {
    if (assignment && assignment.eligible) {
      // rolled 但新资格显示未掷：已进入下一 bracket 的 ROLL，需重新领令牌走掷骰流程
      var rolledStale = ui.sub === 'rolled' && !assignment.alreadyRolled;
      if (ui.screen !== 'main' || ROLL_SUBS.indexOf(ui.sub) < 0 || rolledStale) {
        // 进入（或下一 bracket 重新进入）掷骰阶段：从提示页/赛后视图自动进入掷骰流程
        boot();
        return;
      }
      if (assignment.alreadyRolled && ui.sub !== 'rolled' && ui.sub !== 'calibrating') {
        ui.die = findMyDie();
        if (ui.die == null && my && my.die != null) ui.die = my.die;
        ui.sub = 'rolled';
        render();
      }
      return;
    }
    var route = stageRoute();
    if (route === 'ended') {
      // 冠军产生或本队被淘汰：令牌作废，引导回大厅
      clearMy();
      ui.screen = 'ended';
      render();
      return;
    }
    if (route === 'blindbox' || route === 'battle' || route === 'waiting') {
      // ROLL 之外不再需要掷骰令牌；校准得到的时钟偏移保留，供本地倒计时使用
      if (my) clearMy();
      if (ui.screen !== 'main') {
        ui.screen = 'main';
        ui.sub = route;
        render();
      } else {
        // 已在主界面（掷骰完成等待推进）：只重绘阶段区，避免打断猜阵点选
        ui.sub = route;
        paintStage();
      }
      return;
    }
    if (ui.screen === 'main') clearMy();
    ui.screen = 'idle';
    render();
  }

  function setNet(txt, warn) {
    var el = $('#pl-net');
    if (!el) return;
    el.textContent = txt;
    el.style.color = warn ? '#ff5a5a' : '';
  }

  /* ---------- 队长重投弹窗：对比本队 rerollLog 增量，新条目排队逐条弹出 ---------- */

  function checkRerollPopups() {
    var team = myTeam();
    if (!team) return;
    var log = team.rerollLog || [];
    if (rerollBaseline < 0 || log.length < rerollBaseline) {
      rerollBaseline = log.length;
      return;
    }
    for (var i = rerollBaseline; i < log.length; i++) rerollQueue.push(log[i]);
    rerollBaseline = log.length;
    pumpRerollQueue();
  }

  function pumpRerollQueue() {
    if (rerollShowing || !rerollQueue.length) return;
    rerollShowing = true;
    var item = rerollQueue.shift();
    var toast = document.createElement('div');
    toast.className = 'pl-reroll-toast';
    toast.innerHTML = '<div class="pl-reroll-card">' +
      '<small>队长重投骰子</small>' +
      '<div class="pl-reroll-main">🎲 <b>' + esc(item.playerName) + '</b> 的骰子被重投</div>' +
      '<div class="pl-reroll-delta"><span>' + Number(item.from) + '</span><i>→</i><span>' + Number(item.to) + '</span></div>' +
      '<button type="button" class="btn btn-primary">知道了</button></div>';
    document.body.appendChild(toast);
    var closed = false;
    var done = function () {
      if (closed) return;
      closed = true;
      if (toast.parentNode) toast.parentNode.removeChild(toast);
      rerollShowing = false;
      pumpRerollQueue();
    };
    toast.querySelector('button').onclick = done;
    setTimeout(done, 6000);
  }

  /* ==================== 4. 渲染 ==================== */

  function render() {
    // 投骰动画播放中：整页重绘会冲掉立方体 DOM；动画结束后的重绘/下一次回源会补齐目标视图
    if (ui.rollAnimating) return;
    var root = $('#player-root');
    switch (ui.screen) {
      case 'loading': root.innerHTML = '<div class="pl-wait"><span class="big-ico">⏳</span>正在查询掷骰资格…</div>'; break;
      case 'idle': renderIdle(root); break;
      case 'main': renderMain(root); break;
      case 'ended': renderEnded(root); break;
      case 'error': renderError(root); break;
    }
  }

  function renderStatus() {
    var el = $('#pl-calib-status');
    if (!el) return;
    el.textContent = ui.calibText;
    el.className = 'pl-status' + (ui.calibWarn ? ' warn' : '');
  }

  function stageText(stage) {
    var names = {
      CAPTAIN_VOTE: '队长投票',
      SQUAD_FORM: '小队组建',
      ROLL: '全员掷骰',
      BLIND_BOX: '盲盒',
      TACTICS: '战术布置',
      BATTLE: '对局',
      RESULT: '结果公布'
    };
    return names[stage] || stage || '未开始';
  }

  /* ---------- 4.1 无需掷骰提示页 ---------- */

  function renderIdle(root) {
    var stage = assignment && assignment.stage;
    var h = '<div class="pl-title">当前无需掷骰</div>';
    if (!(assignment && assignment.teamId)) {
      h += '<div class="pl-wait"><span class="big-ico">🪑</span>你还没有加入任何队伍<br><small>请先到队伍大厅加入队伍</small></div>';
    } else if (stage && stage !== 'ROLL') {
      h += '<div class="pl-wait"><span class="big-ico">⏳</span>当前不在掷骰阶段<br><small>当前阶段：' + esc(stageText(stage)) + ' · 轮到本队掷骰时本页会自动进入</small></div>';
    } else {
      h += '<div class="pl-wait"><span class="big-ico">⏳</span>本轮你的队伍没有比赛<br><small>轮到本队掷骰时本页会自动进入</small></div>';
    }
    h += '<div class="pl-foot"><a class="btn btn-primary" href="/lobby">返回队伍大厅</a></div>';
    root.innerHTML = h;
  }

  /* ---------- 4.2 主界面（校准/倒计时/掷骰） ---------- */

  function renderMain(root) {
    var h = '<div class="pl-me">' +
      '<button type="button" id="pl-team-toggle" class="pl-team-btn">🏴 ' + esc(myTeamName()) + ' ▾</button>' +
      '<div class="pl-me-slot">' + esc(loginUser ? loginUser.displayName : '') + '</div></div>' +
      '<div id="pl-calib-status" class="pl-status' + (ui.calibWarn ? ' warn' : '') + '">' + esc(ui.calibText || '') + '</div>' +
      '<div id="pl-stage"></div>' +
      '<div class="pl-foot"><a class="btn btn-ghost" href="/lobby">返回队伍大厅</a></div>';
    root.innerHTML = h;
    bindTeamToggle();
    paintStage();
  }

  /** 战队信息弹层：默认收起，点击队名按钮才渲染；数据全部来自已加载的整局状态，不发请求 */
  function teamPopHTML() {
    var team = myTeam();
    if (!team) return '<div class="pl-team-pop"><p class="pl-team-pop-empty">暂无队伍数据</p></div>';
    var captainId = team.roles && team.roles.captain;
    var players = team.players || [];
    var pid = myPlayerId();
    var captainName = '';
    var items = '';
    for (var i = 0; i < players.length; i++) {
      var p = players[i];
      var isCaptain = captainId != null && String(p.id) === String(captainId);
      if (isCaptain) captainName = p.name;
      items += '<span class="pl-team-pop-member' + (isCaptain ? ' is-captain' : '') + (String(p.id) === pid ? ' is-me' : '') + '">' +
        esc(p.name) + (isCaptain ? ' 👑' : '') + (String(p.id) === pid ? '（我）' : '') + '</span>';
    }
    return '<div class="pl-team-pop">' +
      '<div class="pl-team-pop-head"><b>' + esc(team.name || myTeamName()) + '</b>' +
      (captainName ? '<span>队长：' + esc(captainName) + '</span>' : '<span>队长待选出</span>') + '</div>' +
      (captainId != null && String(captainId) === pid ? '<p class="pl-team-pop-role">你是本队队长</p>' : '') +
      '<div class="pl-team-pop-grid">' + items + '</div></div>';
  }

  function closeTeamPop() {
    var pop = $('#pl-team-pop-wrap');
    if (pop && pop.parentNode) pop.parentNode.removeChild(pop);
  }

  function bindTeamToggle() {
    var btn = $('#pl-team-toggle');
    if (!btn) return;
    btn.onclick = function (event) {
      if (event && event.stopPropagation) event.stopPropagation();
      if ($('#pl-team-pop-wrap')) { closeTeamPop(); return; }
      var wrap = document.createElement('div');
      wrap.id = 'pl-team-pop-wrap';
      wrap.innerHTML = teamPopHTML();
      btn.parentNode.appendChild(wrap);
    };
  }

  /** 本人小队的掷骰窗口：优先取 roll-assignment 回传的小队错峰字段，缺失时回退全局时刻 */
  function rollOpenAt() {
    if (!assignment) return 0;
    if (assignment.rollOpenAt != null) return Number(assignment.rollOpenAt);
    return Number(assignment.rollGoAt || 0);
  }
  function rollDeadlineAt() {
    if (!assignment) return 0;
    if (assignment.rollDeadlineAt != null) return Number(assignment.rollDeadlineAt);
    return Number(assignment.stageDeadlineAt || 0);
  }

  function goReached() {
    var openAt = rollOpenAt();
    return !!openAt && serverNow() >= openAt;
  }
  function deadlinePassed() {
    var deadline = rollDeadlineAt();
    return !!deadline && serverNow() > deadline;
  }

  function paintStage() {
    var stage = $('#pl-stage');
    if (!stage) return;
    // 开盒动画播放中：回源/走秒触发的重绘会冲掉动画 DOM，动画结束后再统一渲染结果卡
    if (ui.sub === 'blindbox' && ui.boxAnimating) return;
    // 投骰动画播放中同理：立方体 DOM 由动画托管，落定后再切 rolled 结果卡
    if (ui.rollAnimating) return;
    if (ui.sub !== 'battle') { ui.battleKey = ''; ui.preEdit = false; }
    var h = '';
    if (ui.sub === 'calibrating') {
      h = '<div class="pl-wait"><span class="big-ico">⏱️</span>正在自动校准设备时钟…<br><small>无需任何操作</small></div>';
    } else if (ui.sub === 'calibration-error') {
      h = '<div class="pl-status err">' + esc(ui.calibText || '设备时钟校准失败，暂时不能掷骰') + '</div>' +
        '<div class="pl-foot"><button id="pl-retry-calibration" class="btn btn-primary btn-xl">重新校准</button></div>';
    } else if (ui.sub === 'countdown') {
      var remain = Math.max(1, Math.ceil((rollOpenAt() - serverNow()) / 1000));
      h = '<div class="pl-command-countdown"><small>掷骰即将开始</small><strong>' + remain + '</strong>' +
        '<p>秒后开掷 · 截止时刻全员统一，开掷后立即点击【掷！】</p></div>';
    } else if (ui.sub === 'go') {
      if (deadlinePassed()) {
        h = '<div class="pl-wait"><span class="big-ico">⌛</span>本轮掷骰窗口已结束<br><small>未提交的掷骰将由系统代掷，等待稍后开盲盒…</small></div>';
      } else {
        var left = Math.max(0, Math.ceil((rollDeadlineAt() - serverNow()) / 1000));
        var liveRollBtn = $('#pl-roll');
        // 走秒时就地刷新文案：每 200ms 重建按钮会吞掉正在落下的点击
        if (liveRollBtn && (ui.notice || '') === ui.renderedNotice) {
          var subEl = stage.querySelector('.pl-sub');
          if (subEl) subEl.textContent = '窗口剩余 ' + left + ' 秒 · 每人限掷 1 枚';
          liveRollBtn.disabled = !!ui.rolling;
          return;
        }
        h = (ui.notice ? '<div class="pl-status warn">' + esc(ui.notice) + '</div>' : '') +
          '<button id="pl-roll" class="pl-roll-btn"' + (ui.rolling ? ' disabled' : '') + '>掷！</button>' +
          '<div class="pl-sub">窗口剩余 ' + left + ' 秒 · 每人限掷 1 枚</div>';
      }
    } else if (ui.sub === 'rolled') {
      var mePlayer = findMyPlayer();
      var rolledText = mePlayer && mePlayer.autoRolled ? '窗口已结束，由系统代掷，等待开盲盒' : '已扔完，等待稍后开盲盒';
      h = '<div class="pl-title" style="font-size:24px">🎲 你的点数</div>' +
        '<div class="pl-reveal-dice"><div class="pl-reveal-slot mine">' + dieHTML('big', ui.die) + '</div></div>' +
        '<div class="pl-wait" style="padding-top:0"><span class="big-ico">✅</span>' + rolledText + '<br><small>阶段推进后本页将自动进入开盲盒</small></div>';
    } else if (ui.sub === 'blindbox') {
      h = blindBoxHTML();
    } else if (ui.sub === 'battle') {
      var battle = battleInfo();
      if (battle && battle.key === ui.battleKey && $('#pl-battle')) {
        // 结构未变（仅他方提交猜阵/倒计时走动）：就地刷新已交人数，保留点选状态
        updateGuessCounts(battle);
        return;
      }
      ui.battleKey = battle ? battle.key : '';
      h = battleHTML(battle);
    } else if (ui.sub === 'waiting') {
      h = waitingHTML();
    } else if (ui.sub === 'kick') {
      h = '<div class="pl-status err">连接凭证已失效（可能服务器重启或令牌被重新领取）。</div>' +
        '<div class="pl-foot"><button id="pl-rejoin" class="btn btn-primary btn-xl">重新加入 →</button></div>';
    }
    stage.innerHTML = h;
    ui.renderedNotice = ui.sub === 'go' ? (ui.notice || '') : '';

    var rollBtn = $('#pl-roll');
    if (rollBtn) rollBtn.onclick = doRoll;
    Array.prototype.slice.call(document.querySelectorAll('.bb-box')).forEach(function (boxBtn) {
      boxBtn.onclick = function () { doBlindBoxOpen(Number(boxBtn.getAttribute('data-idx'))); };
    });
    bindGuessGrid();
    var retryCalibrationBtn = $('#pl-retry-calibration');
    if (retryCalibrationBtn) retryCalibrationBtn.onclick = retryCalibration;
    var rejoin = $('#pl-rejoin');
    if (rejoin) rejoin.onclick = function () {
      clearMy();
      ui.sub = 'calibrating';
      boot();
    };
  }

  function doRoll() {
    if (ui.sub !== 'go' || !my || !my.token) return;
    if (ui.rolling || ui.rollAnimating) return; // 请求在途/动画播放中，忽略重复点击
    if (!goReached() || deadlinePassed()) return;   // go 前点击无效，截止后不再提交
    ui.rolling = true;
    ui.notice = '';
    // 点数以 /api/roll 响应为准，动画只是本地表演：点击即起滚掩盖网络耗时，响应到达后定格到真实点数
    var canAnimate = !!window.gsap && !!window.DiceRollUI && !(window.BlindBoxUI && window.BlindBoxUI.reducedMotion());
    var anim = null;
    if (canAnimate) {
      ui.rollAnimating = true;
      anim = window.DiceRollUI.startTumble($('#pl-stage'));
    } else {
      // 低动效/GSAP 缺失：保持原有按钮禁用态，响应到达直接出结果卡
      var rollBtn = $('#pl-roll');
      if (rollBtn) rollBtn.disabled = true;
    }
    var clientTs = Date.now();
    api('/api/roll', { token: my.token, clientTs: clientTs }).then(function (res) {
      ui.rolling = false;
      ui.die = res && res.die != null ? Number(res.die) : null;
      if (my) {
        my.die = ui.die;
        my.rollTs = res && res.rollTs != null ? Number(res.rollTs) : null;
        saveMy();
      }
      if (anim && ui.die != null) {
        // sub 在响应到达时即切 rolled（与无动画路径一致）；动画期间若阶段推进，
        // applyAssignment 会改写 sub，动画结束只负责解除守卫并按当前 sub 重绘
        ui.sub = 'rolled';
        anim.settle(ui.die, function () {
          ui.rollAnimating = false;
          paintStage();
        });
      } else {
        if (anim) { anim.abort(); ui.rollAnimating = false; }
        ui.sub = 'rolled';
        paintStage();
      }
    }).catch(function (err) {
      ui.rolling = false;
      if (anim) { anim.abort(); ui.rollAnimating = false; }
      if (err && (err.status === 401 || err.status === 403)) {
        ui.sub = 'kick';
        render();
        return;
      }
      if (err && err.status === 409) {
        // go 前/截止后/重复掷：展示服务端提示并回源（重复掷时回源会带出已掷结果）
        ui.notice = err.message || '本次掷骰未被接受';
        refreshAssignment();
      } else {
        ui.notice = err && err.message ? err.message : '掷骰提交失败，请检查网络后重试';
      }
      paintStage();
    });
  }

  function dieHTML(cls, value) {
    var pips = '';
    for (var i = 0; i < 9; i++) pips += '<i></i>';
    return '<span class="die ' + (cls || '') + '" data-v="' + (value || 0) + '">' + pips + '<span class="die-q">?</span></span>';
  }

  /* ---------- 4.3 盲盒 / 对局（猜阵）视图 ---------- */

  /** 阶段倒计时统一由右侧阶段面板承载；tick 到点后回源推进视图 */

  /* ---- 盲盒 ---- */

  /** 个人盲盒结果卡：只要本人已开过盲盒（含战术/对局阶段）就展示在玩家端，
   *  避免"开了却看不到、要退回大厅才知道"；blindBoxHTML 与 waitingHTML 共用。 */
  function myBlindBoxCard() {
    var me = findMyPlayer();
    if (!me) return '';
    var box = me.blindBox != null ? Number(me.blindBox) : ui.myBox;
    if (box == null) return '';
    box = Number(box);
    var tier = (box > 0 ? '+' : '') + box;
    var dice = me.diceFinal != null ? Number(me.diceFinal) : (me.dice != null ? Number(me.dice) : null);
    var finalPoints = dice != null ? dice + box : null;
    var h = '<div class="pl-box-result">' +
      '<div class="pl-box-tier ' + (box >= 0 ? 'pos' : 'neg') + '">' + tier + '</div>' +
      '<div class="pl-box-formula">' +
        (dice != null
          ? '<div><small>骰子</small><b>' + dice + '</b></div><i>→</i>'
          : '') +
        '<div><small>最终点数</small><strong>' + (finalPoints != null ? finalPoints : '?') + '</strong></div>' +
      '</div></div>';
    return h;
  }

  function blindBoxHTML() {
    var team = myTeam();
    var players = (team && team.players) || [];
    var opened = 0;
    for (var i = 0; i < players.length; i++) if (players[i].blindBoxOpened) opened++;
    var me = findMyPlayer();
    var openedSelf = !!(me && me.blindBoxOpened) || ui.myBox != null;
    if (ui.myBox != null && !(me && me.blindBoxOpened)) opened++;   // 自己刚开、回源未至：本地先计入进度
    var h = '<div class="pl-title" style="font-size:24px">🎁 开盲盒</div>';
    if (openedSelf) {
      var box = me && me.blindBox != null ? Number(me.blindBox) : (ui.myBox != null ? ui.myBox : 0);
      var tier = (box > 0 ? '+' : '') + box;
      var dice = me.dice != null ? Number(me.dice) : null;
      var diceFinal = me.diceFinal != null ? Number(me.diceFinal) : dice;
      var finalPoints = diceFinal != null ? diceFinal + box : null;
      h += '<div class="pl-box-result">' +
        '<div class="pl-box-tier ' + (box >= 0 ? 'pos' : 'neg') + '">' + tier + '</div>' +
        '<div class="pl-box-formula">' +
          '<div><small>原骰子</small>' + dieHTML('big', dice) + '</div>' +
          '<i>→</i>' +
          '<div><small>盲盒档位</small><b>' + tier + '</b></div>' +
          '<i>→</i>' +
          '<div><small>最终点数</small><strong>' + (finalPoints != null ? finalPoints : '?') + '</strong></div>' +
        '</div>' +
      '</div>';
      h += '<div class="pl-wait" style="padding-top:14px"><span class="big-ico">✅</span>等待全队开盒…<br>' +
        '<small>本队进度 ' + opened + ' / ' + players.length + ' · 全员开完后进入队长战术阶段</small></div>' +
        '<p class="pl-deadline-hint">本阶段剩余时间见右侧阶段面板</p>';
    } else {
      h += '<div class="pl-sub">从 3 个盲盒中选 1 个开启：档位 -2 ~ +5（不含 0）· 每人限开一次 · 25 秒内不选视为放弃（按 0 计），系统不代选不代开</div>' +
        (ui.notice ? '<div class="pl-status warn">' + esc(ui.notice) + '</div>' : '') +
        window.BlindBoxUI.boxesHTML() +
        '<p class="pl-deadline-hint">剩余时间见右侧阶段面板</p>';
    }
    return h;
  }

  function doBlindBoxOpen(idx) {
    if (ui.sub !== 'blindbox' || ui.boxAnimating) return;
    var boxEls = Array.prototype.slice.call(document.querySelectorAll('.bb-box'));
    if (boxEls.length !== 3) return;
    boxEls.forEach(function (b) { b.disabled = true; });
    ui.notice = '';
    // 响应里就有盲盒档位与三盒内容：动画播完再渲染结果卡，同时回源同步全队进度
    api('/api/lobby/player-action', { type: 'blind-box-open', selections: [String(idx)] }).then(function (res) {
      var canAnimate = res && res.blindBox != null
        && Array.isArray(res.boxes) && res.boxes.length === 3 && res.picked === idx
        && window.gsap && !window.BlindBoxUI.reducedMotion();
      if (canAnimate) {
        ui.boxAnimating = true;
        window.BlindBoxUI.playBoxReveal(boxEls, idx, res.boxes.map(Number), function () {
          ui.myBox = Number(res.blindBox);
          ui.boxAnimating = false;
          paintStage();
        });
      } else {
        // 幂等重放/低动效/GSAP 缺失：直接出结果卡，与刷新重进一致
        if (res && res.blindBox != null) ui.myBox = Number(res.blindBox);
        paintStage();
      }
      refreshAssignment();
    }).catch(function (err) {
      boxEls.forEach(function (b) { b.disabled = false; });
      ui.notice = err && err.message ? err.message : '开盲盒失败，请检查网络后重试';
      if (err && (err.status === 400 || err.status === 409)) refreshAssignment();
      paintStage();
    });
  }

  /* ---- 对局：猜阵 / 观战 / 揭晓 / 本场结果 ---- */

  /** 汇总本队当前比赛的渲染上下文；key 为结构指纹，用于跳过无谓的重绘 */
  function battleInfo() {
    var team = myTeam();
    var match = team && myCurrentMatch();
    if (!team || !match) return null;
    var side = match.a === team.id ? 'A' : 'B';
    var enemy = findTeamById(side === 'A' ? match.b : match.a);
    var round = match.round || 1;
    var squads = team.squads || [];
    var squad = squads[round - 1] || [];
    var pid = myPlayerId();
    var inSquad = false;
    for (var i = 0; i < squad.length; i++) if (String(squad[i]) === pid) inSquad = true;
    var myRound = 0;
    for (var k = 0; k < squads.length && k < 6; k++) {
      if ((squads[k] || []).indexOf(pid) >= 0) { myRound = k + 1; break; }
    }
    var sideStatus = (match.guessStatus && match.guessStatus[side]) || {};
    var submitted = !!sideStatus[pid];
    var preSide = myRound > round && match.preGuessStatus && match.preGuessStatus[myRound] && match.preGuessStatus[myRound][side];
    var preSubmitted = !!(preSide && preSide[pid]);
    var key = ['battle', match.id, match.status, match.phase, match.round, match.roundPhase, submitted, preSubmitted].join('|');
    return { team: team, match: match, side: side, enemy: enemy, round: round,
      squad: squad, inSquad: inSquad, myRound: myRound, submitted: submitted, preSubmitted: preSubmitted, key: key };
  }

  function guessCounts(match) {
    var gs = match.guessStatus || {};
    var counts = { A: 0, B: 0 };
    for (var side in counts) {
      var s = gs[side] || {};
      for (var k in s) counts[side]++;
    }
    return counts;
  }

  function guessCountsText(battle) {
    var counts = guessCounts(battle.match);
    var mine = battle.side === 'A' ? counts.A : counts.B;
    var theirs = battle.side === 'A' ? counts.B : counts.A;
    return '我方已交 ' + mine + ' / 5 · 对方已交 ' + theirs + ' / 5';
  }

  /** 猜阵 5 秒下限提示：交齐后最快 5 秒揭晓；已交齐且未满 5 秒时提示"即将揭晓" */
  function guessRevealLine(battle) {
    var counts = guessCounts(battle.match);
    if (counts.A >= 5 && counts.B >= 5) {
      var openedAt = Number(battle.match.guessOpenedAt || 0);
      if (openedAt && serverNow() < openedAt + 5000) return '双方已交齐，即将揭晓（至少 5 秒）';
      return '双方已交齐，即将揭晓';
    }
    return '双方交齐后最快 5 秒揭晓';
  }

  function updateGuessCounts(battle) {
    var el = $('#pl-guess-counts');
    if (el) el.textContent = guessCountsText(battle);
    var min = $('#pl-guess-min');
    if (min) min.textContent = guessRevealLine(battle);
  }

  /** 对峙式比分头：我方金色、敌方红色；side 为本队所在侧（'A'/'B'） */
  function battleScoreHTML(match, side) {
    return '<div class="pl-battle-score"><span class="' + (side === 'A' ? 'is-me' : 'is-foe') + '">' + esc(teamNameOf(match.a)) + '</span><b>' +
      (match.winsA || 0) + ' : ' + (match.winsB || 0) + '</b><span class="' + (side === 'A' ? 'is-foe' : 'is-me') + '">' + esc(teamNameOf(match.b)) + '</span></div>';
  }

  /** 6 局赛道：与大厅 hero 共用 .arena-cells 样式 */
  function plTrackHtml(match, side) {
    var rounds = match.rounds || [];
    var cells = '';
    for (var n = 1; n <= 6; n++) {
      var entry = null;
      for (var i = 0; i < rounds.length; i++) if (Number(rounds[i].round) === n) { entry = rounds[i]; break; }
      var current = match.status === 'active' && match.phase === 'BATTLE' && Number(match.round) === n;
      var cls = '', mark = '';
      if (entry) {
        if (entry.winner) { cls = entry.winner === side ? 'is-win' : 'is-lose'; mark = cls === 'is-win' ? '胜' : '负'; }
        else { cls = 'is-draw'; mark = '平'; }
      } else if (current) cls = 'is-current';
      cells += '<div class="arena-cell ' + cls + '"><i>第 ' + n + ' 局</i><b>' + mark + '</b></div>';
    }
    return '<div class="arena-cells pl-track">' + cells + '</div>';
  }

  function battleHTML(battle) {
    if (!battle) return '<div class="pl-wait"><span class="big-ico">⏳</span>等待对局数据…</div>';
    var match = battle.match;
    if (match.status === 'done' || match.phase === 'RESULT' || match.phase === 'FINISHED' || match.phase === 'OVERTIME_PENDING') {
      return battleResultHTML(battle);
    }
    if (match.phase !== 'BATTLE') {
      // 下一 bracket 场次已生成但掷骰流程尚未开始：短暂过渡，避免误渲染猜阵
      return '<div id="pl-battle"><div class="pl-wait"><span class="big-ico">⏳</span>等待本轮对局开始…<br>' +
        '<small>新一轮掷骰即将开始，本页会自动进入</small></div></div>';
    }
    var h = '<div id="pl-battle">' +
      '<div class="pl-title" style="font-size:24px">⚔️ 第 ' + battle.round + ' / 6 局</div>' +
      battleScoreHTML(match, battle.side) + plTrackHtml(match, battle.side);
    if (match.roundPhase === 'REVEAL') return h + battleRevealHTML(battle) + '</div>';
    // GUESS：密封猜阵，双方各 5 份
    h += '<div class="pl-guess-counts" id="pl-guess-counts">' + esc(guessCountsText(battle)) + '</div>' +
      '<p class="pl-guess-min" id="pl-guess-min">' + esc(guessRevealLine(battle)) + '</p>' +
      '<p class="pl-deadline-hint">剩余时间见右侧阶段面板</p>';
    if (battle.submitted) {
      h += '<div class="pl-wait"><span class="big-ico">🔒</span>猜阵已密封提交，等待揭晓<br>' +
        '<small>双方都交齐 5 份后最快 5 秒揭晓，或倒计时结束后强制揭晓</small></div>';
    } else if (battle.inSquad) {
      h += guessGridHTML(battle);
    } else {
      h += '<div class="pl-wait"><span class="big-ico">👀</span>本轮你的小队不出战，观战中<br>' +
        '<small>第 ' + battle.round + ' 局猜阵进行中 · 出战队友正在提交猜阵</small></div>';
    }
    // 提前猜阵：本人小队出战第 2~6 局且非本局时，可提前提交该局猜阵
    if (!battle.inSquad && battle.myRound > battle.round && battle.myRound >= 2) h += preGuessHTML(battle);
    return h + '</div>';
  }

  /** 敌方 30 人花名册点选 5 人（敌方点数与小队编排按规则对玩家不可见）；
   *  preRound 传入时渲染"提前猜阵"模式，guessKey 与当前轮区分开，避免点选状态串台 */
  function guessGridHTML(battle, preRound) {
    var enemyPlayers = (battle.enemy && battle.enemy.players) || [];
    var guessKey = preRound ? battle.match.id + ':pre:' + preRound : battle.match.id + ':' + battle.round;
    if (ui.guessKey !== guessKey) {
      ui.guessKey = guessKey;
      ui.guessSel = [];
    }
    var h = '<div class="pl-sub">' + (preRound
      ? '第 ' + preRound + ' 局你出战，选出你认为敌方该局出战的 5 名队员'
      : '本轮你出战！选出你认为敌方本局出战的 5 名队员') + '</div>' +
      (ui.notice ? '<div class="pl-status warn">' + esc(ui.notice) + '</div>' : '') +
      '<div class="pl-roster">';
    for (var i = 0; i < enemyPlayers.length; i++) {
      var p = enemyPlayers[i];
      var pid = String(p.id);
      var selected = ui.guessSel.indexOf(pid) >= 0;
      h += '<button type="button" class="pl-roster-item' + (selected ? ' selected' : '') + '" data-pid="' + esc(pid) + '" aria-pressed="' + selected + '">' +
        '<b>' + esc(p.name) + '</b><span>' + esc(p.department || '') + '</span></button>';
    }
    h += '</div>' +
      '<div class="pl-guess-foot"><span id="pl-guess-n">已选 ' + ui.guessSel.length + ' / 5</span>' +
      '<button id="pl-guess-submit" class="btn btn-primary btn-xl"' + (ui.guessSel.length === 5 ? '' : ' disabled') + '>' +
      (preRound ? '提前提交猜阵' : '密封提交猜阵') + '</button></div>';
    return h;
  }

  /** 提前猜阵区块：已提交显示撤回/改投，否则复用猜阵点选网格 */
  function preGuessHTML(battle) {
    if (battle.preSubmitted && !ui.preEdit) {
      return '<div class="pl-preguess">' +
        '<div class="pl-sub">你将在第 ' + battle.myRound + ' 局出战 · 已提前提交，可撤回/改投</div>' +
        '<div class="pl-wait"><span class="big-ico">🔒</span>提前猜阵已密封提交<br>' +
        '<small>第 ' + battle.myRound + ' 局开始时自动生效</small></div>' +
        '<div class="pl-foot"><button id="pl-preguess-retract" class="btn btn-ghost">撤回提前猜阵</button>' +
        '<button id="pl-preguess-edit" class="btn btn-primary">改投</button></div></div>';
    }
    return '<div class="pl-preguess">' +
      '<div class="pl-sub">提前猜阵 · 你将在第 ' + battle.myRound + ' 局出战，可现在就提交该局猜阵</div>' +
      guessGridHTML(battle, battle.myRound) + '</div>';
  }

  /** 点选只改按钮状态与计数，不触发整页重绘，避免被 SSE 刷新打断 */
  function bindGuessGrid() {
    var grid = $('.pl-roster');
    if (grid) {
      var items = grid.querySelectorAll('.pl-roster-item');
      for (var i = 0; i < items.length; i++) {
        items[i].onclick = function () {
          var pid = this.getAttribute('data-pid');
          var idx = ui.guessSel.indexOf(pid);
          if (idx >= 0) ui.guessSel.splice(idx, 1);
          else if (ui.guessSel.length < 5) ui.guessSel.push(pid);
          var selected = ui.guessSel.indexOf(pid) >= 0;
          this.classList.toggle('selected', selected);
          this.setAttribute('aria-pressed', String(selected));
          var n = $('#pl-guess-n');
          if (n) n.textContent = '已选 ' + ui.guessSel.length + ' / 5';
          var submitBtn = $('#pl-guess-submit');
          if (submitBtn) submitBtn.disabled = ui.guessSel.length !== 5;
        };
      }
    }
    var submit = $('#pl-guess-submit');
    if (submit) submit.onclick = doGuessSubmit;
    var preRetract = $('#pl-preguess-retract');
    if (preRetract) preRetract.onclick = doPreGuessRetract;
    var preEdit = $('#pl-preguess-edit');
    if (preEdit) preEdit.onclick = function () {
      ui.preEdit = true;
      ui.battleKey = '';   // 强制重绘为可点选的网格
      paintStage();
    };
  }

  function doGuessSubmit() {
    if (ui.sub !== 'battle' || ui.guessSel.length !== 5) return;
    var submit = $('#pl-guess-submit');
    if (submit) submit.disabled = true;
    ui.notice = '';
    var type = ui.guessKey.indexOf(':pre:') >= 0 ? 'pre-guess' : 'round-guess';
    api('/api/lobby/player-action', { type: type, selections: ui.guessSel.slice() }).then(function () {
      if (type === 'pre-guess') { ui.preEdit = false; ui.battleKey = ''; }
      refreshAssignment();
    }).catch(function (err) {
      if (submit) submit.disabled = false;
      ui.notice = err && err.message ? err.message : '猜阵提交失败，请检查网络后重试';
      if (err && (err.status === 400 || err.status === 409)) refreshAssignment();
      ui.battleKey = '';   // 强制重绘以展示错误提示
      paintStage();
    });
  }

  function doPreGuessRetract() {
    var btn = $('#pl-preguess-retract');
    if (btn) btn.disabled = true;
    ui.notice = '';
    ui.preEdit = false;
    api('/api/lobby/player-action', { type: 'retract-guess', selections: [] }).then(function () {
      ui.battleKey = '';
      refreshAssignment();
    }).catch(function (err) {
      if (btn) btn.disabled = false;
      ui.notice = err && err.message ? err.message : '撤回失败，请检查网络后重试';
      if (err && (err.status === 400 || err.status === 409)) refreshAssignment();
      ui.battleKey = '';
      paintStage();
    });
  }

  /** REVEAL：刚揭晓一局的双方战力明细（按需加载的单场详情 rounds 末条） */
  function battleRevealHTML(battle) {
    var match = battle.match;
    var detail = matchDetails[matchDetailKey(match)];
    var rounds = detail ? (detail.rounds || []) : (match.rounds || []);
    var last = rounds[rounds.length - 1];
    if (last && last.powerA == null) last = null;   // 摘要条目没有战力明细，等详情接口
    if (!last) {
      loadMatchDetail(match, function () { ui.battleKey = ''; paintStage(); });
      return '<div class="pl-wait">正在加载本局结果…</div>';
    }
    var nameA = teamNameOf(match.a);
    var nameB = teamNameOf(match.b);
    var winSide = last.winner || null;
    function sideHTML(side, name) {
      var crit = last['crit' + side];
      return '<div class="pl-reveal-side' + (winSide === side ? ' win' : '') + '">' +
        '<h4>' + esc(name) + (winSide === side ? ' 🏅' : '') + '</h4>' +
        '<div class="pl-reveal-power">' + esc(last['power' + side]) + '</div>' +
        '<small>基础 ' + esc(last['base' + side]) + (crit ? ' × 1.5 同步暴击' : '') +
        ' · 猜阵命中 ' + esc(last['guessHits' + side]) + '（+' + esc(last['guessBonus' + side]) + '）</small></div>';
    }
    return '<div class="pl-status' + (winSide ? '' : ' warn') + '" style="text-align:center">第 ' + esc(last.round) + ' 局揭晓：' +
      (winSide ? esc(winSide === 'A' ? nameA : nameB) + ' 胜' : '双方战平') + '</div>' +
      '<div class="pl-reveal-sides">' + sideHTML('A', nameA) + sideHTML('B', nameB) + '</div>' +
      '<p class="pl-deadline-hint">下一局倒计时见右侧阶段面板</p>';
  }

  /** 本场打完（RESULT/FINISHED/OVERTIME_PENDING）：胜者、比分与平局链提示 */
  function battleResultHTML(battle) {
    var match = battle.match;
    var winnerId = match.winner;
    var won = !!winnerId && winnerId === battle.team.id;
    var overtimePending = match.phase === 'OVERTIME_PENDING';
    var h = '<div id="pl-battle">' +
      '<div class="pl-title" style="font-size:24px">' + (overtimePending ? '三连环全平 · 待加赛' : winnerId ? (won ? '🎉 本场获胜' : '本场惜败') : '本场结束') + '</div>' +
      battleScoreHTML(match, battle.side) + plTrackHtml(match, battle.side);
    if (winnerId) h += '<div class="pl-sub">胜者：' + esc(teamNameOf(winnerId)) + '</div>';
    var tieText = window.TournamentUI ? TournamentUI.tieBreakText(match, teamNameOf(match.a), teamNameOf(match.b)) : '';
    if (overtimePending && !tieText) tieText = '胜场、总点数、GMV 全部打平，等待管理员安排两队加赛';
    if (tieText) h += '<div class="pl-sub">' + esc(tieText) + '</div>';
    h += '<div class="pl-wait"><span class="big-ico">📋</span>等待下一场对阵<br>' +
      '<small>详细战报与后续赛程请在队伍大厅查看</small></div>' +
      '<div class="pl-foot"><a class="btn btn-primary" href="/lobby">前往队伍大厅 →</a></div></div>';
    return h;
  }

  /** TACTICS：只有队长操作，其余队员等待对局开始 */
  function waitingHTML() {
    var card = myBlindBoxCard();
    var team = myTeam();
    var captainId = team && team.roles && team.roles.captain;
    var captainName = '';
    if (captainId && team) {
      var players = team.players || [];
      for (var i = 0; i < players.length; i++) {
        if (String(players[i].id) === String(captainId)) { captainName = players[i].name; break; }
      }
    }
    return '<div class="pl-title" style="font-size:24px">🧠 战术布置中</div>' +
      (card || '') +
      '<div class="pl-wait"><span class="big-ico">⏳</span>队长' + (captainName ? ' <b>' + esc(captainName) + '</b> ' : '') + '正在进行重掷与排阵<br>' +
      '<small>当前阶段：' + esc(stageText(assignment && assignment.stage)) + ' · 对局开始后本页自动进入猜阵</small></div>' +
      '<p class="pl-deadline-hint">本阶段剩余时间见右侧阶段面板</p>';
  }

  /* ---------- 4.4 赛程结束提示页 ---------- */

  function renderEnded(root) {
    var champion = gameState && gameState.champion;
    var cls, mark, title, score = '', sub;
    if (champion) {
      if (champion === myTeamId()) {
        cls = 'is-victory'; mark = 'CHAMPION';
        title = '🏆 恭喜！本队夺得冠军';
        sub = '全部赛程已结束，荣耀属于你们';
      } else {
        cls = 'is-end'; mark = 'GAME OVER';
        title = '比赛已全部结束';
        sub = '冠军已产生：' + esc(teamNameOf(champion));
      }
    } else {
      var lost = null, allMatches = (gameState && gameState.matches) || {};
      for (var key in allMatches) {
        var m = allMatches[key];
        if (m && m.status === 'done' && (m.a === myTeamId() || m.b === myTeamId()) && m.winner !== myTeamId()) lost = m;
      }
      if (lost) {
        var mySideA = lost.a === myTeamId();
        var myScore = Number(mySideA ? lost.winsA : lost.winsB) || 0;
        var oppScore = Number(mySideA ? lost.winsB : lost.winsA) || 0;
        cls = 'is-elim'; mark = 'ELIMINATED';
        title = '本场惜败 · 本队被淘汰';
        score = myScore + ' : ' + oppScore;
        sub = '对手「' + esc(teamNameOf(mySideA ? lost.b : lost.a)) + '」晋级 · 后续赛程与战报请在队伍大厅查看';
        var lostTieText = window.TournamentUI ? TournamentUI.tieBreakText(lost, mySideA ? '我方' : '对方', mySideA ? '对方' : '我方') : '';
        if (lostTieText) sub = esc(lostTieText) + '<br>' + sub;
      } else {
        cls = 'is-end'; mark = 'GAME OVER';
        title = '本队赛程已结束';
        sub = '本队本轮比赛已结束 · 后续赛程与战报请在队伍大厅查看';
      }
    }
    root.innerHTML =
      '<div class="pl-poster ' + cls + '">' +
        '<div class="arena-watermark">' + mark + '</div>' +
        '<h1 class="arena-post-title">' + title + '</h1>' +
        (score ? '<div class="arena-final">' + score + '</div>' : '') +
        '<p class="arena-post-sub">' + sub + '</p>' +
        '<div class="pl-foot"><a class="btn btn-primary btn-xl" href="/lobby">前往队伍大厅 →</a></div>' +
      '</div>';
  }

  /* ---------- 4.5 无服务器错误页 ---------- */

  function renderError(root) {
    root.innerHTML =
      '<div class="pl-title">⚠ 未连接到联机服务器</div>' +
      '<div class="pl-status err">' + esc(ui.errText || '无法访问服务器') + '</div>' +
      '<div class="pl-sub">请确认：① 主持人已启动服务器；② 本机与主持人电脑在同一局域网；③ 通过服务器地址访问本页（形如 http://服务器IP:8080/player ）。</div>' +
      '<div class="pl-foot"><button id="pl-retry" class="btn btn-primary btn-xl">重试连接</button>' +
      '<a class="btn btn-ghost" href="/lobby">返回队伍大厅</a></div>';
    $('#pl-retry').onclick = boot;
  }

  /* ==================== 5. 启动 ==================== */

  function boot() {
    ui.screen = 'loading';
    render();
    Promise.all([
      bootJson('/api/auth/me'),
      bootJson('/api/roll-assignment'),
      loadGameState()
    ]).then(function (result) {
      loginUser = result[0];
      assignment = result[1];
      applyGameState(result[2]);
      checkRerollPopups();
      var userEl = $('#pl-user');
      if (userEl) userEl.textContent = '👤 ' + loginUser.displayName;
      setNet('已连接服务器', false);
      connectEvents();

      // 会话恢复校验：换账号或换队伍则作废旧会话
      if (my && (my.username !== loginUser.username ||
        (assignment.teamId && my.teamId && my.teamId !== assignment.teamId))) {
        clearMy();
      }

      if (!assignment.eligible) {
        clearMy();
        var route = stageRoute();
        if (route === 'ended') {
          ui.screen = 'ended';
          render();
          return;
        }
        if (route === 'blindbox' || route === 'battle' || route === 'waiting') {
          // 中途打开/刷新页面：ROLL 之外无需令牌与校准，直接进入对应阶段视图
          ui.screen = 'main';
          ui.sub = route;
          ui.calibText = '';
          render();
          return;
        }
        ui.screen = 'idle';
        render();
        return;
      }

      ui.screen = 'main';
      if (assignment.alreadyRolled) {
        ui.die = findMyDie();
        if (ui.die == null && my && my.die != null) ui.die = my.die;
        ui.sub = 'rolled';
        ui.calibText = '';
        render();
        return;
      }

      // 新一轮掷骰：清掉上一轮的盲盒本地结果
      ui.myBox = null;
      ui.calibText = '';
      // 不阻塞界面：领令牌后立刻进入倒计时/可掷，时钟校准在后台完成并自我修正
      var enterRoll = function () {
        var openAt = rollOpenAt();
        ui.sub = openAt && serverNow() < openAt ? 'countdown' : 'go';
        render();
      };
      var backgroundCalibrate = function () {
        calibrate(5, function (est, tokenExpired) {
          if (tokenExpired) { rejoinAndCalibrate(); return; }
          finishCalibration(est);
        });
      };
      if (my && my.token) {
        // 恢复会话：令牌可能仍有效，直接进视图；失效则由 rejoinAndCalibrate 后台重领
        enterRoll();
        backgroundCalibrate();
      } else {
        api('/api/join', {}).then(function (res) {
          my = {
            teamId: assignment.teamId,
            username: loginUser.username,
            playerId: 'u' + loginUser.id,
            token: res.token,
            die: null,
            rollTs: null
          };
          saveMy();
          enterRoll();
          backgroundCalibrate();
        }).catch(joinFailed);
      }
    }).catch(function (error) {
      if (error && error.status === 401) {
        location.replace('/login.html?next=player');
        return;
      }
      setNet('未连接', true);
      ui.errText = error && error.message ? error.message : '无法连接比赛服务';
      ui.screen = 'error';
      render();
    });
  }

  /* ---------- 阶段侧栏：随 200ms tick 驱动，倒计时以校准后的服务端时间轴为准 ---------- */
  /* 返回当前采用的 deadline（ms），供 tick 判断到点回源；无面板时返回 null */
  function tickStagePanel() {
    if (!window.StagePanel) return null;
    var stage = assignment && assignment.stage;
    if (ui.screen !== 'main' || !stage) { window.StagePanel.update({ stage: '' }); return null; }
    var deadline = Number((assignment && assignment.stageDeadlineAt) || (gameState && gameState.stageDeadlineAt) || 0) || null;
    var extra = '';
    if (stage === 'BATTLE') {
      var match = myCurrentMatch();
      if (match && match.status === 'active') {
        if (match.phase === 'RESULT') {
          deadline = Number(match.resultReadyAt || 0) || deadline;
          extra = '本场结果展示中';
        } else if (match.phase === 'BATTLE') {
          deadline = match.roundPhase === 'REVEAL' ? (Number(match.revealUntil || 0) || deadline) : (Number(match.guessDeadlineAt || 0) || deadline);
          var roundNo = Number(match.round || 1);
          if (match.roundPhase === 'REVEAL') {
            extra = '第 ' + roundNo + ' / 6 局 · 结果揭晓中';
          } else {
            var battle = battleInfo();
            extra = battle && !battle.inSquad
              ? '第 ' + roundNo + ' / 6 局 · 本轮你的小队不出战，观战中'
              : '第 ' + roundNo + ' / 6 局 · 猜阵进行中';
          }
        }
      }
    } else if (stage === 'BLIND_BOX') {
      var bbTeam = myTeam();
      var bbPlayers = (bbTeam && bbTeam.players) || [];
      var bbOpened = 0;
      for (var bi = 0; bi < bbPlayers.length; bi++) if (bbPlayers[bi].blindBoxOpened) bbOpened++;
      extra = '本队已开盒 ' + bbOpened + ' / ' + bbPlayers.length + ' 人';
    } else if (stage === 'TACTICS') {
      var tTeam = myTeam();
      var captainId = tTeam && tTeam.roles && tTeam.roles.captain;
      var captainName = '';
      if (captainId && tTeam) {
        var tPlayers = tTeam.players || [];
        for (var ti = 0; ti < tPlayers.length; ti++) {
          if (String(tPlayers[ti].id) === String(captainId)) { captainName = tPlayers[ti].name; break; }
        }
      }
      extra = '队长' + (captainName ? ' ' + captainName + ' ' : '') + '正在重投骰子、排兵布阵';
    } else if (stage === 'ROLL') {
      if (rollDeadlineAt()) deadline = rollDeadlineAt();
      if (ui.sub === 'rolled') extra = '已扔完，等待稍后开盲盒';
      else if (ui.sub === 'go' && goReached() && !deadlinePassed()) extra = '开掷了，立即点击【掷！】';
      else if (rollOpenAt() && serverNow() < rollOpenAt()) extra = '掷骰即将开始，请准备';
    }
    window.StagePanel.update({ stage: stage, stageLabel: stageText(stage), deadline: deadline, serverOffset: ui.offset, extraLine: extra });
    return deadline;
  }

  // 倒计时走秒与 go 时刻切换：以校准后的服务端时间轴为准
  var lastExpireRefreshAt = 0;
  setInterval(function () {
    var deadline = tickStagePanel();
    if (ui.screen !== 'main' || !assignment) return;
    if (ui.sub === 'countdown') {
      if (goReached()) ui.sub = 'go';
      paintStage();
    } else if (ui.sub === 'go') {
      paintStage();
    }
    // 到点回源：任何子状态下当前 deadline 过期都回源拿新视图，SSE 漏消息时也能自愈；
    // 服务端尚未推进时同一批过期数据最多每 1.5s 重试一次，避免刷爆接口
    if (deadline && serverNow() > Number(deadline)) {
      var nowMs = Date.now();
      if (nowMs - lastExpireRefreshAt > 1500) {
        lastExpireRefreshAt = nowMs;
        scheduleRefresh();
      }
    }
  }, 200);

  $('#pl-logout').onclick = function () {
    fetch('/api/auth/logout', { method: 'POST' }).finally(function () {
      location.replace('/login.html?next=player');
    });
  };

  // 战队信息弹层：点击弹层外部或按 Esc 收起
  document.addEventListener('click', function (event) {
    var pop = $('#pl-team-pop-wrap');
    if (pop && !pop.contains(event.target)) closeTeamPop();
  });
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') closeTeamPop();
  });

  try { sessionStorage.removeItem('dice-arena-player-v1'); } catch (e) { }
  if (window.GameRules) window.GameRules.init();
  if (window.StagePanel) window.StagePanel.init();
  boot();

})();
