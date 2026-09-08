import '../styles.css';
import '../game-rules.js';
import '../stage-panel.js';
import '../tournament-ui.js';
// 开盲盒/投骰动画库与共享 UI：打包进 lobby chunk（大厅原地开盒、原地掷骰入口使用）
import { gsap } from 'gsap';
window.gsap = gsap;
import '../blind-box.js';
import '../dice-roll.js';
import '../lobby.js';
