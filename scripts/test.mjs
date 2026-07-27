// 모의 데이터로 집계 로직 검증. 네트워크 없이 돈다.
//   node scripts/test.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import YAML from 'yaml';
import { aggregate, buildRanking, heatmap, streaks, activityStatus, dayShift, toDay } from './aggregate.mjs';
import { emitAll } from './emit.mjs';

const ROOT = new URL('..', import.meta.url).pathname;
const yml = async (n) => YAML.parse(await readFile(join(ROOT, 'config', n), 'utf8'));

const TODAY = '2026-07-27';
const at = (day, h = 12) => `${day}T${String(h - 9).padStart(2, '0')}:00:00Z`; // KST h시

let pass = 0;
const ok = (name, fn) => { fn(); console.log(`  ✓ ${name}`); pass++; };

// ── 모의 저장소 ──────────────────────────────────────────────────────
function mockRepo(fullName, commits, pulls = [], issues = []) {
  return {
    fullName,
    meta: {
      description: 'mock', stargazerCount: 10, forkCount: 2, isArchived: false,
      pushedAt: at(TODAY), primaryLanguage: { name: 'Go' },
      defaultBranchRef: { name: 'main' }, issues: { totalCount: issues.length },
      pullRequests: { totalCount: 0 }, licenseInfo: { spdxId: 'MIT' },
      languages: { totalSize: 100, edges: [{ size: 100, node: { name: 'Go', color: '#0af' } }] },
    },
    commits, pulls, issues,
  };
}
const C = (login, day, { add = 10, del = 5, merge = false, msg = 'work', hour = 12 } = {}) => ({
  oid: Math.random().toString(16).slice(2).padEnd(40, '0'),
  messageHeadline: msg, committedDate: at(day, hour),
  additions: add, deletions: del, changedFilesIfAvailable: 1,
  parents: { totalCount: merge ? 2 : 1 },
  author: { email: `${login}@x.com`, name: login, user: { login, databaseId: 1 } },
});
const PR = (login, day, { merged = true, reviews = [] } = {}) => ({
  number: Math.floor(Math.random() * 999), title: 'pr', state: merged ? 'MERGED' : 'OPEN',
  isDraft: false, createdAt: at(day), mergedAt: merged ? at(dayShift(day, 1)) : null,
  closedAt: null, updatedAt: at(dayShift(day, 1)), additions: 20, deletions: 3, changedFiles: 2,
  author: { login }, mergedBy: { login },
  reviews: { nodes: reviews.map(([r, d]) => ({ author: { login: r }, state: 'APPROVED', submittedAt: at(d) })) },
});

// ── 시나리오 ────────────────────────────────────────────────────────
const d = (n) => dayShift(TODAY, -n);

const repos = [
  mockRepo('hsf-foundation/core-engine', [
    // handong-dev: 최근 활발
    ...Array.from({ length: 8 }, (_, i) => C('handong-dev', d(i), { hour: 15 })),
    // 캡 테스트: 하루에 30커밋 (cap 20)
    ...Array.from({ length: 30 }, () => C('handong-dev', d(10))),
    // merge commit — 제외되어야 함
    C('handong-dev', d(3), { merge: true, msg: 'Merge branch main' }),
    // 봇 — 제외되어야 함
    C('dependabot[bot]', d(1), { add: 30000 }),
    // additions 캡 테스트: 5000줄 커밋 → 2000으로 잘림
    C('handong-dev', d(20), { add: 5000, del: 0 }),
    // asokolov: 예전엔 활발, 최근 뚝 (slowing)
    ...Array.from({ length: 20 }, (_, i) => C('asokolov', d(31 + i))),
    C('asokolov', d(5)),
    // dk_coder: 70일째 잠수 (dormant)
    ...Array.from({ length: 5 }, (_, i) => C('dk_coder', d(70 + i))),
    // members.yml에 없는 외부인
    C('random-outsider', d(2)),
  ], [
    PR('handong-dev', d(4), { reviews: [['asokolov', d(3)], ['handong-dev', d(3)]] }), // 셀프리뷰 1개 포함
    PR('mrialoz', d(6), { merged: false, reviews: [['handong-dev', d(5)]] }),
  ], [
    { number: 1, title: 'bug', state: 'OPEN', createdAt: at(d(2)), closedAt: null,
      updatedAt: at(d(2)), author: { login: 'mrialoz' }, labels: { nodes: [] } },
  ]),
];

const [projects, members, policy, terms] = await Promise.all(
  ['projects.yml', 'members.yml', 'score-policy.yml', 'terms.yml'].map(yml));

console.log('\n집계 로직 검증\n');
const agg = aggregate(repos, projects, members, policy);
const P = (l) => agg.people.get(l);

ok('merge commit 제외', () => {
  const total = [...P('handong-dev').daily.values()].reduce((a, x) => a + x.commits, 0);
  assert.equal(total, 8 + 30 + 1, `merge 포함 시 40, 실제 ${total}`);
});

ok('봇 제외', () => {
  assert.equal(P('dependabot[bot]').excluded, true);
  const rows = buildRanking(agg.people, '1970-01-01', TODAY, policy);
  assert.equal(rows.find((r) => r.login === 'dependabot[bot]'), undefined);
});

