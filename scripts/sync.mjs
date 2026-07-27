#!/usr/bin/env node
// 진입점. GitHub Actions가 1시간마다 이 파일을 실행한다.
//   node scripts/sync.mjs            최근 400일
//   node scripts/sync.mjs --days 90  기간 지정
//   node scripts/sync.mjs --dry      수집만 하고 JSON은 쓰지 않음

import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import YAML from 'yaml';
import { GitHub, fetchRepo } from './github.mjs';
import { aggregate, toDay } from './aggregate.mjs';
import { emitAll } from './emit.mjs';

const ROOT = new URL('..', import.meta.url).pathname;
const arg = (k, def) => {
  const i = process.argv.indexOf(`--${k}`);
  return i >= 0 ? (process.argv[i + 1] ?? true) : def;
};

const yml = async (name) => YAML.parse(await readFile(join(ROOT, 'config', name), 'utf8'));

async function main() {
  const t0 = Date.now();
  const [projects, members, policy, terms] = await Promise.all(
    ['projects.yml', 'members.yml', 'score-policy.yml', 'terms.yml'].map(yml));

  const days = Number(arg('days', 400));
  const since = new Date(Date.now() - days * 86400000).toISOString();
  const today = toDay(new Date().toISOString());

  const gh = new GitHub(process.env.GITHUB_TOKEN ?? process.env.GH_TOKEN);
  const allRepos = projects.flatMap((p) => p.repos);
  console.log(`▶ 저장소 ${allRepos.length}개, 최근 ${days}일 수집`);

  const repos = [];
  for (const full of allRepos) {
    try {
      const r = await fetchRepo(gh, full, since);
      repos.push(r);
      console.log(`  ✓ ${full}  commits=${r.commits.length} prs=${r.pulls.length} issues=${r.issues.length}`);
    } catch (e) {
      // 저장소 하나가 실패해도 나머지는 계속 — 부분 실패로 전체 대시보드를 죽이지 않는다
      console.error(`  ✗ ${full}: ${e.message}`);
    }
  }
  if (!repos.length) throw new Error('수집된 저장소가 없습니다. 토큰 권한을 확인하세요.');

  const agg = aggregate(repos, projects, members, policy);
  console.log(`▶ 기여자 ${[...agg.people.values()].filter((p) => !p.excluded).length}명 집계`);
  if (agg.unknown.length) {
    console.log(`▶ members.yml 미등록 ${agg.unknown.length}명: ` +
      agg.unknown.slice(0, 10).map((u) => `${u.login}(${u.count})`).join(', '));
  }

  if (arg('dry', false)) { console.log('— dry run, JSON 생략'); return; }

  const written = await emitAll(join(ROOT, 'data'), {
    agg, repos, projects, terms, policy, today,
    generatedAt: new Date().toISOString(),
  });

  const total = written.reduce((a, w) => a + w.bytes, 0);
  console.log(`▶ JSON ${written.length}개 / ${(total / 1024).toFixed(0)}KB`);
  for (const w of written.filter((w) => w.bytes > 100_000)) {
    console.warn(`  ! ${w.rel} 가 ${(w.bytes / 1024).toFixed(0)}KB — 분할을 검토하세요`);
  }
  console.log(`▶ GraphQL 요청 ${gh.calls}회, 포인트 ${gh.cost} / 5000  (${((Date.now() - t0) / 1000).toFixed(1)}s)`);
}

main().catch((e) => { console.error(e); process.exit(1); });
