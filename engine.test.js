/**
 * 「王牌攻守擂·骰子大亨」引擎测试
 * 直接运行：node engine.test.js
 * 全部通过打印成功摘要并以退出码 0 结束；任一失败以非 0 退出
 */
'use strict';

const assert = require('assert').strict;
const GameEngine = require('./frontend/engine.js');

/* ---------- 极简测试框架 ---------- */
const tests = [];
function test(name, fn) { tests.push({ name, fn }); }

/** 浮点数近似相等 */
function almostEqual(actual, expected, eps = 1e-9) {
  assert.ok(
    Math.abs(actual - expected) < eps,
    `期望 ${actual} ≈ ${expected}（误差 < ${eps}）`
  );
}

/** 可注入的确定性随机数发生器（LCG） */
function makeRng(seed) {
  let s = seed >>> 0;
  return function () {
    s = (s * 1103515245 + 12345) % 2147483648;
    return s / 2147483648;
  };
}

/* ---------- API 完整性与默认配置 ---------- */

test('API 导出与规格一致', () => {
  const fns = ['createTeams', 'rollDie', 'rollDiceSet', 'isLeopard', 'syncMultiplier',
    'computeAttack', 'decideRound', 'quotaFor', 'drawBracket', 'checkProphetGuess', 'standings'];
  for (const name of fns) {
    assert.equal(typeof GameEngine[name], 'function', `缺少函数 ${name}`);
  }
  assert.ok(Array.isArray(GameEngine.MOCK_TEAMS), 'MOCK_TEAMS 应为数组');
  assert.deepStrictEqual(GameEngine.DEFAULT_CONFIG, {
    gmvPerDice: 100000,
    playersPerTeam: 30,
    backendCount: 5,
    matchWinRounds: 1,
    syncWindowMs: 500,
    syncMultiplier: 1.5,
    leopardMultiplier: 3,
    prophetBonus: 2,
    fatigueMultiplier: 1,
    days: 2
  });
});

/* ---------- MOCK_TEAMS 与 createTeams ---------- */

test('MOCK_TEAMS 数据与规格一致', () => {
  assert.equal(GameEngine.MOCK_TEAMS.length, 8);
  const t1 = GameEngine.MOCK_TEAMS[0];
  assert.deepStrictEqual(t1, {
    id: 't1', name: '雷霆战区', shortName: '雷霆',
    gmv: { day1: 331500, day2: 338200 },
    growth: { day1: 8.6, day2: 7.9 }
  });
});

test('createTeams 生成 8 队 × 30 人，角色/队长/命名正确', () => {
  const teams = GameEngine.createTeams();
  assert.equal(teams.length, 8);
  for (let i = 0; i < 8; i++) {
    const team = teams[i];
    assert.equal(team.id, `t${i + 1}`);
    assert.equal(team.players.length, 30);
    assert.equal(team.captainId, `${team.id}-p01`);
    assert.equal(team.viceCaptainId, `${team.id}-p18`);
    const fronts = team.players.filter(p => p.role === 'front');
    const backs = team.players.filter(p => p.role === 'back');
    assert.equal(fronts.length, 25);
    assert.equal(backs.length, 5);
    // p01-p25 前端，p26-p30 后端
    team.players.forEach((p, idx) => {
      const num = String(idx + 1).padStart(2, '0');
      assert.equal(p.id, `${team.id}-p${num}`);
      assert.equal(p.name, `${team.shortName}-${num}`);
      assert.equal(p.role, idx + 1 >= 26 ? 'back' : 'front');
    });
  }
  assert.equal(teams[0].players[0].name, '雷霆-01');
  assert.equal(teams[0].players[17].name, '雷霆-18');
});

test('createTeams 每次返回全新对象（深拷贝互不影响）', () => {
  const a = GameEngine.createTeams();
  a[0].players[0].name = '被篡改';
  a[0].gmv.day1 = 1;
  const b = GameEngine.createTeams();
  assert.equal(b[0].players[0].name, '雷霆-01');
  assert.equal(b[0].gmv.day1, 331500);
  assert.equal(GameEngine.MOCK_TEAMS[0].gmv.day1, 331500, 'MOCK_TEAMS 不应被污染');
});

/* ---------- 配额 ---------- */

