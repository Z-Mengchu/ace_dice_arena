/**
 * 「王牌攻守擂·骰子大亨」纯逻辑引擎
 * 不依赖任何 DOM / 浏览器 API，可在 node 中直接 require
 * UMD 风格：浏览器挂到 window.GameEngine，node 下 module.exports = GameEngine
 */
(function (root, factory) {
  const GameEngine = factory();
  if (typeof module === 'object' && module.exports) {
    module.exports = GameEngine; // node 环境
  } else {
    root.GameEngine = GameEngine; // 浏览器环境（root 即 window）
  }
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  /** 默认配置 */
  const DEFAULT_CONFIG = {
    gmvPerDice: 100000,     // 积累期：每多少 GMV 兑换 1 次掷骰机会
    playersPerTeam: 30,     // 每队队员数
    backendCount: 5,        // 每队后端人数（p26-p30）
    matchWinRounds: 1,      // 每两队只进行一轮攻擂，一轮直接定胜负
    syncWindowMs: 500,      // 同步点击首尾时差阈值（毫秒）
    syncMultiplier: 1.5,    // 同步加成倍率
    leopardMultiplier: 3,   // 豹子（5 骰全同）倍率
    prophetBonus: 2,        // 军师猜中对方全部 5 名出战队员的加分（乘法之后再加）
    fatigueMultiplier: 1,   // 新流程不在攻擂战中使用 GMV 疲劳惩罚
    days: 2                 // 比赛天数
  };

  /** 8 个战区 mock 数据（gmv / growth 均为 day1 / day2 两天，growth 为增长率百分数值） */
  const MOCK_TEAMS = [
    { id: 't1', name: '雷霆战区', shortName: '雷霆', gmv: { day1: 331500, day2: 338200 }, growth: { day1: 8.6, day2: 7.9 } },
    { id: 't2', name: '烈焰战区', shortName: '烈焰', gmv: { day1: 298700, day2: 305400 }, growth: { day1: 5.2, day2: 6.0 } },
    { id: 't3', name: '飓风战区', shortName: '飓风', gmv: { day1: 342300, day2: 349800 }, growth: { day1: 9.1, day2: 8.4 } },
    { id: 't4', name: '磐石战区', shortName: '磐石', gmv: { day1: 286400, day2: 291500 }, growth: { day1: 3.8, day2: 4.6 } },
    { id: 't5', name: '星驰战区', shortName: '星驰', gmv: { day1: 319900, day2: 324600 }, growth: { day1: 7.4, day2: 6.8 } },
    { id: 't6', name: '锋芒战区', shortName: '锋芒', gmv: { day1: 305600, day2: 312900 }, growth: { day1: 6.1, day2: 7.2 } },
    { id: 't7', name: '凌云战区', shortName: '凌云', gmv: { day1: 353200, day2: 358700 }, growth: { day1: 9.7, day2: 9.2 } },
    { id: 't8', name: '破晓战区', shortName: '破晓', gmv: { day1: 292800, day2: 299100 }, growth: { day1: 4.5, day2: 5.1 } }
  ];

  /**
   * 生成单支队伍的 30 名队员
   * id 格式 t{n}-p{01..30}；最后 5 人为后端(back)
   * name 形如 `雷霆-01`
   */
  function buildPlayers(team) {
    const players = [];
    const frontCount = DEFAULT_CONFIG.playersPerTeam - DEFAULT_CONFIG.backendCount; // 25
    for (let i = 1; i <= DEFAULT_CONFIG.playersPerTeam; i++) {
      const num = String(i).padStart(2, '0');
      players.push({
        id: team.id + '-p' + num,
        name: team.shortName + '-' + num,
        role: i > frontCount ? 'back' : 'front'
      });
    }
    return players;
  }

  /**
   * 基于 MOCK_TEAMS 生成完整队伍数组（含 players、队长/副队长字段）
   * 每次调用返回全新对象（深拷贝），多次调用互不影响
   * 队长 = p01，副队长 = p18
   */
  function createTeams() {
    return MOCK_TEAMS.map(function (t) {
      return {
        id: t.id,
        name: t.name,
        shortName: t.shortName,
        gmv: { day1: t.gmv.day1, day2: t.gmv.day2 },
        growth: { day1: t.growth.day1, day2: t.growth.day2 },
        captainId: t.id + '-p01',
        viceCaptainId: t.id + '-p18',
        players: buildPlayers(t)
      };
    });
  }

  /** 掷 1 个骰子，返回 1-6 整数；rng 可注入便于测试 */
  function rollDie(rng = Math.random) {
    return Math.floor(rng() * 6) + 1;
  }

  /** 掷 n 个骰子，返回长度 n 的数组（默认 5 个） */
  function rollDiceSet(n = 5, rng = Math.random) {
    const dice = [];
    for (let i = 0; i < n; i++) dice.push(rollDie(rng));
    return dice;
  }

  /** 是否豹子（全部骰子点数相同） */
  function isLeopard(dice) {
    if (!Array.isArray(dice) || dice.length === 0) return false;
    return dice.every(function (d) { return d === dice[0]; });
  }

  /**
   * 同步倍率：首尾时差 ≤ syncWindowMs → syncMultiplier，否则 1
   * spreadMs 传入 null / undefined 表示无同步数据，按 1 计
   */
  function syncMultiplier(spreadMs, config = DEFAULT_CONFIG) {
    if (spreadMs === null || spreadMs === undefined) return 1;
    return spreadMs <= config.syncWindowMs ? config.syncMultiplier : 1;
  }

  /**
   * 计算一次攻击
   * total = (diceSum + growthCoef) × syncMult × leopardMult × fatigueMult + prophetBonus
   */
  function computeAttack({ dice, growthCoef, spreadMs = null, prophetHit = false, fatigued = false }, config = DEFAULT_CONFIG) {
    const diceSum = dice.reduce(function (a, b) { return a + b; }, 0);
    const syncMult = syncMultiplier(spreadMs, config);
    const leopard = isLeopard(dice);
    const leopardMult = leopard ? config.leopardMultiplier : 1;
    const fatigueMult = fatigued ? config.fatigueMultiplier : 1;
    const prophetBonus = prophetHit ? config.prophetBonus : 0;
    const total = (diceSum + growthCoef) * syncMult * leopardMult * fatigueMult + prophetBonus;
    return { diceSum, growthCoef, syncMult, leopardMult, fatigueMult, prophetBonus, total, isLeopard: leopard };
  }

  /**
   * 单局胜负判定：仅一方豹子 → 该方直接胜；
   * 双方豹子或都无豹子 → total 高者胜；total 相等 → 'tie'
   */
  function decideRound(attackA, attackB) {
    if (attackA.isLeopard && !attackB.isLeopard) return 'A';
    if (attackB.isLeopard && !attackA.isLeopard) return 'B';
    if (attackA.total > attackB.total) return 'A';
    if (attackB.total > attackA.total) return 'B';
    return 'tie';
  }

  /** 掷骰配额：floor(gmv / gmvPerDice)，每天独立计算 */
  function quotaFor(gmv, config = DEFAULT_CONFIG) {
    return Math.floor(gmv / config.gmvPerDice);
  }

  /**
   * 随机抽签生成 8 强对阵：Fisher-Yates 打乱后两两配对
   * 返回 [[id,id],[id,id],[id,id],[id,id]]，不修改入参数组
   */
  function drawBracket(teamIds, rng = Math.random) {
    const shuffled = teamIds.slice(); // 拷贝，避免修改入参
    for (let i = shuffled.length - 1; i > 0; i--) {
      const j = Math.floor(rng() * (i + 1));
      const tmp = shuffled[i];
      shuffled[i] = shuffled[j];
      shuffled[j] = tmp;
    }
    const pairs = [];
    for (let i = 0; i < shuffled.length; i += 2) {
      pairs.push([shuffled[i], shuffled[i + 1]]);
    }
    return pairs;
  }

  /** 军师猜阵容：两个 5 人 id 数组集合相等即中，忽略顺序 */
  function checkProphetGuess(guessIds, lineupIds) {
    if (!Array.isArray(guessIds) || !Array.isArray(lineupIds)) return false;
    const guessSet = new Set(guessIds);
    const lineSet = new Set(lineupIds);
    if (guessSet.size !== lineSet.size) return false;
    for (const id of guessSet) {
      if (!lineSet.has(id)) return false;
    }
    return true;
  }

  /**
   * 总冠军排名：先比两天合计胜场 totalWins，并列比两天增长率之和 growthSum，
   * 再并列比两天 GMV 之和保证确定性；rank 从 1 开始，无并列名次
   * 返回新数组，每项在原字段上追加 totalWins、growthSum、rank
   */
  function standings(rows, config = DEFAULT_CONFIG) {
    return rows
      .map(function (r) {
        return Object.assign({}, r, {
          totalWins: r.winsDay1 + r.winsDay2,
          growthSum: r.growthDay1 + r.growthDay2
        });
      })
      .sort(function (a, b) {
        if (b.totalWins !== a.totalWins) return b.totalWins - a.totalWins;
        if (b.growthSum !== a.growthSum) return b.growthSum - a.growthSum;
        return (b.gmvDay1 + b.gmvDay2) - (a.gmvDay1 + a.gmvDay2);
      })
      .map(function (r, i) {
        return Object.assign({}, r, { rank: i + 1 });
      });
  }

  /* ---------- 联机模式：NTP 式时钟偏移估算与同步判定 ---------- */

  /**
   * 估算设备本地时钟与服务器时钟的偏移（NTP 式）
   * samples: [{c0, s, c1}, ...]（c0=客户端发送时刻本地时间，s=服务器收到时回复的服务器时间，
   * c1=客户端收到回复时刻本地时间，均为毫秒）
   * 每个样本：offset_i = s - (c0 + c1) / 2，rtt_i = c1 - c0
   * 取 rtt 最小的样本（往返越短估算越准），返回 { offset, rtt }
   * samples 为空返回 { offset: 0, rtt: null }
   */
  function estimateClockOffset(samples) {
    if (!Array.isArray(samples) || samples.length === 0) {
      return { offset: 0, rtt: null };
    }
    let best = null;
    for (let i = 0; i < samples.length; i++) {
      const { c0, s, c1 } = samples[i];
      const offset = s - (c0 + c1) / 2;
      const rtt = c1 - c0;
      if (best === null || rtt < best.rtt) best = { offset, rtt };
    }
    return best;
  }

  /** 把设备本地时刻换算到服务器时间轴：clientTs + offset */
  function normalizeTime(clientTs, offset) {
    return clientTs + offset;
  }

  /**
   * 计算一组时间戳（毫秒）的首尾极差
   * 空数组 → { spreadMs: null, earliest: null, latest: null }；单元素 → spreadMs 0
   */
  function computeSpread(timestamps) {
    if (!Array.isArray(timestamps) || timestamps.length === 0) {
      return { spreadMs: null, earliest: null, latest: null };
    }
    const earliest = Math.min.apply(null, timestamps);
    const latest = Math.max.apply(null, timestamps);
    return { spreadMs: latest - earliest, earliest, latest };
  }

  /**
   * 同步判定：5 名出战队员校准后的点击时刻是否构成有效同步
   * earlyCount = 早于 goTs 的时间戳个数（抢跑数）；goTs 为 null 时不判抢跑，恒 0
   * syncOk 需同时满足：timestamps.length >= 5、spreadMs !== null 且 <= config.syncWindowMs、earlyCount === 0
   */
  function checkSync(timestamps, goTs = null, config = DEFAULT_CONFIG) {
    if (!Array.isArray(timestamps)) timestamps = [];
    const spreadMs = computeSpread(timestamps).spreadMs;
    const earlyCount = (goTs === null || goTs === undefined)
      ? 0
      : timestamps.filter(function (t) { return t < goTs; }).length;
    const syncOk = timestamps.length >= 5
      && spreadMs !== null
      && spreadMs <= config.syncWindowMs
      && earlyCount === 0;
    return { syncOk, spreadMs, earlyCount };
  }

  return {
    DEFAULT_CONFIG,
    MOCK_TEAMS,
    createTeams,
    rollDie,
    rollDiceSet,
    isLeopard,
    syncMultiplier,
    computeAttack,
    decideRound,
    quotaFor,
    drawBracket,
    checkProphetGuess,
    standings,
    estimateClockOffset,
    normalizeTime,
    computeSpread,
    checkSync
  };
});
