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
   * opts.koi 为 true 时，在选中点数弹出瞬间叠加全屏锦鲤彩蛋（大厅开出 +5 时由调用方传入）。
   */
  function playBoxReveal(boxEls, picked, values, done, opts) {
    var tierOf = function (v) { return (v > 0 ? '+' : '') + v; };
    var koi = !!(opts && opts.koi);
    var finish = function () {
      boxEls.forEach(function (b, i) {
        b.classList.add('opened');
        b.classList.toggle('picked', i === picked);
        var valueEl = b.querySelector('.bb-value');
        valueEl.textContent = tierOf(values[i]);
        valueEl.className = 'bb-value show ' + (values[i] >= 0 ? 'pos' : 'neg');
        b.querySelector('.bb-tag').textContent = i === picked ? '你的选择' : '';
      });
      if (koi) playKoiEffect({ text: '锦鲤附体' });
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
        // 彩蛋时刻：选中点数弹出瞬间触发锦鲤，与点数缩放动画同步
        if (koi) playKoiEffect({ text: '锦鲤附体' });
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

  /**
   * 彩蛋：全屏锦鲤特效（约 3.1s），鲤鱼游入摆尾 + 水波 + 金字标题 + 金色粒子。
   * 覆盖层挂在 body 上、不拦截点击，播完自动移除；低动效偏好下直接不播。
   */
  function playKoiEffect(options) {
    options = options || {};
    var text = options.text || '锦鲤附体';
    if (reducedMotion()) return;
    var old = document.querySelector('.bb-koi-fx');
    if (old) old.parentNode.removeChild(old);
    var fx = document.createElement('div');
    fx.className = 'bb-koi-fx';
    fx.setAttribute('aria-hidden', 'true');
    fx.innerHTML =
      '<div class="bb-koi-glow"></div>' +
      '<div class="bb-koi-wave bb-koi-wave-1"></div>' +
      '<div class="bb-koi-wave bb-koi-wave-2"></div>' +
      '<div class="bb-koi-wrap"><div class="bb-koi">' +
        '<span class="bb-koi-tail"></span>' +
        '<span class="bb-koi-body">' +
          '<i class="bb-koi-spot bb-koi-spot-1"></i>' +
          '<i class="bb-koi-spot bb-koi-spot-2"></i>' +
          '<i class="bb-koi-eye"></i><i class="bb-koi-fin"></i>' +
        '</span>' +
      '</div></div>' +
      '<div class="bb-koi-title"><span class="bb-koi-title-main">' + text + '</span><span class="bb-koi-title-sub">好运正在发生</span></div>' +
      '<div class="bb-koi-particles"></div>';
    document.body.appendChild(fx);
    var particles = fx.querySelector('.bb-koi-particles');
    for (var i = 0; i < 30; i++) {
      var p = document.createElement('i');
      p.className = 'bb-koi-particle';
      particles.appendChild(p);
      var size = 4 + Math.random() * 9;
      p.style.width = size + 'px';
      p.style.height = size + 'px';
      p.style.left = (5 + Math.random() * 90) + '%';
      p.style.top = (15 + Math.random() * 75) + '%';
    }
    if (!window.gsap) {
      // 无 GSAP：静态展示片刻后移除
      setTimeout(function () { if (fx.parentNode) fx.parentNode.removeChild(fx); }, 2600);
      return;
    }
    var gsap = window.gsap;
    var koiWrap = fx.querySelector('.bb-koi-wrap');
    var koi = fx.querySelector('.bb-koi');
    var tail = fx.querySelector('.bb-koi-tail');
    var title = fx.querySelector('.bb-koi-title');
    var glow = fx.querySelector('.bb-koi-glow');
    var waves = fx.querySelectorAll('.bb-koi-wave');
    var particleEls = fx.querySelectorAll('.bb-koi-particle');
    var tailTween = gsap.to(tail, { rotation: 26, duration: 0.18, yoyo: true, repeat: -1, ease: 'sine.inOut', transformOrigin: '100% 50%' });
    var bodyTween = gsap.to(koi, { y: -12, rotation: -3, duration: 0.55, yoyo: true, repeat: -1, ease: 'sine.inOut' });
    var tl = gsap.timeline({ onComplete: function () { tailTween.kill(); bodyTween.kill(); fx.remove(); } });
    tl.fromTo(fx, { opacity: 0 }, { opacity: 1, duration: 0.18, ease: 'power1.out' }, 0)
      .fromTo(glow, { opacity: 0, scale: 0.3 }, { opacity: 0.9, scale: 1.35, duration: 0.8, ease: 'power2.out' }, 0)
      .fromTo(waves, { opacity: 0, scale: 0.3 }, { opacity: 0.55, scale: 2.2, duration: 1.6, stagger: 0.18, ease: 'power1.out' }, 0.1)
      .fromTo(koiWrap, { x: '-75vw', y: '28vh', rotation: -12, scale: 0.65 }, { x: '0vw', y: 0, rotation: 7, scale: 1.15, duration: 1.25, ease: 'power2.out' }, 0.12)
      .fromTo(title, { opacity: 0, scale: 0.3, y: 30 }, { opacity: 1, scale: 1, y: 0, duration: 0.55, ease: 'back.out(2.5)' }, 0.75);
    particleEls.forEach(function (p) {
      gsap.fromTo(p, { opacity: 0, scale: 0, x: 0, y: 30 }, {
        opacity: 1, scale: 1, x: (Math.random() - 0.5) * 140, y: -50 - Math.random() * 120,
        rotation: Math.random() * 360, duration: 0.8 + Math.random() * 1.2, delay: 0.35 + Math.random() * 0.65, ease: 'power2.out'
      });
    });
    tl.to(koiWrap, { x: '72vw', y: '-30vh', scale: 0.78, rotation: -14, duration: 1.25, ease: 'power2.in' }, 1.65)
      .to(title, { opacity: 0, y: -18, scale: 1.08, duration: 0.45, ease: 'power2.in' }, 2.2)
      .to(fx, { opacity: 0, duration: 0.45, ease: 'power1.in' }, 2.65);
  }

  window.BlindBoxUI = { boxesHTML: boxesHTML, reducedMotion: reducedMotion, playBoxReveal: playBoxReveal, playKoiEffect: playKoiEffect };
})();