test('8 队 mock 配额：day1 按每 10 万 GMV 兑换一次', () => {
  const quotas = GameEngine.MOCK_TEAMS.map(t => GameEngine.quotaFor(t.gmv.day1));
  assert.deepStrictEqual(quotas, [3, 2, 3, 2, 3, 3, 3, 2]);
});

test('8 队 mock 配额：day2 按每 10 万 GMV 兑换一次', () => {
  const quotas = GameEngine.MOCK_TEAMS.map(t => GameEngine.quotaFor(t.gmv.day2));
  assert.deepStrictEqual(quotas, [3, 3, 3, 2, 3, 3, 3, 2]);
});

test('quotaFor 向下取整且支持自定义 config', () => {
  assert.equal(GameEngine.quotaFor(299999), 2);
  assert.equal(GameEngine.quotaFor(300000), 3);
  assert.equal(GameEngine.quotaFor(331500, { gmvPerDice: 50000 }), 6);
});

/* ---------- 掷骰 ---------- */

test('rollDie 返回 1-6 整数', () => {
  assert.equal(GameEngine.rollDie(() => 0), 1);
  assert.equal(GameEngine.rollDie(() => 0.9999999999), 6);
  const rng = makeRng(42);
  for (let i = 0; i < 2000; i++) {
    const v = GameEngine.rollDie(rng);
    assert.ok(Number.isInteger(v) && v >= 1 && v <= 6, `非法骰子点数 ${v}`);
  }
});

test('rollDiceSet 长度正确、范围 1-6', () => {
  const rng = makeRng(7);
  const d5 = GameEngine.rollDiceSet(5, rng);
  assert.equal(d5.length, 5);
  d5.forEach(v => assert.ok(Number.isInteger(v) && v >= 1 && v <= 6));
  const d3 = GameEngine.rollDiceSet(3, rng);
  assert.equal(d3.length, 3);
  // 默认参数 n=5
  assert.equal(GameEngine.rollDiceSet(undefined, makeRng(1)).length, 5);
});

test('isLeopard 判定', () => {
  assert.equal(GameEngine.isLeopard([5, 5, 5, 5, 5]), true);
  assert.equal(GameEngine.isLeopard([5, 5, 5, 5, 4]), false);
  assert.equal(GameEngine.isLeopard([1, 2, 3, 4, 5]), false);
  assert.equal(GameEngine.isLeopard([]), false);
});

/* ---------- 同步倍率 ---------- */

test('syncMultiplier：null/undefined 按 1，≤500 为 1.5，>500 为 1', () => {
  assert.equal(GameEngine.syncMultiplier(null), 1);
  assert.equal(GameEngine.syncMultiplier(undefined), 1);
  assert.equal(GameEngine.syncMultiplier(0), 1.5);
  assert.equal(GameEngine.syncMultiplier(500), 1.5);
  assert.equal(GameEngine.syncMultiplier(501), 1);
  assert.equal(GameEngine.syncMultiplier(300, { syncWindowMs: 200, syncMultiplier: 2 }), 1);
});

/* ---------- computeAttack ---------- */

test('computeAttack 返回字段齐全', () => {
  const r = GameEngine.computeAttack({ dice: [1, 2, 3, 4, 5], growthCoef: 8.6 });
  assert.deepStrictEqual(
    Object.keys(r).sort(),
    ['diceSum', 'fatigueMult', 'growthCoef', 'isLeopard', 'leopardMult', 'prophetBonus', 'syncMult', 'total'].sort()
  );
});

test('computeAttack 基础值：(15+8.6)×1×1×1+0 = 23.6', () => {
  const r = GameEngine.computeAttack({ dice: [1, 2, 3, 4, 5], growthCoef: 8.6 });
  assert.equal(r.diceSum, 15);
  assert.equal(r.growthCoef, 8.6);
  assert.equal(r.syncMult, 1);
  assert.equal(r.leopardMult, 1);
  assert.equal(r.fatigueMult, 1);
  assert.equal(r.prophetBonus, 0);
  assert.equal(r.isLeopard, false);
  almostEqual(r.total, 23.6);
});

test('computeAttack 同步加成 ×1.5', () => {
  const r = GameEngine.computeAttack({ dice: [1, 2, 3, 4, 5], growthCoef: 8.6, spreadMs: 300 });
  assert.equal(r.syncMult, 1.5);
  almostEqual(r.total, 23.6 * 1.5); // 35.4
});

