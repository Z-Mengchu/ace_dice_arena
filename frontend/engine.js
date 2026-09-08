/**
 * 「骰子擂台·田忌赛马」纯逻辑引擎（新版规则）
 * 不依赖任何 DOM / 浏览器 API，可在 node 中直接 require
 * UMD 风格：浏览器挂到 window.GameEngine，node 下 module.exports = GameEngine
 *
 * 与后端 ParallelTournamentService 的数值规则保持一致：
 *  - 个人点数 = diceFinal + blindBox
 *  - 小队战力 = round2(base × (同步暴击 ? 1.5 : 1)) + guessBonus
 *  - guessBonus = min(命中人次 × 0.4, 10)
 *  - 同步暴击 = 小队 5 人掷骰时刻首尾差 ≤ 500ms 且无系统代掷
 *  - 比赛胜负链 = 6 局胜场 → 30 人总点数 → 增长系数 → 队伍 ID 字典序
 */
(function (root, factory) {
  const GameEngine = factory();
  // Vite 会把这个 UMD 文件转换为 CommonJS 包装；此时即使在浏览器中，
  // `module.exports` 分支也会成立。始终挂载全局变量，兼容仍通过
  // `window.GameEngine` 访问引擎的非模块脚本。
  root.GameEngine = GameEngine;
  if (typeof module === 'object' && module.exports) {
    module.exports = GameEngine; // node 环境
  }
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  /** 默认配置：与服务端 ParallelTournamentService 常量一一对应 */
  const DEFAULT_CONFIG = {
    gmvPerReroll: 100000,     // 每 10 万元 GMV 兑换 1 次重掷配额
    playersPerTeam: 30,       // 每队队员数
    squadsPerTeam: 6,         // 每队小队数（1~6 号出场位）
    squadSize: 5,             // 每小队人数
    rerollLimitPerMatch: 5,   // 每场重掷次数上限
    guessBonusPerHit: 0.4,    // 猜阵每命中一人次的加成
    guessBonusCap: 10,        // 猜阵单局加成上限
    syncWindowMs: 500,        // 同步暴击首尾时差阈值（毫秒）
    syncCritMultiplier: 1.5,  // 同步暴击倍率
    days: 2,                  // 比赛天数
    blindBoxValues: [5, 4, 3, 2, 1, -1, -2],           // 盲盒点数档（不含 0）
    blindBoxWeights: [1, 3, 8, 20, 28, 22, 18]         // 对应概率（百分比）
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
   * id 格式 t{n}-p{01..30}；name 形如 `雷霆-01`
   */
  function buildPlayers(team) {
    const players = [];
    for (let i = 1; i <= DEFAULT_CONFIG.playersPerTeam; i++) {
      const num = String(i).padStart(2, '0');
      players.push({ id: team.id + '-p' + num, name: team.shortName + '-' + num });
    }
    return players;
  }

  /**
   * 基于 MOCK_TEAMS 生成完整队伍数组（含 30 名队员）
   * 每次调用返回全新对象（深拷贝），多次调用互不影响
   */
  function createTeams() {
    return MOCK_TEAMS.map(function (t) {
      return {
        id: t.id,
        name: t.name,
        shortName: t.shortName,
        gmv: { day1: t.gmv.day1, day2: t.gmv.day2 },
        growth: { day1: t.growth.day1, day2: t.growth.day2 },
        players: buildPlayers(t)
      };
    });
  }

  /** 掷 1 个骰子，返回 1-6 整数；rng 可注入便于测试 */
  function rollDie(rng = Math.random) {
    return Math.floor(rng() * 6) + 1;
  }

  /** 开盲盒：按权重抽取一个点数档（+5/+4/+3/+2/+1/-1/-2，不含 0） */
  function drawBlindBox(rng = Math.random, config = DEFAULT_CONFIG) {
    const values = config.blindBoxValues;
    const weights = config.blindBoxWeights;
    const total = weights.reduce(function (a, b) { return a + b; }, 0);
    let r = rng() * total;
    for (let i = 0; i < values.length; i++) {
      if (r < weights[i]) return values[i];
      r -= weights[i];
    }
    return values[values.length - 1];
  }

  /** 四舍五入保留两位小数：与服务端 round2 一致 */
  function round2(value) {
    return Math.round(value * 100) / 100;
  }

  /** 个人点数 = 最终骰子值 + 盲盒加减 */
  function personalPoints(player) {
    return (player && (player.diceFinal || 0) || 0) + (player && (player.blindBox || 0) || 0);
  }

  /** 猜阵加成 = min(命中人次 × 0.4, 10) */
  function guessBonus(hits, config = DEFAULT_CONFIG) {
    return round2(Math.min(hits * config.guessBonusPerHit, config.guessBonusCap));
  }

  /**
   * 同步暴击判定：小队 5 人掷骰时刻首尾差 ≤ syncWindowMs；
   * 含系统代掷队员的小队必无暴击（显式判定，不依赖时刻差）。
   */
  function syncCrit(timestamps, anyAutoRolled = false, config = DEFAULT_CONFIG) {
    if (anyAutoRolled) return false;
    if (!Array.isArray(timestamps) || timestamps.length < config.squadSize) return false;
    const spread = computeSpread(timestamps).spreadMs;
    return spread !== null && spread <= config.syncWindowMs;
  }

  /**
   * 小队战力 = round2(base × (同步暴击 ? 1.5 : 1)) + guessBonus
   * base 为该小队 5 人个人点数之和。
   */
  function squadPower(base, crit, hits, config = DEFAULT_CONFIG) {
    return round2(base * (crit ? config.syncCritMultiplier : 1)) + guessBonus(hits, config);
  }

  /** 重掷配额 = floor(gmv / 100000) */
  function rerollQuotaFor(gmv, config = DEFAULT_CONFIG) {
    return Math.floor(gmv / config.gmvPerReroll);
  }

  /**
   * 平局链比较：返回正数表示 A 方胜。
   * 30 人总点数多者胜 → 增长系数高者胜 → 队伍 ID 字典序小者胜。
   */
  function compareMatchTieBreak(pointsA, pointsB, coefficientA, coefficientB, idA, idB) {
    if (pointsA !== pointsB) return pointsA - pointsB;
    if (coefficientA !== coefficientB) return coefficientA - coefficientB;
    if (idA === idB) return 0;
    return idA < idB ? 1 : -1;
  }

  /**
   * 比赛胜负链：6 局胜场多者胜 → 30 人总点数 → 增长系数 → 队伍 ID。
   * 返回 { winnerSide, tieBreak }。
   */
  function decideMatchWinner({ winsA, winsB, totalPointsA, totalPointsB, coefficientA, coefficientB, idA, idB }) {
    if (winsA !== winsB) return { winnerSide: winsA > winsB ? 'A' : 'B', tieBreak: '胜场' };
    const comparison = compareMatchTieBreak(totalPointsA, totalPointsB, coefficientA, coefficientB, idA, idB);
    let tieBreak = '队伍ID';
    if (totalPointsA !== totalPointsB) tieBreak = '总点数';
    else if (coefficientA !== coefficientB) tieBreak = '增长系数';
    return { winnerSide: comparison >= 0 ? 'A' : 'B', tieBreak };
  }

  /** 单局胜负：战力高者胜，相等记平局（winner 为 'A' / 'B' / null） */
  function decideRoundWinner(powerA, powerB) {
    if (powerA > powerB) return 'A';
    if (powerB > powerA) return 'B';
    return null;
  }

  /**
   * 随机抽签生成 8 强对阵：Fisher-Yates 打乱后两两配对
   * 返回 [[id,id],[id,id],[id,id],[id,id]]，不修改入参数组
   */
  function drawBracket(teamIds, rng = Math.random) {
    const shuffled = teamIds.slice();
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

  /** 按名册顺序把 30 名队员均分为 6 支 5 人小队（有序即 1~6 号出场位） */
  function buildSquads(players, config = DEFAULT_CONFIG) {
    const squads = [];
    for (let s = 0; s < config.squadsPerTeam; s++) {
      const squad = [];
      for (let i = s * config.squadSize; i < s * config.squadSize + config.squadSize; i++) {
        squad.push(players[i]);
      }
      squads.push(squad);
    }
    return squads;
  }

  /**
   * 单场完整模拟（确定性可复现，rng 可注入）：
   * 掷骰 + 盲盒 → 分 6×5 小队 → 6 局逐局结算 → 平局链定胜负。
   * opts: { rng, day, guessHits, rerolls, rollTs }
   *  - guessHits: [{ A, B }] × 6，逐局注入猜阵命中人次（默认 0）
   *  - rerolls: { [teamId]: count } 重掷次数（默认 0）
   *  - rollTs: (teamId, squadIndex, memberIndex) => ms（默认队内 240ms 内 → 触发暴击）
   * 返回与后端 rounds[] / match 结构对齐的结果对象。
   */
  function simulateMatch(teamA, teamB, opts = {}) {
    const config = Object.assign({}, DEFAULT_CONFIG, opts.config || {});
    const rng = opts.rng || Math.random;
    const day = opts.day || 1;
    const coefficientOf = function (team) {
      if (opts.coefficients && opts.coefficients[team.id] != null) return opts.coefficients[team.id];
      return 1 + (team.growth ? (team.growth['day' + day] || 0) : 0) / 100;
    };
    const prepare = function (team) {
      const players = team.players.map(function (p) {
        const dice = rollDie(rng);
        return { id: p.id, name: p.name, dice: dice, diceFinal: dice, blindBox: drawBlindBox(rng, config), rollTs: null, autoRolled: false };
      });
      const squads = buildSquads(players, config);
      squads.forEach(function (squad, sIdx) {
        squad.forEach(function (p, mIdx) {
          p.rollTs = opts.rollTs ? opts.rollTs(team.id, sIdx, mIdx) : 1000 + sIdx * 2000 + mIdx * 60;
        });
      });
      const rerollCount = Math.min((opts.rerolls && opts.rerolls[team.id]) || 0, config.rerollLimitPerMatch);
      for (let i = 0; i < rerollCount; i++) {
        const target = squads[0][i % squads[0].length];
        target.diceFinal = rollDie(rng);
        target.rerolled = true;
      }
      return { id: team.id, name: team.name, players: players, squads: squads, coefficient: coefficientOf(team) };
    };
    const A = prepare(teamA), B = prepare(teamB);
    const rounds = [];
    let winsA = 0, winsB = 0;
    for (let round = 1; round <= config.squadsPerTeam; round++) {
      const squadA = A.squads[round - 1], squadB = B.squads[round - 1];
      const baseA = squadA.reduce(function (sum, p) { return sum + personalPoints(p); }, 0);
      const baseB = squadB.reduce(function (sum, p) { return sum + personalPoints(p); }, 0);
      const critA = syncCrit(squadA.map(function (p) { return p.rollTs; }), squadA.some(function (p) { return p.autoRolled; }), config);
      const critB = syncCrit(squadB.map(function (p) { return p.rollTs; }), squadB.some(function (p) { return p.autoRolled; }), config);
      const hint = opts.guessHits && opts.guessHits[round - 1] || {};
      const hitsA = hint.A || 0, hitsB = hint.B || 0;
      const powerA = squadPower(baseA, critA, hitsA, config);
      const powerB = squadPower(baseB, critB, hitsB, config);
      const winner = decideRoundWinner(powerA, powerB);
      if (winner === 'A') winsA++;
      else if (winner === 'B') winsB++;
      rounds.push({
        round: round,
        baseA: baseA, critA: critA, guessHitsA: hitsA, guessBonusA: guessBonus(hitsA, config), powerA: powerA,
        baseB: baseB, critB: critB, guessHitsB: hitsB, guessBonusB: guessBonus(hitsB, config), powerB: powerB,
        winner: winner
      });
    }
    const totalPointsA = A.players.reduce(function (sum, p) { return sum + personalPoints(p); }, 0);
    const totalPointsB = B.players.reduce(function (sum, p) { return sum + personalPoints(p); }, 0);
    const decided = decideMatchWinner({
      winsA: winsA, winsB: winsB,
      totalPointsA: totalPointsA, totalPointsB: totalPointsB,
      coefficientA: A.coefficient, coefficientB: B.coefficient,
      idA: teamA.id, idB: teamB.id
    });
    return {
      idA: teamA.id, idB: teamB.id,
      rounds: rounds, winsA: winsA, winsB: winsB,
      totalPointsA: totalPointsA, totalPointsB: totalPointsB,
      coefficientA: A.coefficient, coefficientB: B.coefficient,
      winnerSide: decided.winnerSide,
      winner: decided.winnerSide === 'A' ? teamA.id : teamB.id,
      tieBreak: decided.tieBreak
    };
  }

  /**
   * 8 队单败淘汰模拟：抽签 → 1/4 决赛 ×4 → 半决赛 ×2 → 决赛，
   * 每场用 simulateMatch 结算，返回按轮次组织的对阵与冠军。
   * teams 为含 .id 与 .players 的队伍对象数组（createTeams() 的产物）。
   */
  function simulateBracket(teams, opts = {}) {
    const rng = opts.rng || Math.random;
    const byId = {};
    teams.forEach(function (team) { byId[team.id] = team; });
    const pairs = drawBracket(teams.map(function (team) { return team.id; }), rng);
    const play = function (aId, bId) {
      return simulateMatch(byId[aId], byId[bId], Object.assign({}, opts, { rng: rng }));
    };
    const quarterfinals = pairs.map(function (pair) {
      const result = play(pair[0], pair[1]);
      return { a: pair[0], b: pair[1], winner: result.winner, result: result };
    });
    const semifinalIds = quarterfinals.map(function (q) { return q.winner; });
    const semifinals = [
      play(semifinalIds[0], semifinalIds[1]),
      play(semifinalIds[2], semifinalIds[3])
    ].map(function (result) {
      return { a: result.idA, b: result.idB, winner: result.winner, result: result };
    });
    const final = play(semifinals[0].winner, semifinals[1].winner);
    return {
      quarterfinals: quarterfinals,
      semifinals: semifinals,
      final: final,
      champion: final.winner
    };
  }

  /**
   * 总冠军排名：先比两天合计胜场 totalWins，并列比两天 GMV 之和 gmvSum，
   * 再并列按队伍 ID 字典序保证确定性；rank 从 1 开始，无并列名次
   */
  function standings(rows, config = DEFAULT_CONFIG) {
    return rows
      .map(function (r) {
        return Object.assign({}, r, {
          totalWins: r.winsDay1 + r.winsDay2,
          gmvSum: r.gmvDay1 + r.gmvDay2
        });
      })
      .sort(function (a, b) {
        if (b.totalWins !== a.totalWins) return b.totalWins - a.totalWins;
        if (b.gmvSum !== a.gmvSum) return b.gmvSum - a.gmvSum;
        if (a.id === b.id) return 0;
        return a.id < b.id ? -1 : 1;
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
   * 取 rtt 最小的样本，返回 { offset, rtt }
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
   * 联机掷骰同步判定（保留，兼容旧 server.js）：
   * 5 名出战队员校准后的点击时刻是否构成有效同步。
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
    drawBlindBox,
    round2,
    personalPoints,
    guessBonus,
    syncCrit,
    squadPower,
    rerollQuotaFor,
    compareMatchTieBreak,
    decideMatchWinner,
    decideRoundWinner,
    drawBracket,
    buildSquads,
    simulateMatch,
    simulateBracket,
    standings,
    estimateClockOffset,
    normalizeTime,
    computeSpread,
    checkSync
  };
});
