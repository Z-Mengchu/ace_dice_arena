(function () {
  'use strict';
  var data = null;
  var performance = null;
  var testMode = null;
  var soloCandidates = [];
  var gameState = null;
  var eventRefreshTimer = null;
  var eventRefreshType = null;

  function api(path, method, body) {
    return fetch(path, {
      method: method || 'GET',
      headers: { 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined
    }).then(readResponse);
  }
  function readResponse(response) {
    return response.json().catch(function () { return {}; }).then(function (result) {
      if (response.status === 401) location.replace('/login');
      if (!response.ok) throw new Error(result.error || '操作失败');
      return result;
    });
  }
  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (character) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character];
    });
  }
  function phaseText(phase) {
    return { PREPARING: '等待分组', GROUPED: '准备中', PLAYING: '比赛中', FINISHED: '已结束' }[phase] || phase;
  }
  function money(value) {
    return Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 });
  }
  function render() {
    document.getElementById('hub-phase').textContent = phaseText(data.phase);
    document.getElementById('phase-lights').innerHTML = ['PREPARING', 'GROUPED', 'PLAYING'].map(function (phase) {
      return '<span class="' + (data.phase === phase ? 'on' : '') + '">' + phaseText(phase) + '</span>';
    }).join('');
    var users = [];
    data.teams.forEach(function (team) { users = users.concat(team.members); });
    var participants = users.filter(function (user) { return user.teamId; });
    var ready = participants.filter(function (user) { return user.ready; }).length;
    document.getElementById('ready-summary').innerHTML = '<strong>' + ready + ' / 240</strong><span>本轮参赛用户已准备</span>';
    document.getElementById('start-game').disabled = !data.allReady || data.phase !== 'GROUPED';
    var completedDays = gameState && gameState.dayResults ? Object.keys(gameState.dayResults).length : 0;
    document.getElementById('reset-ready').disabled = completedDays !== 1 || data.phase !== 'FINISHED';
    document.getElementById('start-game').textContent = '开始第 ' + Math.min(2, completedDays + 1) + ' 天比赛';
    document.getElementById('ready-all').disabled = participants.length === 0 || data.phase === 'PLAYING';
    document.getElementById('admin-stage-kicker').textContent=gameState&&gameState.stage==='ROLE_VOTE'?'第一阶段 · 三项匿名投票':gameState&&gameState.stage==='ACCUMULATION'?'第二阶段 · 八队必须全部投完':'赛事树 · 当前对阵优先';
    document.getElementById('admin-stage-title').textContent=gameState&&gameState.stage==='ROLE_VOTE'?'核心角色投票':gameState&&gameState.stage==='ACCUMULATION'?'积累期总进度':gameState&&gameState.matches?'当前赛程 · '+TournamentUI.currentStage(gameState):'分组与攻擂观战';
    document.getElementById('match-grid').innerHTML = gameState&&gameState.stage==='ACCUMULATION'
      ? gameState.teams.map(accumulationAdminHtml).join('') : gameState&&gameState.matches
        ? TournamentUI.matches(gameState).map(tournamentMatchHtml).join('') : data.matches.map(matchHtml).join('');
    bindForceButtons();
    renderFlowMonitor();
    document.getElementById('roster-count').textContent = participants.length + ' 名已分组 · ' + (users.length - participants.length) + ' 名观战';
    document.getElementById('admin-roster').innerHTML = data.teams.map(teamRoster).join('');
    renderPerformance();
    renderTwoDayRecords();
    renderTestMode();
    bindRoster();
  }
  function renderTwoDayRecords() {
    var results = gameState && gameState.dayResults ? gameState.dayResults : {};
    var completed = ['day1', 'day2'].filter(function (key) { return results[key]; }).length;
    var overall = gameState && gameState.overallResult;
    document.getElementById('two-day-progress').textContent = completed + ' / 2 天';
    var dayCards = [1, 2].map(function (day) {
      var result = results['day' + day];
      if (!result) {
        var waiting = day === 1 || results.day1 ? '等待本日开赛' : '第 1 天结束后可重新分组';
        return '<article class="day-record is-empty"><header><i>DAY ' + day + '</i><b>第 ' + day + ' 天</b></header><p>' + waiting + '</p></article>';
      }
      var champion = (result.teams || []).find(function (team) { return team.id === result.champion; });
      var rows = (result.teams || []).slice().sort(function (a, b) { return b.matchWins - a.matchWins || b.roundWins - a.roundWins; }).map(function (team) {
        return '<div class="day-team ' + (team.id === result.champion ? 'is-champion' : '') + '"><span><b>' + esc(team.name) + '</b><small>系数 ×' + Number(team.growthCoefficient || 1).toFixed(4) + '</small></span><strong>' + team.matchWins + ' 胜 / ' + team.matchLosses + ' 负</strong></div>';
      }).join('');
      var roster = !overall && day === completed && champion ? TournamentUI.resultRosterHtml([{ day: day, team: champion }]) : '';
      return '<article class="day-record"><header><i>DAY ' + day + '</i><b>第 ' + day + ' 天</b><span>冠军 · ' + esc(champion ? champion.name : result.champion) + '</span></header><div class="day-team-list">' + rows + '</div>' + roster + '</article>';
    }).join('');
    var winner = overall && (overall.standings || []).find(function (team) { return team.id === overall.champion; });
    var winnerEntries = winner ? [1, 2].map(function (day) { var result = results['day' + day], team = result && (result.teams || []).find(function (item) { return item.id === overall.champion; }); return team ? { day: day, team: team } : null; }).filter(Boolean) : [];
    var overallCard = winner ? '<article class="day-record overall-record"><header><i>FINAL</i><b>两天最终总冠军</b><span>冠军 · ' + esc(winner.name) + '</span></header><div class="overall-champion-score"><strong>' + winner.totalMatchWins + ' 场胜利</strong><small>两天业绩增长率合计 ' + Number(winner.totalGrowthRate || 0).toFixed(2) + '%</small></div>' + TournamentUI.resultRosterHtml(winnerEntries) + '</article>' : '';
    document.getElementById('two-day-records').innerHTML = dayCards + overallCard;
  }
  function renderTestMode() {
    if (!testMode) return;
    var deck = document.getElementById('test-mode-deck');
    deck.classList.toggle('hidden', !testMode.enabled);
    if (!testMode.enabled) return;
    var phase = testMode.active ? phaseText(testMode.phase) : '未建立';
    document.getElementById('test-mode-phase').textContent = phase;
    document.getElementById('test-mode-meta').textContent = testMode.champion
      ? '冠军：' + testMode.champion
      : (testMode.active ? testMode.testUsers + ' 名临时队员 · 全赛场同步推进' : '不会使用真实玩家操作');
    document.getElementById('test-mode-prepare').disabled = testMode.active;
    var boundPlayers = testMode.sandboxPlayers || [];
    document.getElementById('test-mode-advance').disabled = !testMode.active || testMode.phase !== 'PLAYING' || boundPlayers.length > 0;
    document.getElementById('test-mode-cleanup').disabled = !testMode.active;
    document.getElementById('test-mode-player-view').classList.toggle('is-disabled', !testMode.active);
    var consoleBox = document.getElementById('sandbox-player-console');
    var playerA = document.getElementById('sandbox-player-a'), playerB = document.getElementById('sandbox-player-b');
    var teamA = document.getElementById('sandbox-team-a'), teamB = document.getElementById('sandbox-team-b');
    var identityA = document.getElementById('sandbox-identity-a'), identityB = document.getElementById('sandbox-identity-b');
    var bindButton = document.getElementById('sandbox-players-bind');
    var previousA = playerA.value, previousB = playerB.value, previousTeamA = teamA.value, previousTeamB = teamB.value;
    consoleBox.classList.remove('hidden');
    consoleBox.classList.toggle('is-waiting', !testMode.active);
    if (!testMode.active) {
      document.getElementById('formal-player-entry').classList.add('hidden');
      document.getElementById('sandbox-player-title').textContent = '先建立沙盘，再指定两名真实玩家';
      document.getElementById('sandbox-player-hint').textContent = '点击上方“建立沙盘”，成功创建临时席位后，这里会载入真实用户。';
      playerA.innerHTML = playerB.innerHTML = '<option value="">请先建立沙盘</option>';
      playerA.disabled = playerB.disabled = teamA.disabled = teamB.disabled = identityA.disabled = identityB.disabled = bindButton.disabled = true;
      bindButton.textContent = '等待沙盘建立';
      return;
    }
    var playerOptions = soloCandidates.length ? soloCandidates.map(function (candidate) {
      return '<option value="' + esc(candidate.username) + '">' + esc(candidate.displayName) + ' · ' + esc(candidate.department) + ' (' + esc(candidate.username) + ')</option>';
    }).join('') : '<option value="">暂无真实普通用户</option>';
    playerA.innerHTML = playerOptions; playerB.innerHTML = playerOptions;
    var teamOptions = data.teams.filter(function (team) { return team.id !== 'spectator'; }).map(function (team) {
      return '<option value="' + team.id + '">' + esc(team.name) + '</option>';
    }).join('');
    teamA.innerHTML = teamOptions;
    if (previousA && soloCandidates.some(function (candidate) { return candidate.username === previousA; })) playerA.value = previousA;
    if (previousB && soloCandidates.some(function (candidate) { return candidate.username === previousB; })) playerB.value = previousB;
    else if (soloCandidates.length > 1) playerB.selectedIndex = 1;
    teamA.value = previousTeamA || 't1';
    function renderSecondTeams(preferred) {
      var index = Number(teamA.value.slice(1)) - 1;
      var opponentId = 't' + (index % 2 === 0 ? index + 2 : index);
      teamB.innerHTML = data.teams.filter(function (team) { return team.id === teamA.value || team.id === opponentId; }).map(function (team) {
        return '<option value="' + team.id + '">' + esc(team.name) + (team.id === teamA.value ? ' · 同队' : ' · 对手') + '</option>';
      }).join('');
      teamB.value = Array.from(teamB.options).some(function (option) { return option.value === preferred; }) ? preferred : teamA.value;
    }
    renderSecondTeams(previousTeamB || 't1');
    teamA.onchange = function () { renderSecondTeams(teamA.value); };
    var bound = boundPlayers.length === 2;
    var formalEntry = document.getElementById('formal-player-entry');
    formalEntry.classList.toggle('hidden', !bound);
    document.getElementById('sandbox-player-title').textContent = bound ? '双人沙盘信号已锁定' : '指定两名真实玩家';
    document.getElementById('sandbox-player-hint').textContent = bound
      ? boundPlayers.map(function (player) { return player.displayName + ' · 队伍 ' + player.teamId.slice(1) + ' · ' + (player.identity === 'back' ? '后端' : '前端'); }).join(' / ')
      : '可分别指定前端或后端身份；同队可测试频道与共同投票，相对队伍可控制比赛双方。';
    playerA.disabled = playerB.disabled = teamA.disabled = teamB.disabled = identityA.disabled = identityB.disabled = bound;
    bindButton.disabled = bound || soloCandidates.length < 2;
    bindButton.textContent = bound ? '双人玩家已锁定' : '锁定双人沙盘';
    if (bound) {
      playerA.value = boundPlayers[0].username; playerB.value = boundPlayers[1].username;
      teamA.value = boundPlayers[0].teamId; renderSecondTeams(boundPlayers[1].teamId);
      identityA.value = boundPlayers[0].identity || 'front'; identityB.value = boundPlayers[1].identity || 'front';
      document.getElementById('test-mode-player-view').href = '/sandbox-player?team=' + boundPlayers[0].teamId;
      document.getElementById('copy-player-a-login').dataset.username = boundPlayers[0].username;
      document.getElementById('copy-player-b-login').dataset.username = boundPlayers[1].username;
    }
  }
  function renderPerformance() {
    if (!performance) return;
    var clean = performance.unmatchedNames.length === 0 && performance.ambiguousNames.length === 0;
    document.getElementById('performance-state').textContent = performance.totalRows ? (clean ? '数据可分组' : '需要修正') : '尚未载入';
    document.getElementById('performance-state').className = 'performance-state ' + (clean && performance.totalRows ? 'is-ready' : '');
    document.getElementById('random-group').disabled = !performance.canGroup || data.phase === 'PLAYING';
    if (!performance.totalRows) {
      document.getElementById('performance-summary').innerHTML = '<p>下载模板并载入业绩后，这里会显示匹配人数与本轮前端 GMV。</p>';
      return;
    }
    var warnings = [];
    if (performance.unmatchedNames.length) warnings.push('未匹配：' + performance.unmatchedNames.map(esc).join('、'));
    if (performance.ambiguousNames.length) warnings.push('重名或重复：' + performance.ambiguousNames.map(esc).join('、'));
    if (performance.groupingIssue) warnings.push(esc(performance.groupingIssue));
    document.getElementById('performance-summary').innerHTML =
      '<div><strong>' + performance.matchedUsers + '</strong><span>名前端 · ' + performance.totalRows + ' 行业绩</span></div>' +
      '<div><strong>¥' + money(performance.totalGmv) + '</strong><span>本轮前端总 GMV</span></div>' +
      (warnings.length ? '<p class="import-warning">' + warnings.join('<br>') + '</p>' : '<p class="import-ok">姓名匹配完成，可以生成分组。</p>');
  }
  function matchHtml(match) {
    var a = data.teams.find(function (team) { return team.id === match.teamA; });
    var b = data.teams.find(function (team) { return team.id === match.teamB; });
    var stateA = gameState && gameState.teams && gameState.teams.find(function (team) { return team.id === match.teamA; });
    var stateB = gameState && gameState.teams && gameState.teams.find(function (team) { return team.id === match.teamB; });
    var attackLine = stateA && stateB && gameState.stage === 'ATTACK'
      ? '<p class="match-attack-line">积累攻击 ' + (stateA.accumulationPoints || 0) + ' × ' + Number(stateA.growthCoefficient || 1).toFixed(4) + ' / ' + (stateB.accumulationPoints || 0) + ' × ' + Number(stateB.growthCoefficient || 1).toFixed(4) + '</p>' : '';
    return '<article class="watch-card"><small>GROUP ' + match.number + ' · ' + ((a ? a.members.length : 0) + (b ? b.members.length : 0)) + '/60 人</small><div><b>' + esc(match.nameA) + '</b><strong>' + match.scoreA + ' : ' + match.scoreB + '</strong><b>' + esc(match.nameB) + '</b></div><p>' + (a ? a.readyCount : 0) + '/' + (a ? a.members.length : 0) + ' 准备 · ' + (b ? b.readyCount : 0) + '/' + (b ? b.members.length : 0) + ' 准备</p>' + attackLine + '</article>';
  }
  var forceBound = false;
  /** 现场兜底：某一环节等不到人时，管理员可以立刻推进本局。 */
  function bindForceButtons() {
    if (forceBound) return;
    forceBound = true;
    document.getElementById('match-grid').addEventListener('click', function (event) {
      var button = event.target.closest ? event.target.closest('[data-force-match]') : null;
      if (!button) return;
      var matchId = button.getAttribute('data-force-match');
      if (!window.confirm('确认强制推进本局？系统会立即按超时规则处理当前等待中的环节。')) return;
      button.disabled = true;
      api('/api/admin/matches/' + encodeURIComponent(matchId) + '/force', 'POST')
        .then(function (result) {
          button.textContent = '已推进：' + (result && result.forced ? result.forced.join('、') : matchId);
        })
        .catch(function (error) {
          button.disabled = false;
          window.alert(error && error.message ? error.message : '推进失败');
        });
    });
  }

  function tournamentMatchHtml(match) {
    var stage=TournamentUI.stage(match),teams=gameState.teams||[],a=teams.find(function(team){return team.id===match.a;}),b=teams.find(function(team){return team.id===match.b;}),rollA=match.rolls&&match.rolls.A,rollB=match.rolls&&match.rolls.B;
    var detail=match.status==='done'?'胜者 · '+esc((teams.find(function(team){return team.id===match.winner;})||{}).name||match.winner):match.phase==='RESULT'?'本场结果展示中':'单轮攻擂进行中';
    var attack=rollA&&rollA.finalAttack!=null||rollB&&rollB.finalAttack!=null?'<p class="match-attack-line">最终攻击 '+(rollA&&rollA.finalAttack!=null?Number(rollA.finalAttack).toFixed(2):'--')+' / '+(rollB&&rollB.finalAttack!=null?Number(rollB.finalAttack).toFixed(2):'--')+'</p>':'';
    return '<article class="watch-card tournament-card '+(match.status==='active'?'is-live':'is-history')+'"><small><span>'+esc(stage.label)+'</span><i>'+TournamentUI.status(match)+'</i></small><div><b>'+esc(a?a.name:match.a)+'</b><strong>'+Number(match.winsA||0)+' : '+Number(match.winsB||0)+'</strong><b>'+esc(b?b.name:match.b)+'</b></div><p>'+detail+'</p>'+attack+(match.status==='active'?'<button class="btn btn-ghost btn-force" data-force-match="'+esc(match.id)+'">强制推进本局</button>':'')+'</article>';
  }
  function accumulationAdminHtml(team) {
    var quota=team.accumulationQuota||0,rolled=team.accumulationRolled||0,done=rolled>=quota,rolling=team.accumulationRolling;
    var action=done?'':('<button class="btn btn-primary accumulation-roll-all" data-team-id="'+esc(team.id)+'" '+(rolling?'disabled':'')+'>'+(rolling?'玩家掷骰中':'一键投完剩余骰子')+'</button>');
    return '<article class="watch-card accumulation-admin '+(done?'complete':'')+'"><small>积累期 · '+(done?'已完成':'进行中')+'</small><div><b>'+esc(team.name)+'</b><strong>'+rolled+' / '+quota+'</strong><b>攻击 '+(team.accumulationPoints||0)+'</b></div><p>GMV ¥'+money(team.gmv)+' · 系数 ×'+Number(team.growthCoefficient||1).toFixed(4)+' · 剩余 '+Math.max(0,quota-rolled)+' 次</p>'+action+'</article>';
  }
  function teamPlayerName(team, playerId) {
    var player = (team.players || []).find(function (candidate) { return candidate.id === playerId; });
    return player ? player.name : (playerId || '待定');
  }
  function roleVoteHtml(team) {
    var labels = { captain: '队长', strategist: '军师', pitcher: '王牌投手' };
    var roles = team.roles || {}, allVotes = team.roleVotes || {};
    return ['captain', 'strategist', 'pitcher'].map(function (role) {
      var votes = allVotes[role] || {}, tally = {}, voters = [];
      Object.keys(votes).forEach(function (voterId) {
        var voter = (team.players || []).find(function (player) { return player.id === voterId; });
        if (!voter || voter.managed) return;
        var candidateId = votes[voterId];
        tally[candidateId] = (tally[candidateId] || 0) + 1;
        voters.push(teamPlayerName(team, voterId) + ' → ' + teamPlayerName(team, candidateId));
      });
      var ranking = Object.keys(tally).sort(function (a, b) { return tally[b] - tally[a]; }).map(function (candidateId) {
        return teamPlayerName(team, candidateId) + ' ' + tally[candidateId] + '票';
      }).join('、');
      var elected = roles[role] ? '<b>当选：' + esc(teamPlayerName(team, roles[role])) + '</b>' : '<b>' + (team.roleVoteStage === role ? '投票中' : '待开始') + '</b>';
      var eligibleVoters=(team.players||[]).filter(function(player){return !player.managed;}).length,summary=ranking||'暂无有效票';
      if(roles[role])summary+=' · 弃票 '+Math.max(0,eligibleVoters-voters.length)+' 人';
      var assigned=Object.keys(roles).map(function(key){return roles[key];}),candidates=(team.players||[]).filter(function(player){return !player.managed&&assigned.indexOf(player.id)<0;});
      var adminControl=gameState&&gameState.stage==='ROLE_VOTE'&&team.roleVoteStage===role?'<div class="admin-role-assign"><select data-admin-role-candidate>'+candidates.map(function(player){return'<option value="'+esc(player.id)+'">'+esc(player.name)+' · '+(player.role==='back'?'后端':'前端')+'</option>';}).join('')+'</select><button class="btn btn-primary" data-assign-team="'+esc(team.id)+'" data-assign-role="'+role+'" '+(candidates.length?'':'disabled')+'>管理员指定'+labels[role]+'</button></div>':'';
      return '<div class="flow-role-row"><span>' + labels[role] + '</span>' + elected + '<small>' + summary + '</small>' +
        (voters.length ? '<details><summary>查看 ' + voters.length + ' 张实名选票</summary><p>' + voters.map(esc).join('<br>') + '</p></details>' : '') + adminControl + '</div>';
    }).join('');
  }
  function matchForTeam(teamId) {
    var matches = gameState && gameState.matches ? Object.keys(gameState.matches).map(function (key) { return gameState.matches[key]; }) : [];
    return matches.find(function (match) { return match.status === 'active' && (match.a === teamId || match.b === teamId); }) ||
      matches.slice().reverse().find(function (match) { return match.a === teamId || match.b === teamId; });
  }
  function attackFlowHtml(team) {
    var match = matchForTeam(team.id);
    if (!match) return '<div class="flow-empty">等待本队对局建立</div>';
    var side = match.a === team.id ? 'A' : 'B', sidePhase = match.phase === 'ATTACKING' && match.sidePhases ? match.sidePhases[side] : null, phases = {
      PROPHET: '军师预言', LINEUP: '队长选择阵容', CONFIRM_A: 'A 队确认进攻', CONFIRM_B: 'B 队确认进攻',
      ROLL_A: 'A 队五人同步点击', ROLL_B: 'B 队五人同步点击', PITCHER_ROLL_A: 'A 队王牌投手最终投骰', PITCHER_ROLL_B: 'B 队王牌投手最终投骰',
      PREPARING: '五人备战准备', COUNTDOWN: '队长发令 · 3 秒倒计时', CONFIRM: '准备进攻', ROLL: '五人同步点击', PITCHER_ROLL: '王牌投手最终投骰', WAITING: '进攻完成，等待对方', RESULT: '本局结果展示', FINISHED: '本场结束'
    };
    var prophet = match.prophet && match.prophet[side], lineup = match.lineups && match.lineups[side];
    var roll = match.rolls && match.rolls[side], dice = roll && roll.dice || [], diceSum = dice.reduce(function (sum, value) { return sum + Number(value || 0); }, 0), timing=match.timing||{},timingReady=timing['spreadMs'+side]!=null;
    var prophetClock=match.phase==='PROPHET'?'<i class="vote-countdown" data-prophet-deadline="'+Number(match.prophetDeadlineAt||0)+'">剩余 '+Math.max(0,Math.ceil((Number(match.prophetDeadlineAt||Date.now())-Date.now())/1000))+' 秒</i>':'';
    return '<div class="flow-match-stage"><span>当前流程</span><b>' + esc(phases[sidePhase || match.phase] || sidePhase || match.phase) + prophetClock + '</b><small>双方并行攻擂 · 比分 ' + (match.winsA || 0) + ' : ' + (match.winsB || 0) + '</small></div>' +
      '<div class="flow-detail"><span>军师预言</span><b>' + (Array.isArray(prophet) ? (prophet.length ? prophet.map(function (id) { return esc(teamPlayerName((gameState.teams || []).find(function (candidate) { return candidate.id === (side === 'A' ? match.b : match.a); }) || {}, id)); }).join('、') : '放弃预言') : '待提交') + '</b></div>' +
      '<div class="flow-detail"><span>出战阵容</span><b>' + (Array.isArray(lineup) && lineup.length ? lineup.map(function (id) { return esc(teamPlayerName(team, id)); }).join('、') : '待选出') + '</b></div>' +
      '<div class="flow-dice-result"><span>默契计时 / 最终骰子</span><div>' + (dice.length ? dice.map(function (value) { return '<i>' + value + '</i>'; }).join('') : '<small>'+(timingReady?'五人点击完成，等待王牌投手最终投骰':'等待五名出战队员同步点击')+'</small>') + '</div><b>' + (dice.length ? '点数 ' + diceSum + ' · 攻擂攻击 ' + Number(roll.attackPhaseAttack == null ? roll.attack || 0 : roll.attackPhaseAttack).toFixed(2) : timingReady?('最大误差 '+Number(timing['spreadMs'+side]).toFixed(0)+' ms · '+(timing['syncOk'+side]?'×1.5':'原始攻击力')):'尚未完成计时') + '</b></div>' +
      '<div class="flow-attack-total"><span>积累攻击 ' + Number(team.accumulationPoints || 0) + '</span><strong>' + (roll && roll.finalAttack != null ? '本轮总攻击力 ' + Number(roll.finalAttack).toFixed(2) + ' · 盲盒 ×' + Number(roll.attackBoostMultiplier || 1).toFixed(2) : '系数 ×' + Number(team.growthCoefficient || 1).toFixed(4)) + '</strong></div>';
  }
  function renderFlowMonitor() {
    var grid = document.getElementById('team-flow-grid'), stage = document.getElementById('flow-monitor-stage');
    if (!gameState || !gameState.teams) {
      stage.textContent = '等待开赛'; grid.innerHTML = '<p class="flow-empty">比赛开始后，这里会显示八支队伍的完整实时流程。</p>'; return;
    }
    stage.textContent = gameState.stage === 'ROLE_VOTE' ? '核心角色投票' : gameState.stage === 'ACCUMULATION' ? '积累期' : '攻擂战';
    grid.innerHTML = gameState.teams.map(function (team) {
      var quota = team.accumulationQuota || 0, rolled = team.accumulationRolled || 0, dice = team.accumulationDice || [];
      var accumulation = '<div class="flow-accumulation"><span>积累期</span><b>' + rolled + ' / ' + quota + ' 次 · ' + Number(team.accumulationPoints || 0) + ' 点</b><div>' + dice.slice(-12).map(function (value) { return '<i>' + value + '</i>'; }).join('') + '</div><small>' + (dice.length > 12 ? '显示最近 12 枚 · ' : '') + 'GMV ¥' + money(team.gmv) + '</small></div>';
      return '<article class="team-flow-card"><header><div><small>' + esc(team.id.toUpperCase()) + '</small><h3>' + esc(team.name) + '</h3></div><span>' + (gameState.stage === 'ACCUMULATION' ? (rolled >= quota ? '积累完成' : '积累中') : (team.roleVoteStage === 'complete' ? '角色已确定' : '角色投票中')) + '</span></header>' + accumulation + roleVoteHtml(team) + (gameState.stage === 'ATTACK' ? attackFlowHtml(team) : '') + '</article>';
    }).join('');
  }
  function teamRoster(team) {
    var cap = team.id === 'spectator' ? '观战' : team.members.length + '/30';
    var teamGmv = team.members.reduce(function (sum, user) { return sum + (user.frontEnd ? Number(user.gmv) : 0); }, 0);
    var frontCount = team.members.filter(function (user) { return user.frontEnd; }).length;
    var stats = team.id === 'spectator' ? cap : cap + ' · 前端 ' + frontCount + ' · GMV ¥' + money(teamGmv);
    return '<div class="roster-team"><h3>' + esc(team.name) + ' <small>' + stats + ' · ' + team.readyCount + ' 已准备</small></h3>' + team.members.map(function (user) {
      var role = user.frontEnd ? '<em class="role-chip front">前端 · ¥' + money(user.gmv) + '</em>' : '<em class="role-chip">后端</em>';
      var displayName = user.standIn ? (user.originalDisplayName || user.displayName) : user.displayName;
      var managedChip = user.standIn ? '<em class="managed-seat-chip"><i></i>托管替补</em>' : (user.afk ? '<em class="managed-seat-chip afk"><i></i>挂机托管</em>' : '');
      var standInAction = user.standIn
        ? '<button class="restore-player-button" data-restore-stand-in="' + user.id + '" type="button" ' + (data.phase === 'GROUPED' ? '' : 'disabled title="仅准备阶段可恢复"') + '>恢复真实队员</button>'
        : (team.id !== 'spectator' && data.phase === 'GROUPED' ? '<button class="btn btn-ghost stand-in-button" data-stand-in-user="' + user.id + '" type="button">设为托管替补</button>' : '');
      var teamControl = user.standIn ? '<div class="managed-team-lock"><small>锁定席位</small><b>队伍 ' + esc((user.teamId || '').slice(1)) + '</b></div>' : '<select data-user="' + user.id + '">' + ['', 't1', 't2', 't3', 't4', 't5', 't6', 't7', 't8'].map(function (id) {
        return '<option value="' + id + '" ' + (user.teamId === id ? 'selected' : '') + '>' + (id ? ('队伍 ' + id.slice(1)) : '观战席') + '</option>';
      }).join('') + '</select>';
      return '<div class="roster-user '+(user.standIn?'is-stand-in':(user.afk?'is-afk':''))+'"><span><b>' + esc(displayName) + managedChip + '</b><small>' + esc(user.department) + ' ' + role + '</small></span><i class="' + (user.ready ? 'ready':'') + '">' + (user.standIn ? '自动准备' : (user.afk ? '挂机托管' : (user.ready ? '已准备' : '等待'))) + '</i>' + teamControl + standInAction + '</div>';
    }).join('') + '</div>';
  }
  function bindRoster() {
    document.querySelectorAll('[data-user]').forEach(function (select) {
      select.onchange = function () {
        api('/api/admin/users/' + select.dataset.user + '/team', 'PUT', { teamId: select.value || null }).then(load).catch(showError);
      };
    });
    document.querySelectorAll('[data-stand-in-user]').forEach(function (button) {
      button.onclick = function () {
        if (!window.confirm('确定将该成员移入观战席，并用系统托管的沙盘队友补位吗？')) return;
        button.disabled = true;
        api('/api/admin/users/' + button.dataset.standInUser + '/stand-in', 'POST', {}).then(load).catch(showError);
      };
    });
    document.querySelectorAll('[data-restore-stand-in]').forEach(function (button) {
      button.onclick = function () {
        if (!window.confirm('恢复后，真实队员将回到该队伍并重新确认准备。是否继续？')) return;
        button.disabled = true;
        api('/api/admin/users/' + button.dataset.restoreStandIn + '/stand-in/restore', 'POST', {}).then(load).catch(showError);
      };
    });
  }
  function showError(error) {
    var element = document.getElementById('admin-error');
    element.textContent = error.message; element.classList.remove('hidden');
  }
  function clearError() { document.getElementById('admin-error').classList.add('hidden'); }
  function load() {
    Promise.all([api('/api/admin/dashboard'), api('/api/admin/performance/status'), api('/api/admin/test-mode/status'), api('/api/admin/test-mode/solo-candidates'), api('/api/game-state')]).then(function (results) {
      data = results[0]; performance = results[1]; testMode = results[2]; soloCandidates = results[3]; gameState=results[4]&&results[4].state||null; render();
    }).catch(showError);
  }
  function loadGameStateOnly() {
    api('/api/game-state').then(function (result) {
      gameState = result && result.state || null;
      render();
    }).catch(showError);
  }
  function queueEventRefresh(type) {
    if (type === 'lobby') eventRefreshType = 'lobby';
    else if (!eventRefreshType) eventRefreshType = 'game';
    if (eventRefreshTimer) return;
    eventRefreshTimer = setTimeout(function () {
      var pending = eventRefreshType;
      eventRefreshTimer = null; eventRefreshType = null;
      if (pending === 'lobby') load(); else loadGameStateOnly();
    }, 200);
  }
  function connectStateEvents() {
    var events = new EventSource('/api/lobby/events');
    events.onopen = function () { queueEventRefresh('lobby'); };
    events.onmessage = function (event) {
      var message = JSON.parse(event.data);
      if (message.type === 'lobby' || message.type === 'game') queueEventRefresh(message.type);
    };
  }

  document.getElementById('performance-file').onchange = function (event) {
    var file = event.target.files[0];
    if (!file) return;
    clearError();
    var form = new FormData(); form.append('file', file);
    document.getElementById('performance-state').textContent = '正在载入…';
    fetch('/api/admin/performance/import', { method: 'POST', body: form }).then(readResponse)
      .then(function (result) { performance = result; render(); })
      .catch(showError).finally(function () { event.target.value = ''; });
  };
  document.getElementById('random-group').onclick = function () {
    clearError(); api('/api/admin/random-group', 'POST', {}).then(load).catch(showError);
  };
  document.getElementById('start-game').onclick = function () { api('/api/admin/start', 'POST', {}).then(load).catch(showError); };
  var readyAllDialog = document.getElementById('ready-all-dialog');
  document.getElementById('ready-all').onclick = function () {
    clearError();
    if (readyAllDialog.showModal) readyAllDialog.showModal();
    else submitReadyAll(window.confirm('是否将未准备玩家标记为挂机？\n确定：标记挂机；取消：仅设为准备。'));
  };
  function submitReadyAll(markAfk) {
    if (readyAllDialog.open) readyAllDialog.close();
    api('/api/admin/ready-all', 'POST', { markAfk: markAfk }).then(load).catch(showError);
  }
  document.getElementById('ready-all-afk').onclick = function () { submitReadyAll(true); };
  document.getElementById('ready-all-only').onclick = function () { submitReadyAll(false); };
  document.getElementById('ready-all-cancel').onclick = function () { readyAllDialog.close(); };
  var nextDayDialog = document.getElementById('next-day-dialog');
  document.getElementById('reset-ready').onclick = function () {
    clearError();
    if (nextDayDialog.showModal) nextDayDialog.showModal();
    else enterNextDay(window.confirm('第二天是否重新分组？\n确定：重新分组；取消：保留第一天分组。'));
  };
  function enterNextDay(regroup) {
    if (nextDayDialog.open) nextDayDialog.close();
    api('/api/admin/reset-ready', 'POST', { regroup: regroup }).then(load).catch(showError);
  }
  document.getElementById('next-day-regroup').onclick = function () { enterNextDay(true); };
  document.getElementById('next-day-keep').onclick = function () { enterNextDay(false); };
  document.getElementById('next-day-cancel').onclick = function () { nextDayDialog.close(); };
  document.getElementById('reset-tournament').onclick = function () {
    if (!window.confirm('确定清空第 1 天和第 2 天的全部战况吗？该操作无法从页面恢复。')) return;
    clearError(); api('/api/admin/reset-tournament', 'POST', {}).then(load).catch(showError);
  };
  document.getElementById('test-mode-prepare').onclick = function () { clearError(); api('/api/admin/test-mode/prepare', 'POST', {}).then(load).catch(showError); };
  document.getElementById('test-mode-advance').onclick = function () { clearError(); api('/api/admin/test-mode/advance', 'POST', {}).then(load).catch(showError); };
  document.getElementById('test-mode-cleanup').onclick = function () { clearError(); api('/api/admin/test-mode/cleanup', 'POST', {}).then(load).catch(showError); };
  document.getElementById('match-grid').onclick = function (event) {
    var button = event.target.closest('.accumulation-roll-all');
    if (!button || button.disabled) return;
    var team = gameState && gameState.teams && gameState.teams.find(function (item) { return item.id === button.dataset.teamId; });
    if (!team || !window.confirm('确定替“' + team.name + '”投完全部剩余积累骰吗？')) return;
    clearError(); button.disabled = true; button.textContent = '正在投骰…';
    api('/api/admin/accumulation/' + encodeURIComponent(team.id) + '/roll-all', 'POST', {}).then(load).catch(function (error) { showError(error); load(); });
  };
  document.getElementById('team-flow-grid').onclick = function (event) {
    var button = event.target.closest('[data-assign-role]');
    if (!button || button.disabled) return;
    var control = button.closest('.admin-role-assign'), select = control && control.querySelector('[data-admin-role-candidate]');
    var team = gameState && gameState.teams && gameState.teams.find(function (item) { return item.id === button.dataset.assignTeam; });
    var player = team && (team.players || []).find(function (item) { return item.id === (select && select.value); });
    var labels = { captain: '队长', strategist: '军师', pitcher: '王牌投手' };
    if (!team || !player || !window.confirm('确定指定“' + player.name + '”为“' + team.name + '”的' + labels[button.dataset.assignRole] + '吗？当前角色投票将立即结束。')) return;
    clearError(); button.disabled = true; button.textContent = '正在指定…';
    api('/api/admin/role-vote/' + encodeURIComponent(team.id) + '/assign', 'POST', {
      role: button.dataset.assignRole, playerId: player.id
    }).then(load).catch(function (error) { showError(error); load(); });
  };
  document.getElementById('sandbox-players-bind').onclick = function () {
    clearError();
    api('/api/admin/test-mode/sandbox-players', 'POST', {
      firstUsername: document.getElementById('sandbox-player-a').value,
      firstTeamId: document.getElementById('sandbox-team-a').value,
      firstIdentity: document.getElementById('sandbox-identity-a').value,
      secondUsername: document.getElementById('sandbox-player-b').value,
      secondTeamId: document.getElementById('sandbox-team-b').value,
      secondIdentity: document.getElementById('sandbox-identity-b').value
    }).then(load).catch(showError);
  };
  function copyFormalLogin(button) {
    var url = location.origin + '/login?next=%2Flobby&username=' + encodeURIComponent(button.dataset.username || '');
    function done() { var original = button.textContent; button.textContent = '登录链接已复制'; setTimeout(function () { button.textContent = original; }, 1600); }
    if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(url).then(done).catch(function () { window.prompt('复制正式玩家登录链接', url); });
    else window.prompt('复制正式玩家登录链接', url);
  }
  document.getElementById('copy-player-a-login').onclick = function () { copyFormalLogin(this); };
  document.getElementById('copy-player-b-login').onclick = function () { copyFormalLogin(this); };
  document.getElementById('hub-logout').onclick = function () { api('/api/auth/logout', 'POST', {}).finally(function () { location.replace('/login'); }); };
  function updateProphetCountdowns() { document.querySelectorAll('[data-prophet-deadline]').forEach(function (node) { node.textContent = '剩余 ' + Math.max(0, Math.ceil((Number(node.dataset.prophetDeadline || Date.now()) - Date.now()) / 1000)) + ' 秒'; }); }
  load(); connectStateEvents(); setInterval(updateProphetCountdowns, 250);
})();