test('computeAttack 豹子 ×3', () => {
  const r = GameEngine.computeAttack({ dice: [4, 4, 4, 4, 4], growthCoef: 0 });
  assert.equal(r.isLeopard, true);
  assert.equal(r.leopardMult, 3);
  almostEqual(r.total, 20 * 3); // 60
});

test('computeAttack 军师 +2（乘法之后再加）', () => {
  const r = GameEngine.computeAttack({ dice: [1, 2, 3, 4, 5], growthCoef: 0, spreadMs: 100, prophetHit: true });
  assert.equal(r.prophetBonus, 2);
  almostEqual(r.total, 15 * 1.5 + 2); // 24.5
});

test('computeAttack 当前流程不启用疲劳惩罚', () => {
  const r = GameEngine.computeAttack({ dice: [1, 2, 3, 4, 5], growthCoef: 8.6, fatigued: true });
  assert.equal(r.fatigueMult, 1);
  almostEqual(r.total, 23.6);
});

test('computeAttack 全部叠加：(10+5)×1.5×3+2 = 69.5', () => {
  const r = GameEngine.computeAttack({
    dice: [2, 2, 2, 2, 2], growthCoef: 5, spreadMs: 100, prophetHit: true, fatigued: true
  });
  assert.equal(r.syncMult, 1.5);
  assert.equal(r.leopardMult, 3);
  assert.equal(r.fatigueMult, 1);
  assert.equal(r.prophetBonus, 2);
  almostEqual(r.total, 69.5);
});

/* ---------- decideRound ---------- */

test('decideRound 单方豹子直接胜（即使 total 更低）', () => {
  const leopardLow = GameEngine.computeAttack({ dice: [1, 1, 1, 1, 1], growthCoef: 0 }); // total 15
  const normalHigh = GameEngine.computeAttack({ dice: [6, 6, 6, 6, 5], growthCoef: 0 }); // total 29
  assert.ok(leopardLow.total < normalHigh.total);
  assert.equal(GameEngine.decideRound(leopardLow, normalHigh), 'A');
  assert.equal(GameEngine.decideRound(normalHigh, leopardLow), 'B');
});

test('decideRound 双方豹子比 total', () => {
  const a = GameEngine.computeAttack({ dice: [1, 1, 1, 1, 1], growthCoef: 0 }); // 15
  const b = GameEngine.computeAttack({ dice: [6, 6, 6, 6, 6], growthCoef: 0 }); // 90
  assert.equal(GameEngine.decideRound(a, b), 'B');
  assert.equal(GameEngine.decideRound(b, a), 'A');
});

test('decideRound 无豹子比 total', () => {
  const a = GameEngine.computeAttack({ dice: [1, 2, 3, 4, 5], growthCoef: 0 }); // 15
  const b = GameEngine.computeAttack({ dice: [2, 3, 4, 5, 6], growthCoef: 0 }); // 20
  assert.equal(GameEngine.decideRound(a, b), 'B');
  assert.equal(GameEngine.decideRound(b, a), 'A');
});

test('decideRound total 相等为 tie', () => {
  const a = GameEngine.computeAttack({ dice: [1, 2, 3, 4, 5], growthCoef: 0 });
  const b = GameEngine.computeAttack({ dice: [5, 4, 3, 2, 1], growthCoef: 0 });
  assert.equal(GameEngine.decideRound(a, b), 'tie');
});

/* ---------- drawBracket ---------- */

test('drawBracket 生成 4 对、8 个不重复队伍、不修改入参', () => {
  const input = ['t1', 't2', 't3', 't4', 't5', 't6', 't7', 't8'];
  const snapshot = input.slice();
  const bracket = GameEngine.drawBracket(input, makeRng(99));
  assert.equal(bracket.length, 4);
  bracket.forEach(pair => assert.equal(pair.length, 2));
  const flat = bracket.reduce((acc, p) => acc.concat(p), []);
  assert.deepStrictEqual(flat.slice().sort(), snapshot.slice().sort());
  assert.equal(new Set(flat).size, 8);
  assert.deepStrictEqual(input, snapshot, '入参数组不应被修改');
});

/* ---------- checkProphetGuess ---------- */

test('checkProphetGuess 顺序不同也算中', () => {
  const lineup = ['t2-p01', 't2-p02', 't2-p03', 't2-p18', 't2-p19'];
  const guess = ['t2-p19', 't2-p01', 't2-p18', 't2-p03', 't2-p02'];
  assert.equal(GameEngine.checkProphetGuess(guess, lineup), true);
});

