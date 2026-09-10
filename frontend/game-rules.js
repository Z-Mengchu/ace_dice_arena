import { icon } from './icons.js';

(function () {
  'use strict';

  var rules = {
    CAPTAIN_VOTE: {
      step: '第一阶段', title: '队长投票', icon: 'vote', summary: '开战前队内投票 pick 咱们的总指挥！全员每人 1 票，20 秒内完成。分队、排兵布阵全靠靠谱队长带队~',
      items: ['队内所有人都可以参选，投给你信任的小伙伴！', '一人只有 1 票，系统托管的队友不能参与投票。', '票数打平 / 没人投票，直接取名单排序第一位当队长。', '20 秒没投票直接弃权，系统按现有票数统计。']
    },
    SQUAD_FORM: {
      step: '第二阶段', title: '队长分组', icon: 'squad', summary: '队长把 30 人划分 6 支小队，每队 5 人！同时定好 1-6 出场顺位，限时 90 秒。分组搭配很关键，田忌赛马的第一步！',
      items: ['仅队长拥有操作权限，拖拽、点选都可以调整队员。', '6 个小队各 5 人，30 位小伙伴全部分配，不重复不漏人。', '当日分组持续生效；每一轮的掷骰、盲盒、重掷、出场顺序会重新计算。', '超时或者队长缺席，系统自动随机平均分成 6 个小队。']
    },
    ROLL: {
      step: '第三阶段', title: '全员掷骰', icon: 'dice', summary: '统一倒计时集体掷骰子拿到个人基础点数！同一小队 5 人 0.5 秒内全部掷完，直接解锁 ×1.5 默契暴击！手快有手慢无！',
      items: ['小队间隔 1 秒错峰开启掷骰，截止时间全队统一。', '每人掷 1 颗骰子，点数系统随机生成，不能自选。', '同小队 5 人掷骰时间差 ≤0.5s，解锁全队 ×1.5 默契暴击！', '超时没手动掷骰，系统代掷；只要有代掷，小队无法触发暴击。']
    },
    BLIND_BOX: {
      step: '第四阶段', title: '开盲盒抽 buff', icon: 'gift', summary: '掷完骰子拆盲盒！三选一，有加分惊喜也有减益 debuff，直接刷新你的个人点数，限时 25 秒纯拼手气！',
      items: ['3 个盲盒选 1 个开启，开完还可以偷看剩下两个盒子内容。', '点数有加分也有减益，开盒前完全保密，全看运气。', '大概率能拿到加分，但超级大奖极为稀有！锦鲤在哪里！', '25 秒不选择直接放弃，计 0 分，系统不会帮你开盲盒。']
    },
    TACTICS: {
      step: '第五阶段', title: '战术窗口｜重掷 + 排阵', icon: 'brain', summary: '90 秒战术博弈时间！队伍 GMV 兑换重掷次数，队长指定队员重新摇骰子；排好 6 支小队出场顺序。用好机会极限翻盘！',
      items: ['每 10 万 GMV 兑换 1 次重掷，单场最多 5 次，由队长指定重掷队员。', '重掷只刷新骰子点数，掷骰时间、盲盒加成保留，暴击不受干扰。', '出场顺序锁定不可修改！强弱小队怎么对位，考验队长田忌赛马的智慧。', '队长点确认代表本队就绪，需要修改可以先取消确认；全部队伍确认立刻开打。', '超时没有操作，剩余重掷次数直接作废，出场顺序默认小队编号锁定。']
    },
    BATTLE: {
      step: '第六阶段', title: '六局对战', icon: 'swords', summary: '开打前秘密猜对手出场阵容，猜中直接涨战力！双方小队按顺序开启 6 场 5v5 PK，小队战力更高拿下单局胜利！',
      items: ['本局出战的 5 位队员，30 秒内秘密提交猜阵，互相看不到对方猜测。', '还没轮到你的局可以提前猜，对局自动生效；提交后支持撤回和改选。', '双方 5 份猜阵全部交齐，最快 5 秒揭晓；倒计时结束强制揭晓，不等晚到的人。', '猜中 1 位对手，小队战力 +0.4，单局最多 +10 分上限。', '小队战力 = 5 人点数总和（暴击则 ×1.5）+ 猜阵加成；战力高赢下本局，战力相同则本局双方不得分。', '单局结果展示 10 秒自动进入下一局，一口气打完 6 场对决。']
    },
    RESULT: {
      step: '第七阶段', title: '比赛结算', icon: 'trophy', summary: '6 局结束算总账！赢局更多直接取胜；3:3 打平就比拼全队总点数。完整战报查看每一局细节，输赢原因一目了然。',
      items: ['优先对比 6 局小局胜场数，胜场更高队伍获胜。', '3-3 大平局，依次对比全队 30 人总点数、队伍 GMV。', '三项全部持平，管理员安排加赛，直到分出胜负。', '战报完整记录战力、暴击、盲盒、重掷、猜阵全部明细，复盘超方便。', '胜者晋级下一轮，一路厮杀决出当日总冠军！']
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

  function allRulesHtml() {
    return '<p class="rules-step">ACE DICE ARENA · 裁判手册</p><h2 id="rules-title">游戏规则</h2><p class="rules-summary">比赛一共七个阶段，从选队长一路打到出结果，跟着流程走就行。</p><div class="rules-index">' + order.map(function (key, index) {
      var rule = rules[key];
      return '<article><i>' + String(index + 1).padStart(2, '0') + '</i><div><b>' + icon(rule.icon) + ' ' + rule.step + '｜' + rule.title + '</b><span>' + rule.summary + '</span><ul>' + rule.items.map(function (item) { return '<li>' + item + '</li>'; }).join('') + '</ul></div></article>';
    }).join('') + '</div>';
  }

  function open() {
    var shell = ensureModal();
    document.getElementById('rules-content').innerHTML = allRulesHtml();
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

  /* 只读访问器：阶段侧栏等组件复用规则文案与下一阶段预览，不暴露内部结构 */
  function rule(key) { return rules[key] || null; }
  function next(key) {
    var index = order.indexOf(key);
    return index >= 0 && index < order.length - 1 ? rules[order[index + 1]] : null;
  }

  window.GameRules = { init: init, open: open, rule: rule, next: next };
})();
