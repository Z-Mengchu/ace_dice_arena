(function () {
  'use strict';

  var STAGE_TITLES = {
    CAPTAIN_VOTE: ['第一阶段 · 队长投票', '八队投票选出队长'],
    SQUAD_FORM: ['第二阶段 · 分队', '队长编排 6 支 5 人小队'],
    ROLL: ['第三阶段 · 全员掷骰', '321 倒计时后 30 人各掷 1 枚'],
    BLIND_BOX: ['第四阶段 · 开盲盒', '每人手动开启个人盲盒'],
    TACTICS: ['第五阶段 · 战术窗口', '队长重掷 + 排出场顺序'],
    BATTLE: ['第六阶段 · 六局对局', '6 局胜场制 · 逐局猜阵揭晓']
  };

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function team(state, id) { return (state.teams || []).find(function (t) { return t.id === id; }) || { id: id, name: id, players: [] }; }
  function playerName(teamNode, id) { var p = (teamNode.players || []).find(function (x) { return x.id === id; }); return p ? p.name : id; }
  function countdown(deadlineAt) {
    if (deadlineAt == null) return '';
    var s = Math.max(0, Math.ceil((Number(deadlineAt) - Date.now()) / 1000));
    return '<i class="feed-countdown">剩余 ' + s + ' 秒</i>';
  }
  function wallHead(kicker, title, sub) {
    return '<section class="wall-head"><div><small>' + esc(kicker) + '</small><h1>' + esc(title) + '</h1></div><p>' + esc(sub) + '</p></section>';
  }
  function progressBar(done, total) {
    var pct = total ? Math.min(100, Math.round(done / total * 100)) : 0;
    return '<div class="accumulation-watch-progress"><i style="width:' + pct + '%"></i></div>';
  }

  /* ---------- 各阶段全队进度卡 ---------- */

  function teamCard(teamNode, index, bodyHtml, doneText) {
    return '<article class="live-feed team-watch"><header><span>TEAM 0' + (index + 1) + '</span><b>' + esc(teamNode.name) + '</b></header>' + bodyHtml + '</article>';
  }

  function voteBody(teamNode) {
    var votes = Object.keys(teamNode.roleVotes || {}).length;
    var eligible = (teamNode.players || []).filter(function (p) { return !p.managed; }).length;
    var captain = teamNode.roles && teamNode.roles.captain;
    return '<div class="feed-meta"><span>已投票 ' + votes + ' / ' + eligible + '</span><span>' + (captain ? '队长 ' + esc(playerName(teamNode, captain)) : '等待计票') + '</span></div>' + progressBar(votes, eligible);
  }

  function squadBody(teamNode) {
    var squads = teamNode.squads;
    var done = Array.isArray(squads) && squads.length === 6;
    var roster = (squads || []).map(function (s) { return s.length; }).join(' / ');
    return '<div class="feed-meta"><span>' + (done ? '已分队 · 每队 ' + roster + ' 人' : '等待队长分队') + '</span><span>' + (done ? '完成' : '进行中') + '</span></div>' + progressBar(done ? 6 : 0, 6);
  }

  function rollBody(teamNode, state) {
    var total = (teamNode.players || []).length;
    var rolled = (teamNode.players || []).filter(function (p) { return p.dice != null; }).length;
    return '<div class="feed-meta"><span>已掷 ' + rolled + ' / ' + total + '</span>' + countdown(state.rollGoAt ? state.rollGoAt : state.stageDeadlineAt) + '</div>' + progressBar(rolled, total);
  }

  function blindBoxBody(teamNode) {
    var total = (teamNode.players || []).length;
    var opened = (teamNode.players || []).filter(function (p) { return p.blindBox != null; }).length;
    return '<div class="feed-meta"><span>已开 ' + opened + ' / ' + total + '</span><span>' + (opened >= total ? '完成' : '开盒中') + '</span></div>' + progressBar(opened, total);
  }

  function tacticsBody(teamNode) {
    var limit = Math.min(teamNode.rerollQuota || 0, 5);
    var used = teamNode.rerollUsed || 0;
    var locked = teamNode.squadOrderLocked;
    return '<div class="feed-meta"><span>重掷 ' + used + ' / ' + limit + '</span><span>' + (locked ? '出场顺序已锁定' : '待锁定顺序') + '</span></div>' + progressBar(used, limit);
  }

  /* ---------- 对局卡：6 局比分 + 每轮明细 ---------- */

  var matchDetails = {}, matchDetailPending = {};
  var lastState = null;

  function detailKey(match) { return match.id + ':' + (match.rounds || []).length; }
  function hasFullRounds(match) { var rounds = match.rounds || []; return rounds.length > 0 && rounds[0].powerA != null; }
  function loadMatchDetail(match, onDone) {
    var key = detailKey(match);
    if (matchDetails[key]) { if (onDone) onDone(matchDetails[key]); return; }
    var pending = matchDetailPending[key];
    if (!pending) {
      pending = fetch('/api/game-state/matches/' + encodeURIComponent(match.id))
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (d) { if (d) matchDetails[key] = d; return d; })
        .catch(function () { return null; })
        .finally(function () { delete matchDetailPending[key]; });
      matchDetailPending[key] = pending;
    }
    if (onDone) pending.then(function (d) { if (d) onDone(d); });
  }

  /* 管理员/大屏视角的 game-state 自带完整 rounds，直接渲染；普通用户视角只有局号与胜负，点击展开时再拉详情。 */
  function roundsSectionHtml(match, a, b) {
    var rounds = match.rounds || [];
    if (!rounds.length) return '';
    if (hasFullRounds(match)) return roundsListHtml(match, a, b);
    return '<div class="rounds-detail-slot" data-rounds-slot="' + esc(match.id) + '">'
      + '<button type="button" class="btn btn-ghost rounds-toggle-btn" data-rounds-match="' + esc(match.id) + '">查看逐局战况（' + rounds.length + ' 局）</button></div>';
  }

  function scoreCells(match, a, b) {
    var rounds = match.rounds || [];
    return '<div class="score-cells">' + [1, 2, 3, 4, 5, 6].map(function (n) {
      var entry = rounds.find(function (r) { return r.round === n; });
      var current = match.phase === 'BATTLE' && match.round === n;
      var cls = entry ? (entry.winner ? 'is-' + String(entry.winner).toLowerCase() : 'is-draw') : current ? 'is-current' : '';
      return '<div class="score-cell ' + cls + '"><i>' + n + '</i><b>' + (entry ? (entry.winner ? esc((entry.winner === 'A' ? a : b).name) : '平') : current ? '…' : '') + '</b></div>';
    }).join('') + '</div>';
  }

  function roundSideHtml(name, entry, side, won) {
    var crit = entry['crit' + side];
    return '<div class="round-side' + (won ? ' is-winner' : '') + '"><b>' + esc(name) + '</b><span>基础 ' + Number(entry['base' + side] || 0) + (crit ? ' <em>暴击 ×1.5</em>' : '') + '</span><span>猜中 ' + Number(entry['guessHits' + side] || 0) + ' · +' + Number(entry['guessBonus' + side] || 0) + '</span><strong>' + Number(entry['power' + side] || 0) + '</strong></div>';
  }
  function roundEntryHtml(entry, a, b) {
    var w = entry.winner, winnerName = w ? (w === 'A' ? a : b).name : '';
    return '<div class="round-entry"><div class="round-entry-head"><b>第 ' + Number(entry.round) + ' 局</b><span>' + (w ? esc(winnerName) + ' 胜' : '平局') + '</span></div><div class="round-entry-sides">' + roundSideHtml(a.name, entry, 'A', w === 'A') + roundSideHtml(b.name, entry, 'B', w === 'B') + '</div></div>';
  }
  function roundsListHtml(match, a, b) {
    var rounds = match.rounds || [];
    if (!rounds.length) return '';
    return '<div class="rounds-list">' + rounds.map(function (entry) { return roundEntryHtml(entry, a, b); }).join('') + '</div>';
  }

  function rerollLine(state, match) {
    var parts = [];
    ['A', 'B'].forEach(function (side) {
      var t = team(state, match[side === 'A' ? 'a' : 'b']);
      (t.rerollLog || []).forEach(function (log) {
        parts.push(esc(t.name) + '·' + esc(log.playerName || log.playerId) + ' ' + (log.from != null ? log.from : '?') + '→' + (log.to != null ? log.to : '?'));
      });
    });
    return parts.length ? '<div class="feed-meta"><span>重掷：' + parts.map(esc).join('，') + '</span></div>' : '';
  }

  function matchCard(state, match) {
    var a = team(state, match.a), b = team(state, match.b);
    var stage = TournamentUI.stage(match);
    var done = match.status === 'done';
    var result = match.phase === 'RESULT';
    var headerText = done ? '已完赛' : result ? '结果结算中' : match.phase === 'BATTLE' ? ('第 ' + Number(match.round || 1) + ' 局 · ' + (match.roundPhase === 'REVEAL' ? '结果揭晓中' : '猜阵进行中')) : '等待开赛';
    var winnerName = done && match.winner ? esc(team(state, match.winner).name) : '';
    var tieLine = result || done ? '<div class="feed-meta"><span>判定依据：' + esc(match.tieBreak || '胜场') + '</span>' + (match.totalPointsA != null ? '<span>总点数 ' + match.totalPointsA + ' : ' + match.totalPointsB + '</span>' : '') + '</div>' : '';
    var live = match.status === 'active';
    return '<article class="live-feed ' + (done ? 'finished ' : '') + (result ? 'showing-result ' : '') + 'match-card">'
      + '<header><span>' + esc(stage.label) + ' · ' + (live ? 'LIVE' : 'ARCHIVE') + '</span><b>' + esc(headerText) + '</b></header>'
      + '<div class="feed-score"><div><strong>' + esc(a.name) + '</strong><em>' + Number(match.winsA || 0) + '</em></div><span>:</span><div><em>' + Number(match.winsB || 0) + '</em><strong>' + esc(b.name) + '</strong></div></div>'
      + scoreCells(match, a, b)
      + roundsSectionHtml(match, a, b)
      + rerollLine(state, match)
      + tieLine
      + (done ? '<div class="feed-winner">胜者 · ' + winnerName + '</div>' : '')
      + '</article>';
  }

  /* ---------- 顶层渲染 ---------- */

  function renderStage(state, stageTitle) {
    var teams = state.teams || [];
    var label = STAGE_TITLES[state.stage] || stageTitle;
    document.getElementById('tb-status').textContent = label[0];
    var body;
    if (state.stage === 'CAPTAIN_VOTE') body = teams.map(function (t, i) { return teamCard(t, i, voteBody(t)); }).join('');
    else if (state.stage === 'SQUAD_FORM') body = teams.map(function (t, i) { return teamCard(t, i, squadBody(t)); }).join('');
    else if (state.stage === 'ROLL') body = teams.map(function (t, i) { return teamCard(t, i, rollBody(t, state)); }).join('');
    else if (state.stage === 'BLIND_BOX') body = teams.map(function (t, i) { return teamCard(t, i, blindBoxBody(t)); }).join('');
    else if (state.stage === 'TACTICS') body = teams.map(function (t, i) { return teamCard(t, i, tacticsBody(t)); }).join('');
    else body = '';
    document.getElementById('spectator-root').innerHTML = wallHead(label[0], label[1], STAGE_TITLES[state.stage] ? '八支队伍按同一时间轴并行推进，超时由系统自动兜底。' : '') + (body ? '<section class="feed-grid">' + body + '</section>' : '');
  }

  function render(state) {
    var root = document.getElementById('spectator-root');
    if (!state || state.mode !== 'parallel') {
      root.innerHTML = '<section class="spectator-empty"><b>等待管理员开始游戏</b><p>开赛后依次进入队长投票、分队、掷骰、盲盒、战术与六局对局。</p></section>';
      document.getElementById('tb-status').textContent = '等待开赛';
      return;
    }
    if (state.champion) {
      document.getElementById('tb-status').textContent = '第 ' + state.day + ' 天冠军已产生';
      var champion = team(state, state.champion);
      root.innerHTML = wallHead('CHAMPION · DAY ' + state.day, '今日冠军 · ' + champion.name, '总决赛已结束，比赛结果已同步保存。');
      return;
    }
    if (state.stage === 'BATTLE') {
      var matches = TournamentUI.matches(state);
      document.getElementById('tb-status').textContent = '六局对局 · ' + matches.filter(function (m) { return m.status === 'done'; }).length + ' 场已完赛';
      root.innerHTML = wallHead('PHASE SIX · LIVE BRACKET', '六局对局 · 实时赛程', '当前对阵优先显示，下方保留半决赛与首轮历史。每队按 1~6 号小队逐局对垒，胜场多者晋级。')
        + '<section class="feed-grid">' + matches.map(function (m) { return matchCard(state, m); }).join('') + '</section>';
      return;
    }
    renderStage(state, null);
  }

  function load() {
    fetch('/api/game-state').then(function (r) {
      if (r.status === 401) { location.replace('/login'); throw new Error(); }
      return r.status === 204 ? null : r.json();
    }).then(function (d) { lastState = d && d.state; render(lastState); refreshExpanded(lastState); }).catch(function () {});
  }

  /* 逐局战况按需展开：事件委托，展开状态跨刷新保留 */
  var expandedRounds = {};
  document.addEventListener('click', function (event) {
    var btn = event.target && event.target.closest ? event.target.closest('[data-rounds-match]') : null;
    if (!btn) return;
    var matchId = btn.getAttribute('data-rounds-match');
    var slot = btn.closest('[data-rounds-slot]');
    var match = lastState && lastState.matches ? lastState.matches[matchId] : null;
    if (!slot || !match) return;
    if (expandedRounds[matchId]) { expandedRounds[matchId] = false; render(lastState); return; }
    expandedRounds[matchId] = true;
    btn.disabled = true; btn.textContent = '战况加载中…';
    loadMatchDetail(match, function (detail) {
      if (!expandedRounds[matchId]) return;
      var a = team(lastState, detail.a), b = team(lastState, detail.b);
      slot.innerHTML = '<button type="button" class="btn btn-ghost rounds-toggle-btn" data-rounds-match="' + esc(matchId) + '">收起逐局战况</button>' + roundsListHtml(detail, a, b);
    });
  });

  /* 展开过的场次在每次刷新后自动补拉最新明细 */
  function refreshExpanded(state) {
    if (!state || !state.matches) return;
    Object.keys(expandedRounds).forEach(function (matchId) {
      if (!expandedRounds[matchId]) return;
      var match = state.matches[matchId];
      if (!match || hasFullRounds(match)) return;
      var slot = document.querySelector('[data-rounds-slot="' + matchId + '"]');
      if (!slot) return;
      loadMatchDetail(match, function (detail) {
        if (!expandedRounds[matchId]) return;
        var a = team(state, detail.a), b = team(state, detail.b);
        slot.innerHTML = '<button type="button" class="btn btn-ghost rounds-toggle-btn" data-rounds-match="' + esc(matchId) + '">收起逐局战况</button>' + roundsListHtml(detail, a, b);
      });
    });
  }

  document.getElementById('btn-logout').onclick = function () {
    fetch('/api/auth/logout', { method: 'POST' }).finally(function () { location.replace('/login'); });
  };

  /* SSE 驱动刷新 + 15 秒兜底慢轮询；页面隐藏时暂停，恢复可见立即补拉 */
  var refreshTimer = null;
  function queueLoad() {
    if (document.hidden || refreshTimer) return;
    refreshTimer = setTimeout(function () { refreshTimer = null; load(); }, 300);
  }
  function connectEvents() {
    var source = new EventSource('/api/lobby/events');
    source.onmessage = function (e) {
      var m;
      try { m = JSON.parse(e.data); } catch (err) { return; }
      if (m.type === 'game' || m.type === 'lobby') queueLoad();
    };
  }
  setInterval(function () { if (!document.hidden) load(); }, 15000);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) load(); });
  load();
  connectEvents();
})();
