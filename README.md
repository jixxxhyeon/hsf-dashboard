# HSF Dashboard — 데이터 파이프라인

HSF 참여자 현황 대시보드. **서버도 DB도 없다.**
GitHub Actions가 1시간마다 GitHub을 긁어서 `data/*.json`을 커밋하고, 프론트는 그 JSON만 읽는다.

```
GitHub GraphQL ──▶ scripts/sync.mjs ──▶ data/*.json ──▶ 정적 사이트
        (Actions cron, 1시간)              (git 커밋)
```

## 구조

```
config/                 ← 사람이 관리하는 유일한 데이터. 수정은 PR로.
  projects.yml            프로젝트 ↔ 저장소 묶음
  members.yml             학번/학과/한글이름/역할  (GitHub에 없는 정보)
  terms.yml               학기 정의
  score-policy.yml        점수 가중치 · 어뷰징 상한
scripts/
  github.mjs              GraphQL 수집 (rate limit 재시도, 커서 페이지네이션)
  aggregate.mjs           집계 · 점수 · 랭킹 · 히트맵 · 상태판정  ← 순수 함수
  emit.mjs                화면별 JSON 생성
  sync.mjs                진입점
  bootstrap-members.mjs   members.yml 자동 생성/갱신
  test.mjs                모의 데이터 검증 (네트워크 불필요)
web/
  contributor.html        Contributor 상세 화면
data/                   ← 생성물. 직접 고치지 말 것.
.github/workflows/sync.yml
```

## 실행

```bash
npm ci
npm test                                        # 네트워크 없이 집계 로직 검증 (15개)

# 1) 회원 명부 자동 생성 — GitHub에서 긁어올 수 있는 건 다 채운다
GITHUB_TOKEN=github_pat_xxx node scripts/bootstrap-members.mjs          # 미리보기
GITHUB_TOKEN=github_pat_xxx node scripts/bootstrap-members.mjs --write  # 적용

# 2) 동기화
GITHUB_TOKEN=github_pat_xxx npm run sync
GITHUB_TOKEN=github_pat_xxx node scripts/sync.mjs --days 90 --dry
```

## 회원 명부 자동 생성

`bootstrap-members.mjs`가 채우는 것과 못 채우는 것:

| | 항목 | 출처 |
|---|---|---|
| **자동** | login, 이름, 아바타, 위치, 소속, bio, 가입일 | GitHub 프로필 |
| **자동** | **학번** | 커밋 author 이메일이 `22400437@handong.ac.kr` 형태일 때 정규식 추출 |
| **자동** | 커밋 이메일 목록 (`aliases.emails`) | 계정이 바뀌어도 매칭되도록 보존 |
| **추정** | role | 남의 PR 머지 3회↑ → maintainer, 커밋 10↑ → committer |
| **추정** | primaryProject | 커밋이 가장 많은 프로젝트 |
| **수기** | 학과, 확정 한글 이름, 기술 태그 | GitHub에 존재하지 않음 |

추정·미입력 항목에는 `needsReview: [...]`가 붙는다. 사람이 확인하고 그 줄을 지우면 된다.
**손으로 고친 값은 재실행해도 덮어쓰이지 않는다.**

## 토큰 — fine-grained PAT

`Settings → Developer settings → Personal access tokens → Fine-grained tokens`

| 항목 | 값 |
|---|---|
| Resource owner | HSF org (개인 계정 아님) |
| Repository access | Only select repositories → HSF 저장소들 |
| Repository permissions | `Contents: Read`, `Issues: Read`, `Pull requests: Read`, `Metadata: Read` |
| Organization permissions | `Members: Read` (활동 없는 인원까지 명부에 넣으려면) |
| Expiration | 최대 366일 — 만료일을 캘린더에 등록해 둘 것 |

권한이 미리 채워진 생성 링크:
<https://github.com/settings/personal-access-tokens/new?name=HSF+Dashboard+Sync&contents=read&issues=read&pull_requests=read&members=read&expires_in=366>

Actions Secret 이름은 `HSF_SYNC_TOKEN`.

> - Actions 기본 `GITHUB_TOKEN`은 쓰지 마라. 저장소당 1,000회/시간이고 다른 repo를 못 읽는다.
> - fine-grained PAT은 **조직 하나만** 접근할 수 있다. HSF 저장소가 여러 org에 흩어져 있으면
>   GitHub App으로 가야 한다.
> - org에서 승인 정책을 켜 뒀다면 토큰이 `pending` 상태로 대기하고, 승인 전까지는
>   공개 저장소만 읽힌다.

## 화면

`web/contributor.html` — 파일을 그대로 열면 내장 샘플로 렌더되고,
정적 서버로 띄우면 `data/contributors/{login}.json`을 읽는다.

```bash
npx serve .        # → http://localhost:3000/web/contributor.html?login=handong-dev
```

## 생성되는 JSON

| 파일 | 화면 | 크기 |
|---|---|---|
| `meta.json` | 공통 (학기 목록, 미등록 기여자) | ~2KB |
| `overview.json` | Overview 전체 | ~10KB |
| `rankings/{학기}.json`, `rankings/all-time.json` | Rankings | ~40KB |
| `projects.json` | Projects 목록 | ~10KB |
| `projects/{slug}.json` | Projects 상세 | ~8KB |
| `contributors.json` | Contributors 목록 · 검색 | ~30KB |
| `contributors/{login}.json` | 개인 이력 (히트맵 포함) | ~5KB |

**한 파일에 다 넣지 마라.** 156명 × 365일을 통짜로 만들면 수 MB가 되고 첫 로딩이 죽는다.
히트맵은 `{start, counts[365]}` 정수 배열로 압축했다 — 날짜를 키로 쓰면 10배 커진다.

## 프론트에서 쓰는 법

```js
const res = await fetch('/data/overview.json');
const { kpi, topContributors, velocity, recentActivities, attention } = await res.json();
```

프론트는 **계산하지 않는다.** 정렬·집계·점수는 전부 sync 단계에서 끝난다.
필터링(프로젝트/역할)과 페이지네이션만 클라이언트에서 한다.

## 설계상 지켜야 할 것

- **날짜는 KST 기준.** UTC로 자르면 밤 9시 이후 커밋이 전날로 밀려 히트맵이 어긋난다.
- **제외한 데이터는 지우지 않는다.** merge commit·봇·셀프리뷰는 원본 카운트를 보존하고 점수에서만 뺀다.
- **점수 정책을 바꾸면 `version`을 올린다.** `meta.json`에 박히므로 "언제 왜 순위가 바뀌었는지" 추적된다.
- **줄 수는 점수에 넣지 않는다.** 넣으면 코드를 길게 쓰는 인센티브가 생긴다 (`linesAffectScore: false`).

## 나중에 DB가 필요해지는 시점

읽기 전용인 동안은 이 구조로 충분하다. 아래가 필요해지면 Postgres로 넘어가라.

- 사이트에서 **값을 수정**하는 기능 (역할 변경, 코멘트, 승인)
- 임의 기간 조회 (`3월 2일 ~ 4월 7일`)
- 로그인 / 권한 분리
- 기여자 500명 초과

그때도 `aggregate.mjs`의 일별 집계 구조를 그대로 `contribution_daily` 테이블에 INSERT하면 된다.
수집·집계 로직은 재사용되고 출력 대상만 바뀐다. (`hsf_schema.sql` 참고)
