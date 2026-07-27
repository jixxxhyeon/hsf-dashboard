// 화면별 JSON 생성.
//
// 핵심 원칙: 한 파일에 다 넣지 마라.
// 156명 × 365일을 통짜 JSON으로 만들면 수 MB가 되고 첫 로딩이 죽는다.
// 화면 단위로 "이미 계산이 끝난" 파일을 쪼개서 만든다. 프론트는 계산하지 않는다.

import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import {
  buildRanking, heatmap, streaks, activityStatus, projectStats,
  avgMergeHours, healthScore, sumRange, dayShift, toDay,
} from './aggregate.mjs';

async function put(root, rel, obj) {
  const path = join(root, rel);
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, JSON.stringify(obj));
  return { rel, bytes: Buffer.byteLength(JSON.stringify(obj)) };
}

export async function emitAll(outDir, ctx) {
  const { agg, repos, projects, terms, policy, today, generatedAt } = ctx;
  const { people, projectDaily, activities, hourHist, unknown } = agg;
  const written = [];
  const W = async (rel, obj) => written.push(await put(outDir, rel, obj));

  const d30 = dayShift(today, -29);
  const d60 = dayShift(today, -59);
  const d7 = dayShift(today, -6);
  const d14 = dayShift(today, -13);
  const monthStart = today.slice(0, 8) + '01';
  const prevMonthEnd = dayShift(monthStart, -1);
  const prevMonthStart = prevMonthEnd.slice(0, 8) + '01';

  const active = [...people.values()].filter((p) => !p.excluded);
  const totalIn = (from, to, field) =>
    active.reduce((a, p) => a + sumRange(p, from, to)[field], 0);

  // ── meta.json ────────────────────────────────────────────────────
  await W('meta.json', {
    generatedAt, today,
    policyVersion: policy.version,
    terms: terms.map((t) => ({ code: t.code, name: t.name, current: !!t.current })),
    projects: projects.map((p) => ({ slug: p.slug, name: p.name })),
    repoCount: repos.length,
    memberCount: active.filter((p) => p.member).length,
    // 미등록 기여자 — members.yml에 추가해야 이름이 뜬다. 관리자 알림용.
    unregistered: unknown.slice(0, 50),
  });

  // ── overview.json ────────────────────────────────────────────────
  const curCommits = totalIn(monthStart, today, 'commits');
  const prevCommits = totalIn(prevMonthStart, prevMonthEnd, 'commits');
  const wk = totalIn(d7, today, 'commits');
  const wkPrev = totalIn(d14, dayShift(d7, -1), 'commits');
  const activeCount = active.filter((p) => sumRange(p, monthStart, today).commits > 0).length;

  const openIssues = repos.reduce((a, r) => a + r.issues.filter((i) => i.state === 'OPEN').length, 0);
  const newIssues = repos.reduce(
    (a, r) => a + r.issues.filter((i) => i.state === 'OPEN' && toDay(i.createdAt) >= d30).length, 0);

  // 관리 목적의 핵심: 이탈 감지
  const attention = { slowing: [], dormant: [] };
  for (const p of active) {
    if (!p.member) continue;
    const st = activityStatus(p, today, policy);
    if (st.status === 'active') continue;
    attention[st.status].push({
      login: p.login, nameKo: p.member.nameKo,
      primaryProject: p.member.primaryProject ?? null,
      recent: st.recent, prev: st.prev, dropPct: st.dropPct,
      lastActive: [...p.daily.keys()].sort().pop() ?? null,
    });
  }
  attention.slowing.sort((a, b) => b.dropPct - a.dropPct);
  attention.dormant.sort((a, b) => (a.lastActive ?? '') < (b.lastActive ?? '') ? 1 : -1);

  const currentTerm = terms.find((t) => t.current) ?? terms.at(-1);
  const termRanking = buildRanking(people, currentTerm.startsOn, currentTerm.endsOn, policy);

  const velocity = [];
  for (let d = d30; d <= today; d = dayShift(d, 1)) {
    let c = 0;
    for (const pd of projectDaily.values()) c += pd.get(d)?.commits ?? 0;
    velocity.push({ day: d, commits: c });
  }

  await W('overview.json', {
    generatedAt,
    kpi: {
      totalCommits: { value: curCommits, deltaPct: pct(curCommits, prevCommits), window: 'month' },
      activeContributors: { value: activeCount, window: 'month' },
      openIssues: { value: openIssues, newLast30: newIssues },
      weeklyGrowthPct: pct(wk, wkPrev),
    },
    topContributors: termRanking.slice(0, 5).map((r) => ({
      ...pickCard(r),
      spark: last5(people, r.login, today),
    })),
    velocity,
    recentActivities: activities.slice(0, 30).map((a) => ({
      ...a, actorName: nameOf(people, a.actor),
    })),
    attention,   // ← 화면에 없지만 "현황 관리"에는 이게 제일 중요하다
  });

  // ── rankings/*.json ──────────────────────────────────────────────
  for (const t of terms) {
    const rows = buildRanking(people, t.startsOn, t.endsOn, policy);
    await W(`rankings/${t.code}.json`, {
      generatedAt, term: t, policyVersion: policy.version,
      stats: {
        totalReviews: rows.reduce((a, r) => a + r.reviews, 0),
        avgMergeHours: avgMergeHours(repos, t.startsOn, t.endsOn),
        contributors: rows.length,
      },
      peakHours: hourHist,
      rows,
    });
  }
  const allRows = buildRanking(people, '1970-01-01', today, policy);
  await W('rankings/all-time.json', {
    generatedAt, term: null, policyVersion: policy.version,
    stats: {
      totalReviews: allRows.reduce((a, r) => a + r.reviews, 0),
      avgMergeHours: avgMergeHours(repos, '1970-01-01', today),
      contributors: allRows.length,
    },
    peakHours: hourHist,
    rows: allRows,
  });

  // ── projects.json + projects/{slug}.json ─────────────────────────
  const summaries = [];
  for (const proj of projects) {
    const projRepos = repos.filter((r) => proj.repos.includes(r.fullName));
    const st = projectStats(proj.slug, projectDaily, people, projRepos, d30, today);
    const c30 = st.series.reduce((a, s) => a + s.commits, 0);
    const contrib30 = new Set();
    for (const p of active) {
      if ((p.projects.get(proj.slug)?.commits ?? 0) > 0 &&
          sumRange(p, d30, today).commits > 0) contrib30.add(p.login);
    }
    const open = projRepos.reduce((a, r) => a + r.issues.filter((i) => i.state === 'OPEN').length, 0);
    const merge = avgMergeHours(projRepos, d30, today);
    const health = healthScore({
      commitsLast30: c30, contributorsLast30: contrib30.size,
      busFactor: st.busFactor, openIssues: open, avgMergeH: merge,
    });

    summaries.push({
      slug: proj.slug, name: proj.name, description: proj.description,
      category: proj.category, status: proj.status,
      repoCount: projRepos.length, contributors: st.contributors.length,
      commitsLast30: c30, openIssues: open, busFactor: st.busFactor,
      health: health.score,
      stars: projRepos.reduce((a, r) => a + (r.meta.stargazerCount ?? 0), 0),
    });

    // GitHub 저장소 페이지처럼 보이는 상세
    await W(`projects/${proj.slug}.json`, {
      generatedAt,
      slug: proj.slug, name: proj.name, description: proj.description,
      category: proj.category, status: proj.status, startedOn: proj.startedOn ?? null,
      health,
      repos: projRepos.map((r) => ({
        fullName: r.fullName, description: r.meta.description,
        language: r.meta.primaryLanguage?.name ?? null,
        languages: (r.meta.languages?.edges ?? []).map((e) => ({
          name: e.node.name, color: e.node.color,
          pct: Math.round((e.size / (r.meta.languages.totalSize || 1)) * 1000) / 10,
        })),
        stars: r.meta.stargazerCount, forks: r.meta.forkCount,
        openIssues: r.meta.issues?.totalCount ?? 0,
        openPRs: r.meta.pullRequests?.totalCount ?? 0,
        license: r.meta.licenseInfo?.spdxId ?? null,
        defaultBranch: r.meta.defaultBranchRef?.name ?? 'main',
        pushedAt: r.meta.pushedAt, archived: r.meta.isArchived,
        url: `https://github.com/${r.fullName}`,
      })),
      velocity: st.series,
      contributors: st.contributors,
      busFactor: st.busFactor,
      avgMergeHours: merge,
      recentActivities: activities.filter((a) => a.project === proj.slug).slice(0, 20)
        .map((a) => ({ ...a, actorName: nameOf(people, a.actor) })),
    });
  }
  summaries.sort((a, b) => b.commitsLast30 - a.commitsLast30);
  await W('projects.json', { generatedAt, projects: summaries });

  // ── contributors.json + contributors/{login}.json ────────────────
  const index = [];
  for (const p of active) {
    const hm = heatmap(p, today, 365);
    const sk = streaks(hm.counts);
    const st = activityStatus(p, today, policy);
    const all = sumRange(p, '1970-01-01', today);
    const myRank = allRows.find((r) => r.login === p.login);

    index.push({
      login: p.login, nameKo: p.member?.nameKo ?? p.login,
      nameEn: p.member?.nameEn ?? null,
      department: p.member?.department ?? null,
      role: p.member?.role ?? 'contributor',
      primaryProject: p.member?.primaryProject ?? null,
      registered: !!p.member,
      status: st.status,
      contributions: hm.total,
      commits: all.commits, prs: all.prsOpened, reviews: all.reviews,
      rank: myRank?.rank ?? null, score: myRank?.score ?? 0,
    });

    await W(`contributors/${p.login.toLowerCase()}.json`, {
      generatedAt,
      profile: {
        login: p.login,
        nameKo: p.member?.nameKo ?? p.login,
        nameEn: p.member?.nameEn ?? null,
        // 학번은 기본으로 내보내지 않는다. 화면에서 쓰지 않고, data/ 를 공개 저장소에
        // 올리는 순간 개인정보가 된다. 정말 필요하면 EMIT_STUDENT_NO=1 로 켠다.
        studentNo: process.env.EMIT_STUDENT_NO === '1' ? p.member?.studentNo ?? null : null,
        department: p.member?.department ?? null,
        location: p.member?.location ?? null,
        skills: p.member?.skills ?? [],
        role: p.member?.role ?? 'contributor',
        joinedOn: p.member?.joinedOn ?? null,
        githubUrl: `https://github.com/${p.login}`,
        registered: !!p.member,
      },
      status: st,
      totals: {
        contributions: hm.total,
        commits: all.commits, prsOpened: all.prsOpened, prsMerged: all.prsMerged,
        reviews: all.reviews, issuesOpened: all.issuesOpened,
        additions: all.additions, deletions: all.deletions,
        longestStreak: sk.longest, currentStreak: sk.current,
        activeRepos: new Set(p.commits.map((c) => c.repo)).size,
      },
      rank: myRank ? { overall: myRank.rank, score: myRank.score } : null,
      heatmap: hm,                     // {start, counts[365]} — 약 1KB
      projects: [...p.projects].map(([slug, c]) => ({
        slug, name: projects.find((x) => x.slug === slug)?.name ?? slug,
        commits: c.commits, prs: c.prsOpened, reviews: c.reviews,
        additions: c.additions, deletions: c.deletions,
      })).sort((a, b) => b.commits - a.commits),
      monthly: monthlySeries(p, 12, today),
      recentCommits: p.commits.slice(0, 20),
    });
  }
  index.sort((a, b) => b.score - a.score);
  await W('contributors.json', { generatedAt, contributors: index });

  return written;
}

