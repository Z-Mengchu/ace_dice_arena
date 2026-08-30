(function () {
  'use strict';

  var rules = {
    ACCUMULATION: {
      step: '第二阶段', title: '积累期', summary: '三名核心角色产生后，把赛前 GMV 兑换出的骰子全部投完，再进入攻擂战。',
      items: ['每 10 万元 GMV 兑换 1 次投骰机会，开局一次性发放。', '队内任意玩家都可逐次投骰；每次请求只会成功消耗一个配额。', '本队所有配额必须全部投完，点数之和作为积累期攻击力。']
    },
    ROLE_CAPTAIN: {
      step: '第一阶段 1 / 3', title: '投票选出队长', summary: '开赛后先由全队独立投票，本阶段限时 20 秒。',
      items: ['队长只能从前端队员中产生。', '每名真实玩家只能投一票；托管队友不参与投票。', '20 秒内未提交视为弃票，系统不会代替玩家补票。']
    },
    ROLE_STRATEGIST: {
      step: '第一阶段 2 / 3', title: '投票选出军师', summary: '队长产生后，全队单独进行军师投票，限时 20 秒。',
      items: ['军师可从尚未担任核心角色的队员中产生。', '军师负责在每局开始时预言对方的出战阵容。', '预言在本局结果阶段揭晓。']
    },
    ROLE_PITCHER: {
      step: '第一阶段 3 / 3', title: '投票选出王牌投手', summary: '最后单独选出王牌投手，本阶段限时 20 秒。',
      items: ['王牌投手只能从后端队员中产生。', '王牌投手与其他入选队员一样，需要先进入备战席并准备。', '五名队员完成同步点击后，由王牌投手投出最终五枚骰子。']
    },
    PROPHET: {
      step: '每局阶段 1', title: '军师预言', summary: '双方军师秘密预言对方本局可能出战的五名队员。',
      items: ['只有本队军师能够提交预言，也可以选择本局放弃。', '预言限时 30 秒，倒计时结束仍未提交则自动视为放弃。', '提交后内容密封；结果阶段命中预言时，按游戏设定结算攻击加成。']
    },
    LINEUP: {
      step: '每局阶段 2', title: '队长选择出战阵容', summary: '本阶段不是全队投票，只由队长一人决定。',
      items: ['队长从本队选择五名本局出战队员。', '阵容中必须至少包含一名后端队员。', '双方队长都提交后，页面短暂展示阵容，入选队员随后自动进入备战席。']
    },
    CONFIRM: {
      step: '每局阶段 3', title: '五人备战与队长发令', summary: '出战五人自动进入备战席，主动准备后等待队长统一发令。',
      items: ['每名出战队员都要点击“准备”，全队可以看到五人的准备状态。', '五人全部准备后，队长点击“发号施令”，系统开始 3 秒倒计时。', '两支队伍独立备战、同时进攻，不需要等待对方。']
    },
    TIMING: {
      step: '每局阶段 4', title: '五人同步点击', summary: '五名出战队员各点击一次，此时只计算操作时间误差，不产生骰子。',
      items: ['3 秒倒计时结束后，五名出战队员各点击一次进攻。', '五次点击的最大时间误差不超过 0.5 秒，本局攻擂攻击力 ×1.5。', '未达到 0.5 秒使用原始攻击力，没有额外惩罚。']
    },
    PITCHER_ROLL: {
      step: '每局阶段 5', title: '王牌投手最终投骰', summary: '同步结果确定后，由王牌投手完成本局真正的五枚骰子投掷。',
      items: ['只有本队当选的王牌投手可以操作。', '系统一次生成五枚最终骰子，并叠加上一阶段得到的同步倍率。', '最终攻击力会与积累期攻击力、队伍增长率系数共同结算。']
    },
    RESULT: {
      step: '每局阶段 6', title: '本局结果', summary: '系统展示双方骰子、预言结果和最终攻击力，并直接判定本场胜负。',
      items: ['攻擂开始后的 20 秒内，除队长、出战队员、王牌投手和托管队员外，在线队员可各领取一次 ×1.00～×1.50 攻击力盲盒；本队已领取结果取平均值。', '最终攻击力 =（积累期攻击力 + 攻擂攻击力）× 增长率系数 × 盲盒平均倍率。', '每两支队伍只进行这一轮攻擂，最终攻击力较高的一方获胜；相同时依次比较增长率系数和本轮五枚骰子。']
    }
  };

  var order = ['ROLE_CAPTAIN', 'ROLE_STRATEGIST', 'ROLE_PITCHER', 'ACCUMULATION', 'PROPHET', 'LINEUP', 'CONFIRM', 'TIMING', 'PITCHER_ROLL', 'RESULT'];
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
    return '<p class="rules-step">ACE DICE ARENA · 裁判手册</p><h2 id="rules-title">游戏规则</h2><p class="rules-summary">从核心角色投票到本局结算，比赛由系统按以下固定顺序推进。</p><div class="rules-index">' + order.map(function (key, index) {
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

  window.GameRules = { init: init, open: open, announce: announce };
})();
