/**
 * 阶段侧栏（stage-panel）—— lobby / player 共用的右侧浮动面板
 * 纯原生 JS，挂 window.StagePanel 全局（与 game-rules.js 同一模式）。
 * 展示：当前阶段名（大标题）+ 大号倒计时 + 一行动态状态 + 一行规则 + 下一阶段预览；
 * 可折叠为窄条（阶段名 + 秒数），折叠状态存 sessionStorage，刷新后保持。
 */
(function () {
  'use strict';

  var COLLAPSE_KEY = 'ace-dice-stage-panel-collapsed';

  var panel = null;
  var collapsed = false;
  var current = { stage: '', stageLabel: '', deadline: null, serverOffset: 0, extraLine: '' };

  try { collapsed = sessionStorage.getItem(COLLAPSE_KEY) === '1'; } catch (e) { }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function ensurePanel() {
    if (panel) return panel;
    panel = document.createElement('aside');
    panel.id = 'stage-panel';
    panel.className = 'stage-panel hidden';
    document.body.appendChild(panel);
    return panel;
  }

  function ruleOf(stage) {
    return window.GameRules && window.GameRules.rule ? window.GameRules.rule(stage) : null;
  }
  function nextOf(stage) {
    return window.GameRules && window.GameRules.next ? window.GameRules.next(stage) : null;
  }

  /** 倒计时以服务端时间轴为准：deadline - (本地时刻 + 校准偏移) */
  function secondsLeft() {
    if (current.deadline == null || !isFinite(Number(current.deadline))) return null;
    return Math.max(0, Math.ceil((Number(current.deadline) - (Date.now() + (current.serverOffset || 0))) / 1000));
  }

  function render() {
    var box = ensurePanel();
    if (!current.stage) { box.classList.add('hidden'); box.innerHTML = ''; return; }
    var s = secondsLeft();
    box.classList.remove('hidden');
    box.classList.toggle('is-collapsed', collapsed);
    box.classList.toggle('is-warn', s != null && s <= 30 && s > 10);
    box.classList.toggle('is-urgent', s != null && s <= 10);
    if (collapsed) {
      box.innerHTML = '<div class="sp-strip"><b>' + esc(current.stageLabel) + '</b>' +
        (s == null ? '' : '<span>' + s + 's</span>') + '</div>' +
        '<button type="button" class="sp-toggle" aria-expanded="false" title="展开阶段面板">▸</button>';
    } else {
      var rule = ruleOf(current.stage);
      var next = nextOf(current.stage);
      box.innerHTML = '<header><div class="sp-stage">' + esc(current.stageLabel) + '</div>' +
        '<button type="button" class="sp-toggle" aria-expanded="true" title="收起阶段面板">▾</button></header>' +
        (s == null ? '' : '<div class="sp-time"><b>' + s + '</b><span>秒</span></div>') +
        (current.extraLine ? '<p class="sp-extra">' + esc(current.extraLine) + '</p>' : '') +
        (rule ? '<p class="sp-rule">' + esc(rule.summary) + '</p>' : '') +
        (next ? '<p class="sp-next">下一阶段 · ' + esc(next.title) + '</p>' : '');
    }
    var toggle = box.querySelector('.sp-toggle');
    if (toggle) toggle.onclick = function () {
      collapsed = !collapsed;
      try { sessionStorage.setItem(COLLAPSE_KEY, collapsed ? '1' : '0'); } catch (e) { }
      render();
    };
  }

  function init() {
    ensurePanel();
    render();
  }

  /**
   * update({stage, stageLabel, deadline, serverOffset, extraLine})
   * stage 为空串/缺省时隐藏面板；deadline 为 ms 时间戳，缺省则不显示秒数。
   */
  function update(state) {
    current = {
      stage: state && state.stage ? String(state.stage) : '',
      stageLabel: state && state.stageLabel ? String(state.stageLabel) : '',
      deadline: state && state.deadline != null ? Number(state.deadline) : null,
      serverOffset: state && state.serverOffset ? Number(state.serverOffset) : 0,
      extraLine: state && state.extraLine ? String(state.extraLine) : ''
    };
    render();
  }

  window.StagePanel = { init: init, update: update };
})();
