#!/usr/bin/env node
// members.yml 자동 생성 / 갱신
//
//   node scripts/bootstrap-members.mjs                    미리보기만
//   node scripts/bootstrap-members.mjs --write            config/members.yml 갱신
//   node scripts/bootstrap-members.mjs --org hsf-foundation --write
//
// GitHub API로 채울 수 있는 것 / 없는 것을 명확히 나눈다.
//
//   [자동] login, 이름(프로필에 설정한 값), 아바타, 위치, 소속, bio, 가입일
//   [자동] 학번  ← 커밋 author 이메일이 22400437@handong.ac.kr 형태면 추출된다
//   [추정] role, primaryProject  ← 행동에서 유추. 반드시 사람이 확인해야 한다
//   [수기] 한글 이름 확정, 학과, 기술 태그  ← GitHub에 존재하지 않는 정보
//
// 기존 members.yml의 수기 입력 값은 절대 덮어쓰지 않는다.

import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import YAML from 'yaml';
import { GitHub, fetchRepo } from './github.mjs';
import { toDay } from './aggregate.mjs';

const ROOT = new URL('..', import.meta.url).pathname;
const arg = (k, def) => {
  const i = process.argv.indexOf(`--${k}`);
  return i >= 0 ? (process.argv[i + 1]?.startsWith('--') ? true : process.argv[i + 1] ?? true) : def;
};

// 학교 이메일에서 학번을 뽑는다. 한동대 커밋은 대개 이 형태다.
const EMAIL_DOMAIN = String(arg('email-domain', 'handong.ac.kr'));
const STUDENT_NO = new RegExp(`^(\\d{6,10})@${EMAIL_DOMAIN.replace(/\./g, '\\.')}$`, 'i');

// 사람이 채워야 하는 필드 — 이미 값이 있으면 건드리지 않는다
const MANUAL = ['nameKo', 'studentNo', 'department', 'skills', 'role',
                'primaryProject', 'status', 'joinedOn', 'aliases', 'public'];

const Q_ORG_MEMBERS = `
query($org:String!, $cursor:String) {
  rateLimit { cost remaining }
  organization(login:$org) {
    membersWithRole(first:100, after:$cursor) {
      pageInfo { hasNextPage endCursor }
      nodes { login }
    }
  }
}`;

const USER_FIELDS = `
  login name email location company bio avatarUrl websiteUrl createdAt databaseId
  socialAccounts(first:5) { nodes { provider url } }
`;

function batchUserQuery(logins) {
  const parts = logins.map((l, i) => `u${i}: user(login:${JSON.stringify(l)}) { ${USER_FIELDS} }`);
  return `query { rateLimit { cost remaining } ${parts.join('\n')} }`;
}

