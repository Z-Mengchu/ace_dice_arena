(function () {
  'use strict';
  var teamIds = ['t1','t2','t3','t4','t5','t6','t7','t8'];
  var teamId = new URLSearchParams(location.search).get('team') || 't1';
  if (teamIds.indexOf(teamId) < 0) teamId = 't1';
  var lobby = null, game = null;

  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }
  function json(response) { return response.json().catch(function () { return {}; }).then(function (body) { if (response.status === 401) location.replace('/login'); if (!response.ok) throw new Error(body.error || '无法读取玩家视角'); return body; }); }
  function api(path) { return fetch(path).then(json); }
  function teamName(id) { var team = lobby && lobby.teams.find(function (item) { return item.id === id; }); return team ? team.name : id; }
  function phaseName(phase) { return {PROPHET:'军师预言',LINEUP:'队长选择阵容',CONFIRM_A:'A 队进攻确认',ROLL_A:'A 队五人同步点击',PITCHER_ROLL_A:'A 队王牌投手最终投骰',CONFIRM_B:'B 队进攻确认',ROLL_B:'B 队五人同步点击',PITCHER_ROLL_B:'B 队王牌投手最终投骰',RESULT:'本局结果',FINISHED:'比赛完成'}[phase] || '等待系统信号'; }
  function tournamentCard(match) {
    var stage=TournamentUI.stage(match),winner=game.teams.find(function(team){return team.id===match.winner;}),detail=match.status==='done'?'胜者 · '+(winner?winner.name:match.winner):match.phase==='RESULT'?'本场结果展示中':'单轮攻擂进行中';
    return '<article class="watch-card tournament-card '+(match.status==='active'?'is-live':'is-history')+'"><small><span>'+esc(stage.label)+'</span><i>'+TournamentUI.status(match)+'</i></small><div><b>'+esc(teamName(match.a))+'</b><strong>'+Number(match.winsA||0)+' : '+Number(match.winsB||0)+'</strong><b>'+esc(teamName(match.b))+'</b></div><p>'+esc(detail)+'</p></article>';
  }

  function activeMatch() {
    var matches = Object.keys((game && game.matches) || {}).map(function (key) { return game.matches[key]; });
    return matches.find(function (match) { return match.status === 'active' && (match.a === teamId || match.b === teamId); })
      || matches.slice().reverse().find(function (match) { return match.a === teamId || match.b === teamId; });
  }
  function playerCards(players, selected) {
    return '<div class="sandbox-choice-grid">' + players.map(function (player, index) {
      return '<div class="sandbox-choice ' + (selected && index < 5 ? 'selected' : '') + '"><b>' + esc(player.name || player.displayName) + '</b><small>' + esc(player.department) + ' · ' + ((player.role === 'back' || player.frontEnd === false) ? '后端' : '前端') + '</small></div>';
    }).join('') + '</div>';
  }
  function resultSide(team, roll, won) {
    return '<div class="round-result-side ' + (won ? 'is-winner' : '') + '"><b>' + esc(teamName(team.id)) + '</b><div class="round-result-dice">' + ((roll && roll.dice) || []).map(function (value) { return '<i>' + value + '</i>'; }).join('') + '</div><strong>' + esc(roll && roll.finalAttack == null ? '-' : roll.finalAttack) + '</strong><small>（积累 ' + esc(roll && roll.accumulationAttack || 0) + ' + 攻擂 ' + esc(roll && roll.attackPhaseAttack || 0) + '）× 业绩 ' + Number(roll && roll.growthCoefficient || 1).toFixed(4) + ' × 盲盒 ' + Number(roll && roll.attackBoostMultiplier || 1).toFixed(2) + '</small></div>';
  }
  function prophetResult(match, side, team, opponent) {
    var guess = match.prophet && match.prophet[side], hit = match.prophetResults && match.prophetResults[side];
    var names = !Array.isArray(guess) || !guess.length ? '未预言' : guess.map(function (id) { var player = (opponent.players || []).find(function (item) { return item.id === id; }); return esc(player ? player.name : id); }).join('、');
    return '<div class="prophet-result ' + (hit ? 'is-hit' : '') + '"><b>' + esc(team.name) + '军师</b><span>' + names + '</span><strong>' + (!Array.isArray(guess) || !guess.length ? '未预言' : hit ? '预言命中 · 攻击 +2' : '预言未命中') + '</strong></div>';
  }
  function renderAction() {
    var box = document.getElementById('sandbox-action-view'), match = activeMatch();
    if (!game) { box.innerHTML = '<p class="hub-eyebrow">PLAYER CONSOLE</p><h2>等待比赛建立</h2><p class="sandbox-muted">在总控台建立沙盘后，这里会显示普通用户的比赛操作界面。</p>'; return; }
    if (game.stage === 'ROLE_VOTE') { var roleTeam=game.teams.find(function(team){return team.id===teamId;}),roleLabels={captain:'队长',strategist:'军师',pitcher:'王牌投手'};box.innerHTML='<p class="hub-eyebrow">PHASE ONE · ROLE ELECTION</p><h2>核心角色投票 · '+esc(teamName(teamId))+'</h2><p class="sandbox-action-copy">当前正在投票选出'+esc(roleLabels[roleTeam.roleVoteStage]||'核心角色')+'。三项投票全部完成后才会进入积累期。</p><div class="sandbox-readonly-note">真实玩家请登录自己的账号，在正式队伍大厅完成匿名投票。</div>';return; }
    if (game.stage === 'ACCUMULATION') { var accumulationTeam=game.teams.find(function(team){return team.id===teamId;}),quota=accumulationTeam.accumulationQuota||0,rolled=accumulationTeam.accumulationRolled||0;box.innerHTML='<p class="hub-eyebrow">PHASE TWO · ACCUMULATION</p><h2>积累期 · '+esc(teamName(teamId))+'</h2><p class="sandbox-action-copy">每 10 万元 GMV 兑换 1 次掷骰机会，必须将 '+quota+' 次全部投完。</p><div class="accumulation-score"><div><small>掷骰进度</small><b>'+rolled+' / '+quota+'</b></div><div><small>剩余机会</small><b>'+Math.max(0,quota-rolled)+'</b></div><div><small>累计点数</small><b>'+(accumulationTeam.accumulationPoints||0)+'</b></div></div><div class="accumulation-dice">'+(accumulationTeam.accumulationDice||[]).map(function(value){return'<i>'+value+'</i>';}).join('')+'</div><div class="sandbox-readonly-note">真实玩家请登录自己的账号，在正式队伍大厅完成积累投骰。</div>';return; }
    if (!match) { box.innerHTML = '<p class="hub-eyebrow">PLAYER CONSOLE</p><h2>等待攻擂对阵</h2><p class="sandbox-muted">积累期完成后系统会自动建立攻擂流程。</p>'; return; }
    var side = match.a === teamId ? 'A' : 'B', opponentId = side === 'A' ? match.b : match.a;
    var mine = game.teams.find(function (team) { return team.id === teamId; });
    var opponent = game.teams.find(function (team) { return team.id === opponentId; });
    var phase = match.phase, submitted = match.submitted || {}, title = phaseName(phase), copy = '', cards = '';
    if (phase === 'RESULT') {
      var seconds = Math.max(0, Math.ceil(((match.resultReadyAt || Date.now()) - Date.now()) / 1000));
      var winnerName = teamName(match[match.roundWinner === 'A' ? 'a' : 'b']) + ' 拿下本场';
      var resultA = game.teams.find(function (team) { return team.id === match.a; }), resultB = game.teams.find(function (team) { return team.id === match.b; });
      cards = '<div class="round-result-board">' + resultSide(resultA, match.rolls && match.rolls.A, match.roundWinner === 'A') + '<div class="round-result-verdict"><span>RESULT</span><b>' + esc(winnerName) + '</b><small>' + (match.matchPoint ? '本场胜负已定' : '比分 ' + match.winsA + ' : ' + match.winsB) + '</small></div>' + resultSide(resultB, match.rolls && match.rolls.B, match.roundWinner === 'B') + '</div><div class="prophet-result-grid">' + prophetResult(match, 'A', resultA, resultB) + prophetResult(match, 'B', resultB, resultA) + '</div><p class="round-result-next">结果展示中 · 系统约 ' + seconds + ' 秒后自动推进</p>';
      copy = '双方五枚骰子与最终攻击值已锁定，当前阶段不接受新的玩家操作。';
    } else if (match.status === 'done') {
      var won=match.winner===teamId,stageInfo=TournamentUI.stage(match),stageMatches=TournamentUI.matches(game).filter(function(item){return TournamentUI.stage(item).rank===stageInfo.rank;}),stageDone=stageMatches.filter(function(item){return item.status==='done';}).length;
      title = won ? '本场获胜 · 等待其他对阵' : '本场结束 · 转入观战';
      copy = won ? '本队已经晋级，当前 '+stageInfo.shortLabel+' '+stageDone+' / '+stageMatches.length+' 场结束。全部结果产生后系统自动建立下一场对阵。' : '本队本日赛程已经结束，玩家可以继续查看后续实时赛况和使用队内频道。';
    } else if (phase === 'PROPHET') {
      copy = submitted['prophet' + side] ? '本队预言已经密封，正在等待对方。' : '军师需要从 ' + teamName(opponentId) + ' 选择可能出战的 5 人。';
      cards = playerCards((opponent && opponent.players) || [], false);
    } else if (phase === 'LINEUP') {
      copy = submitted['lineup' + side] ? '本队队长已经确定本轮 5 人阵容。' : '等待本队队长选择本轮 5 名出战队员。';
      cards = playerCards((mine && mine.players) || [], false);
    } else if (phase.indexOf('CONFIRM_') === 0) {
      var turn = phase.slice(-1);
      copy = turn === side ? '轮到本队进攻：队长确认后，由投手确认，系统随后等待五台设备。' : '对方队伍正在确认进攻，本队玩家看到等待提示。';
      cards = turn === side ? '<div class="sandbox-confirm-row"><span>队长：' + ((match.sync || {})['captain' + side] ? '已准备' : '等待确认') + '</span><span>投手：' + ((match.sync || {})['pitcher' + side] ? '已确认' : '等待确认') + '</span></div>' : '';
    } else if (phase.indexOf('ROLL_') === 0) {
      copy = phase.slice(-1) === side ? '本队五名出战队员正在完成同步点击，此阶段只记录操作时间。' : '对方正在完成五人同步点击，本队等待系统切换攻方。';
    } else if (phase.indexOf('PITCHER_ROLL_') === 0) {
      copy = phase.slice(-1) === side ? '五人同步点击已完成，等待本队王牌投手投出最终五枚骰子。' : '对方王牌投手正在完成最终投骰。';
    }
    var followHint = game.sandboxPlayers && game.sandboxPlayers.length
      ? '只读预览 · 双人沙盘：' + game.sandboxPlayers.map(function (player) { return player.displayName + ' / ' + teamName(player.teamId); }).join('，')
      : '只读预览 · 在总控台点击“推进入下一阶段”模拟玩家提交';
    box.innerHTML = '<div class="sandbox-action-head"><div><p class="hub-eyebrow">PLAYER CONSOLE · ' + esc(match.id.toUpperCase()) + '</p><h2>' + esc(title) + '</h2></div><span>单轮攻擂 · ' + esc(teamName(match.a)) + ' ' + match.winsA + ':' + match.winsB + ' ' + esc(teamName(match.b)) + '</span></div><p class="sandbox-action-copy">' + esc(copy) + '</p>' + cards + '<div class="sandbox-readonly-note">' + esc(followHint) + '</div>';
  }
  function render() {
    var myTeam = lobby.teams.find(function (team) { return team.id === teamId; });
    document.getElementById('sandbox-signal').textContent = teamName(teamId) + ' · ' + (lobby.phase === 'FINISHED' ? '比赛结束' : '实时信号已接入');
    document.getElementById('sandbox-team-tabs').innerHTML = teamIds.map(function (id, index) { return '<a class="' + (id === teamId ? 'active' : '') + '" href="?team=' + id + '"><i>CH ' + (index + 1) + '</i><b>' + esc(teamName(id).replace('战区','')) + '</b></a>'; }).join('');
    var match = activeMatch(), headline = game&&game.stage==='ROLE_VOTE'?'核心角色投票':game&&game.stage==='ACCUMULATION'?'积累期 · 全部次数必须投完':match ? phaseName(match.phase) : (lobby.phase === 'FINISHED' ? '本轮比赛结束' : '等待比赛安排');
    document.getElementById('sandbox-player-hero').innerHTML = '<div><p class="hub-eyebrow">ORDINARY PLAYER VIEW</p><h1>' + esc(headline) + '</h1><p>当前以 <b>' + esc(lobby.me.displayName) + '</b> 的身份观察 ' + esc(teamName(teamId)) + '。此页面展示普通用户在同一时刻看到的信息。</p></div><strong>' + esc(teamName(teamId).replace('战区','')) + '<small>第 ' + teamId.slice(1) + ' 频道</small></strong>';
    document.getElementById('sandbox-roster-title').textContent = teamName(teamId) + '队伍成员';
    document.getElementById('sandbox-roster-meta').textContent = myTeam.members.length + '/30 · 全员已准备';
    document.getElementById('sandbox-roster').innerHTML = myTeam.members.map(function (user) { return '<div class="teammate ready"><b>' + esc(user.displayName) + '</b><span>' + esc(user.department) + '</span><i>' + (user.frontEnd ? 'FRONT' : 'BACK') + ' · READY</i></div>'; }).join('');
    document.getElementById('sandbox-matches').innerHTML = game&&game.matches?TournamentUI.matches(game).map(tournamentCard).join(''):lobby.matches.map(function (matchView) { return '<article class="watch-card"><small>GROUP ' + matchView.number + '</small><div><b>' + esc(matchView.nameA) + '</b><strong>' + matchView.scoreA + ' : ' + matchView.scoreB + '</strong><b>' + esc(matchView.nameB) + '</b></div></article>'; }).join('');
    renderAction();
  }
  function load() {
    Promise.all([api('/api/admin/test-mode/player-view?teamId=' + encodeURIComponent(teamId)), api('/api/game-state')]).then(function (results) {
      lobby = results[0]; game = results[1].state || null; render();
    }).catch(function (error) { var box = document.getElementById('sandbox-error'); box.textContent = error.message + '，请返回总控台建立沙盘。'; box.classList.remove('hidden'); });
  }
  load(); setInterval(load, 2000);
})();
