/**
 * 三选一盲盒：礼盒 HTML 与开盒动画（GSAP 时间轴），/player 与 /lobby 两个开盒入口共用。
 * 动画只是本地表演，开盒进度以 API 响应为准；GSAP 经 window.gsap 由页面入口注入。
 */
(function () {
  'use strict';

  /** 3 个待选礼盒的 HTML（CSS 绘制：盒盖 + 盒身 + 缎带） */
  function boxesHTML() {
    return '<div class="bb-row">' + [0, 1, 2].map(function (i) {
      return '<button type="button" class="bb-box" data-idx="' + i + '" aria-label="盲盒 ' + (i + 1) + '">' +
        '<span class="bb-gift"><span class="bb-lid"></span><span class="bb-base"></span></span>' +
        '<span class="bb-value"></span><span class="bb-tag"></span></button>';
    }).join('') + '</div>';
  }

  /** 设备偏好低动效：调用方跳过动画直接出结果 */
  function reducedMotion() {
    try { return window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) { return false; }
  }

  /**
   * 开盒动画（约 2.8s）：选中盒抖动、其余变暗 → 翻盖爆光、点数弹出
   * → 三盒全翻开对比（选中高亮"你的选择"）。完成后回调 done。
   */
  function playBoxReveal(boxEls, picked, values, done) {
    var tierOf = function (v) { return (v > 0 ? '+' : '') + v; };
    var finish = function () {
      boxEls.forEach(function (b, i) {
        b.classList.add('opened');
        b.classList.toggle('picked', i === picked);
        var valueEl = b.querySelector('.bb-value');
        valueEl.textContent = tierOf(values[i]);
        valueEl.className = 'bb-value show ' + (values[i] >= 0 ? 'pos' : 'neg');
        b.querySelector('.bb-tag').textContent = i === picked ? '你的选择' : '';
      });
      done();
    };
    if (!window.gsap) { finish(); return; }
    var pickedEl = boxEls[picked];
    var others = boxEls.filter(function (_, i) { return i !== picked; });
    var tl = window.gsap.timeline({ onComplete: done });
    tl.to(others, { opacity: 0.3, scale: 0.9, duration: 0.4, ease: 'power2.out' }, 0)
      .to(pickedEl.querySelector('.bb-gift'), {
        keyframes: [{ rotation: -8 }, { rotation: 8 }, { rotation: -6 }, { rotation: 6 }, { rotation: 0 }],
        duration: 0.7, ease: 'power1.inOut', transformOrigin: '50% 90%'
      }, 0)
      .to(pickedEl.querySelector('.bb-lid'), { y: -56, rotation: -24, opacity: 0, duration: 0.45, ease: 'power2.out' }, 0.75)
      .to(pickedEl.querySelector('.bb-base'), { scale: 1.12, duration: 0.18, yoyo: true, repeat: 1, ease: 'power2.out' }, 0.75)
      .add(function () {
        pickedEl.classList.add('opened', 'picked');
        var valueEl = pickedEl.querySelector('.bb-value');
        valueEl.textContent = tierOf(values[picked]);
        valueEl.className = 'bb-value show ' + (values[picked] >= 0 ? 'pos' : 'neg');
        pickedEl.querySelector('.bb-tag').textContent = '你的选择';
      }, 0.95)
      .fromTo(pickedEl.querySelector('.bb-value'), { scale: 0 }, { scale: 1, duration: 0.5, ease: 'back.out(2.2)' }, 0.95)
      // 陪跑值揭晓：另外两盒翻盖展示内容
      .to(others.map(function (b) { return b.querySelector('.bb-lid'); }),
        { y: -30, rotation: -14, opacity: 0, duration: 0.4, stagger: 0.12, ease: 'power2.out' }, 1.6)
      .add(function () {
        others.forEach(function (b) {
          var i = boxEls.indexOf(b);
          b.classList.add('opened');
          var valueEl = b.querySelector('.bb-value');
          valueEl.textContent = tierOf(values[i]);
          valueEl.className = 'bb-value show ' + (values[i] >= 0 ? 'pos' : 'neg');
        });
      }, 1.8)
      .fromTo(others.map(function (b) { return b.querySelector('.bb-value'); }),
        { scale: 0 }, { scale: 1, duration: 0.4, stagger: 0.12, ease: 'back.out(1.8)' }, 1.8)
      .to(others, { opacity: 0.55, scale: 0.95, duration: 0.4, ease: 'power2.out' }, 2.3)
      .to({}, { duration: 0.25 });   // 收尾停留，总时长约 2.8s
  }

  window.BlindBoxUI = { boxesHTML: boxesHTML, reducedMotion: reducedMotion, playBoxReveal: playBoxReveal };
})();
