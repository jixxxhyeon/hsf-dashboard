// 집계 계층 — 순수 함수. 네트워크를 타지 않으므로 단위 테스트가 가능하다.
//
// raw 이벤트 → 사람×프로젝트×날짜 일별 집계 → 점수 → 랭킹 → 화면별 데이터
// 옛 DB 설계의 contribution_daily 테이블과 정확히 같은 모양을 메모리에서 만든다.
// 나중에 Postgres로 옮길 때 이 구조를 그대로 INSERT하면 된다.

const TZ = 'Asia/Seoul';

/** UTC ISO → 한국 날짜 'YYYY-MM-DD'.
 *  UTC로 자르면 밤 9시 이후 커밋이 전날로 밀려서 히트맵이 어긋난다. */
export function toDay(iso, tz = TZ) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(new Date(iso));
}

export function hourOf(iso, tz = TZ) {
  return Number(new Intl.DateTimeFormat('en-GB', {
    timeZone: tz, hour: '2-digit', hour12: false,
  }).format(new Date(iso)));
}

const EMPTY = () => ({
  commits: 0, prsOpened: 0, prsMerged: 0, reviews: 0,
  issuesOpened: 0, issuesClosed: 0, additions: 0, deletions: 0,
});

const addInto = (a, b) => { for (const k of Object.keys(a)) a[k] += b[k] ?? 0; return a; };

export const dayShift = (day, n) => {
  const d = new Date(`${day}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};

export const daysBetween = (from, to) =>
  Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86400000);

// ─────────────────────────────────────────────────────────────────────
// 1. 정규화 + 일별 집계
// ─────────────────────────────────────────────────────────────────────

/**
 * @param repos    fetchRepo() 결과 배열
 * @param projects config/projects.yml
 * @param members  config/members.yml
 * @param policy   config/score-policy.yml
 */
export function aggregate(repos, projects, members, policy) {
  const projectByRepo = new Map();
  for (const p of projects) for (const r of p.repos) projectByRepo.set(r, p);

  const memberByLogin = new Map();
  for (const m of members) {
    memberByLogin.set(m.github.toLowerCase(), m);
    for (const alt of m.aliases?.logins ?? []) memberByLogin.set(alt.toLowerCase(), m);
  }
  const memberByEmail = new Map();
  for (const m of members) for (const e of m.aliases?.emails ?? []) memberByEmail.set(e.toLowerCase(), m);

  const excl = policy.exclude ?? {};
  const caps = policy.caps ?? {};

  const people = new Map();       // login → person
  const projectDaily = new Map(); // slug  → Map<day, counts>
  const activities = [];
  const unknownLogins = new Map();
  const hourHist = new Array(24).fill(0);

  const isBot = (login) => !login || login.endsWith('[bot]') || login === 'github-actions';

  function person(login, email) {
    const key = (login || `email:${email}`).toLowerCase();
    let p = people.get(key);
    if (!p) {
      const m = memberByLogin.get(key) ?? (email ? memberByEmail.get(email.toLowerCase()) : null);
      if (!m && login) unknownLogins.set(login, (unknownLogins.get(login) ?? 0) + 1);
      p = {
        login: login ?? key, member: m ?? null,
        excluded: m?.status === 'excluded' || (excl.bots !== false && isBot(login)),
        daily: new Map(), projects: new Map(), commits: [],
      };
      people.set(key, p);
    }
    return p;
  }

  function bump(p, slug, day, field, n = 1) {
    if (p.excluded || !slug) return;
    let d = p.daily.get(day);
    if (!d) { d = EMPTY(); p.daily.set(day, d); }
    d[field] += n;

    let byP = p.projects.get(slug);
    if (!byP) { byP = EMPTY(); p.projects.set(slug, byP); }
    byP[field] += n;

    let pd = projectDaily.get(slug);
    if (!pd) { pd = new Map(); projectDaily.set(slug, pd); }
    let pdd = pd.get(day);
    if (!pdd) { pdd = EMPTY(); pd.set(day, pdd); }
    pdd[field] += n;
  }

  for (const repo of repos) {
    const proj = projectByRepo.get(repo.fullName);
    if (!proj) { console.warn(`  ! projects.yml에 없는 저장소: ${repo.fullName}`); continue; }
    const slug = proj.slug;

    // ── 커밋 ──
    for (const c of repo.commits) {
      if (excl.mergeCommits !== false && c.parents?.totalCount > 1) continue;
      const login = c.author?.user?.login;
      const p = person(login, c.author?.email);
      if (p.excluded) continue;

      const day = toDay(c.committedDate);
      const capA = caps.additionsPerCommit ?? Infinity;
      bump(p, slug, day, 'commits');
      bump(p, slug, day, 'additions', Math.min(c.additions ?? 0, capA));
      bump(p, slug, day, 'deletions', Math.min(c.deletions ?? 0, capA));
      hourHist[hourOf(c.committedDate)]++;

      p.commits.push({
        sha: c.oid.slice(0, 7), message: c.messageHeadline,
        repo: repo.fullName, project: slug, at: c.committedDate,
      });
      activities.push({
        kind: 'commit', actor: p.login, project: slug, repo: repo.fullName,
        summary: c.messageHeadline, url: `https://github.com/${repo.fullName}/commit/${c.oid}`,
        at: c.committedDate,
      });
    }

    // ── PR + 리뷰 ──
    for (const pr of repo.pulls) {
      const author = pr.author?.login;
      const pa = person(author);
      if (!pa.excluded && !pr.isDraft) {
        bump(pa, slug, toDay(pr.createdAt), 'prsOpened');
        if (pr.mergedAt) {
          bump(pa, slug, toDay(pr.mergedAt), 'prsMerged');
          activities.push({
            kind: 'pr_merged', actor: pr.mergedBy?.login ?? author, project: slug,
            repo: repo.fullName, summary: `merged PR #${pr.number} — ${pr.title}`,
            url: `https://github.com/${repo.fullName}/pull/${pr.number}`, at: pr.mergedAt,
          });
        }
      }
      for (const rv of pr.reviews?.nodes ?? []) {
        const rl = rv.author?.login;
        if (!rl || !rv.submittedAt) continue;
        if (excl.selfReviews !== false && rl === author) continue;  // 셀프 승인 제외
        const rp = person(rl);
        if (rp.excluded) continue;
        bump(rp, slug, toDay(rv.submittedAt), 'reviews');
      }
    }

    // ── 이슈 ──
    for (const is of repo.issues) {
      const p = person(is.author?.login);
      if (p.excluded) continue;
      bump(p, slug, toDay(is.createdAt), 'issuesOpened');
      if (is.closedAt) bump(p, slug, toDay(is.closedAt), 'issuesClosed');
    }
  }

  activities.sort((a, b) => (a.at < b.at ? 1 : -1));
  for (const p of people.values()) p.commits.sort((a, b) => (a.at < b.at ? 1 : -1));

  return {
    people, projectDaily, activities, hourHist,
    unknown: [...unknownLogins].sort((a, b) => b[1] - a[1]).map(([login, n]) => ({ login, count: n })),
  };
}

