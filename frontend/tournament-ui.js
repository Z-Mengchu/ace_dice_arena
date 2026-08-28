(function () {
  'use strict';

  function stage(match) {
    var id = String(match && match.id || '').toLowerCase();
    var prefix = id.charAt(0), index = Math.max(1, Number(id.slice(1)) || 1);
    if (prefix === 'f') return { rank: 3, index: index, label: '总决赛', shortLabel: '总决赛', code: 'FINAL' };
    if (prefix === 's') return { rank: 2, index: index, label: '半决赛 · 第 ' + index + ' 场', shortLabel: '半决赛', code: 'SEMI FINAL' };
    return { rank: 1, index: index, label: '首轮 · 第 ' + index + ' 场', shortLabel: '首轮', code: 'OPENING ROUND' };
  }

  function matches(state) {
    var values = Object.keys(state && state.matches || {}).map(function (key) { return state.matches[key]; });
    return values.sort(function (a, b) {
      var active = (a.status === 'active' ? 0 : 1) - (b.status === 'active' ? 0 : 1);
      if (active) return active;
      var sa = stage(a), sb = stage(b);
      return sb.rank - sa.rank || sa.index - sb.index;
    });
  }

  function status(match) {
    if (match.status === 'done') return '已结束';
    if (match.phase === 'RESULT') return '结果展示';
    return '进行中';
  }

  function currentStage(state) {
    var active = matches(state).find(function (match) { return match.status === 'active'; });
    if (active) return stage(active).shortLabel;
    var latest = matches(state)[0];
    return latest ? stage(latest).shortLabel : '等待对阵';
  }

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (character) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character];
    });
  }

  function resultMembers(entries) {
    var members = [], byKey = Object.create(null);
    (entries || []).forEach(function (entry) {
      if (!entry || !entry.team) return;
      (entry.team.players || []).forEach(function (raw) {
        var legacy = typeof raw === 'string';
        var rawName = legacy ? raw : raw.name;
        var standIn = legacy ? /^沙盘队友（.*）$/.test(rawName || '') : Boolean(raw.standIn || raw.managed);
        var name = standIn && legacy ? rawName.replace(/^沙盘队友（/, '').replace(/）$/, '') : rawName;
        var department = legacy ? '' : (raw.department || '');
        var key = legacy ? String(name) + '|' + department : (raw.id || String(name) + '|' + department);
        var member = byKey[key];
        if (!member) {
          member = { name: name || '未命名队员', department: department, standIn: standIn, days: [] };
          byKey[key] = member; members.push(member);
        }
        member.standIn = member.standIn || standIn;
        if (entry.day && member.days.indexOf(entry.day) < 0) member.days.push(entry.day);
      });
    });
    return members;
  }

  function resultRosterHtml(entries) {
    var members = resultMembers(entries), standIns = members.filter(function (member) { return member.standIn; }).length;
    if (!members.length) return '<div class="result-roster result-roster-empty">该场历史记录暂未包含成员明细</div>';
    return '<div class="result-roster"><div class="result-roster-head"><div><small>CHAMPION ROSTER</small><b>胜利队伍成员</b></div><span>' + members.length + ' 名参赛队友' + (standIns ? ' · ' + standIns + ' 名沙盘队友' : '') + '</span></div>' +
      '<div class="result-roster-grid">' + members.map(function (member, index) {
        var dayLabel = member.days.length ? 'DAY ' + member.days.join('·') : '本场';
        return '<div class="result-member ' + (member.standIn ? 'is-stand-in' : '') + '"><i>' + String(index + 1).padStart(2, '0') + '</i><span><b>' + esc(member.name) + '</b><small>' + esc(member.department || (member.standIn ? '系统托管席位' : '正式参赛成员')) + '</small></span><em>' + (member.standIn ? '沙盘队友' : '参赛队友') + '</em><u>' + dayLabel + '</u></div>';
      }).join('') + '</div></div>';
  }

  window.TournamentUI = { stage: stage, matches: matches, status: status, currentStage: currentStage,
    resultMembers: resultMembers, resultRosterHtml: resultRosterHtml };
})();
