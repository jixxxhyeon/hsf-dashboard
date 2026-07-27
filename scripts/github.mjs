// GitHub GraphQL 수집 계층
// REST가 아닌 GraphQL을 쓰는 이유: REST는 커밋 목록에 additions/deletions가 없어서
// 커밋 1,240개면 요청 1,240번을 더 보내야 한다. GraphQL은 목록에 같이 온다.

const API = 'https://api.github.com/graphql';

export class GitHub {
  constructor(token, { onRateLimit } = {}) {
    if (!token) throw new Error('GITHUB_TOKEN이 없습니다.');
    this.token = token;
    this.onRateLimit = onRateLimit;
    this.cost = 0;
    this.calls = 0;
  }

  async query(q, variables = {}) {
    for (let attempt = 0; attempt < 5; attempt++) {
      const res = await fetch(API, {
        method: 'POST',
        headers: {
          Authorization: `bearer ${this.token}`,
          'Content-Type': 'application/json',
          'User-Agent': 'hsf-dashboard-sync',
        },
        body: JSON.stringify({ query: q, variables }),
      });

      // 2차 rate limit — Retry-After를 존중하지 않으면 계정이 차단될 수 있다
      if (res.status === 403 || res.status === 429) {
        const wait = Number(res.headers.get('retry-after') || 60);
        await sleep(wait * 1000);
        continue;
      }
      if (!res.ok) throw new Error(`GitHub ${res.status}: ${await res.text()}`);

      const body = await res.json();
      if (body.errors) {
        const msg = body.errors.map((e) => e.message).join('; ');
        // 일부 필드 실패는 부분 데이터로 진행 (예: 빈 저장소의 defaultBranchRef)
        if (!body.data) throw new Error(`GraphQL: ${msg}`);
        console.warn(`  ! GraphQL 경고: ${msg}`);
      }
      this.calls++;
      if (body.data?.rateLimit) {
        this.cost += body.data.rateLimit.cost;
        this.onRateLimit?.(body.data.rateLimit);
      }
      return body.data;
    }
    throw new Error('rate limit 재시도 초과');
  }

  /** 커서 페이지네이션 공통 처리 */
  async *paginate(q, variables, pick) {
    let cursor = null;
    for (;;) {
      const data = await this.query(q, { ...variables, cursor });
      const conn = pick(data);
      if (!conn) return;
      yield* conn.nodes ?? [];
      if (!conn.pageInfo?.hasNextPage) return;
      cursor = conn.pageInfo.endCursor;
    }
  }
}

const RATE = `rateLimit { cost remaining resetAt }`;

// ── 커밋 ──────────────────────────────────────────────────────────
// parents.totalCount > 1 이면 merge commit
export const Q_COMMITS = `
query($owner:String!, $name:String!, $since:GitTimestamp!, $cursor:String) {
  ${RATE}
  repository(owner:$owner, name:$name) {
    defaultBranchRef { target { ... on Commit {
      history(since:$since, first:100, after:$cursor) {
        pageInfo { hasNextPage endCursor }
        nodes {
          oid
          messageHeadline
          committedDate
          additions
          deletions
          changedFilesIfAvailable
          parents { totalCount }
          author { email name user { login databaseId } }
        }
      }
    }}}
  }
}`;

// ── PR + 리뷰 (중첩 조회로 요청 수를 1/20로 줄인다) ────────────────
export const Q_PULLS = `
query($owner:String!, $name:String!, $cursor:String) {
  ${RATE}
  repository(owner:$owner, name:$name) {
    pullRequests(first:50, after:$cursor, orderBy:{field:UPDATED_AT, direction:DESC}) {
      pageInfo { hasNextPage endCursor }
      nodes {
        number title state isDraft
        createdAt mergedAt closedAt updatedAt
        additions deletions changedFiles
        author { login }
        mergedBy { login }
        reviews(first:30) {
          nodes { author { login } state submittedAt }
        }
      }
    }
  }
}`;

// ── 이슈 ──────────────────────────────────────────────────────────
export const Q_ISSUES = `
query($owner:String!, $name:String!, $cursor:String) {
  ${RATE}
  repository(owner:$owner, name:$name) {
    issues(first:100, after:$cursor, orderBy:{field:UPDATED_AT, direction:DESC}) {
      pageInfo { hasNextPage endCursor }
      nodes {
        number title state createdAt closedAt updatedAt
        author { login }
        labels(first:10) { nodes { name } }
      }
    }
  }
}`;

// ── 저장소 메타 ────────────────────────────────────────────────────
export const Q_REPO = `
query($owner:String!, $name:String!) {
  ${RATE}
  repository(owner:$owner, name:$name) {
    databaseId name nameWithOwner description isArchived
    stargazerCount forkCount pushedAt
    primaryLanguage { name }
    defaultBranchRef { name }
    issues(states:OPEN) { totalCount }
    pullRequests(states:OPEN) { totalCount }
    languages(first:8, orderBy:{field:SIZE, direction:DESC}) {
      totalSize
      edges { size node { name color } }
    }
    licenseInfo { spdxId }
  }
}`;

/** 저장소 하나의 모든 활동을 수집 */
export async function fetchRepo(gh, fullName, sinceISO) {
  const [owner, name] = fullName.split('/');
  const vars = { owner, name, since: sinceISO };

  const meta = (await gh.query(Q_REPO, { owner, name })).repository;
  if (!meta) throw new Error(`저장소를 찾을 수 없음: ${fullName} (권한 확인)`);

  const commits = [];
  for await (const c of gh.paginate(
    Q_COMMITS, vars,
    (d) => d.repository?.defaultBranchRef?.target?.history
  )) commits.push(c);

  const pulls = [];
  for await (const p of gh.paginate(Q_PULLS, vars, (d) => d.repository?.pullRequests)) {
    // 수집 기간 이전 PR을 만나면 중단 (UPDATED_AT 내림차순이므로 안전)
    if (p.updatedAt < sinceISO) break;
    pulls.push(p);
  }

  const issues = [];
  for await (const i of gh.paginate(Q_ISSUES, vars, (d) => d.repository?.issues)) {
    if (i.updatedAt < sinceISO && i.state !== 'OPEN') break;
    issues.push(i);
  }

  return { fullName, meta, commits, pulls, issues };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