test('checkProphetGuess 差 1 人不算中', () => {
  const lineup = ['t2-p01', 't2-p02', 't2-p03', 't2-p18', 't2-p19'];
  const guess = ['t2-p01', 't2-p02', 't2-p03', 't2-p18', 't2-p20'];
  assert.equal(GameEngine.checkProphetGuess(guess, lineup), false);
  assert.equal(GameEngine.checkProphetGuess(['t2-p01'], lineup), false);
  assert.equal(GameEngine.checkProphetGuess(null, lineup), false);
});

/* ---------- standings ---------- */

test('standings：胜场优先 → 增长率之和 → GMV 之和，rank 从 1 无并列', () => {
  const rows = [
    { teamId: 'h', winsDay1: 0, winsDay2: 1, gmvDay1: 100, gmvDay2: 100, growthDay1: 6, growthDay2: 6 },
    { teamId: 'e', winsDay1: 2, winsDay2: 2, gmvDay1: 5000, gmvDay2: 4999, growthDay1: 15, growthDay2: 15 },
    { teamId: 'a', winsDay1: 3, winsDay2: 3, gmvDay1: 50, gmvDay2: 50, growthDay1: 1, growthDay2: 1 },
    { teamId: 'd', winsDay1: 3, winsDay2: 2, gmvDay1: 100, gmvDay2: 200, growthDay1: 5, growthDay2: 5 },
    { teamId: 'c', winsDay1: 2, winsDay2: 3, gmvDay1: 300, gmvDay2: 200, growthDay1: 5, growthDay2: 5 },
    { teamId: 'b', winsDay1: 3, winsDay2: 2, gmvDay1: 50, gmvDay2: 50, growthDay1: 9.1, growthDay2: 8.9 },
    { teamId: 'g', winsDay1: 1, winsDay2: 1, gmvDay1: 100, gmvDay2: 100, growthDay1: 7, growthDay2: 7 },
    { teamId: 'f', winsDay1: 1, winsDay2: 2, gmvDay1: 100, gmvDay2: 100, growthDay1: 8, growthDay2: 8 }
  ];
  const result = GameEngine.standings(rows);
  assert.equal(result.length, 8);
  // a 胜场 6 第一（尽管增长率最低）；b/c/d 同 5 胜，b 增长率和最高，
  // c 与 d 增长率和相同，c 的 GMV 之和更高；e 胜场 4 即使增长率/GMV 最高也排第 5
  assert.deepStrictEqual(result.map(r => r.teamId), ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h']);
  assert.deepStrictEqual(result.map(r => r.rank), [1, 2, 3, 4, 5, 6, 7, 8]);
  assert.equal(result[0].totalWins, 6);
  almostEqual(result[1].growthSum, 18);
  assert.equal(result[2].totalWins, 5);
  // 保留原始字段
  assert.equal(result[0].gmvDay1, 50);
  assert.equal(result[0].winsDay2, 3);
});

/* ---------- 联机模式：时钟偏移估算与同步判定 ---------- */

test('estimateClockOffset 选中 rtt 最小样本的 offset（真实 offset +50ms）', () => {
  const samples = [
    { c0: 10000, s: 10150, c1: 10200 }, // offset=50,  rtt=200
    { c0: 20000, s: 20120, c1: 20150 }, // offset=45,  rtt=150
    { c0: 30000, s: 30065, c1: 30030 }, // offset=50,  rtt=30 ← 最小 rtt
    { c0: 40000, s: 40300, c1: 40200 }, // offset=200, rtt=200
    { c0: 50000, s: 50140, c1: 50100 }  // offset=90,  rtt=100
  ];
  assert.deepStrictEqual(GameEngine.estimateClockOffset(samples), { offset: 50, rtt: 30 });
});

test('estimateClockOffset 空数组返回 { offset: 0, rtt: null }', () => {
  assert.deepStrictEqual(GameEngine.estimateClockOffset([]), { offset: 0, rtt: null });
});

test('estimateClockOffset 单样本直接返回其值', () => {
  // offset = 1060 - (1000+1040)/2 = 40，rtt = 1040 - 1000 = 40
  assert.deepStrictEqual(
    GameEngine.estimateClockOffset([{ c0: 1000, s: 1060, c1: 1040 }]),
    { offset: 40, rtt: 40 }
  );
});

test('normalizeTime 基本换算', () => {
  assert.equal(GameEngine.normalizeTime(1000, 50), 1050);
  assert.equal(GameEngine.normalizeTime(1000, -30), 970);
  assert.equal(GameEngine.normalizeTime(1000, 0), 1000);
});

test('computeSpread 5 个乱序时间戳的极差与首尾值', () => {
  assert.deepStrictEqual(
    GameEngine.computeSpread([1300, 1000, 1200, 1100, 1250]),
    { spreadMs: 300, earliest: 1000, latest: 1300 }
  );
});

test('computeSpread 空数组与单元素边界', () => {
  assert.deepStrictEqual(GameEngine.computeSpread([]), { spreadMs: null, earliest: null, latest: null });
  assert.deepStrictEqual(GameEngine.computeSpread([42]), { spreadMs: 0, earliest: 42, latest: 42 });
});

test('checkSync 5 个时间戳极差 200ms → syncOk true', () => {
  assert.deepStrictEqual(
    GameEngine.checkSync([10000, 10080, 10120, 10160, 10200]),
    { syncOk: true, spreadMs: 200, earlyCount: 0 }
  );
});

test('checkSync 极差 900ms → syncOk false', () => {
  assert.deepStrictEqual(
    GameEngine.checkSync([10000, 10080, 10120, 10160, 10900]),
    { syncOk: false, spreadMs: 900, earlyCount: 0 }
  );
});

test('checkSync 仅 4 个时间戳 → syncOk false', () => {
  assert.deepStrictEqual(
    GameEngine.checkSync([10000, 10080, 10120, 10160]),
    { syncOk: false, spreadMs: 160, earlyCount: 0 }
  );
});

test('checkSync 含 1 个早于 goTs 的抢跑 → syncOk false 且 earlyCount=1', () => {
  assert.deepStrictEqual(
    GameEngine.checkSync([9900, 10050, 10080, 10120, 10160], 10000),
    { syncOk: false, spreadMs: 260, earlyCount: 1 }
  );
});

test('checkSync goTs 传 null 时不判抢跑', () => {
  assert.deepStrictEqual(
    GameEngine.checkSync([9900, 10050, 10080, 10120, 10160], null),
    { syncOk: true, spreadMs: 260, earlyCount: 0 }
  );
});

test('checkSync 边界：极差恰为 syncWindowMs(500) → true；空数组 → false', () => {
  assert.equal(GameEngine.checkSync([10000, 10100, 10200, 10300, 10500]).syncOk, true);
  assert.deepStrictEqual(GameEngine.checkSync([]), { syncOk: false, spreadMs: null, earlyCount: 0 });
});

test('联机校准流程：不同偏移的设备校准到服务器时间轴后再判定', () => {
  const goServer = 50000;
  // 服务器时间轴上 5 人都在口令后 100ms 内点击（真实物理同时）
  const serverClicks = [goServer + 20, goServer + 45, goServer + 60, goServer + 80, goServer + 95];
  const offsets = [1200, -800, 3000, -1500, 0]; // 各设备本地时钟相对服务器的偏移
  // 设备本地时间戳 = 服务器时刻 - offset；后台校准 normalizeTime(local, offset) 还原
  const localClicks = serverClicks.map((t, i) => t - offsets[i]);
  const normalized = localClicks.map((t, i) => GameEngine.normalizeTime(t, offsets[i]));
  assert.deepStrictEqual(
    GameEngine.checkSync(normalized, goServer),
    { syncOk: true, spreadMs: 75, earlyCount: 0 }
  );
  // 未校准直接比：各设备时钟差异导致极差巨大，误判为不同步
  assert.equal(GameEngine.checkSync(localClicks, goServer).syncOk, false);
});

/* ---------- 运行器 ---------- */

let passed = 0;
let failed = 0;
for (const { name, fn } of tests) {
  try {
    fn();
    passed++;
    console.log(`✓ ${name}`);
  } catch (err) {
    failed++;
    console.error(`✗ ${name}`);
    console.error(err && err.message ? err.message : err);
  }
}

console.log(`\n共 ${tests.length} 项测试：通过 ${passed}，失败 ${failed}`);
if (failed > 0) {
  console.error('存在失败用例，测试未通过');
  process.exit(1);
}
console.log('全部测试通过 ✔');
process.exit(0);
