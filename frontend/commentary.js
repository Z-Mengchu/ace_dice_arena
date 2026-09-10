/**
 * 赛况解说（commentary）—— 大厅 / 玩家页共用的顶部即时点评横幅
 * 纯原生 JS，挂 window.Commentary 全局（与 stage-panel.js 同一模式）。
 * Commentary.show(text, tone)：tone ∈ 'good' | 'bad'；多条调用排队逐条播放（前一条播完再播下一条）。
 * 点评只是添头：内部全程 try/catch，任何异常都不影响主流程。
 */
import { icon } from './icons.js';

(function () {
  'use strict';

  var HOLD_MS = 3500;   // 入场落定后的停留时长
  var queue = [];
  var playing = false;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function pump() {
    if (playing || !queue.length) return;
    playing = true;
    var item = queue.shift();
    var wrap = null;
    var finished = false;
    function done() {
      if (finished) return;
      finished = true;
      if (wrap && wrap.parentNode) wrap.parentNode.removeChild(wrap);
      playing = false;
      pump();
    }
    try {
      wrap = document.createElement('div');
      wrap.className = 'cmt-wrap';
      wrap.innerHTML = '<div class="cmt-banner cmt-' + item.tone + '">' +
        icon(item.tone === 'good' ? 'sparkle' : 'warn', 24) +
        '<span>' + esc(item.text) + '</span></div>';
      document.body.appendChild(wrap);
      var banner = wrap.firstChild;
      if (window.gsap) {
        var tl = window.gsap.timeline({ onComplete: done });
        tl.fromTo(banner,
          { scale: 0.6, y: -36, opacity: 0 },
          { scale: 1, y: 0, opacity: 1, duration: 0.55, ease: 'back.out(1.7)' })
          .to(banner, { opacity: 0, y: -24, duration: 0.35, ease: 'power2.in' }, '+=' + (HOLD_MS / 1000));
      } else {
        // GSAP 缺失：静态展示后移除，点评不阻塞主流程
        setTimeout(done, HOLD_MS + 600);
      }
    } catch (e) {
      done();
    }
  }

  function show(text, tone) {
    try {
      if (!text || (tone !== 'good' && tone !== 'bad')) return;
      queue.push({ text: String(text), tone: tone });
      pump();
    } catch (e) { }
  }

  window.Commentary = { show: show };
})();