ok('additions 캡 (5000 → 2000)', () => {
  const day = P('handong-dev').daily.get(d(20));
  assert.equal(day.additions, 2000);
});

ok('일일 커밋 캡 (30 → 20점)', () => {
  const day = P('handong-dev').daily.get(d(10));
  assert.equal(day.commits, 30, '원본 카운트는 보존');
  // 그 날 하루만 점수화
  const only = { ...P('handong-dev'), daily: new Map([[d(10), day]]) };
  const rows = buildRanking(new Map([['x', only]]), d(10), d(10), policy);
  assert.equal(rows[0].score, 20 * policy.weights.commit, `캡 미적용 시 30점, 실제 ${rows[0].score}`);
});

ok('셀프리뷰 제외', () => {
  const reviews = [...P('handong-dev').daily.values()].reduce((a, x) => a + x.reviews, 0);
  assert.equal(reviews, 1, `handong-dev는 남의 PR 리뷰 1건만 (실제 ${reviews})`);
  assert.equal([...P('asokolov').daily.values()].reduce((a, x) => a + x.reviews, 0), 1);
});

ok('미등록 기여자 감지', () => {
  assert.ok(agg.unknown.some((u) => u.login === 'random-outsider'));
});

ok('랭킹 정렬 + 동점자 처리', () => {
  const rows = buildRanking(agg.people, '1970-01-01', TODAY, policy);
  assert.equal(rows[0].login, 'handong-dev');
  for (let i = 1; i < rows.length; i++) assert.ok(rows[i - 1].score >= rows[i].score);
  const ranks = rows.map((r) => r.rank);
  assert.deepEqual([...ranks].sort((a, b) => a - b), ranks, '등수는 오름차순');
});

ok('점수 = 가중치 합 (수동 검산)', () => {
  const w = policy.weights;
  const rows = buildRanking(agg.people, '1970-01-01', TODAY, policy);
  const a = rows.find((r) => r.login === 'asokolov');
  // asokolov: 커밋 21, PR 0, 리뷰 1
  const expect = 21 * w.commit + 1 * w.review;
  assert.equal(a.score, expect, `기대 ${expect}, 실제 ${a.score}`);
});

ok('히트맵 365칸 + 합계 일치', () => {
  const hm = heatmap(P('handong-dev'), TODAY, 365);
  assert.equal(hm.counts.length, 365);
  assert.equal(hm.total, hm.counts.reduce((a, b) => a + b, 0));
  assert.ok(hm.counts[364] > 0, '오늘 칸이 마지막');
});

ok('스트릭 계산', () => {
  assert.deepEqual(streaks([0, 1, 1, 1, 0, 1, 1]), { longest: 3, current: 2 });
  assert.deepEqual(streaks([0, 0, 0]), { longest: 0, current: 0 });
  const hm = heatmap(P('handong-dev'), TODAY, 365);
  assert.ok(streaks(hm.counts).current >= 8, '최근 8일 연속 커밋');
});

ok('활동 상태: active / slowing / dormant', () => {
  assert.equal(activityStatus(P('handong-dev'), TODAY, policy).status, 'active');
  assert.equal(activityStatus(P('asokolov'), TODAY, policy).status, 'slowing');
  assert.equal(activityStatus(P('dk_coder'), TODAY, policy).status, 'dormant');
});

ok('KST 날짜 경계 (UTC로 자르면 어긋남)', () => {
  // KST 2026-07-27 01:00 = UTC 2026-07-26 16:00
  assert.equal(toDay('2026-07-26T16:00:00Z'), '2026-07-27');
  assert.equal(toDay('2026-07-26T14:59:00Z'), '2026-07-26');
});

// ── JSON 생성까지 ────────────────────────────────────────────────────
const out = join(ROOT, 'data');
const written = await emitAll(out, {
  agg, repos, projects, terms, policy, today: TODAY,
  generatedAt: new Date().toISOString(),
});

ok('JSON 생성 + 크기 점검', () => {
  assert.ok(written.length >= 8);
  const big = written.filter((w) => w.bytes > 200_000);
  assert.equal(big.length, 0, `200KB 초과 파일: ${big.map((b) => b.rel)}`);
});

const overview = JSON.parse(await readFile(join(out, 'overview.json'), 'utf8'));
ok('overview.attention 이 이탈자를 잡아냄', () => {
  assert.ok(overview.attention.slowing.some((x) => x.login === 'asokolov'));
  assert.ok(overview.attention.dormant.some((x) => x.login === 'dk_coder'));
});

const me = JSON.parse(await readFile(join(out, 'contributors/handong-dev.json'), 'utf8'));
ok('개인 페이지에 프로필+히트맵+커밋이력', () => {
  assert.equal(me.profile.nameKo, '김한동');
  assert.equal(me.profile.department, 'Computer Science Engineering');
  assert.equal(me.heatmap.counts.length, 365);
  assert.ok(me.recentCommits.length > 0);
  assert.ok(me.totals.additions > 0);
});

console.log(`\n생성 파일:`);
for (const w of written.sort((a, b) => b.bytes - a.bytes).slice(0, 10)) {
  console.log(`  ${String(Math.round(w.bytes / 1024)).padStart(4)}KB  ${w.rel}`);
}
console.log(`\n${pass}개 검증 통과\n`);
