/**
 * 「骰子擂台·田忌赛马」引擎测试（新版规则）
 * 直接运行：node engine.test.js
 * 全部通过打印成功摘要并以退出码 0 结束；任一失败以非 0 退出
 *
 * 本测试与后端 ParallelTournamentServiceTest 使用同一组常量与手算期望值，
 * 用于对拍关键公式，防止 engine.js 与服务端数值规则漂移。
 */
'use strict';

const assert = require('assert').strict;
const GameEngine = require('./frontend/engine.js');

/* ---------- 极简测试框架 ---------- */
const tests = [];
function test(name, fn) { tests.push({ name, fn }); }

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
  const fns = ['createTeams', 'rollDie', 'drawBlindBox', 'round2', 'personalPoints',
    'guessBonus', 'syncCrit', 'squadPower', 'rerollQuotaFor', 'compareMatchTieBreak',
    'decideMatchWinner', 'decideRoundWinner', 'drawBracket', 'buildSquads',
    'simulateMatch', 'simulateBracket', 'standings'];
  for (const name of fns) {
    assert.equal(typeof GameEngine[name], 'function', `缺少函数 ${name}`);
  }
  assert.ok(Array.isArray(GameEngine.MOCK_TEAMS), 'MOCK_TEAMS 应为数组');
  assert.equal(GameEngine.DEFAULT_CONFIG.rerollLimitPerMatch, 5);
  assert.equal(GameEngine.DEFAULT_CONFIG.guessBonusPerHit, 0.4);
  assert.equal(GameEngine.DEFAULT_CONFIG.guessBonusCap, 10);
  assert.equal(GameEngine.DEFAULT_CONFIG.syncWindowMs, 500);
  assert.equal(GameEngine.DEFAULT_CONFIG.syncCritMultiplier, 1.5);
  assert.equal(GameEngine.DEFAULT_CONFIG.gmvPerReroll, 100000);
  assert.deepStrictEqual(GameEngine.DEFAULT_CONFIG.blindBoxValues, [5, 4, 3, 2, 1, -1, -2]);
  assert.deepStrictEqual(GameEngine.DEFAULT_CONFIG.blindBoxWeights, [1, 3, 8, 20, 28, 22, 18]);
});

/* ---------- 数值规则（与服务端 ParallelTournamentServiceTest 对拍） ---------- */

test('createTeams 生成 8 队各 30 人且不被污染', () => {
  assert.equal(GameEngine.MOCK_TEAMS.length, 8);
  const teams = GameEngine.createTeams();
  assert.equal(teams.length, 8);
  teams.forEach(team => {
    assert.equal(team.players.length, 30, `${team.id} 应有 30 人`);
    assert.equal(team.players[0].id, team.id + '-p01');
  });
  teams[0].players[0].name = '被修改';
  assert.notEqual(GameEngine.MOCK_TEAMS[0], teams[0]);
});

test('rollDie 返回 1-6 且边界正确', () => {
  assert.equal(GameEngine.rollDie(() => 0), 1);
  assert.equal(GameEngine.rollDie(() => 0.9999999999), 6);
  const rng = makeRng(7);
  for (let i = 0; i < 1000; i++) {
    const v = GameEngine.rollDie(rng);
    assert.ok(v >= 1 && v <= 6, `骰子越界 ${v}`);
  }
});

test('personalPoints 为最终骰子值加盲盒', () => {
  assert.equal(GameEngine.personalPoints({ diceFinal: 4, blindBox: -2 }), 2);
  assert.equal(GameEngine.personalPoints({ diceFinal: 6, blindBox: 3 }), 9);
  assert.equal(GameEngine.personalPoints({ diceFinal: 5 }), 5); // 盲盒缺省按 0
});

test('guessBonus 每人次 0.4 且封顶 10', () => {
  assert.equal(GameEngine.guessBonus(0), 0);
  assert.equal(GameEngine.guessBonus(3), 1.2);
  assert.equal(GameEngine.guessBonus(25), 10);
  assert.equal(GameEngine.guessBonus(30), 10);
});

