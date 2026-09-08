(function () {
  'use strict';

  var rules = {
    CAPTAIN_VOTE: {
      step: '第一阶段', title: '队长投票', summary: '开赛后先由全队独立投票选出队长，本阶段限时 20 秒。',
      items: ['全员皆可被选为队长，不再限制前端/后端。', '每名真实玩家只能投一票；托管队友不参与投票。', '平票或无人投票时，按队员名单顺序取先者当选。', '20 秒内未提交视为弃票，系统按已投票计票。']
    },
    SQUAD_FORM: {
      step: '第二阶段', title: '队长分队', summary: '队长把本队 30 人编排成 6 支 5 人小队（即 1~6 号出场位），限时 90 秒。',
      items: ['只有当选队长可以操作，拖拽或点选均可。', '6 支小队各 5 人，必须覆盖全队 30 名队员且不重复。', '分队结果全天锁定不变；每个 bracket 轮次的掷骰/盲盒/重掷/排阵会重新进行。', '超时或队长缺席，由系统随机均分 6×5。']
    },
    ROLL: {
      step: '第三阶段', title: '全员掷骰', summary: '全队同一 321 倒计时，6 支小队按 1 秒间隔错峰开掷，每支小队各有 15 秒掷骰窗口。',
      items: ['进入本阶段后全员同时看到 3 秒倒计时，go 之后第 1 小队率先开掷，后续小队每隔 1 秒依次轮到。', '每支小队自轮到时起有 15 秒窗口；每人只掷 1 枚骰子，点数由服务端生成，玩家无法挑选。', '同一小队 5 人的掷骰时刻首尾差 ≤0.5 秒，该小队触发 ×1.5 同步暴击。', '窗口内未掷者由系统代掷，含代掷队员的小队必无暴击（不依赖时刻差）。']
    },
    BLIND_BOX: {
      step: '第四阶段', title: '开盲盒', summary: '每人手动开启自己的盲盒，效果只作用于本人，限时 15 秒。',
      items: ['盲盒对个人点数做 +5 / +4 / +3 / +2 / +1 / -1 / -2 的加减（不含 0）。', '默认概率分布为 1% / 3% / 8% / 20% / 28% / 22% / 18%。', '15 秒内不开视为放弃（按 0 计），系统不再代开。']
    },
    TACTICS: {
      step: '第五阶段', title: '战术窗口：重掷 + 排阵', summary: '队长在 90 秒内使用重掷次数、排定 1~6 号小队出场顺序，并确认完成战术布置。',
      items: ['重掷配额 = 本队 GMV ÷ 10 万（向下取整），每场最多用 5 次。', '重掷只改点数，保留原掷骰时刻（暴击判定不变）与盲盒加成。', '出场顺序按 1~6 号锁定，锁定后本轮不可更改。', '队长确认完成后本队就绪；全部队伍确认后立即进入对局。确认后须先取消确认才能再调整。', '超时：未用重掷作废，顺序按小队编号默认锁定。']
    },
    BATTLE: {
      step: '第六阶段', title: '六局对局', summary: '双方按 1~6 号小队逐局对垒，每局先猜阵、再揭晓胜负，串行推进。',
      items: ['每局由本局出战小队的 5 人各提交一份对敌方出战 5 人的猜测，限时 30 秒，密封提交。', '第 2~6 局出战小队的成员可提前提交该局猜阵，轮到该局时自动生效；提交后仍可撤回或改投。', '双方交齐 5 份后最快 5 秒揭晓，未满 5 秒需稍等；30 秒倒计时结束强制揭晓。', '命中人次 ×0.4 加到本局出战小队战力，单局加成上限 10 分。', '小队战力 = 5 人个人点数之和 ×（同步暴击 ? 1.5 : 1）+ 猜阵加成。', '战力高者赢下本局；相等记平局，双方都不得分。每局揭晓展示 10 秒后进入下一局。']
    },
    RESULT: {
      step: '第七阶段', title: '比赛结算', summary: '6 局打完后判定本场胜负，生成战报并晋级下一轮。',
      items: ['先比 6 局胜场数，胜场多者胜。', '胜场相同（如 3:3）依次比：30 人最终个人点数总和 → 增长系数 → 队伍 ID。', '战报记录每局双方战力、暴击、盲盒、重掷与猜阵明细。', '胜者晋级，直至决出当日冠军。']
    }
  };

  var order = ['CAPTAIN_VOTE', 'SQUAD_FORM', 'ROLL', 'BLIND_BOX', 'TACTICS', 'BATTLE', 'RESULT'];
  var modal;

  function ensureModal() {
    if (modal) return modal;
    modal = document.createElement('div');
    modal.className = 'rules-modal';
    modal.setAttribute('aria-hidden', 'true');
    modal.innerHTML = '<div class="rules-backdrop" data-rules-close></div><section class="rules-sheet" role="dialog" aria-modal="true" aria-labelledby="rules-title"><button class="rules-close" data-rules-close aria-label="关闭游戏规则">×</button><div id="rules-content"></div></section>';
    document.body.appendChild(modal);
    modal.addEventListener('click', function (event) {
      if (event.target.hasAttribute('data-rules-close')) close();
    });
    document.addEventListener('keydown', function (event) { if (event.key === 'Escape') close(); });
    return modal;
  }

  function ruleHtml(key) {
    var rule = rules[key];
    if (!rule) return '';
    return '<p class="rules-step">' + rule.step + '</p><h2 id="rules-title">' + rule.title + '</h2><p class="rules-summary">' + rule.summary + '</p><ol>' + rule.items.map(function (item) { return '<li>' + item + '</li>'; }).join('') + '</ol>';
  }

  function allRulesHtml() {
    return '<p class="rules-step">ACE DICE ARENA · 裁判手册</p><h2 id="rules-title">游戏规则</h2><p class="rules-summary">从队长投票到本场结算，比赛由系统按以下七个阶段固定推进。</p><div class="rules-index">' + order.map(function (key, index) {
      var rule = rules[key];
      return '<article><i>' + String(index + 1).padStart(2, '0') + '</i><div><b>' + rule.title + '</b><span>' + rule.summary + '</span><ul>' + rule.items.map(function (item) { return '<li>' + item + '</li>'; }).join('') + '</ul></div></article>';
    }).join('') + '</div>';
  }

  function open(key) {
    var shell = ensureModal();
    document.getElementById('rules-content').innerHTML = key && rules[key] ? ruleHtml(key) : allRulesHtml();
    shell.classList.add('open');
    shell.setAttribute('aria-hidden', 'false');
    document.body.classList.add('rules-open');
    var closeButton = shell.querySelector('.rules-close');
    if (closeButton) closeButton.focus();
  }

  function close() {
    if (!modal) return;
    modal.classList.remove('open');
    modal.setAttribute('aria-hidden', 'true');
    document.body.classList.remove('rules-open');
  }

  function init() {
    ensureModal();
    Array.prototype.slice.call(document.querySelectorAll('[data-game-rules]')).forEach(function (button) {
      button.onclick = function () { open(); };
    });
  }

  function announce(key, occurrence) {
    if (!rules[key] || !occurrence) return;
    var storageKey = 'ace-dice-rule-stage';
    try {
      if (sessionStorage.getItem(storageKey) === occurrence) return;
      sessionStorage.setItem(storageKey, occurrence);
    } catch (ignore) { }
    open(key);
  }

  /* 只读访问器：阶段侧栏等组件复用规则文案与下一阶段预览，不暴露内部结构 */
  function rule(key) { return rules[key] || null; }
  function next(key) {
    var index = order.indexOf(key);
    return index >= 0 && index < order.length - 1 ? rules[order[index + 1]] : null;
  }

  window.GameRules = { init: init, open: open, announce: announce, rule: rule, next: next };
})();
