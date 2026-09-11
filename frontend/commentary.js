/**
 * 赛况解说（commentary）—— 大厅 / 玩家页共用的弹幕气泡（右下角，聊天气球上方）
 * 纯原生 JS，挂 window.Commentary 全局（与 stage-panel.js 同一模式）。
 * Commentary.show(text, tone)：个人即时点评，tone ∈ 'good' | 'bad'。
 * Commentary.event({ kind, name, title, text })：场景事件气泡，kind 决定专属样式与图标：
 *   roll-big / roll-small / box-buff / box-debuff / reroll-up / reroll-down / guess-many / guess-few
 * 队内频道展开时播报已在聊天列表里，气泡不再弹出；频道收起（或页面无频道）时气泡在小球上方弹出。
 * 气泡只是添头：内部全程 try/catch，任何异常都不影响主流程；容器 pointer-events:none，不遮挡居中卡片。
 */
import { icon } from './icons.js';

(function () {
  'use strict';

  var MAX_ITEMS = 24;          // 侧边流最多保留条数，超出移除最旧
  var HOLD_MS = 12000;         // 单条停留时长，到点自动淡出

  var KIND_META = {
    'roll-big':    { icon: 'dice',    title: '掷出大点数' },
    'roll-small':  { icon: 'dice',    title: '掷出小点数' },
    'box-buff':    { icon: 'gift',    title: '盲盒正向 buff' },
    'box-debuff':  { icon: 'gift',    title: '盲盒负面 debuff' },
    'reroll-up':   { icon: 'sparkle', title: '重掷点数上涨' },
    'reroll-down': { icon: 'warn',    title: '重掷点数下跌' },
    'guess-many':  { icon: 'crystal', title: '猜阵命中多人' },
    'guess-few':   { icon: 'crystal', title: '猜阵命中很少' },
    'good':        { icon: 'sparkle', title: '' },
    'bad':         { icon: 'warn',    title: '' }
  };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function feedEl() {
    var el = document.getElementById('cmt-feed');
    if (!el) {
      el = document.createElement('div');
      el.id = 'cmt-feed';
      document.body.appendChild(el);
    }
    return el;
  }

  function trim(feed) {
    while (feed.children.length > MAX_ITEMS) feed.removeChild(feed.firstChild);
  }

  /**
   * 队内频道展开时播报已经进了聊天列表，不再弹气泡；
   * 聊天框收起成小球（或本页没有频道，如掷骰页）时，气泡在小球上方（右下）弹出。
   */
  function chatExpanded() {
    var room = document.getElementById('team-chat');
    return !!room && !room.classList.contains('hidden') && !room.classList.contains('chat-collapsed');
  }

  function append(html) {
    if (chatExpanded()) return;
    var feed = feedEl();
    var item = document.createElement('div');
    item.innerHTML = html;
    var bubble = item.firstChild;
    feed.appendChild(bubble);
    trim(feed);
    setTimeout(function () {
      bubble.classList.add('is-leaving');
      setTimeout(function () { if (bubble.parentNode) bubble.parentNode.removeChild(bubble); }, 420);
    }, HOLD_MS);
  }

  /** 场景事件气泡：名字 · 事件标题 ＋ 吐槽文案，kind 专属配色与图标 */
  function event(payload) {
    try {
      if (!payload || !payload.text) return;
      var kind = KIND_META[payload.kind] ? payload.kind : (payload.kind === 'bad' ? 'bad' : 'good');
      var meta = KIND_META[kind];
      append('<div class="cmt-bubble cmt-' + esc(kind) + '">' +
        '<i class="cmt-ico">' + icon(meta.icon, 18) + '</i>' +
        '<div class="cmt-body">' +
        '<div class="cmt-head">' + (payload.name ? '<b>' + esc(payload.name) + '</b>' : '') +
        (meta.title ? '<span>' + esc(meta.title) + '</span>' : '') + '</div>' +
        '<p>' + esc(payload.text) + '</p>' +
        '</div></div>');
    } catch (e) { }
  }

  /** 个人点评：并入侧边流，沿用 good/bad 两档语气 */
  function show(text, tone) {
    try {
      if (!text || (tone !== 'good' && tone !== 'bad')) return;
      event({ kind: tone, text: text });
    } catch (e) { }
  }

  window.Commentary = { show: show, event: event };
})();
