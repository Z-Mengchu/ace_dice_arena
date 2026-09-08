/**
 * 3D 立方体骰子投骰动画（GSAP 时间轴）：/player 掷骰端与大厅原地掷骰共用。
 * 动画只是本地表演，点数以 /api/roll 响应为准；GSAP 经 window.gsap 由页面入口注入。
 */
(function () {
  'use strict';

  /** 平面骰子（与 player 端结果卡同一套 pips 样式）：value 为 0 时显示 "?" */
  function dieHTML(cls, value) {
    var pips = '';
    for (var i = 0; i < 9; i++) pips += '<i></i>';
    return '<span class="die ' + (cls || '') + '" data-v="' + (value || 0) + '">' + pips + '<span class="die-q">?</span></span>';
  }

  /** 六面立方体骰子场景：面复用 .die 的 pips 样式，对面点数和为 7 */
  function sceneHTML() {
    var faces = '';
    for (var v = 1; v <= 6; v++) faces += dieHTML('', v);
    return '<div class="dice3d-scene"><div class="dice3d-hop"><div class="dice3d dice3d-live">' + faces + '</div></div>' +
      '<div class="dice3d-shadow"></div></div>' +
      '<div class="pl-sub dice3d-tip">掷骰中…</div>';
  }

  /** 让指定点数的面正对观众时立方体需要的 rotationX/rotationY（整圈数由调用方叠加） */
  var FACE_TARGET = {
    1: { x: 0, y: 0 }, 6: { x: 0, y: 180 },
    3: { x: 0, y: -90 }, 4: { x: 0, y: 90 },
    2: { x: -90, y: 0 }, 5: { x: 90, y: 0 }
  };

  /**
   * 起滚：container 原位替换为翻滚场景，匀速翻滚等待服务端点数。
   * settle(value, cb)：响应到达后减速多圈定格到 value 面，落地弹跳 + 金色定格约 0.5s 后回调；
   * abort()：出错时杀掉所有补间，由调用方重绘错误视图。
   */
  function startTumble(container) {
    container.innerHTML = sceneHTML();
    var scene = container.querySelector('.dice3d-scene');
    var hop = container.querySelector('.dice3d-hop');
    var cube = container.querySelector('.dice3d-live');
    var tip = container.querySelector('.dice3d-tip');
    var g = window.gsap;
    var startAt = Date.now();
    var spinning = true;
    var tumble = null;
    (function spin() {
      if (!spinning) return;
      tumble = g.to(cube, { rotationX: '+=360', rotationY: '+=240', duration: 0.6, ease: 'none', onComplete: spin });
    })();
    return {
      settle: function (value, cb) {
        var target = FACE_TARGET[value] || FACE_TARGET[1];
        // 响应太快也至少滚 0.75s，否则像没掷
        var wait = Math.max(0, 750 - (Date.now() - startAt)) / 1000;
        g.delayedCall(wait, function () {
          spinning = false;
          if (tumble) tumble.kill();
          var curX = Number(g.getProperty(cube, 'rotationX')) || 0;
          var curY = Number(g.getProperty(cube, 'rotationY')) || 0;
          var endX = (Math.floor(curX / 360) + 2) * 360 + target.x;
          var endY = (Math.floor(curY / 360) + 2) * 360 + target.y;
          g.timeline({ onComplete: cb })
            .to(cube, { rotationX: endX, rotationY: endY, duration: 1.05, ease: 'power3.out' }, 0)
            .fromTo(hop, { y: -44 }, { y: 0, duration: 0.55, ease: 'bounce.out' }, 0.5)
            .add(function () {
              scene.classList.add('landed');
              tip.textContent = '点数 ' + value;
            }, 1.05)
            .to({}, { duration: 0.5 });   // 定格停留，总时长约 2s
        });
      },
      abort: function () {
        spinning = false;
        if (tumble) tumble.kill();
        g.killTweensOf(cube);
        g.killTweensOf(hop);
      }
    };
  }

  window.DiceRollUI = { dieHTML: dieHTML, sceneHTML: sceneHTML, startTumble: startTumble };
})();