// ── helpers ────────────────────────────────────────────────────────
const pct = (cur, prev) => (prev > 0 ? Math.round(((cur - prev) / prev) * 1000) / 10 : null);

const pickCard = (r) => ({
  login: r.login, nameKo: r.nameKo, role: r.role, rank: r.rank,
  primaryProject: r.primaryProject, score: r.score,
  commits: r.commits, prs: r.prs, reviews: r.reviews,
});

function nameOf(people, login) {
  const p = people.get(String(login).toLowerCase());
  return p?.member?.nameKo ?? login;
}

function last5(people, login, today) {
  const p = people.get(login.toLowerCase());
  const out = [];
  for (let i = 4; i >= 0; i--) {
    const d = p?.daily.get(dayShift(today, -i));
    out.push(d ? d.commits + d.prsOpened + d.reviews : 0);
  }
  return out;
}

function monthlySeries(p, months, today) {
  const out = [];
  const [y, m] = today.split('-').map(Number);
  for (let i = months - 1; i >= 0; i--) {
    const dt = new Date(Date.UTC(y, m - 1 - i, 1));
    const key = dt.toISOString().slice(0, 7);
    const t = { month: key, commits: 0, prs: 0, reviews: 0 };
    for (const [day, d] of p.daily) {
      if (day.slice(0, 7) !== key) continue;
      t.commits += d.commits; t.prs += d.prsOpened; t.reviews += d.reviews;
    }
    out.push(t);
  }
  return out;
}
