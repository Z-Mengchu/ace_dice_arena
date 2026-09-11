/**
 * Lucide 内联 SVG 图标辅助模块。
 * 用法：import { icon } from './icons.js';
 *       html += icon('dices');            // 标题行 20px
 *       html += icon('timer', 16);        // 正文行内 16px
 * 图标颜色跟随 currentColor。
 */
import Vote from 'lucide/dist/esm/icons/vote.mjs';
import Crown from 'lucide/dist/esm/icons/crown.mjs';
import Users from 'lucide/dist/esm/icons/users.mjs';
import Dices from 'lucide/dist/esm/icons/dices.mjs';
import Gift from 'lucide/dist/esm/icons/gift.mjs';
import Brain from 'lucide/dist/esm/icons/brain.mjs';
import Lightbulb from 'lucide/dist/esm/icons/lightbulb.mjs';
import Swords from 'lucide/dist/esm/icons/swords.mjs';
import Trophy from 'lucide/dist/esm/icons/trophy.mjs';
import Timer from 'lucide/dist/esm/icons/timer.mjs';
import Hourglass from 'lucide/dist/esm/icons/hourglass.mjs';
import Eye from 'lucide/dist/esm/icons/eye.mjs';
import Sparkles from 'lucide/dist/esm/icons/sparkles.mjs';
import CircleCheck from 'lucide/dist/esm/icons/circle-check.mjs';
import TriangleAlert from 'lucide/dist/esm/icons/triangle-alert.mjs';
import Lock from 'lucide/dist/esm/icons/lock.mjs';
import MessageCircle from 'lucide/dist/esm/icons/message-circle.mjs';
import LoaderCircle from 'lucide/dist/esm/icons/loader-circle.mjs';
import ScanSearch from 'lucide/dist/esm/icons/scan-search.mjs';
import ChevronUp from 'lucide/dist/esm/icons/chevron-up.mjs';
import ChevronDown from 'lucide/dist/esm/icons/chevron-down.mjs';

var ICONS = {
  vote: Vote,
  crown: Crown,
  squad: Users,
  dice: Dices,
  gift: Gift,
  brain: Brain,
  idea: Lightbulb,
  swords: Swords,
  trophy: Trophy,
  timer: Timer,
  hourglass: Hourglass,
  eye: Eye,
  sparkle: Sparkles,
  check: CircleCheck,
  warn: TriangleAlert,
  lock: Lock,
  chat: MessageCircle,
  loading: LoaderCircle,
  crystal: ScanSearch,
  up: ChevronUp,
  down: ChevronDown
};

function attrsToString(attrs) {
  var out = '';
  Object.keys(attrs).forEach(function (key) {
    out += ' ' + key + '="' + String(attrs[key]).replace(/"/g, '&quot;') + '"';
  });
  return out;
}

/**
 * 渲染内联 SVG 字符串。name 见 ICONS 表；size 默认 20（标题行），正文行内传 16。
 * 未知名称返回空字符串，保证渲染不中断。
 */
export function icon(name, size) {
  var nodes = ICONS[name];
  if (!nodes) return '';
  var s = size || 20;
  var body = nodes.map(function (node) {
    return '<' + node[0] + attrsToString(node[1]) + '/>';
  }).join('');
  return '<svg class="icon icon-' + name + '" xmlns="http://www.w3.org/2000/svg" width="' + s + '" height="' + s +
    '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    body + '</svg>';
}
