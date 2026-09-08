(function(){'use strict';var data=null,es=null,chatCollapsed=false,refreshTimer=null,refreshType=null,lobbyLoading=false,lobbyReloadPending=false,readySubmitting=false,gameLoading=false,captainVotePick='',squadDraft=null,squadOrderDraft=null,rerollBusy=false,gsCache=null,firstLoad=true,squadViewOpen=false,teammateFoldOpen=null,opponentFoldOpen=false,expandedRounds={},matchDetails={},matchDetailPending={},preGuessKey='',preGuessSel=[],preGuessEdit=false;
function announceRule(rule,occurrence){if(window.GameRules)window.GameRules.announce(rule,String(occurrence));}
  function fetchWithTimeout(path,options){var controller=typeof AbortController==='function'?new AbortController():null,timer=controller?setTimeout(function(){controller.abort();},10000):null,requestOptions=options||{};if(controller)requestOptions.signal=controller.signal;return fetch(path,requestOptions).catch(function(error){if(error&&error.name==='AbortError')throw new Error('请求超时，请检查网络后重试');throw error;}).finally(function(){if(timer)clearTimeout(timer);});}
  function api(path,method,body){return fetchWithTimeout(path,{method:method||'GET',headers:{'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined}).then(function(r){return r.json().catch(function(){return{}}).then(function(d){if(r.status===401)location.replace('/login');if(!r.ok)throw new Error(d.error||'操作失败');return d;});});}
function esc(s){return String(s==null?'':s).replace(/[&<>"']/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
function renderAfkState(){if(!data)return;var me=data.me,team=data.teams.find(function(item){return item.id===me.teamId;}),box=document.getElementById('afk-notice');if(!box){box=document.createElement('section');box.id='afk-notice';var room=document.getElementById('my-team');room.parentNode.insertBefore(box,room);}var afkMembers=team?team.members.filter(function(member){return member.afk;}):[];if(!afkMembers.length){box.className='afk-notice hidden';box.innerHTML='';return;}var names=afkMembers.map(function(member){return esc(member.originalDisplayName||member.displayName);}).join('、');box.className='afk-notice '+(me.afk?'is-self':'is-team');box.innerHTML=me.afk?'<div><small>AFK MODE · 已由系统托管</small><b>你当前被标记为挂机</b><p>挂机期间不能投票、掷骰或进行战术操作，全程由系统托管。返回后请先取消挂机。</p></div><button id="cancel-afk" class="btn btn-primary">取消挂机，恢复参与</button>':'<div><small>TEAM AVAILABILITY</small><b>本队挂机成员：'+names+'</b><p>挂机成员已由系统托管，掷骰、盲盒与猜阵由系统自动完成，且其所在小队无法触发同步暴击。</p></div>';document.querySelectorAll('#my-team .teammate').forEach(function(node,index){var member=team.members[index];if(!member||!member.afk)return;node.classList.add('afk');var name=node.querySelector('b');if(name&&!name.querySelector('.teammate-afk-tag'))name.insertAdjacentHTML('beforeend','<em class="teammate-afk-tag">挂机</em>');var status=node.lastElementChild;if(status)status.textContent='AFK';});var cancel=document.getElementById('cancel-afk');if(cancel)cancel.onclick=function(){cancel.disabled=true;cancel.textContent='正在恢复…';api('/api/lobby/afk/cancel','POST',{}).then(function(){queueRefresh('lobby');}).catch(function(error){cancel.disabled=false;cancel.textContent='取消挂机，恢复参与';window.alert(error.message);});};}
/* 队长身份常驻展示：名单里给队长加"队长"标记（render 重绘后需重建），并在操作台上方显示队长横幅，队长本人看到"你是本队队长" */
function syncCaptainUi(gs){
  var box=document.getElementById('captain-badge');
  if(!box){box=document.createElement('section');box.id='captain-badge';box.className='captain-badge hidden';var actions=document.getElementById('player-actions');actions.parentNode.insertBefore(box,actions);}
  var me=data&&data.me,team=me&&me.teamId?data.teams.find(function(item){return item.id===me.teamId;}):null,mine=gs&&team?findMine(gs):null,captainId=mine&&mine.roles&&mine.roles.captain,active=!!data&&(data.phase==='PLAYING'||data.phase==='FINISHED');
  document.querySelectorAll('#my-team .teammate').forEach(function(node,index){
    var member=team&&team.members&&team.members[index],isCaptain=!!(captainId&&member&&('u'+member.id)===captainId);
    node.classList.toggle('is-captain',isCaptain);
    var name=node.querySelector('b'),tag=name?name.querySelector('.teammate-captain-tag'):null;
    if(isCaptain&&!tag)name.insertAdjacentHTML('beforeend','<em class="teammate-captain-tag">队长</em>');
    else if(!isCaptain&&tag)tag.remove();
  });
  /* 队长阵容入口：固定在"我的队伍"区块顶部；内容未变时不重建 DOM，保留 <details> 的展开状态 */
  var squadSlot=document.getElementById('captain-squad-slot');
  if(squadSlot){
    var squadHtml=mine?captainSquadViewHtml(mine):'';
    if(squadSlot.dataset.cached!==squadHtml){squadSlot.innerHTML=squadHtml;squadSlot.dataset.cached=squadHtml;bindCaptainSquadView(squadSlot);}
  }
  if(!active||!mine||!captainId){box.className='captain-badge hidden';box.innerHTML='';return;}
  var isSelf=captainId===myPlayerId();
  box.className='captain-badge'+(isSelf?' is-self':'');
  box.innerHTML='<div><small>TEAM CAPTAIN</small><b>'+(isSelf?'⭐ 你是本队队长':'本队队长 · '+esc(playerName(mine,captainId)))+'</b><p>'+(isSelf?'分队、重掷与出场顺序都由你操作，请在对应阶段留意下方操作面板。':'分队、重掷与出场顺序由队长统一操作，战术想法可以先在队内频道沟通。')+'</p></div>';
}
function opponentRosterHtml(team){if(!team||!team.members||!team.members.length)return'';var afk=team.members.filter(function(u){return u.afk;}).length;return'<details class="roster-fold is-opponent"'+(opponentFoldOpen?' open':'')+'><summary><b>'+esc(team.name)+'</b><span class="roster-fold-stats">'+team.members.length+' 人 · 本场对手'+(afk?' · 挂机 '+afk:'')+'</span><span class="roster-fold-toggle">'+(opponentFoldOpen?'点击收起':'点击展开')+'</span></summary><div class="opponent-roster-grid">'+team.members.map(function(user){var managed=user.standIn?' · 托管':user.afk?' · 挂机':'';return'<div class="opponent-member '+(user.afk?'is-afk':'')+'"><b>'+esc(user.originalDisplayName||user.displayName)+'</b><span>'+esc(user.department)+'</span><i>'+(user.frontEnd?'前端':'后端')+managed+'</i></div>';}).join('')+'</div></details>';}
function render(){var me=data.me,team=data.teams.find(function(t){return t.id===me.teamId;}),opponent=data.teams.find(function(t){return t.id!==me.teamId&&t.id!=='spectator'&&t.members&&t.members.length;});document.getElementById('lobby-identity').textContent=me.displayName+' · '+me.department;var phase={PREPARING:'等待管理员分组',GROUPED:'全员准备中',PLAYING:'比赛已经开始',FINISHED:'本轮已结束'}[data.phase];document.getElementById('lobby-hero').innerHTML='<p class="hub-eyebrow">CURRENT SIGNAL</p><h1>'+phase+'</h1><p>'+(team?'你已进入 '+esc(team.name)+'，请确认队友并完成准备。':'你暂未进入本轮分组，当前处于观战席。')+'</p>';var room=document.getElementById('my-team'),chat=document.getElementById('team-chat');if(team){var canReady=!!data.canReady&&!readySubmitting,readyCount=team.members.filter(function(u){return u.ready;}).length,afkCount=team.members.filter(function(u){return u.afk;}).length,teamFold=teammateFoldOpen===null?(data.phase!=='PLAYING'&&data.phase!=='FINISHED'):teammateFoldOpen;room.innerHTML='<div class="hub-section-title"><div><small>MY TEAM · '+team.members.length+'/30</small><h2>'+esc(team.name)+'</h2></div>'+((data.phase==='PREPARING'||data.phase==='GROUPED')?'<button id="ready-btn" class="btn '+(me.ready?'btn-ghost':'btn-primary')+'" '+(canReady?'':'disabled')+'>'+(readySubmitting?'提交中…':(me.ready?'取消准备':'我已准备'))+'</button>':'')+'</div><div id="captain-squad-slot"></div><details class="roster-fold is-team"'+(teamFold?' open':'')+'><summary><b>全部队员</b><span class="roster-fold-stats">已准备 '+readyCount+' / '+team.members.length+(afkCount?' · 挂机 '+afkCount:'')+'</span><span class="roster-fold-toggle">'+(teamFold?'点击收起':'点击展开')+'</span></summary><div class="teammate-grid">'+team.members.map(function(u){var managed=u.standIn?'<em class="teammate-managed-tag"><i></i>托管</em>':'';return'<div class="teammate '+(u.ready?'ready ':'')+(u.standIn?'managed':'')+'"><b>'+esc(u.originalDisplayName||u.displayName)+managed+'</b><span>'+esc(u.department)+'</span><i>'+(u.standIn?'AUTO':(u.ready?'READY':'WAIT'))+'</i></div>';}).join('')+'</div></details>'+opponentRosterHtml(opponent);var teamFoldEl=room.querySelector('.roster-fold.is-team'),oppFoldEl=room.querySelector('.roster-fold.is-opponent');if(teamFoldEl)teamFoldEl.addEventListener('toggle',function(){teammateFoldOpen=teamFoldEl.open;});if(oppFoldEl)oppFoldEl.addEventListener('toggle',function(){opponentFoldOpen=oppFoldEl.open;});chat.classList.remove('hidden');chat.classList.add('chat-docked');chat.classList.toggle('chat-collapsed',chatCollapsed);syncChatToggle();var b=document.getElementById('ready-btn');if(b)b.onclick=function(){if(!canReady)return;var nextReady=!me.ready;readySubmitting=true;render();api('/api/lobby/ready','POST',{ready:nextReady}).then(function(){var member=team.members.find(function(user){return user.id===me.id;});data.me.ready=nextReady;if(member)member.ready=nextReady;readySubmitting=false;render();queueRefresh('lobby');}).catch(function(error){readySubmitting=false;render();window.alert(error.message);});};}else{room.innerHTML='<div class="empty-stand"><b>👁 观战席</b><p>管理员将未参赛用户留在观战席，你仍可查看四组实时比分。</p></div>';chat.classList.add('hidden');chat.classList.remove('chat-docked');}if(data.phase==='PREPARING'||data.phase==='GROUPED'){document.getElementById('lobby-matches').innerHTML=[0,1,2,3].map(function(i){var pa=data.teams[i*2],pb=data.teams[i*2+1];return'<article class="watch-card"><small>GROUP '+(i+1)+' · '+((pa?pa.members.length:0)+(pb?pb.members.length:0))+' 人</small><div><b>'+esc(pa?pa.name:'')+'</b><strong>'+(pa?pa.readyCount:0)+' : '+(pb?pb.readyCount:0)+'</strong><b>'+esc(pb?pb.name:'')+'</b></div></article>';}).join('');}}
function syncChatToggle(){var button=document.getElementById('chat-toggle'),chat=document.getElementById('team-chat'),ball=document.getElementById('chat-ball');button.textContent=chatCollapsed?'展开':'收起';button.setAttribute('aria-expanded',String(!chatCollapsed));if(ball){var showBall=chatCollapsed&&!chat.classList.contains('hidden');ball.classList.toggle('hidden',!showBall);ball.setAttribute('aria-expanded',String(!chatCollapsed));}}
function playerAction(type,selections){return api('/api/lobby/player-action','POST',{type:type,selections:selections||[]}).then(function(){queueRefresh('game');});}
function showActionError(e){var box=document.getElementById('player-actions');box.insertAdjacentHTML('beforeend','<p class="login-error">'+esc(e.message)+'</p>');}
function playerName(team,id){var player=(team.players||[]).find(function(item){return item.id===id;});return player?player.name:'待选';}
function voteSeconds(deadline){return Math.max(0,Math.ceil((Number(deadline||Date.now())-Date.now())/1000));}
function voteCountdown(deadline,label){if(deadline!=null&&isFinite(Number(deadline))){floatDeadline=Number(deadline);floatLabel=label||'';}return'<span class="vote-countdown" data-vote-deadline="'+Number(deadline||0)+'">剩余 '+voteSeconds(deadline)+' 秒</span>';}
function updateVoteCountdowns(){document.querySelectorAll('[data-vote-deadline]').forEach(function(node){node.textContent='剩余 '+voteSeconds(node.dataset.voteDeadline)+' 秒';});updateFloatCountdown();}
/* 阶段侧栏：各阶段面板通过 voteCountdown 记录当前阶段截止时刻，这里把它驱动到 StagePanel；
   BATTLE 阶段倒计时改取本队 match 的猜阵/揭晓/结果时刻，比阶段总截止更贴近玩家 */
var floatDeadline=null,floatLabel='',floatStage='';
function ensureFloatBox(){if(window.StagePanel)window.StagePanel.init();}
function updateFloatCountdown(){
  if(!window.StagePanel)return;
  var deadline=floatDeadline,extra=floatLabel;
  if(floatStage==='BATTLE'&&data&&data.me&&gsCache){
    var mine=findMine(gsCache),match=mine&&myActiveMatchFor(gsCache,mine.id);
    if(match){
      var ts=match.phase==='RESULT'?match.resultReadyAt:match.roundPhase==='REVEAL'?match.revealUntil:match.guessDeadlineAt;
      if(ts!=null&&isFinite(Number(ts))){
        deadline=Number(ts);
        extra=match.phase==='RESULT'?'本场结果展示':'第 '+Number(match.round||1)+' 局 · '+(match.roundPhase==='REVEAL'?'结果揭晓':'猜阵进行中');
      }
    }
  }
  if(floatStage==='TACTICS'&&data&&data.me&&gsCache){
    var tTeam=findMine(gsCache),cap=tTeam&&tTeam.roles&&tTeam.roles.captain;
    extra='队长 '+playerName(tTeam,cap)+' 正在重投骰子、排兵布阵';
  }
  if(deadline==null){window.StagePanel.update({stage:''});return;}
  window.StagePanel.update({stage:floatStage,stageLabel:stageNameOf(floatStage),deadline:deadline,serverOffset:0,extraLine:extra});
}
/* ---------- 通用小工具 ---------- */
function myPlayerId(){return 'u'+data.me.id;}
function findMine(gs){return (gs.teams||[]).find(function(t){return t.id===data.me.teamId;});}
function stageNameOf(stage){return{CAPTAIN_VOTE:'队长投票',SQUAD_FORM:'小队组建',ROLL:'全员掷骰',BLIND_BOX:'盲盒',TACTICS:'战术布置',BATTLE:'对局',RESULT:'结果公布'}[stage]||'阶段倒计时';}
function finalPoints(p){return Number(p.diceFinal||0)+Number(p.blindBox||0);}
function blindBoxText(p){if(p.blindBox==null)return'未开';var v=Number(p.blindBox);return(v>0?'+':'')+v;}
/* bracket 场次元信息：g1..g4 为 1/4 决赛，s1/s2 半决赛，f1 决赛 */
function bracketStage(match){var id=String(match&&match.id||'').toLowerCase(),prefix=id.charAt(0),index=Math.max(1,Number(id.slice(1))||1);if(prefix==='f')return{rank:3,index:index,label:'总决赛',shortLabel:'决赛'};if(prefix==='s')return{rank:2,index:index,label:'半决赛 · 第 '+index+' 场',shortLabel:'半决赛'};return{rank:1,index:index,label:'1/4决赛 · 第 '+index+' 场',shortLabel:'1/4决赛'};}
function bracketMatches(gs){var values=Object.keys(gs.matches||{}).map(function(k){return gs.matches[k];});values.sort(function(x,y){var active=(x.status==='active'?0:1)-(y.status==='active'?0:1);if(active)return active;var sx=bracketStage(x),sy=bracketStage(y);return sy.rank-sx.rank||sx.index-sy.index;});return values;}
function tournamentWatchCard(gs,match){var stage=bracketStage(match),a=gs.teams.find(function(team){return team.id===match.a;}),b=gs.teams.find(function(team){return team.id===match.b;}),winner=gs.teams.find(function(team){return team.id===match.winner;});var detail;if(match.status==='done')detail='胜者 · '+(winner?winner.name:match.winner);else if(match.phase==='RESULT')detail='本场结果展示中';else if(match.phase==='BATTLE')detail='第 '+Number(match.round||1)+' 局 · '+(match.roundPhase==='REVEAL'?'结果揭晓中':'猜阵进行中');else detail='等待开赛';return '<article class="watch-card tournament-card '+(match.status==='active'?'is-live':'is-history')+'"><small><span>'+esc(stage.label)+'</span><i>'+(match.status==='done'?'已结束':match.status==='active'?'进行中':'未开始')+'</i></small><div><b>'+esc(a?a.name:match.a)+'</b><strong>'+Number(match.winsA||0)+' : '+Number(match.winsB||0)+'</strong><b>'+esc(b?b.name:match.b)+'</b></div><p>'+esc(detail)+'</p></article>';}
function myActiveMatchFor(gs,teamId){return bracketMatches(gs).find(function(m){return m.status==='active'&&(m.a===teamId||m.b===teamId);})||null;}
/* 单败淘汰判定：冠军未产生、本队无进行中（含待开赛）场次、且输过一场已完结比赛 */
function teamEliminated(gs,teamId){if(!teamId||gs.champion||myActiveMatchFor(gs,teamId))return false;return bracketMatches(gs).some(function(m){return m.status==='done'&&(m.a===teamId||m.b===teamId)&&m.winner!==teamId;});}
function eliminatedPanelHtml(gs,match,mine){var a=gs.teams.find(function(t){return t.id===match.a;})||{id:match.a,name:match.a},b=gs.teams.find(function(t){return t.id===match.b;})||{id:match.b,name:match.b},opponent=match.a===mine.id?b:a,stage=bracketStage(match),myScore=Number(match.a===mine.id?match.winsA:match.winsB)||0,opponentScore=Number(match.a===mine.id?match.winsB:match.winsA)||0;return'<div class="post-match-state is-eliminated"><div class="post-match-signal">✕</div><p class="hub-eyebrow">ELIMINATED · SPECTATOR MODE</p><h2>本场惜败 · 本队被淘汰</h2><strong>'+esc(stage.label)+' · 最终比分 '+myScore+' : '+opponentScore+' · 对手「'+esc(opponent.name)+'」晋级</strong><p>本日剩余赛程你将以观战身份参与：下方可查看实时对阵，队内频道保持可用；明日赛事自动重新参赛。</p></div>'+roundsSectionHtml(match,a,b);}
function postMatchHtml(gs,match,mine){var won=match.winner===mine.id,champion=gs.champion,stage=bracketStage(match),sameStage=bracketMatches(gs).filter(function(item){return bracketStage(item).rank===stage.rank;}),finished=sameStage.filter(function(item){return item.status==='done';}).length,next=stage.rank===1?'半决赛':stage.rank===2?'总决赛':'本日比赛结束',opponent=gs.teams.find(function(team){return team.id===(match.a===mine.id?match.b:match.a);}),myScore=match.a===mine.id?match.winsA:match.winsB,opponentScore=match.a===mine.id?match.winsB:match.winsA;
  /* 冠军信息统一由顶部冠军卡展示，赛后面板只保留比分与逐局明细 */
  if(champion)return'';
  if(won){return'<div class="post-match-state is-waiting"><div class="post-match-signal"><i></i></div><p class="hub-eyebrow">QUALIFIED · WAITING SIGNAL</p><h2>本场获胜，等待其他对阵结束</h2><strong>'+esc(mine.name)+' '+myScore+' : '+opponentScore+' '+esc(opponent?opponent.name:'对手')+'</strong><p>本队已经晋级'+next+'。当前 '+esc(stage.shortLabel)+' '+finished+' / '+sameStage.length+' 场结束，全部结果产生后系统会自动建立下一场对阵，无需刷新页面。</p><div class="post-match-progress"><i style="width:'+Math.round(finished/Math.max(1,sameStage.length)*100)+'%"></i></div><small>等待期间可继续使用左下角队内频道，并在下方查看其他队伍实时赛况。</small></div>';}
  return'<div class="post-match-state is-eliminated"><div class="post-match-signal">✕</div><p class="hub-eyebrow">ELIMINATED · SPECTATOR MODE</p><h2>本场惜败 · 本队被淘汰</h2><strong>最终比分 '+myScore+' : '+opponentScore+'</strong><p>对手 '+esc(opponent?opponent.name:'队伍')+' 晋级。你仍可保留队内频道，并在下方观看后续比赛。</p></div>';}
/* ---------- 第一阶段：队长投票 ---------- */
function captainVotePanel(box,gs,mine){
  var voterId=myPlayerId(),votes=mine.roleVotes||{},captain=mine.roles&&mine.roles.captain,voters=(mine.players||[]).filter(function(p){return !p.managed;}),voted=Object.keys(votes).length;
  announceRule('CAPTAIN_VOTE',(gs.startedAt||'game')+'-'+mine.id+'-CAPTAIN_VOTE');
  if(captain){box.innerHTML='<div class="role-ribbon">队长投票 · 已完成</div><h2>本队队长已经产生</h2><div class="captain-elect"><small>TEAM CAPTAIN</small><b>'+esc(playerName(mine,captain))+'</b><span>等待所有队伍完成投票后，队长将负责分队与后续战术操作。</span></div>';return;}
  var progress='<div class="vote-progress"><b>'+voted+' / '+voters.length+'</b><span>已投票（托管队员不计入）</span></div>';
  if(votes[voterId]){box.innerHTML='<div class="role-ribbon">队长投票 · '+voteCountdown(mine.roleVoteDeadlineAt,'队长投票')+'</div>'+progress+'<h2>你的队长选票已提交</h2><p>投票内容全程密封，全员提交或倒计时结束后系统立即计票并公布队长。</p>';return;}
  if(!voters.some(function(p){return p.id===captainVotePick;}))captainVotePick='';
  box.innerHTML='<div class="role-ribbon">第一阶段 · 队长投票 '+voteCountdown(mine.roleVoteDeadlineAt,'队长投票')+'</div>'+progress+'<div class="hub-section-title"><div><small>CAPTAIN VOTE · 每人一票</small><h2>投票选出本队队长</h2></div></div><div class="action-player-grid">'+voters.map(function(p){var selected=p.id===captainVotePick;return'<button class="action-player'+(selected?' selected':'')+'" data-vote-player="'+esc(p.id)+'" aria-pressed="'+selected+'"><b>'+esc(p.name)+'</b><small>'+(p.role==='back'?'后端':'前端')+'</small></button>';}).join('')+'</div><div class="action-footer"><button id="submit-captain-vote" class="btn btn-primary" '+(captainVotePick?'':'disabled')+'>提交队长选票</button></div>';
  box.querySelectorAll('[data-vote-player]').forEach(function(btn){btn.onclick=function(){captainVotePick=btn.dataset.votePlayer;box.querySelectorAll('[data-vote-player]').forEach(function(node){var on=node===btn;node.classList.toggle('selected',on);node.setAttribute('aria-pressed',String(on));});document.getElementById('submit-captain-vote').disabled=false;};});
  document.getElementById('submit-captain-vote').onclick=function(){if(!captainVotePick)return;var pick=captainVotePick;this.disabled=true;playerAction('role-vote',[pick]).catch(function(error){document.getElementById('submit-captain-vote').disabled=false;showActionError(error);});};
}
/* ---------- 第二阶段：队长分队 ---------- */
function squadsReadonlyHtml(mine,showPoints){
  var players=mine.players||[],squads=mine.squads||[];
  return'<div class="squad-grid is-readonly">'+squads.map(function(s,i){var total=null;if(showPoints){s.forEach(function(id){var p=players.find(function(x){return x.id===id;});if(p&&p.diceFinal!=null)total=(total||0)+finalPoints(p);});}return'<div class="squad-cell is-full"><div class="squad-cell-head"><i class="squad-no">'+(i+1)+'</i><b>号小队</b><span>'+s.length+' / 5'+(total!=null?' · 总分 '+total+' 点':'')+'</span></div>'+s.map(function(id){var p=players.find(function(x){return x.id===id;}),points=showPoints&&p&&p.diceFinal!=null?' <i>'+finalPoints(p)+' 点</i>':'';return'<span class="squad-member is-static">'+(p?esc(p.name):esc(id))+points+'</span>';}).join('')+'</div>';}).join('')+'</div>';
}
/* 队长专属折叠面板：分队完成后固定展示在"我的队伍"区块顶部（#captain-squad-slot，由 syncCaptainUi 注入），
   任何阶段都能随时查看本队阵容；展开状态存模块变量，SSE 重绘不丢失 */
function captainSquadViewHtml(mine){
  var captain=mine.roles&&mine.roles.captain;
  if(!mine.squads||captain!==myPlayerId())return'';
  return'<details class="captain-squad-view"'+(squadViewOpen?' open':'')+'><summary><b>本队阵容 · 6 个小队</b><span>'+(squadViewOpen?'点击收起':'点击展开')+'</span></summary>'+squadsReadonlyHtml(mine,true)+'</details>';
}
function bindCaptainSquadView(box){var el=box.querySelector('.captain-squad-view');if(el)el.addEventListener('toggle',function(){squadViewOpen=el.open;});}
function squadFormPanel(box,gs,mine){
  var players=mine.players||[],captain=mine.roles&&mine.roles.captain,isCaptain=captain===myPlayerId();
  announceRule('SQUAD_FORM',(gs.startedAt||'game')+'-day'+gs.day+'-SQUAD_FORM');
  if(mine.squads){box.innerHTML='<div class="role-ribbon">第二阶段 · 分队完成</div><h2>本队 6 个小队已经确定</h2>'+squadsReadonlyHtml(mine,false)+'<p>掷骰与盲盒结束后，队长可以在战术阶段调整 1~6 号小队的出场顺序。</p>';return;}
  if(!isCaptain){box.innerHTML='<div class="role-ribbon">第二阶段 · 队长分队 '+voteCountdown(gs.stageDeadlineAt,'队长分队')+'</div><h2>等待队长完成分队</h2><p>队长 <b>'+esc(playerName(mine,captain))+'</b> 正在把 30 名队员编入 6 个小队（每队 5 人）。全部队伍完成后自动进入掷骰阶段。</p>';return;}
  /* 队长编排：草稿保存在模块变量里，SSE 刷新重绘时不丢失；支持一键随机分队与拖拽 */
  var validIds={};players.forEach(function(p){validIds[p.id]=true;});
  if(!squadDraft||squadDraft.length!==6)squadDraft=[[],[],[],[],[],[]];
  squadDraft=squadDraft.map(function(s){return s.filter(function(id){return validIds[id];});});
  var pick='',drag=null;
  function squadState(){
    var assigned={},filled=0;
    squadDraft.forEach(function(s){s.forEach(function(id){assigned[id]=true;filled++;});});
    return{assigned:assigned,pool:players.filter(function(p){return !assigned[p.id];}),filled:filled,complete:squadDraft.every(function(s){return s.length===5;})};
  }
  function shuffle(list){var a=list.slice();for(var i=a.length-1;i>0;i--){var j=Math.floor(Math.random()*(i+1)),t=a[i];a[i]=a[j];a[j]=t;}return a;}
  function autoFill(reshuffleAll){
    if(reshuffleAll){
      var ids=shuffle(players.map(function(p){return p.id;})),squads=[];
      for(var i=0;i<6;i++)squads.push(ids.slice(i*5,i*5+5));
      squadDraft=squads;
    }else{
      var st=squadState(),rest=shuffle(st.pool.map(function(p){return p.id;})),k=0;
      squadDraft.forEach(function(s){while(s.length<5&&k<rest.length)s.push(rest[k++]);});
    }
  }
  function clearHover(){box.querySelectorAll('.squad-pool,.squad-cell').forEach(function(node){node.classList.remove('is-drop-ok','is-drop-no');});}
  function resolveTarget(node){var el=node&&node.closest?node.closest('[data-squad-member],[data-squad-cell],[data-squad-pool]'):null;if(!el)return null;if(el.hasAttribute('data-squad-member'))return{kind:'member',id:el.getAttribute('data-squad-member'),squadIndex:Number(el.getAttribute('data-squad-index'))};if(el.hasAttribute('data-squad-cell'))return{kind:'cell',squadIndex:Number(el.getAttribute('data-squad-cell'))};return{kind:'pool'};}
  function canDrop(t){if(!t||!drag)return false;if(t.kind==='pool')return drag.from>=0;var squad=squadDraft[t.squadIndex];if(drag.from<0)return squad.length<5;if(drag.from===t.squadIndex)return false;return squad.length<5||t.kind==='member';}
  function doDrop(t){
    if(t.kind==='pool'){if(drag.from>=0){var sa=squadDraft[drag.from],pi=sa.indexOf(drag.id);if(pi>=0)sa.splice(pi,1);}return;}
    var target=squadDraft[t.squadIndex];
    if(drag.from<0){var pos=t.kind==='member'?target.indexOf(t.id):target.length;if(pos<0)pos=target.length;target.splice(pos,0,drag.id);if(pick===drag.id)pick='';return;}
    var source=squadDraft[drag.from],si=source.indexOf(drag.id);
    if(t.squadIndex===drag.from||si<0)return;
    if(target.length<5){source.splice(si,1);var at=t.kind==='member'?target.indexOf(t.id):target.length;if(at<0)at=target.length;target.splice(at,0,drag.id);}
    else if(t.kind==='member'){var ti=target.indexOf(t.id);if(ti<0)return;source[si]=t.id;target[ti]=drag.id;}
  }
  function bindSource(node,id,from){
    node.setAttribute('draggable','true');
    node.addEventListener('dragstart',function(e){drag={id:id,from:from};try{e.dataTransfer.effectAllowed='move';e.dataTransfer.setData('text/plain',String(id));}catch(err){}node.classList.add('is-dragging');});
    node.addEventListener('dragend',function(){drag=null;node.classList.remove('is-dragging');clearHover();});
  }
  function poolCardHtml(p){return'<button type="button" class="action-player squad-pool-player'+(pick===p.id?' selected':'')+'" data-pool-player="'+esc(p.id)+'"><b>'+esc(p.name)+'</b><small>'+(p.role==='back'?'后端':'前端')+(p.managed?' · 托管':'')+'</small></button>';}
  function memberHtml(id,index){var p=players.find(function(x){return x.id===id;});return'<button type="button" class="squad-member" data-squad-member="'+esc(id)+'" data-squad-index="'+index+'">'+(p?esc(p.name):esc(id))+'</button>';}
  function draw(){
    var st=squadState(),pool=st.pool,complete=st.complete;
    box.innerHTML='<div class="role-ribbon">第二阶段 · 队长分队 '+voteCountdown(gs.stageDeadlineAt,'队长分队')+'</div>'
      +'<div class="hub-section-title"><div><small>SQUAD FORM · 6 队 × 5 人</small><h2>把 30 名队员编入 6 个小队</h2></div><span>'+st.filled+' / '+players.length+(complete?' · 已完成':'')+'</span></div>'
      +'<div class="squad-form-panel">'
      +'<div class="squad-toolbar"><button type="button" id="auto-squad-form" class="btn btn-ghost squad-auto-btn">'+(complete?'🎲 重新随机分队':'🎲 一键随机分队')+'</button><p class="squad-toolbar-tip">可先一键随机分队再微调：按住队员直接拖入目标小队，拖回待分配区即移出，小队之间可拖拽互换；点选队员后点小队也能放入。</p></div>'
      +'<div class="squad-pool" data-squad-pool>'+(pool.length?pool.map(poolCardHtml).join(''):'<span class="squad-pool-empty">✅ '+players.length+' 名队员已全部分配，仍可拖拽继续微调</span>')+'</div>'
      +'<div class="squad-grid">'+squadDraft.map(function(s,i){return'<div class="squad-cell'+(s.length===5?' is-full':'')+'" data-squad-cell="'+i+'"><div class="squad-cell-head"><b>'+(i+1)+' 号小队</b><span>'+s.length+' / 5</span></div>'+s.map(function(id){return memberHtml(id,i);}).join('')+(s.length?'':'<span class="squad-cell-empty">空 · 拖入或点选放入</span>')+'</div>';}).join('')+'</div>'
      +'<div class="action-footer"><button id="submit-squad-form" class="btn btn-primary" '+(complete?'':'disabled')+'>'+(complete?'提交分队':'提交分队（每队需满 5 人）')+'</button></div>'
      +'</div>';
    box.querySelectorAll('[data-pool-player]').forEach(function(btn){btn.onclick=function(){pick=pick===btn.dataset.poolPlayer?'':btn.dataset.poolPlayer;draw();};bindSource(btn,btn.dataset.poolPlayer,-1);});
    box.querySelectorAll('[data-squad-member]').forEach(function(btn){btn.onclick=function(event){event.stopPropagation();var s=squadDraft[Number(btn.dataset.squadIndex)],index=s.indexOf(btn.dataset.squadMember);if(index>=0)s.splice(index,1);draw();};bindSource(btn,btn.dataset.squadMember,Number(btn.dataset.squadIndex));});
    box.querySelectorAll('[data-squad-cell]').forEach(function(cell){cell.onclick=function(){var s=squadDraft[Number(cell.dataset.squadCell)];if(!pick||s.length>=5)return;s.push(pick);pick='';draw();};});
    var form=box.querySelector('.squad-form-panel');
    if(form&&!form.__dragBound){form.__dragBound=true;
      form.addEventListener('dragover',function(e){if(!drag)return;var t=resolveTarget(e.target);if(!t)return;var ok=canDrop(t);clearHover();var c=t.kind==='pool'?box.querySelector('.squad-pool'):box.querySelector('[data-squad-cell="'+t.squadIndex+'"]');if(c)c.classList.add(ok?'is-drop-ok':'is-drop-no');if(ok&&e.preventDefault)e.preventDefault();if(e.dataTransfer)e.dataTransfer.dropEffect=ok?'move':'none';});
      form.addEventListener('drop',function(e){if(!drag)return;var t=resolveTarget(e.target);if(!t||!canDrop(t))return;if(e.preventDefault)e.preventDefault();doDrop(t);drag=null;draw();});
      form.addEventListener('dragend',function(){drag=null;clearHover();});
    }
    document.getElementById('auto-squad-form').onclick=function(){var s=squadState();if(s.complete&&!window.confirm('重新随机会打乱当前 6 个小队的全部编排，确定要重新随机分队吗？'))return;autoFill(s.complete);pick='';draw();};
    document.getElementById('submit-squad-form').onclick=function(){if(!squadDraft.every(function(s){return s.length===5;}))return;var flat=squadDraft.reduce(function(all,s){return all.concat(s);},[]);this.disabled=true;playerAction('squad-form',flat).then(function(){squadDraft=null;}).catch(function(error){var btn=document.getElementById('submit-squad-form');if(btn)btn.disabled=false;showActionError(error);});};
  }
  draw();
}
/* ---------- 第三阶段：全员掷骰（小队错峰，间隔 1 秒，各 15 秒窗口） ---------- */
function rollPanel(box,gs,mine){
  var players=mine.players||[],rolled=players.filter(function(p){return p.dice!=null||p.autoRolled;}).length;
  var myId=myPlayerId(),squads=mine.squads||[],mySquad=-1;
  for(var k=0;k<squads.length;k++)if((squads[k]||[]).indexOf(myId)>=0){mySquad=k;break;}
  if(mySquad<0)mySquad=0;
  var goAt=Number(gs.rollGoAt||0),opens=gs.rollOpenAts||[],now=Date.now();
  function openAtOf(k){var v=opens[k];return Number(v!=null&&isFinite(Number(v))?v:goAt+k*1000);}
  var openAt=openAtOf(mySquad),closeAt=openAt+15000;
  var me=players.find(function(p){return p.id===myId;}),myRolled=!!(me&&(me.dice!=null||me.autoRolled));
  var statusHtml;
  if(now<goAt){
    statusHtml='<div class="battle-countdown"><small>统一开掷倒计时</small><strong>'+Math.ceil((goAt-now)/1000)+'</strong><p>倒计时结束后 6 支小队按 1 秒间隔依次开掷，你们是第 '+(mySquad+1)+' 小队。</p></div>';
  }else if(myRolled||now>closeAt){
    statusHtml='<p>已扔完，等待稍后开盲盒。</p>';
  }else if(now<openAt){
    statusHtml='<div class="battle-countdown"><small>你们是第 '+(mySquad+1)+' 小队</small><strong>'+Math.ceil((openAt-now)/1000)+'</strong><p>秒后轮到你们，请留意掷骰端。</p></div>';
  }else{
    statusHtml='<div class="battle-countdown"><small>轮到你们 · 第 '+(mySquad+1)+' 小队</small><strong>'+Math.max(0,Math.ceil((closeAt-now)/1000))+'</strong><p>本小队掷骰窗口剩余秒数，请立即前往掷骰。</p></div>';
  }
  var schedule='<div class="roll-schedule"><b>小队开掷时刻表 · 间隔 1 秒 / 各 15 秒窗口</b>'+[0,1,2,3,4,5].map(function(k){
    var at=openAtOf(k),state=now>at+15000?'已结束':now>=at?'进行中':'+'+Math.max(0,Math.ceil((at-now)/1000))+'s';
    return'<span class="roll-schedule-item'+(k===mySquad?' is-mine':'')+'">'+(k+1)+' 号小队 · '+state+'</span>';
  }).join('')+'</div>';
  box.innerHTML='<div class="role-ribbon">第三阶段 · 全员掷骰 '+voteCountdown(gs.stageDeadlineAt,'全员掷骰')+'</div>'
    +'<div class="hub-section-title"><div><small>ROLL · 小队错峰 · 每队 15 秒</small><h2>'+(now<goAt?'掷骰即将开始':'掷骰进行中')+'</h2></div><span>'+rolled+' / '+players.length+'</span></div>'
    +statusHtml+schedule
    +'<div class="action-footer"><a class="btn btn-primary" href="/player">前往掷骰</a></div>';
  if(now<goAt)setTimeout(loadGameState,Math.min(goAt-now+100,4000));
  else if(now<closeAt)setTimeout(loadGameState,Math.min(closeAt-now+100,16000));
}
/* ---------- 第四阶段：盲盒 ---------- */
function blindBoxPanel(box,gs,mine){
  var players=mine.players||[],opened=players.filter(function(p){return p.blindBoxOpened||p.blindBox!=null;}).length,me=players.find(function(p){return p.id===myPlayerId();}),mineHtml;
  // 开盒窗口只有 15 秒，跳转掷骰端来不及：在大厅原地开盒（player-action 只需登录会话，无需掷骰令牌）
  var myActive=Object.keys(gs.matches||{}).some(function(k){var m=gs.matches[k];return m&&m.status==='active'&&(m.a===mine.id||m.b===mine.id);});
  if(me&&(me.blindBoxOpened||me.blindBox!=null)){mineHtml='<div class="blindbox-result"><div><small>我的盲盒档位</small><b>'+blindBoxText(me)+'</b></div><div><small>我的最终点数（骰子 + 盲盒）</small><strong>'+(me.diceFinal!=null?finalPoints(me)+' 点':'待掷骰结算')+'</strong></div></div>';}
  else if(!myActive){mineHtml='<p>本队本轮没有比赛，无需开盲盒。</p>';}
  else{mineHtml='<p>你还未开启本轮盲盒，盲盒会为最终点数带来有符号的加减；15 秒内不开视为放弃（按 0 计），系统不再代开。</p><div class="action-footer"><button id="lobby-blindbox-open" class="btn btn-primary">立即开盲盒</button></div>';}
  box.innerHTML='<div class="role-ribbon">第四阶段 · 盲盒 '+voteCountdown(gs.stageDeadlineAt,'开盲盒')+'</div><div class="hub-section-title"><div><small>BLIND BOX · 15 秒</small><h2>全员开启盲盒</h2></div><span>'+opened+' / '+players.length+'</span></div>'+mineHtml;
  var openBtn=document.getElementById('lobby-blindbox-open');
  if(openBtn)openBtn.onclick=function(){openBtn.disabled=true;playerAction('blind-box-open',[]).catch(function(error){openBtn.disabled=false;showActionError(error);});};
}
/* ---------- 第五阶段：战术（重掷 + 出场顺序） ---------- */
function rerollLogHtml(mine){var log=mine.rerollLog||[];if(!log.length)return'';return'<div class="reroll-log"><b>重掷记录</b>'+log.map(function(item){return'<span class="reroll-log-item">'+esc(item.playerName)+' '+Number(item.from)+' → '+Number(item.to)+'</span>';}).join('')+'</div>';}
function tacticsPanel(box,gs,mine){
  var players=mine.players||[],captain=mine.roles&&mine.roles.captain,isCaptain=captain===myPlayerId(),locked=!!mine.squadOrderLocked,confirmed=!!mine.tacticsConfirmed,limit=Math.min(Number(mine.rerollQuota||0),5),used=Number(mine.rerollUsed||0),left=Math.max(0,limit-used);
  announceRule('TACTICS',(gs.startedAt||'game')+'-day'+gs.day+'-TACTICS');
  if(locked)squadOrderDraft=null;
  if(!isCaptain){
    box.innerHTML='<div class="role-ribbon">第五阶段 · 战术调整 '+voteCountdown(gs.stageDeadlineAt,'战术调整')+'</div>'
      +'<div class="hub-section-title"><div><small>TACTICS · 队长操作中</small><h2>等待队长完成重掷与排序</h2></div><span>'+(confirmed?'✅ 队长已确认完成，等待对局':(locked?'出场顺序已锁定':'出场顺序未锁定'))+'</span></div>'
      +'<p>队长 <b>'+esc(playerName(mine,captain))+'</b> 正在调整（重掷 '+used+' / '+limit+' 次）。'+(confirmed?'战术已确认，全部队伍确认后立即进入对局。':'')+'</p>'+rerollLogHtml(mine)+squadsReadonlyHtml(mine,true);
    return;
  }
  if(mine.squads&&!locked&&(!squadOrderDraft||squadOrderDraft.length!==6))squadOrderDraft=[1,2,3,4,5,6];
  /* 小队总分 = 5 人最终点数（重掷后骰子+盲盒）之和；名次为 6 支小队中的降序排名，随草稿调整即时重算 */
  function squadTotalOf(origNo){var members=(mine.squads&&mine.squads[origNo-1])||[],sum=0,has=false;members.forEach(function(id){var p=players.find(function(x){return x.id===id;});if(p&&p.diceFinal!=null){sum+=finalPoints(p);has=true;}});return has?sum:null;}
  function squadRankOf(origNo){var t=squadTotalOf(origNo);if(t==null)return null;var rank=1;for(var n=1;n<=6;n++){if(n===origNo)continue;var o=squadTotalOf(n);if(o!=null&&o>t)rank++;}return rank;}
  function orderCardHtml(origNo,pos){
    var members=(mine.squads&&mine.squads[origNo-1])||[],total=squadTotalOf(origNo),rank=squadRankOf(origNo);
    return'<div class="squad-order-card"><div class="squad-order-head"><i>'+(pos+1)+'</i><b>'+(locked?(pos+1)+' 号出战小队':'原 '+origNo+' 号小队')+'</b><span>'+members.length+' 人'+(total!=null?' · 总分 '+total+' 点 · 6 队中第 '+rank+' 高':'')+'</span></div><div class="squad-order-members">'+members.map(function(id){var p=players.find(function(x){return x.id===id;});return'<span>'+(p?esc(p.name):esc(id))+(p&&p.diceFinal!=null?' <em>'+finalPoints(p)+' 点</em>':'')+'</span>';}).join('')+'</div>'+(locked?'':'<div class="squad-order-actions"><button class="btn btn-ghost" data-order-move="up" data-order-index="'+pos+'" '+(pos===0||confirmed?'disabled':'')+'>上移</button><button class="btn btn-ghost" data-order-move="down" data-order-index="'+pos+'" '+(pos===5||confirmed?'disabled':'')+'>下移</button></div>')+'</div>';
  }
  function draw(){
    var order=locked?[1,2,3,4,5,6]:(squadOrderDraft||[1,2,3,4,5,6]);
    box.innerHTML='<div class="role-ribbon">第五阶段 · 战术调整 '+voteCountdown(gs.stageDeadlineAt,'战术调整')+'</div>'
      +'<div class="hub-section-title"><div><small>REROLL · 全队限 '+limit+' 次</small><h2>队长重掷</h2></div><span>剩余 '+left+' 次</span></div>'
      +'<div class="reroll-grid">'+players.map(function(p){var rolled=p.diceFinal!=null;return'<div class="reroll-row'+(p.rerolled?' is-rerolled':'')+'"><b>'+esc(p.name)+'</b><span>骰子 '+(rolled?p.diceFinal:'-')+'</span><span>盲盒 '+blindBoxText(p)+'</span><strong>'+(rolled?finalPoints(p)+' 点':'未掷骰')+'</strong>'+(p.rerolled?'<em>已重掷</em>':'')+'<button class="btn btn-ghost" data-reroll="'+esc(p.id)+'" '+(!rolled||left<=0||rerollBusy||confirmed?'disabled':'')+'>重掷</button></div>';}).join('')+'</div>'
      +rerollLogHtml(mine)
      +'<div class="hub-section-title"><div><small>SQUAD ORDER · 第 1 队最先出战</small><h2>出场顺序</h2></div><span>'+(locked?'已锁定':'未锁定')+'</span></div>'
      +(mine.squads?'<div class="squad-order-list">'+order.map(function(origNo,pos){return orderCardHtml(origNo,pos);}).join('')+'</div>':'<p>分队数据尚未就绪。</p>')
      +(locked?'<p class="squad-order-locked-note">出场顺序已锁定，对局将按此顺序依次出战。</p>':'<div class="action-footer"><button id="lock-squad-order" class="btn btn-primary" '+(mine.squads&&!confirmed?'':'disabled')+'>锁定出场顺序</button></div>')
      +'<div class="tactics-confirm-bar">'+(confirmed
        ?'<span class="tactics-confirm-state is-done">✅ 已确认完成战术布置，重掷与排序已锁定</span><button id="tactics-cancel-confirm" class="btn btn-ghost">取消确认</button>'
        :'<span class="tactics-confirm-state">确认后重掷与排序将锁定；全部队伍确认后立即进入对局</span><button id="tactics-confirm" class="btn btn-primary">完成战术布置</button>')+'</div>';
    box.querySelectorAll('[data-reroll]').forEach(function(btn){btn.onclick=function(){var p=players.find(function(x){return x.id===btn.dataset.reroll;});if(!window.confirm('确定为 '+(p?p.name:'该队员')+' 重掷？重掷后骰子点数重新随机。'))return;rerollBusy=true;btn.disabled=true;playerAction('reroll',[btn.dataset.reroll]).then(function(){rerollBusy=false;}).catch(function(error){rerollBusy=false;showActionError(error);});};});
    box.querySelectorAll('[data-order-move]').forEach(function(btn){btn.onclick=function(){var pos=Number(btn.dataset.orderIndex),target=btn.dataset.orderMove==='up'?pos-1:pos+1;if(target<0||target>=squadOrderDraft.length)return;var tmp=squadOrderDraft[pos];squadOrderDraft[pos]=squadOrderDraft[target];squadOrderDraft[target]=tmp;draw();};});
    var lock=document.getElementById('lock-squad-order');
    if(lock)lock.onclick=function(){if(!window.confirm('锁定后不能再调整出场顺序，确认提交？'))return;lock.disabled=true;playerAction('squad-order',squadOrderDraft.map(function(n){return String(n);})).then(function(){squadOrderDraft=null;}).catch(function(error){lock.disabled=false;showActionError(error);});};
    var confirmBtn=document.getElementById('tactics-confirm');
    if(confirmBtn)confirmBtn.onclick=function(){confirmBtn.disabled=true;playerAction('tactics-confirm',[]).catch(function(error){confirmBtn.disabled=false;showActionError(error);});};
    var cancelConfirmBtn=document.getElementById('tactics-cancel-confirm');
    if(cancelConfirmBtn)cancelConfirmBtn.onclick=function(){cancelConfirmBtn.disabled=true;playerAction('tactics-cancel',[]).catch(function(error){cancelConfirmBtn.disabled=false;showActionError(error);});};
  }
  draw();
}
/* ---------- 第六阶段：对局 ---------- */
function battleScoreboard(match,a,b,side){
  var rounds=match.rounds||[];
  var cells=[1,2,3,4,5,6].map(function(n){var entry=rounds.find(function(r){return r.round===n;}),current=match.phase==='BATTLE'&&match.round===n,cls=entry?(entry.winner?(entry.winner===side?'is-win':'is-lose'):'is-draw'):current?'is-current':'';return'<div class="score-cell '+cls+'"><i>'+n+'</i><b>'+(entry?(entry.winner?(entry.winner===side?'胜':'负'):'平'):current?'…':'')+'</b></div>';}).join('');
  return'<div class="battle-scoreboard"><div class="battle-score-team"><b>'+esc(a.name)+'</b><strong>'+Number(match.winsA||0)+'</strong></div><div class="score-cells">'+cells+'</div><div class="battle-score-team is-right"><strong>'+Number(match.winsB||0)+'</strong><b>'+esc(b.name)+'</b></div></div>';
}
function roundSideHtml(name,entry,side,won){var crit=entry['crit'+side];return'<div class="round-side'+(won?' is-winner':'')+'"><b>'+esc(name)+'</b><span>基础 '+Number(entry['base'+side]||0)+(crit?' <em>暴击 ×1.5</em>':'')+'</span><span>猜中 '+Number(entry['guessHits'+side]||0)+' 人次 · 加成 +'+Number(entry['guessBonus'+side]||0)+'</span><strong>'+Number(entry['power'+side]||0)+'</strong></div>';}
function roundEntryHtml(entry,a,b){var w=entry.winner,winnerName=w?(w==='A'?a:b).name:'';return'<div class="round-entry"><div class="round-entry-head"><b>第 '+Number(entry.round)+' 局</b><span>'+(w?esc(winnerName)+' 胜':'平局')+'</span></div><div class="round-entry-sides">'+roundSideHtml(a.name,entry,'A',w==='A')+roundSideHtml(b.name,entry,'B',w==='B')+'</div></div>';}
function roundsListHtml(match,a,b){var rounds=match.rounds||[];if(!rounds.length)return'';return'<div class="rounds-list">'+rounds.map(function(entry){return roundEntryHtml(entry,a,b);}).join('')+'</div>';}
/* ---------- 单场战况详情：按需加载（/api/game-state 摘要只带局号与胜负） ---------- */
function detailKey(match){return match.id+':'+(match.rounds||[]).length;}
function detailFor(match){return matchDetails[detailKey(match)]||null;}
function loadMatchDetail(match,onDone){var key=detailKey(match),cached=matchDetails[key];if(cached){if(onDone)onDone(cached);return;}var pending=matchDetailPending[key];if(!pending){pending=api('/api/game-state/matches/'+encodeURIComponent(match.id)).then(function(d){matchDetails[key]=d;return d;}).catch(function(){return null;}).finally(function(){delete matchDetailPending[key];});matchDetailPending[key]=pending;}if(onDone)pending.then(function(d){if(d&&expandedRounds[match.id]!==false)onDone(d);});}
function repaint(){if(gsCache)renderPlayerActions(gsCache);}
function roundsSectionHtml(match,a,b){var rounds=match.rounds||[];if(!rounds.length)return'';if(expandedRounds[match.id]){var detail=detailFor(match);return'<div class="rounds-toggle"><button type="button" class="btn btn-ghost rounds-toggle-btn" data-rounds-match="'+esc(match.id)+'">收起逐局战况</button><div class="rounds-detail">'+(detail?roundsListHtml(detail,a,b):'<p class="rounds-loading">战况加载中…</p>')+'</div></div>';}return'<div class="rounds-toggle"><button type="button" class="btn btn-ghost rounds-toggle-btn" data-rounds-match="'+esc(match.id)+'">查看逐局战况（'+rounds.length+' 局）</button></div>';}
function bindRoundsSection(box,match,a,b){var btn=box.querySelector('[data-rounds-match]');if(!btn)return;btn.onclick=function(){expandedRounds[match.id]=!expandedRounds[match.id];if(expandedRounds[match.id]&&!detailFor(match))loadMatchDetail(match,repaint);repaint();};}
/* ---------- 提前猜阵：排队小队在大厅直接提交，无需跳转掷骰端 ---------- */
function mySquadRound(mine,myId){var squads=mine.squads||[];for(var i=0;i<squads.length&&i<6;i++){if((squads[i]||[]).indexOf(myId)>=0)return i+1;}return 0;}
function preGuessHtml(match,mine,a,b,side){
  var myId=myPlayerId(),myRound=mySquadRound(mine,myId);
  if(!myRound||myRound<=match.round||myRound<2)return'';
  var key=match.id+':pre:'+myRound;
  if(key!==preGuessKey){preGuessKey=key;preGuessSel=[];preGuessEdit=false;}
  var status=match.preGuessStatus&&match.preGuessStatus[myRound]&&match.preGuessStatus[myRound][side],submitted=!!(status&&status[myId]);
  if(submitted&&!preGuessEdit)return'<div class="pl-preguess"><div class="hub-section-title"><div><small>PRE-GUESS · 第 '+myRound+' 局你出战</small><h2>提前猜阵已提交</h2></div></div><p>第 '+myRound+' 局开始时自动生效，揭晓前可随时撤回或改投。</p><div class="action-footer"><button type="button" class="btn btn-ghost" id="preguess-retract">撤回提前猜阵</button><button type="button" class="btn btn-primary" id="preguess-edit">改投</button></div></div>';
  var enemy=side==='A'?b:a,grid=((enemy&&enemy.players)||[]).map(function(p){var pid=String(p.id),sel=preGuessSel.indexOf(pid)>=0;return'<button type="button" class="pl-roster-item'+(sel?' selected':'')+'" data-pre-pick="'+esc(pid)+'"><b>'+esc(p.name)+'</b><span>'+esc(p.department||'')+'</span></button>';}).join('');
  return'<div class="pl-preguess"><div class="hub-section-title"><div><small>PRE-GUESS · 第 '+myRound+' 局你出战</small><h2>提前猜阵</h2></div></div><p>趁排队时间，猜一猜敌方第 '+myRound+' 局会派哪 5 人出战；命中可为小队战力加分，该局开始时自动生效，揭晓前可撤回。</p><div class="pl-roster">'+grid+'</div><div class="action-footer"><span id="preguess-n">已选 '+preGuessSel.length+' / 5</span><button type="button" class="btn btn-primary" id="preguess-submit"'+(preGuessSel.length===5?'':' disabled')+'>提前提交猜阵</button></div></div>';
}
function bindPreGuess(box,match,mine){
  var myRound=mySquadRound(mine,myPlayerId());
  if(!myRound||myRound<=match.round||myRound<2)return;
  box.querySelectorAll('[data-pre-pick]').forEach(function(btn){btn.onclick=function(){var pid=btn.dataset.prePick,i=preGuessSel.indexOf(pid);if(i>=0)preGuessSel.splice(i,1);else{if(preGuessSel.length>=5)return;preGuessSel.push(pid);}repaint();};});
  var submit=document.getElementById('preguess-submit');
  if(submit)submit.onclick=function(){if(preGuessSel.length!==5)return;submit.disabled=true;playerAction('pre-guess',preGuessSel.slice()).then(function(){preGuessEdit=false;}).catch(function(error){submit.disabled=false;showActionError(error);});};
  var retract=document.getElementById('preguess-retract');
  if(retract)retract.onclick=function(){retract.disabled=true;playerAction('retract-guess',[]).catch(function(error){retract.disabled=false;showActionError(error);});};
  var edit=document.getElementById('preguess-edit');
  if(edit)edit.onclick=function(){preGuessEdit=true;repaint();};
}
function battlePanel(box,gs,mine){
  var teamId=data.me.teamId,mineMatches=bracketMatches(gs).filter(function(m){return m.a===teamId||m.b===teamId;});
  var match=mineMatches.find(function(m){return m.status==='active';})||mineMatches[0];
  if(!match){box.innerHTML='<div class="role-ribbon">观战模式</div><h2>本队本轮没有对阵</h2><p>你可以在下方查看其他场次的实时比分。</p>';return;}
  announceRule('BATTLE',(gs.startedAt||'game')+'-'+match.id);
  var a=gs.teams.find(function(t){return t.id===match.a;})||{id:match.a,name:match.a},b=gs.teams.find(function(t){return t.id===match.b;})||{id:match.b,name:match.b},side=match.a===teamId?'A':'B',stage=bracketStage(match);
  var head='<div class="role-ribbon">'+esc(stage.label)+' · 对局</div><div class="hub-section-title"><div><small>BATTLE · 6 局胜场制</small><h2>'+esc(a.name)+' VS '+esc(b.name)+'</h2></div><span>'+Number(match.winsA||0)+' : '+Number(match.winsB||0)+'</span></div>'+battleScoreboard(match,a,b,side);
  if(expandedRounds[match.id]&&!detailFor(match))loadMatchDetail(match,repaint);
  if(match.status==='done'){box.innerHTML=head+postMatchHtml(gs,match,mine)+roundsSectionHtml(match,a,b);bindRoundsSection(box,match,a,b);return;}
  if(match.phase==='RESULT'){var winnerTeam=match.winner===match.a?a:match.winner===match.b?b:null,won=match.winner===teamId,resultCls=winnerTeam?(won?' is-win':' is-defeat'):'',resultTitle=!winnerTeam?'本场结果结算中':won?'🎉 本队拿下本场':'本场惜败 · '+esc(winnerTeam.name)+' 晋级';box.innerHTML=head+'<div class="match-result-banner'+resultCls+'"><small>'+(winnerTeam?(won?'VICTORY':'DEFEAT'):'MATCH RESULT')+'</small><h2>'+resultTitle+'</h2><strong>6 局胜场 '+Number(match.winsA||0)+' : '+Number(match.winsB||0)+'</strong><p>判定依据：'+esc(match.tieBreak||'胜场')+'</p>'+(match.resultReadyAt?'<p>'+voteCountdown(match.resultReadyAt,'结果展示')+'后进入后续赛程</p>':'')+'</div>'+roundsSectionHtml(match,a,b);bindRoundsSection(box,match,a,b);return;}
  if(match.phase!=='BATTLE'){box.innerHTML=head+'<p>本场对阵尚未开赛，等待系统开局。</p>';return;}
  var myId=myPlayerId(),inSquad=!!(mine.squads&&mine.squads[match.round-1]&&mine.squads[match.round-1].indexOf(myId)>=0);
  if(match.roundPhase==='REVEAL'){
    var detail=detailFor(match),last=detail?(detail.rounds||[]).slice(-1)[0]:null;
    if(!detail)loadMatchDetail(match,repaint);
    box.innerHTML=head+'<div class="guess-panel is-reveal"><div class="hub-section-title"><div><small>ROUND '+Number(match.round)+' / 6 · REVEAL</small><h2>第 '+Number(match.round)+' 局结果揭晓</h2></div></div>'+(last?roundEntryHtml(last,a,b)+'<p>即将进入下一局。</p>':'<p>正在加载本局结果…</p>')+'</div>'+preGuessHtml(match,mine,a,b,side)+roundsSectionHtml(match,a,b);
    bindRoundsSection(box,match,a,b);
    bindPreGuess(box,match,mine);
    return;
  }
  var status=match.guessStatus||{},mySubmitted=Object.keys(status[side]||{}).length,oppSubmitted=Object.keys(status[side==='A'?'B':'A']||{}).length;
  var pre=preGuessHtml(match,mine,a,b,side);
  box.innerHTML=head+'<div class="guess-panel"><div class="hub-section-title"><div><small>ROUND '+Number(match.round)+' / 6 · GUESS</small><h2>第 '+Number(match.round)+' 局 · 猜阵进行中</h2></div>'+voteCountdown(match.guessDeadlineAt,'第 '+Number(match.round)+' 局 · 猜阵')+'</div><p>本局由双方 '+Number(match.round)+' 号小队出战。出战队员请在掷骰端猜测敌方本局出战的 5 名队员，命中可为小队战力加分。</p><div class="guess-status"><span>我方出战小队已提交 <b>'+mySubmitted+' / 5</b></span><span>对方已提交 <b>'+oppSubmitted+' / 5</b></span></div>'+(inSquad?'<div class="action-footer"><a class="btn btn-primary" href="/player">前往提交猜阵</a></div>':(pre||'<p class="timing-spectator-note">你不在本局出战小队中，可在此关注双方提交进度。</p>'))+'</div>'+roundsSectionHtml(match,a,b);
  bindRoundsSection(box,match,a,b);
  bindPreGuess(box,match,mine);
}
function renderParallel(gs,box){
  var labels={CAPTAIN_VOTE:['第一阶段 · 队长投票','全员投票选出本队队长'],SQUAD_FORM:['第二阶段 · 分队','队长编排 6 个小队'],ROLL:['第三阶段 · 掷骰','全员限时掷骰'],BLIND_BOX:['第四阶段 · 盲盒','全员开启盲盒'],TACTICS:['第五阶段 · 战术','队长重掷与出场排序'],BATTLE:['第六阶段 · 对局','bracket 单败对垒']},label=labels[gs.stage]||['实时赛况','当前赛程'];
  document.getElementById('lobby-stage-kicker').textContent=label[0];
  document.getElementById('lobby-stage-title').textContent=label[1];
  var matches=bracketMatches(gs);
  document.getElementById('lobby-matches').innerHTML=matches.map(function(item){return tournamentWatchCard(gs,item);}).join('');
  var mine=findMine(gs);
  if(!mine){box.classList.add('hidden');return;}
  box.classList.remove('hidden');
  /* 离开对应阶段后丢弃本地草稿，避免脏数据带入下一阶段 */
  if(gs.stage!=='CAPTAIN_VOTE')captainVotePick='';
  if(gs.stage!=='SQUAD_FORM')squadDraft=null;
  if(gs.stage!=='TACTICS'){squadOrderDraft=null;rerollBusy=false;}
  if(gs.stage!=='BATTLE'){preGuessKey='';preGuessSel=[];preGuessEdit=false;}
  if(gs.stage==='CAPTAIN_VOTE'){captainVotePanel(box,gs,mine);return;}
  if(gs.stage==='SQUAD_FORM'){squadFormPanel(box,gs,mine);return;}
  /* 已被淘汰的队伍不再展示掷骰/盲盒/战术等参与型面板，统一转入观战态 */
  if(teamEliminated(gs,mine.id)){
    var lastMatch=matches.filter(function(m){return m.a===mine.id||m.b===mine.id;})[0];
    document.getElementById('lobby-stage-kicker').textContent='观战模式 · 本队已淘汰';
    document.getElementById('lobby-stage-title').textContent='剩余赛程可在下方实时关注';
    box.innerHTML='<div class="role-ribbon">本队赛程已结束</div>'+eliminatedPanelHtml(gs,lastMatch,mine);
    bindRoundsSection(box,lastMatch);
    return;
  }
  if(gs.stage==='ROLL'){rollPanel(box,gs,mine);return;}
  if(gs.stage==='BLIND_BOX'){blindBoxPanel(box,gs,mine);return;}
  if(gs.stage==='TACTICS'){tacticsPanel(box,gs,mine);return;}
  if(gs.stage==='BATTLE'){battlePanel(box,gs,mine);return;}
  var last=bracketMatches(gs).filter(function(m){return m.a===mine.id||m.b===mine.id;})[0];
  if(last){var html=postMatchHtml(gs,last,mine);if(html)box.innerHTML=html;else box.classList.add('hidden');}else box.classList.add('hidden');
}
function renderPlayerActions(gs){var box=document.getElementById('player-actions');floatDeadline=null;floatLabel='';floatStage=gs&&gs.stage?String(gs.stage):'';syncCaptainUi(gs);if(!data||!data.me.teamId||!gs||(data.phase!=='PLAYING'&&data.phase!=='FINISHED')){box.classList.add('hidden');return;}if(gs.mode==='parallel'){renderParallel(gs,box);return;}box.classList.add('hidden');}
/* 冠军卡个性化状态条：仅已入队的登录用户可见，夺冠队与其他队伍文案不同 */
function championPersonalHtml(championId,overall){var me=data&&data.me;if(!me||!me.teamId)return'';if(me.teamId===championId)return'<p class="champion-personal is-self">🎉 恭喜，本队夺冠！</p>';return'<p class="champion-personal">'+(overall?'本队两天赛程已全部结束，最终战绩已保存。':'本队今日赛程已结束，战绩已保存。')+'</p>';}
function renderChampionAnnouncement(gs){var box=document.getElementById('champion-announcement');if(!box){box=document.createElement('section');box.id='champion-announcement';box.className='hub-card champion-announcement hidden';var hero=document.getElementById('lobby-hero');hero.parentNode.insertBefore(box,hero.nextSibling);}/* 冠军卡只在非比赛进行阶段展示：当日冠军产生时大厅阶段会转为 FINISHED，新一轮比赛一开始（PLAYING）即隐藏，避免把操作面板挤到下方 */if(data&&data.phase==='PLAYING'){box.className='hub-card champion-announcement hidden';box.innerHTML='';return;}var results=gs&&gs.dayResults||{},overall=gs&&gs.overallResult;if(overall&&overall.champion){var decidedText={BOTH_DAYS:'两日双冠',MATCH_WINS:'累计胜场决胜',GMV:'GMV 决胜',OVERTIME:'加赛夺冠'}[overall.decidedBy]||'',winner=(overall.standings||[]).find(function(team){return team.id===overall.champion;})||{},entries=[1,2].map(function(day){var result=results['day'+day],team=result&&(result.teams||[]).find(function(item){return item.id===overall.champion;});return team?{day:day,team:team}:null;}).filter(Boolean);box.className='hub-card champion-announcement is-overall';box.innerHTML='<div class="champion-summary"><div class="champion-crown">🏆</div><div><p class="hub-eyebrow">TWO-DAY GRAND CHAMPION</p><h2>两天最终总冠军 · '+esc(winner.name||overall.champion)+'</h2><p>两天累计 <b>'+Number(winner.totalMatchWins||0)+'</b> 场胜利'+(decidedText?' · '+decidedText:'')+'</p>'+championPersonalHtml(overall.champion,true)+'</div></div>'+TournamentUI.resultRosterHtml(entries);return;}if(overall&&overall.status==='OVERTIME_PENDING'){var candidates=overall.candidates||[],candidateNames=candidates.map(function(team){return esc(team.name);}).join(' vs ');box.className='hub-card champion-announcement is-overall';box.innerHTML='<div class="champion-summary"><div class="champion-crown">🏆</div><div><p class="hub-eyebrow">TWO-DAY GRAND CHAMPION</p><h2>两天总冠军加赛待定 · '+candidateNames+'</h2><p>两天胜场与 GMV 均相同，等待管理员安排加赛总决赛。</p></div></div>';return;}var daily=results.day2||results.day1;if(daily&&daily.champion){var team=(daily.teams||[]).find(function(item){return item.id===daily.champion;})||{};box.className='hub-card champion-announcement is-daily';box.innerHTML='<div class="champion-summary"><div class="champion-crown">🏆</div><div><p class="hub-eyebrow">DAY '+Number(daily.day||1)+' CHAMPION</p><h2>今日冠军 · '+esc(team.name||daily.champion)+'</h2><p>今日总决赛已经结束，比赛结果已同步保存。</p>'+championPersonalHtml(daily.champion,false)+'</div></div>'+TournamentUI.resultRosterHtml([{day:Number(daily.day||1),team:team}]);return;}box.className='hub-card champion-announcement hidden';box.innerHTML='';}
function loadGameState(){if(gameLoading)return;gameLoading=true;fetch('/api/game-state').then(function(r){return r.status===204?null:r.json();}).then(function(d){var gs=d&&d.state;gsCache=gs;renderChampionAnnouncement(gs);renderPlayerActions(gs);}).catch(function(){}).finally(function(){gameLoading=false;});}
function queueRefresh(type){if(type==='lobby')refreshType='lobby';else if(!refreshType)refreshType='game';if(refreshTimer)return;refreshTimer=setTimeout(function(){var pending=refreshType;refreshTimer=null;refreshType=null;if(pending==='lobby')load();else loadGameState();},150+Math.floor(Math.random()*450));}
function connect(){if(es)es.close();es=new EventSource('/api/lobby/events');es.onopen=function(){queueRefresh('lobby');};es.onmessage=function(e){var m=JSON.parse(e.data);if(m.type==='lobby')queueRefresh('lobby');if(m.type==='game')queueRefresh('game');if(m.type==='chat'){var l=document.getElementById('chat-list');l.insertAdjacentHTML('beforeend','<div class="chat-msg"><b>'+esc(m.sender)+'</b><p>'+esc(m.content)+'</p></div>');l.scrollTop=l.scrollHeight;}};}
function load(){if(lobbyLoading){lobbyReloadPending=true;return;}lobbyLoading=true;api('/api/lobby').then(function(d){var old=data&&data.me.teamId;data=d;document.querySelector('.lobby-shell').classList.toggle('game-active',d.phase==='PLAYING');render();renderAfkState();syncCaptainUi(gsCache);if(firstLoad){firstLoad=false;loadGameState();}if(old!==d.me.teamId)connect();}).finally(function(){lobbyLoading=false;if(lobbyReloadPending){lobbyReloadPending=false;load();}});}
document.getElementById('chat-toggle').onclick=function(){chatCollapsed=!chatCollapsed;document.getElementById('team-chat').classList.toggle('chat-collapsed',chatCollapsed);syncChatToggle();};document.getElementById('chat-ball').onclick=function(){chatCollapsed=false;document.getElementById('team-chat').classList.remove('chat-collapsed');syncChatToggle();};document.getElementById('chat-form').onsubmit=function(e){e.preventDefault();var i=document.getElementById('chat-input');if(!i.value.trim())return;api('/api/lobby/chat','POST',{content:i.value.trim()}).then(function(){i.value='';});};document.getElementById('lobby-logout').onclick=function(){api('/api/auth/logout','POST',{}).finally(function(){location.replace('/login');});};if(window.GameRules)window.GameRules.init();if(window.StagePanel)window.StagePanel.init();load();connect();setInterval(updateVoteCountdowns,250);})();
