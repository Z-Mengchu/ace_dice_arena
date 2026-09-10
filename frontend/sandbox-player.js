import { icon } from './icons.js';

(function () {
  'use strict';
  var teamIds = ['t1', 't2', 't3', 't4', 't5', 't6', 't7', 't8'];
  var teamId = new URLSearchParams(location.search).get('team') || 't1';
  if (teamIds.indexOf(teamId) < 0) teamId = 't1';
  var lobby = null, game = null;

  var STAGE_HEADLINE = {
    CAPTAIN_VOTE: '队长投票',
    SQUAD_FORM: '队长分队',
    ROLL: '全员掷骰',
    BLIND_BOX: '开盲盒',
    TACTICS: '战术窗口',
    BATTLE: '六局对局'
  };

  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function json(response) { return response.json().catch(function () { return {}; }).then(function (body) { if (response.status === 401) location.replace('/login'); if (!response.ok) throw new Error(body.error || '无法读取玩家视角'); return body; }); }
  function api(path) { return fetch(path).then(json); }
  function teamName(id) { var team = lobby && lobby.teams.find(function (item) { return item.id === id; }); return team ? team.name : id; }
  function playerName(team, id) { var p = (team && team.players || []).find(function (x) { return x.id === id; }); return p ? p.name : id; }
  function countdown(deadlineAt) { return deadlineAt == null ? '' : '剩余 ' + Math.max(0, Math.ceil((Number(deadlineAt) - Date.now()) / 1000)) + ' 秒'; }

  function activeMatch() {
    var matches = Object.keys((game && game.matches) || {}).map(function (key) { return game.matches[key]; });
    return matches.find(function (match) { return match.status === 'active' && (match.a === teamId || match.b === teamId); })
      || matches.slice().reverse().find(function (match) { return match.a === teamId || match.b === teamId; });
  }

  function tournamentCard(match) {
    var stage = TournamentUI.stage(match), winner = game.teams.find(function (team) { return team.id === match.winner; });
    var detail;
    if (match.status === 'done') detail = '胜者 · ' + (winner ? winner.name : match.winner) + (match.tieBreak && match.tieBreak !== '胜场' ? ' · 按' + match.tieBreak + '判定' : '');
    else if (match.phase === 'OVERTIME_PENDING') detail = '三连环全平 · 待加赛';
    else if (match.phase === 'RESULT') detail = '本场结果结算中 · ' + (match.tieBreak || '胜场');
    else if (match.phase === 'BATTLE') detail = '第 ' + Number(match.round || 1) + ' 局 · ' + (match.roundPhase === 'REVEAL' ? '结果揭晓中' : '猜阵进行中');
    else detail = '等待开赛';
    return '<article class="watch-card tournament-card ' + (match.status === 'active' ? 'is-live' : 'is-history') + '"><small><span>' + esc(stage.label) + '</span><i>' + TournamentUI.status(match) + '</i></small><div><b>' + esc(teamName(match.a)) + '</b><strong>' + Number(match.winsA || 0) + ' : ' + Number(match.winsB || 0) + '</strong><b>' + esc(teamName(match.b)) + '</b></div><p>' + esc(detail) + '</p></article>';
  }

  function scoreCells(match, side) {
    var rounds = match.rounds || [];
    return '<div class="score-cells">' + [1, 2, 3, 4, 5, 6].map(function (n) {
      var entry = rounds.find(function (r) { return r.round === n; });
      var current = match.phase === 'BATTLE' && match.round === n;
      var cls = entry ? (entry.winner ? (entry.winner === side ? 'is-win' : 'is-lose') : 'is-draw') : current ? 'is-current' : '';
      return '<div class="score-cell ' + cls + '"><i>' + n + '</i><b>' + (entry ? (entry.winner ? (entry.winner === side ? '胜' : '负') : '平') : current ? '…' : '') + '</b></div>';
    }).join('') + '</div>';
  }

  function roundSideHtml(name, entry, side, won) {
    var crit = entry['crit' + side];
    return '<div class="round-side' + (won ? ' is-winner' : '') + '"><b>' + esc(name) + '</b><span>基础 ' + Number(entry['base' + side] || 0) + (crit ? ' <em>暴击 ×1.5</em>' : '') + '</span><span>猜中 ' + Number(entry['guessHits' + side] || 0) + ' · +' + Number(entry['guessBonus' + side] || 0) + '</span><strong>' + Number(entry['power' + side] || 0) + '</strong></div>';
  }
  function roundsList(match, a, b) {
    var rounds = match.rounds || [];
    if (!rounds.length) return '';
    return '<div class="rounds-list">' + rounds.map(function (entry) {
      var w = entry.winner, winnerName = w ? (w === 'A' ? a : b).name : '';
      return '<div class="round-entry"><div class="round-entry-head"><b>第 ' + Number(entry.round) + ' 局</b><span>' + (w ? esc(winnerName) + ' 胜' : '平局') + '</span></div><div class="round-entry-sides">' + roundSideHtml(a.name, entry, 'A', w === 'A') + roundSideHtml(b.name, entry, 'B', w === 'B') + '</div></div>';
    }).join('') + '</div>';
  }

  function renderAction() {
    var box = document.getElementById('sandbox-action-view');
    if (!game) { box.innerHTML = '<p class="hub-eyebrow">PLAYER CONSOLE</p><h2>等待比赛建立</h2><p class="sandbox-muted">在总控台建立沙盘后，这里会显示普通用户的比赛操作界面。</p>'; return; }
    var mine = game.teams && game.teams.find(function (team) { return team.id === teamId; }) || { id: teamId, name: teamId, players: [] };
    var stage = game.stage;
    var head = '<div class="sandbox-action-head"><div><p class="hub-eyebrow">PLAYER CONSOLE</p><h2>' + esc(STAGE_HEADLINE[stage] || '实时赛况') + ' · ' + esc(teamName(teamId)) + '</h2></div></div>';
    var body = '', copy = '';

    if (stage === 'CAPTAIN_VOTE') {
      var captain = mine.roles && mine.roles.captain;
      var votes = Object.keys(mine.roleVotes || {}).length;
      var eligible = (mine.players || []).filter(function (p) { return !p.managed; }).length;
      body = '<div class="flow-detail"><span>队长</span><b>' + esc(captain ? playerName(mine, captain) : '投票中') + '</b></div><div class="flow-detail"><span>已投票</span><b>' + votes + ' / ' + eligible + '</b></div>';
      copy = '全员投票选出队长；平票 / 零投票按名单顺位选出。';
    } else if (stage === 'SQUAD_FORM') {
      var squads = mine.squads;
      var formed = Array.isArray(squads) && squads.length === 6;
      body = '<div class="flow-detail"><span>分队</span><b>' + (formed ? '已分队 6×5' : '待队长分队') + '</b></div>' + (formed ? '<div class="flow-detail"><span>各小队人数</span><b>' + squads.map(function (s) { return (s || []).length; }).join(' / ') + '</b></div>' : '');
      copy = '队长把 30 人拆成 6 支 5 人小队（1-6 出战位），超时系统自动随机均分。';
    } else if (stage === 'ROLL') {
      var total = (mine.players || []).length;
      var rolled = (mine.players || []).filter(function (p) { return p.dice != null; }).length;
      body = '<div class="flow-detail"><span>已掷</span><b>' + rolled + ' / ' + total + '</b></div><div class="flow-detail"><span>' + (game.rollGoAt ? '倒计时' : '截止') + '</span><b>' + countdown(game.rollGoAt ? game.rollGoAt : game.stageDeadlineAt) + '</b></div>';
      copy = '倒计时 3-2-1！6 个小队错峰 1 秒依次掷骰；同一小队 5 人掷骰时间差 ≤0.5s 解锁 ×1.5 默契暴击！' + icon('sparkle', 16);
    } else if (stage === 'BLIND_BOX') {
      var opened = (mine.players || []).filter(function (p) { return p.blindBox != null; }).length;
      body = '<div class="flow-detail"><span>已开</span><b>' + opened + ' / ' + (mine.players || []).length + '</b></div>';
      copy = '每人手动选盲盒，档位随机、有惊喜也有减益，25 秒不点按 0 分，系统不会帮你开盒。';
    } else if (stage === 'TACTICS') {
      var limit = Math.min(mine.rerollQuota || 0, 5);
      body = '<div class="flow-detail"><span>重掷</span><b>' + Number(mine.rerollUsed || 0) + ' / ' + limit + '</b></div><div class="flow-detail"><span>出场顺序</span><b>' + (mine.squadOrderLocked ? '已锁定' : '待锁定') + '</b></div>';
      copy = '队长消耗 GMV 兑换重掷机会（单场上限 5 次），自由调整小队出场顺序；重掷只刷新骰子点数，盲盒、暴击判定不受影响。';
    } else if (stage === 'BATTLE') {
      var match = activeMatch();
      if (match) {
        var a = game.teams.find(function (t) { return t.id === match.a; }) || { id: match.a, name: match.a };
        var b = game.teams.find(function (t) { return t.id === match.b; }) || { id: match.b, name: match.b };
        var detail;
        if (match.status === 'done') detail = '胜者 · ' + esc(teamName(match.winner)) + (match.tieBreak && match.tieBreak !== '胜场' ? ' · 按' + match.tieBreak + '判定' : '');
        else if (match.phase === 'OVERTIME_PENDING') detail = '三连环全平 · 待加赛';
        else if (match.phase === 'RESULT') detail = '本场结果结算中 · ' + esc(match.tieBreak || '胜场');
        else if (match.phase === 'BATTLE') detail = '第 ' + Number(match.round || 1) + ' 局 · ' + (match.roundPhase === 'REVEAL' ? '结果揭晓中' : '猜阵进行中');
        else detail = '等待开赛';
        body = '<div class="sandbox-action-head"><div><b>' + esc(a.name) + ' ' + Number(match.winsA || 0) + ' : ' + Number(match.winsB || 0) + ' ' + esc(b.name) + '</b><span>' + esc(detail) + '</span></div></div>' + scoreCells(match, match.a === teamId ? 'A' : 'B') + roundsList(match, a, b);
        copy = '每局出战 5 人可以秘密猜敌方出战人员，猜中 1 人 +0.4 战力，单局上限 +10 分！田忌赛马，博弈拉满';
      } else {
        body = '<p class="sandbox-muted">本队本轮没有对阵，等待后续赛程。</p>';
      }
    } else {
      var champ = game.champion ? game.teams.find(function (t) { return t.id === game.champion; }) : null;
      body = '<p class="hub-eyebrow">' + (champ ? 'DAY ' + game.day + ' CHAMPION · ' + esc(champ.name) : '本日赛程进行中') + '</p>';
    }

    var followHint = game.sandboxPlayers && game.sandboxPlayers.length
      ? '只读预览 · 双人沙盘：' + game.sandboxPlayers.map(function (player) { return player.displayName + ' / ' + teamName(player.teamId); }).join('，')
      : '只读预览 · 在总控台点击“推进入下一阶段”模拟玩家提交';
    box.innerHTML = head + '<p class="sandbox-action-copy">' + copy + '</p>' + body + '<div class="sandbox-readonly-note">' + esc(followHint) + '</div>';
  }

  function render() {
    var myTeam = lobby.teams.find(function (team) { return team.id === teamId; });
    document.getElementById('sandbox-signal').textContent = teamName(teamId) + ' · ' + (lobby.phase === 'FINISHED' ? '比赛结束' : '实时信号已接入');
    document.getElementById('sandbox-team-tabs').innerHTML = teamIds.map(function (id, index) { return '<a class="' + (id === teamId ? 'active' : '') + '" href="?team=' + id + '"><i>CH ' + (index + 1) + '</i><b>' + esc(teamName(id).replace('战区', '')) + '</b></a>'; }).join('');
    var headline = game && game.stage ? STAGE_HEADLINE[game.stage] || '实时赛况' : (lobby.phase === 'FINISHED' ? '本轮比赛结束' : '等待比赛安排');
    document.getElementById('sandbox-player-hero').innerHTML = '<div><p class="hub-eyebrow">ORDINARY PLAYER VIEW</p><h1>' + esc(headline) + '</h1><p>当前以 <b>' + esc(lobby.me.displayName) + '</b> 的身份观察 ' + esc(teamName(teamId)) + '。此页面展示普通用户在同一时刻看到的信息。</p></div><strong>' + esc(teamName(teamId).replace('战区', '')) + '<small>第 ' + teamId.slice(1) + ' 频道</small></strong>';
    document.getElementById('sandbox-roster-title').textContent = teamName(teamId) + '队伍成员';
    document.getElementById('sandbox-roster-meta').textContent = myTeam.members.length + '/30 · 全员已准备';
    document.getElementById('sandbox-roster').innerHTML = myTeam.members.map(function (user) { return '<div class="teammate ready"><b>' + esc(user.displayName) + '</b><span>' + esc(user.department) + '</span><i>' + (user.frontEnd ? 'FRONT' : 'BACK') + ' · READY</i></div>'; }).join('');
    document.getElementById('sandbox-matches').innerHTML = game && game.matches ? TournamentUI.matches(game).map(tournamentCard).join('') : lobby.matches.map(function (matchView) { return '<article class="watch-card"><small>GROUP ' + matchView.number + '</small><div><b>' + esc(matchView.nameA) + '</b><strong>' + matchView.scoreA + ' : ' + matchView.scoreB + '</strong><b>' + esc(matchView.nameB) + '</b></div></article>'; }).join('');
    renderAction();
  }
  function load() {
    Promise.all([api('/api/admin/test-mode/player-view?teamId=' + encodeURIComponent(teamId)), api('/api/game-state')]).then(function (results) {
      lobby = results[0]; game = results[1].state || null; render();
    }).catch(function (error) { var box = document.getElementById('sandbox-error'); box.textContent = error.message + '，请返回总控台建立沙盘。'; box.classList.remove('hidden'); });
  }
  load();
  /* SSE 事件驱动 + 10 秒兜底慢轮询；页面隐藏时暂停，恢复可见立即补拉 */
  var refreshTimer = null;
  function queueLoad() {
    if (document.hidden || refreshTimer) return;
    refreshTimer = setTimeout(function () { refreshTimer = null; load(); }, 300);
  }
  var source = new EventSource('/api/lobby/events');
  // （重）连上后补拉一次：断线期间错过的推进靠这次回源追平
  source.onopen = function () { queueLoad(); };
  source.onmessage = function (e) {
    var m;
    try { m = JSON.parse(e.data); } catch (err) { return; }
    if (m.type === 'game' || m.type === 'lobby') queueLoad();
  };
  setInterval(function () { if (!document.hidden) load(); }, 10000);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) load(); });
})();