test('squadPower 先对暴击乘积四舍五入再加猜阵加成', () => {
  assert.equal(GameEngine.squadPower(20, true, 0), 30);
  assert.equal(GameEngine.squadPower(20, false, 0), 20);
  assert.equal(GameEngine.squadPower(13, true, 1), 19.9);
  assert.equal(GameEngine.squadPower(7, false, 30), 17);
});

test('syncCrit 需 5 人且首尾差 ≤500ms', () => {
  assert.equal(GameEngine.syncCrit([1000, 1100, 1200, 1300, 1500], false), true);
  assert.equal(GameEngine.syncCrit([1000, 1100, 1200, 1300, 1501], false), false);
  assert.equal(GameEngine.syncCrit([1000, 1100, 1200, 1300], false), false); // 不足 5 人
});

test('含代掷队员的小队必无暴击（不依赖时刻差）', () => {
  const aligned = [2000, 2000, 2000, 2000, 2000];
  assert.equal(GameEngine.syncCrit(aligned, true), false);
  assert.equal(GameEngine.syncCrit(aligned, false), true);
});

test('rerollQuotaFor 为 GMV 除以 10 万向下取整', () => {
  assert.equal(GameEngine.rerollQuotaFor(331500), 3);
  assert.equal(GameEngine.rerollQuotaFor(299999), 2);
  assert.equal(GameEngine.rerollQuotaFor(300000), 3);
});

test('drawBlindBox 只落在声明档位且覆盖全部档位', () => {
  const values = GameEngine.DEFAULT_CONFIG.blindBoxValues;
  const counts = {};
  const total = 20000;
  const rng = makeRng(1234);
  for (let i = 0; i < total; i++) {
    const v = GameEngine.drawBlindBox(rng);
    assert.ok(values.indexOf(v) >= 0, `盲盒越界 ${v}`);
    counts[v] = (counts[v] || 0) + 1;
  }
  assert.deepStrictEqual(Object.keys(counts).map(Number).sort((a, b) => a - b), values.slice().sort((a, b) => a - b));
  for (const v of values) {
    const ratio = counts[v] / total;
    assert.ok(ratio > 0.005 && ratio < 0.35, `档位 ${v} 占比 ${ratio} 超出 0.5%~35%`);
  }
});

/* ---------- 平局链（与服务端 compareMatchTieBreak / decideMatchWinner 对拍） ---------- */

test('平局链：总点数 → 增长系数 → 队伍 ID 字典序', () => {
  assert.ok(GameEngine.compareMatchTieBreak(100, 99, 1.0, 2.0, 't1', 't2') > 0);
  assert.ok(GameEngine.compareMatchTieBreak(99, 100, 2.0, 1.0, 't1', 't2') < 0);
  assert.ok(GameEngine.compareMatchTieBreak(100, 100, 1.2, 1.1, 't1', 't2') > 0);
  assert.ok(GameEngine.compareMatchTieBreak(100, 100, 1.1, 1.2, 't1', 't2') < 0);
  assert.ok(GameEngine.compareMatchTieBreak(100, 100, 1.0, 1.0, 't1', 't2') > 0);
  assert.ok(GameEngine.compareMatchTieBreak(100, 100, 1.0, 1.0, 't2', 't1') < 0);
});