async function main() {
  const [projects, policyRaw, existingRaw] = await Promise.all([
    readFile(join(ROOT, 'config/projects.yml'), 'utf8'),
    readFile(join(ROOT, 'config/score-policy.yml'), 'utf8'),
    readFile(join(ROOT, 'config/members.yml'), 'utf8').catch(() => '[]'),
  ]);
  const projectList = YAML.parse(projects);
  const existing = YAML.parse(existingRaw) ?? [];
  const byLogin = new Map(existing.map((m) => [m.github.toLowerCase(), m]));

  const gh = new GitHub(process.env.GITHUB_TOKEN ?? process.env.GH_TOKEN);
  const days = Number(arg('days', 400));
  const since = new Date(Date.now() - days * 86400000).toISOString();

  // ── 1) 후보 login 수집 ──────────────────────────────────────────
  const found = new Map();  // login → { commits, prs, reviews, merges, projects:Map, emails:Set, first, last }
  const touch = (login) => {
    if (!login) return null;
    let f = found.get(login);
    if (!f) {
      f = { login, commits: 0, prs: 0, reviews: 0, merges: 0,
            projects: new Map(), emails: new Set(), first: null, last: null };
      found.set(login, f);
    }
    return f;
  };
  const seen = (f, day) => {
    if (!f.first || day < f.first) f.first = day;
    if (!f.last || day > f.last) f.last = day;
  };

  // (a) org 멤버 — members:read 권한이 있을 때만. 없으면 조용히 넘어간다.
  const org = arg('org', projectList[0]?.repos?.[0]?.split('/')[0]);
  if (org) {
    try {
      for await (const n of gh.paginate(Q_ORG_MEMBERS, { org }, (d) => d.organization?.membersWithRole)) {
        touch(n.login);
      }
      console.log(`▶ org ${org} 멤버 ${found.size}명`);
    } catch (e) {
      console.log(`▶ org 멤버 조회 생략 (${e.message.slice(0, 60)}…)`);
      console.log(`  토큰에 Organization permissions → Members: Read 를 주면 활동이 없는 인원도 잡힙니다.`);
    }
  }

  // (b) 실제 활동에서 발견 — 이쪽이 더 확실하다
  console.log(`▶ 저장소 스캔 (최근 ${days}일)`);
  for (const p of projectList) {
    for (const full of p.repos) {
      let r;
      try { r = await fetchRepo(gh, full, since); }
      catch (e) { console.error(`  ✗ ${full}: ${e.message}`); continue; }

      for (const c of r.commits) {
        const login = c.author?.user?.login;
        const f = touch(login);
        if (!f) continue;
        f.commits++;
        f.projects.set(p.slug, (f.projects.get(p.slug) ?? 0) + 1);
        if (c.author?.email) f.emails.add(c.author.email);
        seen(f, toDay(c.committedDate));
      }
      for (const pr of r.pulls) {
        const f = touch(pr.author?.login);
        if (f) { f.prs++; seen(f, toDay(pr.createdAt)); }
        // 남의 PR을 머지할 수 있다 = 사실상 maintainer
        const mb = pr.mergedBy?.login;
        if (mb && mb !== pr.author?.login) {
          const m = touch(mb);
          if (m) { m.merges++; seen(m, toDay(pr.mergedAt)); }
        }
        for (const rv of pr.reviews?.nodes ?? []) {
          if (!rv.author?.login || rv.author.login === pr.author?.login) continue;
          const f2 = touch(rv.author.login);
          if (f2) { f2.reviews++; seen(f2, toDay(rv.submittedAt)); }
        }
      }
      console.log(`  ✓ ${full}`);
    }
  }

  const logins = [...found.keys()].filter((l) => !l.endsWith('[bot]'));
  console.log(`▶ 후보 ${logins.length}명`);

  // ── 2) 프로필 일괄 조회 (50명씩 alias 배치) ─────────────────────
  const profiles = new Map();
  for (let i = 0; i < logins.length; i += 50) {
    const chunk = logins.slice(i, i + 50);
    const data = await gh.query(batchUserQuery(chunk));
    chunk.forEach((l, j) => { const u = data[`u${j}`]; if (u) profiles.set(l, u); });
  }

  // ── 3) 병합 ─────────────────────────────────────────────────────
  const out = [];
  const report = { added: [], updated: [], review: [], kept: 0 };

  for (const login of logins) {
    const f = found.get(login);
    const u = profiles.get(login) ?? {};
    const prev = byLogin.get(login.toLowerCase());

    // 학번: 커밋 이메일 → 프로필 공개 이메일 순으로 탐색
    let studentNo = null, schoolEmail = null;
    for (const e of [...f.emails, u.email].filter(Boolean)) {
      const m = String(e).match(STUDENT_NO);
      if (m) { studentNo = m[1]; schoolEmail = e; break; }
    }

    // 역할 추정 — 어디까지나 제안이다
    const guessedRole = f.merges >= 3 ? 'maintainer'
                      : f.commits >= 10 || f.prs >= 5 ? 'committer'
                      : 'contributor';
    const guessedProject = [...f.projects].sort((a, b) => b[1] - a[1])[0]?.[0] ?? null;

    const auto = {
      github: login,
      nameEn: u.name ?? null,
      avatarUrl: u.avatarUrl ?? null,
      location: u.location ?? null,
      company: u.company ?? null,
      bio: u.bio ?? null,
      githubId: u.databaseId ?? null,
      githubCreatedAt: u.createdAt ? u.createdAt.slice(0, 10) : null,
    };

    const merged = { ...auto };
    // 수기 필드는 기존 값 우선
    for (const k of MANUAL) if (prev?.[k] !== undefined) merged[k] = prev[k];

    // 비어 있는 칸만 자동 추정치로 채운다
    merged.nameKo ??= u.name ?? login;
    merged.studentNo ??= studentNo ?? null;
    merged.department ??= null;
    merged.role ??= guessedRole;
    merged.primaryProject ??= guessedProject;
    merged.status ??= 'active';
    merged.joinedOn ??= f.first ?? null;

    // 커밋 이메일을 alias로 보존 — 나중에 계정이 바뀌어도 매칭된다
    const emails = new Set([...(prev?.aliases?.emails ?? []), ...f.emails]
      .filter((e) => e && !e.endsWith('@users.noreply.github.com')));
    if (emails.size) merged.aliases = { ...(prev?.aliases ?? {}), emails: [...emails].sort() };

    // 사람이 확인해야 하는 항목
    const todo = [];
    if (!merged.department) todo.push('department');
    if (!merged.studentNo) todo.push('studentNo');
    if (!prev) todo.push('role', 'nameKo');
    if (todo.length) { merged.needsReview = todo; report.review.push(`${login}: ${todo.join(', ')}`); }
    else delete merged.needsReview;

    // 키 순서 정리
    out.push(order(merged));
    if (!prev) report.added.push(`${login}${studentNo ? ` (학번 ${studentNo})` : ''}`);
    else if (JSON.stringify(order(prev)) !== JSON.stringify(order(merged))) report.updated.push(login);
    else report.kept++;
  }

  // members.yml에만 있고 활동이 없는 사람 (졸업생 등) 은 유지
  for (const m of existing) {
    if (!found.has(m.github)) { out.push(order(m)); report.kept++; }
  }
  out.sort((a, b) => a.github.localeCompare(b.github));

  // ── 4) 출력 ─────────────────────────────────────────────────────
  console.log(`\n신규 ${report.added.length} / 갱신 ${report.updated.length} / 유지 ${report.kept}`);
  if (report.added.length) console.log(`  + ${report.added.slice(0, 20).join('\n  + ')}`);
  if (report.review.length) {
    console.log(`\n사람이 채워야 하는 항목 ${report.review.length}건:`);
    for (const r of report.review.slice(0, 30)) console.log(`  · ${r}`);
  }

  const yaml = HEADER + YAML.stringify(out, { lineWidth: 100 });
  if (arg('write', false)) {
    await writeFile(join(ROOT, 'config/members.yml'), yaml);
    console.log(`\n✓ config/members.yml 갱신. git diff 로 확인 후 PR 하세요.`);
  } else {
    await writeFile(join(ROOT, 'config/members.generated.yml'), yaml);
    console.log(`\n미리보기를 config/members.generated.yml 에 썼습니다.`);
    console.log(`적용하려면 --write 를 붙이세요.`);
  }
  console.log(`GraphQL 요청 ${gh.calls}회, 포인트 ${gh.cost}`);
}

const KEY_ORDER = ['github', 'nameKo', 'nameEn', 'studentNo', 'department', 'location',
  'company', 'bio', 'skills', 'role', 'primaryProject', 'status', 'joinedOn',
  'avatarUrl', 'githubId', 'githubCreatedAt', 'aliases', 'public', 'needsReview'];

function order(o) {
  const r = {};
  for (const k of KEY_ORDER) if (o[k] !== undefined && o[k] !== null) r[k] = o[k];
  for (const k of Object.keys(o)) if (!(k in r) && o[k] != null) r[k] = o[k];
  return r;
}

const HEADER = `# 이 파일은 scripts/bootstrap-members.mjs 로 자동 생성/갱신됩니다.
# 손으로 고친 값(nameKo, department, skills, role, primaryProject, status)은
# 다시 실행해도 덮어쓰이지 않습니다.
#
# needsReview 가 붙은 항목은 사람이 확인해야 합니다. 확인 후 그 줄을 지우세요.
# GitHub에 없는 정보: 학과, 확정된 한글 이름, 기술 태그, 공식 역할
#
`;

main().catch((e) => { console.error(e); process.exit(1); });
