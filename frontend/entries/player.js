import '../styles.css';
import '../engine.js';
import '../game-rules.js';
import '../stage-panel.js';
import '../tournament-ui.js';
// 开盲盒/投骰动画库与共享 UI：打包进 player chunk（gz 约 25KB 静态资源）
import { gsap } from 'gsap';
window.gsap = gsap;
import '../blind-box.js';
import '../dice-roll.js';
import '../player.js';
