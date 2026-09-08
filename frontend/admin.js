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
  function stageText(stage) {
    return {
      CAPTAIN_VOTE: { kicker: '第一阶段 · 队长投票', title: '八队队长投票' },
      SQUAD_FORM: { kicker: '第二阶段 · 分队', title: '队长编排 6×5 小队' },
      ROLL: { kicker: '第三阶段 · 掷骰', title: '全员限时掷骰' },
      BLIND_BOX: { kicker: '第四阶段 · 盲盒', title: '全员开启盲盒' },
      TACTICS: { kicker: '第五阶段 · 战术', title: '队长重掷与排阵' },
      BATTLE: { kicker: '第六阶段 · 对局', title: '六局对局 · 实时赛程' }
    }[stage] || { kicker: '赛事树 · 当前对阵优先', title: '当前赛程' };
  }
  function countdown(deadlineAt) {
    if (deadlineAt == null) return '—';
    return '剩余 ' + Math.max(0, Math.ceil((Number(deadlineAt) - Date.now()) / 1000)) + ' 秒';
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
    var overtimePending = gameState && gameState.overallResult && gameState.overallResult.status === 'OVERTIME_PENDING' && data.phase !== 'PLAYING';
    document.getElementById('start-overtime').classList.toggle('hidden', !overtimePending);
    document.getElementById('start-game').textContent = '开始第 ' + Math.min(2, completedDays + 1) + ' 天比赛';
    document.getElementById('ready-all').disabled = participants.length === 0 || data.phase === 'PLAYING';
    var stageLabel = stageText(gameState && gameState.stage);
    document.getElementById('admin-stage-kicker').textContent = stageLabel.kicker;
    document.getElementById('admin-stage-title').textContent = stageLabel.title;
    document.getElementById('match-grid').innerHTML = gameState && gameState.matches
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
    var decidedText = { BOTH_DAYS: '两日双冠', MATCH_WINS: '累计胜场决胜', GMV: 'GMV 决胜', OVERTIME: '加赛夺冠' }[overall && overall.decidedBy] || '';
    var winner = overall && (overall.standings || []).find(function (team) { return team.id === overall.champion; });
    var winnerEntries = winner ? [1, 2].map(function (day) { var result = results['day' + day], team = result && (result.teams || []).find(function (item) { return item.id === overall.champion; }); return team ? { day: day, team: team } : null; }).filter(Boolean) : [];
    var overallCard = '';
    if (winner) {
      overallCard = '<article class="day-record overall-record"><header><i>FINAL</i><b>两天最终总冠军</b><span>冠军 · ' + esc(winner.name) + '</span></header><div class="overall-champion-score"><strong>' + winner.totalMatchWins + ' 场胜利</strong><small>两天 GMV 合计 ' + money(winner.totalGmv) + (decidedText ? ' · ' + decidedText : '') + '</small></div>' + TournamentUI.resultRosterHtml(winnerEntries) + '</article>';
    } else if (overall && overall.status === 'OVERTIME_PENDING') {
      var candidates = overall.candidates || [];
      var candidateNames = candidates.map(function (team) { return esc(team.name); }).join(' vs ');
      overallCard = '<article class="day-record overall-record"><header><i>OVERTIME</i><b>两天最终总冠军</b><span>总冠军加赛待定 · ' + candidateNames + '</span></header><div class="overall-champion-score"><small>两天胜场与 GMV 均相同，请点击下方「开始加赛总决赛」按钮安排加赛。</small></div></article>';
    }
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
    return '<article class="watch-card"><small>GROUP ' + match.number + ' · ' + ((a ? a.members.length : 0) + (b ? b.members.length : 0)) + '/60 人</small><div><b>' + esc(match.nameA) + '</b><strong>' + match.scoreA + ' : ' + match.scoreB + '</strong><b>' + esc(match.nameB) + '</b></div><p>' + (a ? a.readyCount : 0) + '/' + (a ? a.members.length : 0) + ' 准备 · ' + (b ? b.readyCount : 0) + '/' + (b ? b.members.length : 0) + ' 准备</p></article>';
  }
  var forceBound = false;
  /** 现场兜底：某一环节等不到人时，管理员可以立刻推进本局。 */
  function bindForceButtons() {
    if (forceBound) return;
    forceBound = true;
    document.getElementById('match-grid').addEventListener('click', function (event) {
      var rematchButton = event.target.closest ? event.target.closest('[data-rematch-match]') : null;
      if (rematchButton) {
        var rematchId = rematchButton.getAttribute('data-rematch-match');
        if (!window.confirm('确认安排两队重赛（加赛）？该场将重新掷骰再打一场。')) return;
        rematchButton.disabled = true;
        api('/api/admin/matches/' + encodeURIComponent(rematchId) + '/rematch', 'POST')
          .catch(function (error) {
            rematchButton.disabled = false;
            window.alert(error && error.message ? error.message : '加赛发起失败');
          });
        return;
      }
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
    var stage = TournamentUI.stage(match), teams = gameState.teams || [];
    var a = teams.find(function (team) { return team.id === match.a; }) || { name: match.a };
    var b = teams.find(function (team) { return team.id === match.b; }) || { name: match.b };
    var detail;
    if (match.status === 'done') detail = '胜者 · ' + esc((teams.find(function (t) { return t.id === match.winner; }) || {}).name || match.winner);
    else if (match.phase === 'OVERTIME_PENDING') detail = '三连环全平（胜场/总点数/GMV）· 待加赛';
    else if (match.phase === 'RESULT') detail = '本场结果结算中 · ' + esc(match.tieBreak || '胜场');
    else if (match.phase === 'BATTLE') detail = '第 ' + Number(match.round || 1) + ' 局 · ' + (match.roundPhase === 'REVEAL' ? '结果揭晓中' : '猜阵进行中');
    else detail = '等待开赛';
    var cells = [1, 2, 3, 4, 5, 6].map(function (n) {
      var entry = (match.rounds || []).find(function (r) { return r.round === n; });
      var current = match.phase === 'BATTLE' && match.round === n;
      var cls = entry ? (entry.winner ? 'is-' + String(entry.winner).toLowerCase() : 'is-draw') : current ? 'is-current' : '';
      return '<div class="score-cell ' + cls + '"><i>' + n + '</i><b>' + (entry ? (entry.winner ? esc((entry.winner === 'A' ? a : b).name) : '平') : current ? '…' : '') + '</b></div>';
    }).join('');
    return '<article class="watch-card tournament-card ' + (match.status === 'active' ? 'is-live' : 'is-history') + '"><small><span>' + esc(stage.label) + '</span><i>' + TournamentUI.status(match) + '</i></small><div><b>' + esc(a.name) + '</b><strong>' + Number(match.winsA || 0) + ' : ' + Number(match.winsB || 0) + '</strong><b>' + esc(b.name) + '</b></div><div class="score-cells admin-score-cells">' + cells + '</div><p>' + detail + '</p>' + (match.phase === 'OVERTIME_PENDING' ? '<button class="btn btn-primary" data-rematch-match="' + esc(match.id) + '">两队重赛（加赛）</button>' : '') + (match.status === 'active' && match.phase !== 'OVERTIME_PENDING' ? '<button class="btn btn-ghost btn-force" data-force-match="' + esc(match.id) + '">强制推进本局</button>' : '') + '</article>';
  }
  function teamPlayerName(team, playerId) {
    var player = (team.players || []).find(function (candidate) { return candidate.id === playerId; });
    return player ? player.name : (playerId || '待定');
  }
  function roleVoteHtml(team) {
    var roles = team.roles || {}, votes = team.roleVotes || {}, tally = {}, voters = [];
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
    var elected = roles.captain ? '<b>当选：' + esc(teamPlayerName(team, roles.captain)) + '</b>' : '<b>投票中</b>';
    var eligibleVoters = (team.players || []).filter(function (player) { return !player.managed; }).length;
    var summary = ranking || '暂无有效票';
    if (roles.captain) summary += ' · 弃票 ' + Math.max(0, eligibleVoters - voters.length) + ' 人';
    var assigned = Object.keys(roles).map(function (key) { return roles[key]; });
    var candidates = (team.players || []).filter(function (player) { return !player.managed && assigned.indexOf(player.id) < 0; });
    var adminControl = gameState && gameState.stage === 'CAPTAIN_VOTE' && !roles.captain
      ? '<div class="admin-role-assign"><select data-admin-role-candidate>' + candidates.map(function (player) { return '<option value="' + esc(player.id) + '">' + esc(player.name) + ' · ' + (player.role === 'back' ? '后端' : '前端') + '</option>'; }).join('') + '</select><button class="btn btn-primary" data-assign-team="' + esc(team.id) + '" data-assign-role="captain" ' + (candidates.length ? '' : 'disabled') + '>管理员指定队长</button></div>' : '';
    return '<div class="flow-role-row"><span>队长</span>' + elected + '<small>' + summary + '</small>' +
      (voters.length ? '<details><summary>查看 ' + voters.length + ' 张实名选票</summary><p>' + voters.map(esc).join('<br>') + '</p></details>' : '') + adminControl + '</div>';
  }
  function matchForTeam(teamId) {
    var matches = gameState && gameState.matches ? Object.keys(gameState.matches).map(function (key) { return gameState.matches[key]; }) : [];
    return matches.find(function (match) { return match.status === 'active' && (match.a === teamId || match.b === teamId); }) ||
      matches.slice().reverse().find(function (match) { return match.a === teamId || match.b === teamId; });
  }
  function flowHtml(team) {
    var match = matchForTeam(team.id);
    if (!match) return '<div class="flow-empty">等待本队对局建立</div>';
    var phaseText2;
    if (match.status === 'done') phaseText2 = '本场已结束';
    else if (match.phase === 'OVERTIME_PENDING') phaseText2 = '三连环全平 · 待加赛';
    else if (match.phase === 'RESULT') phaseText2 = '本场结果结算中';
    else if (match.phase === 'BATTLE') phaseText2 = '第 ' + Number(match.round || 1) + ' 局 · ' + (match.roundPhase === 'REVEAL' ? '揭晓中' : '猜阵中');
    else phaseText2 = '等待开赛';
    var flowTeams = (gameState && gameState.teams) || [];
    var flowA = flowTeams.find(function (t) { return t.id === match.a; }) || { name: match.a };
    var flowB = flowTeams.find(function (t) { return t.id === match.b; }) || { name: match.b };
    var cells = [1, 2, 3, 4, 5, 6].map(function (n) {
      var entry = (match.rounds || []).find(function (r) { return r.round === n; });
      var current = match.phase === 'BATTLE' && match.round === n;
      var cls = entry ? (entry.winner ? 'is-' + String(entry.winner).toLowerCase() : 'is-draw') : current ? 'is-current' : '';
      return '<div class="score-cell ' + cls + '"><i>' + n + '</i><b>' + (entry ? (entry.winner ? esc((entry.winner === 'A' ? flowA : flowB).name) : '平') : current ? '…' : '') + '</b></div>';
    }).join('');
    return '<div class="flow-match-stage"><span>本队对局</span><b>' + esc(phaseText2) + '</b><small>比分 ' + Number(match.winsA || 0) + ' : ' + Number(match.winsB || 0) + (match.tieBreak ? ' · ' + esc(match.tieBreak) : '') + '</small></div>'
      + '<div class="score-cells admin-score-cells">' + cells + '</div>';
  }
  function renderFlowMonitor() {
    var grid = document.getElementById('team-flow-grid'), stage = document.getElementById('flow-monitor-stage');
    if (!gameState || !gameState.teams) {
      stage.textContent = '等待开赛'; grid.innerHTML = '<p class="flow-empty">比赛开始后，这里会显示八支队伍的完整实时流程。</p>'; return;
    }
    var stageName = stageText(gameState.stage).kicker.replace(/^第.阶段 · /, '');
    stage.textContent = stageName;
    grid.innerHTML = gameState.teams.map(function (team) {
      var captain = team.roles && team.roles.captain ? teamPlayerName(team, team.roles.captain) : '待选举';
      var squads = team.squads;
      var squadsText = Array.isArray(squads) && squads.length === 6 ? '已分队 6×5' : '待分队';
      var squadsDetail = Array.isArray(squads) ? squads.map(function (s, i) { return (i + 1) + '号:' + (s || []).length + '人'; }).join(' ') : '';
      var rerollLimit = Math.min(team.rerollQuota || 0, 5);
      var headerBadge;
      if (gameState.stage === 'CAPTAIN_VOTE') headerBadge = team.roles && team.roles.captain ? '队长已定' : '投票中';
      else if (gameState.stage === 'SQUAD_FORM') headerBadge = (Array.isArray(squads) && squads.length === 6) ? '已分队' : '待分队';
      else headerBadge = stageName;
      return '<article class="team-flow-card"><header><div><small>' + esc(team.id.toUpperCase()) + '</small><h3>' + esc(team.name) + '</h3></div><span>' + esc(headerBadge) + '</span></header>'
        + '<div class="flow-detail"><span>队长</span><b>' + esc(captain) + '</b></div>'
        + '<div class="flow-detail"><span>分队</span><b>' + esc(squadsText) + (squadsDetail ? ' · ' + esc(squadsDetail) : '') + '</b></div>'
        + '<div class="flow-detail"><span>重掷</span><b>' + Number(team.rerollUsed || 0) + ' / ' + rerollLimit + (team.squadOrderLocked ? ' · 顺序已锁定' : '') + '</b></div>'
        + roleVoteHtml(team)
        + (gameState.stage === 'BATTLE' ? flowHtml(team) : '')
        + '</article>';
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
  document.getElementById('start-overtime').onclick = function () { clearError(); api('/api/admin/start-overtime', 'POST', {}).then(load).catch(showError); };
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
  document.getElementById('team-flow-grid').onclick = function (event) {
    var button = event.target.closest('[data-assign-role]');
    if (!button || button.disabled) return;
    var control = button.closest('.admin-role-assign'), select = control && control.querySelector('[data-admin-role-candidate]');
    var team = gameState && gameState.teams && gameState.teams.find(function (item) { return item.id === button.dataset.assignTeam; });
    var player = team && (team.players || []).find(function (item) { return item.id === (select && select.value); });
    if (!team || !player || !window.confirm('确定指定“' + player.name + '”为“' + team.name + '”的队长吗？当前队长投票将立即结束。')) return;
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
  load(); connectStateEvents();
  /* SSE 之外的兜底：慢轮询 + 回前台补拉，避免断线后流程监控失鲜 */
  setInterval(function () { if (!document.hidden) queueEventRefresh('game'); }, 15000);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) queueEventRefresh('lobby'); });
})();