test('比赛胜负链走完全程', () => {
  const base = { totalPointsA: 180, totalPointsB: 30, coefficientA: 1.2, coefficientB: 1.0, idA: 't1', idB: 't2' };

  let r = GameEngine.decideMatchWinner(Object.assign({}, base, { winsA: 4, winsB: 2 }));
  assert.equal(r.winnerSide, 'A');
  assert.equal(r.tieBreak, '胜场');

  r = GameEngine.decideMatchWinner(Object.assign({}, base, { winsA: 3, winsB: 3 }));
  assert.equal(r.winnerSide, 'A');
  assert.equal(r.tieBreak, '总点数');

  r = GameEngine.decideMatchWinner({ winsA: 3, winsB: 3, totalPointsA: 180, totalPointsB: 180, coefficientA: 1.2, coefficientB: 1.0, idA: 't1', idB: 't2' });
  assert.equal(r.winnerSide, 'A');
  assert.equal(r.tieBreak, '增长系数');

  r = GameEngine.decideMatchWinner({ winsA: 3, winsB: 3, totalPointsA: 180, totalPointsB: 180, coefficientA: 1.2, coefficientB: 1.2, idA: 't1', idB: 't2' });
  assert.equal(r.winnerSide, 'A');
  assert.equal(r.tieBreak, '队伍ID');

  r = GameEngine.decideMatchWinner({ winsA: 3, winsB: 3, totalPointsA: 180, totalPointsB: 180, coefficientA: 1.2, coefficientB: 1.2, idA: 't2', idB: 't1' });
  assert.equal(r.winnerSide, 'B');
  assert.equal(r.tieBreak, '队伍ID');
});

test('单局胜负：战力高者胜，相等平局', () => {
  assert.equal(GameEngine.decideRoundWinner(40, 5), 'A');
  assert.equal(GameEngine.decideRoundWinner(5, 40), 'B');
  assert.equal(GameEngine.decideRoundWinner(20, 20), null);
});

/* ---------- 分队与抽签 ---------- */

test('buildSquads 按名册顺序均分 6×5', () => {
  const players = GameEngine.createTeams()[0].players;
  const squads = GameEngine.buildSquads(players);
  assert.equal(squads.length, 6);
  squads.forEach(squad => assert.equal(squad.length, 5));
  assert.equal(squads[0][0].id, players[0].id);
  assert.equal(squads[5][4].id, players[29].id);
});

test('drawBracket 生成 4 组两两配对且不修改入参', () => {
  const input = ['t1', 't2', 't3', 't4', 't5', 't6', 't7', 't8'];
  const bracket = GameEngine.drawBracket(input, makeRng(99));
  assert.equal(bracket.length, 4);
  const flat = bracket.flat();
  assert.equal(flat.length, 8);
  assert.deepStrictEqual(flat.slice().sort(), input.slice().sort());
  assert.deepStrictEqual(input, ['t1', 't2', 't3', 't4', 't5', 't6', 't7', 't8']);
});

/* ---------- 整场模拟（确定性） ---------- */

test('simulateMatch 用同一随机种子可复现且结构完整', () => {
  const teams = GameEngine.createTeams();
  const a = GameEngine.simulateMatch(teams[0], teams[1], { rng: makeRng(42), day: 1 });
  const b = GameEngine.simulateMatch(teams[0], teams[1], { rng: makeRng(42), day: 1 });
  assert.deepStrictEqual(a, b);

  assert.equal(a.rounds.length, 6);
  assert.equal(a.winsA + a.winsB <= 6, true);
  assert.ok(a.winnerSide === 'A' || a.winnerSide === 'B');
  assert.ok(['胜场', '总点数', '增长系数', '队伍ID'].indexOf(a.tieBreak) >= 0);
  a.rounds.forEach((round, i) => {
    assert.equal(round.round, i + 1);
    assert.equal(round.powerA, GameEngine.squadPower(round.baseA, round.critA, round.guessHitsA));
    assert.equal(round.powerB, GameEngine.squadPower(round.baseB, round.critB, round.guessHitsB));
    assert.ok(round.winner === 'A' || round.winner === 'B' || round.winner === null);
  });
});