// ─────────────────────────────────────────────────────────────────────
// 2. 점수 / 랭킹
// ─────────────────────────────────────────────────────────────────────

/** 기간 [from, to] 합계. slug를 주면 해당 프로젝트만. */
export function sumRange(p, from, to, slug = null) {
  const t = EMPTY();
  if (slug) {
    // 프로젝트 필터는 일별 프로젝트 분해가 필요 — 전체 기간일 때만 사용
    return addInto(t, p.projects.get(slug) ?? EMPTY());
  }
  for (const [day, d] of p.daily) if (day >= from && day <= to) addInto(t, d);
  return t;
}

/** 캡을 적용한 점수. 캡은 "하루 단위"로 걸어야 의미가 있다. */
export function scoreOf(p, from, to, policy) {
  const w = policy.weights ?? {};
  const caps = policy.caps ?? {};
  let s = 0;
  const totals = EMPTY();
  for (const [day, d] of p.daily) {
    if (day < from || day > to) continue;
    addInto(totals, d);
    const commits = Math.min(d.commits, caps.dailyCommits ?? Infinity);
    const reviews = Math.min(d.reviews, caps.dailyReviews ?? Infinity);
    s += commits * (w.commit ?? 0)
       + d.prsOpened * (w.prOpened ?? 0)
       + d.prsMerged * (w.prMerged ?? 0)
       + reviews * (w.review ?? 0)
       + d.issuesOpened * (w.issueOpened ?? 0)
       + d.issuesClosed * (w.issueClosed ?? 0);
  }
  return { score: Math.round(s * 100) / 100, totals };
}

/** 동점자는 같은 등수, 다음 등수는 건너뛴다 (1,2,2,4) */
export function rank(rows) {
  rows.sort((a, b) => b.score - a.score || a.login.localeCompare(b.login));
  let prev = null, prevRank = 0;
  rows.forEach((r, i) => {
    r.rank = r.score === prev ? prevRank : i + 1;
    prev = r.score; prevRank = r.rank;
  });
  return rows;
}

export function buildRanking(people, from, to, policy) {
  const rows = [];
  for (const p of people.values()) {
    if (p.excluded) continue;
    const { score, totals } = scoreOf(p, from, to, policy);
    if (score === 0) continue;
    rows.push({
      login: p.login,
      nameKo: p.member?.nameKo ?? p.login,
      nameEn: p.member?.nameEn ?? null,
      role: p.member?.role ?? 'contributor',
      primaryProject: p.member?.primaryProject ?? topProject(p),
      registered: !!p.member,
      score,
      commits: totals.commits,
      prs: totals.prsOpened,
      prsMerged: totals.prsMerged,
      reviews: totals.reviews,
      additions: totals.additions,
      deletions: totals.deletions,
    });
  }
  return rank(rows);
}

const topProject = (p) =>
  [...p.projects].sort((a, b) => b[1].commits - a[1].commits)[0]?.[0] ?? null;

// ─────────────────────────────────────────────────────────────────────
// 3. 히트맵 / 스트릭 / 활동 상태
// ─────────────────────────────────────────────────────────────────────