test('simulateMatch 注入猜阵命中与重掷会正确反映进战力', () => {
  const teams = GameEngine.createTeams();
  // 每局 A 方 25 人次命中 → 封顶 10；B 方 0
  const guessHits = [0, 1, 2, 3, 4, 5].map(() => ({ A: 25, B: 0 }));
  const result = GameEngine.simulateMatch(teams[0], teams[1], { rng: makeRng(7), day: 1, guessHits });
  result.rounds.forEach(round => {
    assert.equal(round.guessHitsA, 25);
    assert.equal(round.guessBonusA, 10);
    assert.equal(round.guessBonusB, 0);
  });
});

test('simulateBracket 跑通 8 队单败并产生冠军', () => {
  const teams = GameEngine.createTeams();
  const bracket = GameEngine.simulateBracket(teams, { rng: makeRng(5), day: 1 });
  assert.equal(bracket.quarterfinals.length, 4);
  assert.equal(bracket.semifinals.length, 2);
  assert.ok(bracket.final);
  assert.ok(bracket.champion);
  assert.ok(teams.some(team => team.id === bracket.champion));
});

/* ---------- 总冠军排名 ---------- */

test('standings 按胜场 → GMV 之和 → 队伍 ID 排序', () => {
  const rows = [
    { id: 't2', winsDay1: 2, winsDay2: 2, gmvDay1: 6, gmvDay2: 2 },
    { id: 't1', winsDay1: 2, winsDay2: 2, gmvDay1: 5, gmvDay2: 5 },
    { id: 't3', winsDay1: 3, winsDay2: 0, gmvDay1: 100, gmvDay2: 0 }
  ];
  const result = GameEngine.standings(rows);
  assert.equal(result[0].id, 't1'); // 同为 4 胜，GMV 之和 10 > 8
  assert.equal(result[1].id, 't2');
  assert.equal(result[2].id, 't3'); // 胜场优先于 GMV，3 胜排在 4 胜之后
  assert.deepStrictEqual(result.map(r => r.rank), [1, 2, 3]);
});

/* ---------- 时钟校准（保留，兼容旧 server.js） ---------- */

test('estimateClockOffset 取 rtt 最小的样本', () => {
  assert.deepStrictEqual(GameEngine.estimateClockOffset([{ c0: 1000, s: 1060, c1: 1040 }]), { offset: 40, rtt: 40 });
  assert.deepStrictEqual(GameEngine.estimateClockOffset([]), { offset: 0, rtt: null });
  assert.deepStrictEqual(GameEngine.estimateClockOffset([{ c0: 1000, s: 1060, c1: 1040 }, { c0: 2000, s: 2020, c1: 2010 }]), { offset: 15, rtt: 10 });
});

test('normalizeTime 与 computeSpread', () => {
  assert.equal(GameEngine.normalizeTime(1000, 50), 1050);
  assert.equal(GameEngine.normalizeTime(1000, -30), 970);
  assert.deepStrictEqual(GameEngine.computeSpread([1300, 1000, 1200, 1100, 1250]), { spreadMs: 300, earliest: 1000, latest: 1300 });
  assert.deepStrictEqual(GameEngine.computeSpread([]), { spreadMs: null, earliest: null, latest: null });
});

test('checkSync 需 5 人、无抢跑且首尾差 ≤500ms', () => {
  assert.equal(GameEngine.checkSync([10000, 10080, 10120, 10160, 10200]).syncOk, true);
  assert.equal(GameEngine.checkSync([10000, 10080, 10120, 10160, 10900]).syncOk, false);
  assert.equal(GameEngine.checkSync([9900, 10050, 10080, 10120, 10160], 10000).syncOk, false); // 抢跑
  assert.deepStrictEqual(GameEngine.checkSync([]), { syncOk: false, spreadMs: null, earlyCount: 0 });
});

/* ---------- 运行器 ---------- */

let passed = 0;
for (const t of tests) {
  try {
    t.fn();
    passed++;
    console.log('✓ ' + t.name);
  } catch (error) {
    console.error('✗ ' + t.name);
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
    break;
  }
}
console.log(`\n${passed} / ${tests.length} 通过`);
if (passed !== tests.length) process.exit(1);