/** 365개 정수 배열. 날짜를 키로 쓰면 JSON이 10배 커진다. */
export function heatmap(p, endDay, days = 365) {
  const start = dayShift(endDay, -(days - 1));
  const counts = new Array(days).fill(0);
  for (const [day, d] of p.daily) {
    const i = daysBetween(start, day);
    if (i >= 0 && i < days) counts[i] = d.commits + d.prsOpened + d.reviews + d.issuesOpened;
  }
  return { start, counts, total: counts.reduce((a, b) => a + b, 0) };
}

export function streaks(counts) {
  let longest = 0, run = 0;
  for (const c of counts) { run = c > 0 ? run + 1 : 0; if (run > longest) longest = run; }
  let current = 0;
  for (let i = counts.length - 1; i >= 0 && counts[i] > 0; i--) current++;
  return { longest, current };
}

/** 관리 목적의 핵심 지표 — 화면에는 없지만 "현황 관리"에는 이게 제일 중요하다.
 *  누가 잘하는지보다 누가 이탈 중인지를 알아야 개입할 수 있다. */
export function activityStatus(p, today, policy) {
  const cfg = policy.status ?? {};
  const win = cfg.activeDays ?? 30;
  const recent = sumTotal(sumRange(p, dayShift(today, -win + 1), today));
  const prev = sumTotal(sumRange(p, dayShift(today, -win * 2 + 1), dayShift(today, -win)));
  const dormantFrom = dayShift(today, -(cfg.dormantDays ?? 60) + 1);
  const dormant = sumTotal(sumRange(p, dormantFrom, today)) === 0;

  if (dormant) return { status: 'dormant', recent, prev, dropPct: 100 };
  if (recent === 0) return { status: 'dormant', recent, prev, dropPct: 100 };
  const dropPct = prev > 0 ? Math.round((1 - recent / prev) * 100) : 0;
  if (prev > 0 && dropPct >= (cfg.slowingDropPct ?? 50)) return { status: 'slowing', recent, prev, dropPct };
  return { status: 'active', recent, prev, dropPct };
}

const sumTotal = (t) => t.commits + t.prsOpened + t.reviews + t.issuesOpened;

// ─────────────────────────────────────────────────────────────────────
// 4. 프로젝트 지표
// ─────────────────────────────────────────────────────────────────────

export function projectStats(slug, projectDaily, people, repos, from, to) {
  const daily = projectDaily.get(slug) ?? new Map();
  const series = [];
  for (let d = from; d <= to; d = dayShift(d, 1)) {
    const v = daily.get(d) ?? EMPTY();
    series.push({ day: d, commits: v.commits, prs: v.prsOpened, reviews: v.reviews });
  }
  const contributors = [];
  for (const p of people.values()) {
    const c = p.projects.get(slug);
    if (!c || p.excluded) continue;
    contributors.push({
      login: p.login, nameKo: p.member?.nameKo ?? p.login,
      role: p.member?.role ?? 'contributor',
      commits: c.commits, prs: c.prsOpened, reviews: c.reviews,
      additions: c.additions, deletions: c.deletions,
    });
  }
  contributors.sort((a, b) => b.commits - a.commits);

  // bus factor: 상위 몇 명이 커밋 50%를 차지하는가. 1이면 그 사람이 빠지면 프로젝트가 멈춘다.
  const total = contributors.reduce((a, c) => a + c.commits, 0);
  let acc = 0, bus = 0;
  for (const c of contributors) { acc += c.commits; bus++; if (acc >= total / 2) break; }

  return { series, contributors, busFactor: total ? bus : 0, totalCommits: total };
}

/** PR 열림→머지 평균 시간(시간 단위) */
export function avgMergeHours(repos, from, to) {
  let sum = 0, n = 0;
  for (const r of repos) for (const pr of r.pulls) {
    if (!pr.mergedAt) continue;
    const day = toDay(pr.mergedAt);
    if (day < from || day > to) continue;
    sum += (Date.parse(pr.mergedAt) - Date.parse(pr.createdAt)) / 3600000;
    n++;
  }
  return n ? Math.round((sum / n) * 10) / 10 : null;
}

/** 프로젝트 건강도 0~100. 근거를 components로 남겨야 "왜 94.2점이냐"에 답할 수 있다. */
export function healthScore({ commitsLast30, contributorsLast30, busFactor, openIssues, avgMergeH }) {
  const activity = clamp(commitsLast30 / 60 * 100);
  const people = clamp(contributorsLast30 / 5 * 100);
  const bus = clamp(busFactor / 3 * 100);
  const backlog = clamp(100 - openIssues * 2);
  const latency = avgMergeH == null ? 80 : clamp(100 - avgMergeH * 2);
  const components = {
    activity: r1(activity), contributors: r1(people), busFactor: r1(bus),
    issueBacklog: r1(backlog), reviewLatency: r1(latency),
  };
  const score = activity * 0.3 + people * 0.2 + bus * 0.2 + backlog * 0.15 + latency * 0.15;
  return { score: r1(score), components };
}

const clamp = (n) => Math.max(0, Math.min(100, n));
const r1 = (n) => Math.round(n * 10) / 10;
