# HSF Dashboard

HandongSF 조직의 오픈소스 활동을 모아 보여주는 대시보드.

가장 중요한 기능은 **기간별 활동 참여자 명단**이다. 소중대 사업단에서 마일리지를
취합할 때 "이 기간에 활동한 사람"을 뽑아야 하는데, 그 작업을 버튼 몇 번으로 끝내려고 만들었다.

## 왜 이렇게 만들었나

이전 버전은 GitHub Actions 가 6시간마다 통계를 계산해 JSON 파일로 저장소에 커밋하고,
화면이 그 파일을 읽는 구조였다. 서버가 필요 없다는 장점이 있었지만 한계가 분명했다.

**미리 계산해둔 기간만 볼 수 있었다.** 사업단이 "3월 2일부터 6월 19일까지"를 요구하면
그 기간은 파일에 없으니 스크립트를 고쳐서 다시 돌려야 했다.

그래서 구조를 뒤집었다. **계산 결과 대신 커밋 원본을 DB에 쌓고, 볼 때 집계한다.**
그러면 임의 기간 조회가 SQL 한 줄이 된다.

```sql
WHERE committed_at >= ? AND committed_at < ?
```

이 한 줄을 위해 서버를 도입한 것이고, 나머지는 전부 여기서 파생된다.

## 구성

```
GitHub GraphQL API
        │  6시간마다 (또는 수동)
        ▼
  Spring Boot ──► PostgreSQL       커밋 원본을 그대로 적재
        │
        ▼
     화면 (정적 HTML + fetch)
```

- **Java 21 / Spring Boot 4 / PostgreSQL 16**
- JPA 엔티티 없이 **JdbcTemplate + SQL**. 이 프로젝트는 읽기가 대부분이고
  핵심이 집계 쿼리라서 ORM 이 벌어주는 게 거의 없다.
- 화면은 빌드 도구 없는 **단일 HTML 파일**. 화면 5개, 상태 관리가 필요 없는 규모라
  프레임워크를 얹으면 배포 단계만 늘어난다.

## 화면

| 경로 | 화면 | 접근 |
|---|---|---|
| `/#/` | 개요 — 커밋 추이, 프로젝트별 현황, 최근 커밋 | 공개 |
| `/#/members` | 멤버 — 활동량, 활동 상태, 최근 12주 추이 | 공개 |
| `/#/members/{login}` | 멤버 상세 — 1년 히트맵, 월별, 프로젝트별 기여 | 공개 |
| `/#/report` | 명단 뽑기 — 기간·프로젝트 필터, CSV 내려받기 | 운영진 |
| `/login` | 운영진 로그인 | 공개 |

## 로컬에서 실행하기

```bash
# 1. PostgreSQL 준비
brew install postgresql@16
brew services start postgresql@16
createdb hsf_dashboard

# 2. 환경변수 (파일에 적지 말 것)
export HSF_SYNC_TOKEN=github_pat_...      # GitHub 읽기 토큰
export HSF_ADMIN_PASSWORD=...             # 운영진 비밀번호

# 3. 실행
./gradlew bootRun
```

테이블은 Flyway 가 자동으로 만든다. `http://localhost:8080` 으로 접속.

관리자 비밀번호가 없으면 **앱이 아예 뜨지 않는다.** 무방비 상태로 배포되는 사고를 막기 위해서다.

### GitHub 토큰

[Fine-grained personal access token](https://github.com/settings/personal-access-tokens/new) 을 만든다.
HandongSF 저장소가 모두 공개라 **개인 계정 소유 + Public Repositories (read-only)** 로 충분하다.
조직 소유 토큰은 승인 절차가 필요하므로 굳이 쓰지 않는다.

토큰은 발급 화면에서 한 번만 보여준다. 잃어버리면 Regenerate 로 새로 받으면 된다.

## 처음 데이터 채우기

```bash
# 조직 저장소 전체 등록 (포크·빈 저장소는 자동 제외)
curl -X POST "localhost:8080/api/admin/repositories/import" -u hsf:비밀번호

# 커밋 가져오기 — 처음엔 전체 이력이라 몇 분 걸린다
curl -X POST localhost:8080/api/admin/sync -u hsf:비밀번호

# 커밋한 사람을 회원으로 등록
curl -X POST localhost:8080/api/admin/members/from-accounts -u hsf:비밀번호
```

## API

### 공개

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/bootstrap` | 화면이 쓰는 데이터 전부 (프로젝트·멤버·커밋) |

### 운영진

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/admin/reports/active-members` | 기간별 참여자 명단 |
| GET | `/api/admin/reports/active-members.csv` | 같은 명단을 CSV 로 |
| POST | `/api/admin/repositories/import` | 조직 저장소 일괄 등록 |
| GET | `/api/admin/repositories` | 등록된 저장소 목록 |
| POST | `/api/admin/repositories/{id}/exclude` | 저장소를 집계에서 빼기 |
| POST | `/api/admin/sync` | 지금 바로 동기화 |
| GET | `/api/admin/sync/status` | 최근 동기화 이력 |
| GET | `/api/admin/sync/summary` | 저장소별 커밋 현황 |
| GET | `/api/admin/members` | 회원 목록 |
| POST | `/api/admin/members/from-accounts` | 미등록 계정을 회원으로 등록 |
| PATCH | `/api/admin/members/{id}` | 실명·역할 수정 |
| POST | `/api/admin/members/{keep}/merge/{merge}` | 같은 사람의 계정 두 개 합치기 |

명단 조회 파라미터: `from`, `to` (둘 다 포함), `projects` (slug 쉼표 구분), `minCommits`.

```bash
curl "localhost:8080/api/admin/reports/active-members?from=2026-03-02&to=2026-06-19&projects=histudy" -u hsf:비밀번호
```

## 데이터 모델

| 테이블 | 역할 |
|---|---|
| `project` | 저장소 묶음. histudy-fe + histudy-be = 프로젝트 하나 |
| `repository` | 실제 GitHub 저장소. `excluded` 로 집계에서 뺄 수 있다 |
| `member` | 사람. GitHub 이 모르는 정보(실명, 역할)를 담는다 |
| `github_account` | GitHub 계정. 한 사람이 계정 두 개일 수 있어 `member` 와 N:1 |
| `commit_email` | 커밋 이메일 → 계정 매칭 보조 |
| `commit_log` | **커밋 원본.** 이 프로젝트의 핵심 |
| `pull_request` | PR (통계용, 명단에는 아직 미사용) |
| `sync_log` | 동기화 이력. 문제가 생기면 여기부터 본다 |

테이블 이름이 `commit` 이 아니라 `commit_log` 인 이유는 `commit` 이 SQL 키워드라
쿼리마다 따옴표를 붙여야 하는 상황이 생기기 때문이다.

## 운영하면서 알아야 할 것

**기본 브랜치에 올라온 커밋만 잡힌다.** 머지되지 않은 브랜치의 커밋은 들어오지 않는다.
마일리지 기준으로는 "머지된 기여"만 세는 게 오히려 타당해서 이대로 두고 있다.

**동기화는 마지막 시점보다 하루 앞에서부터 다시 훑는다.** 늦게 푸시된 커밋을
놓치지 않기 위해서다. `sha` 기준으로 덮어쓰기 때문에 중복은 생기지 않는다.

**집계에서 빼야 할 저장소가 생기면** 지우지 말고 `exclude` 를 쓴다. 지우면
조직 저장소를 다시 등록할 때 또 들어온다. 현재 제외된 것:

- `cloud_storage` — 외부 오픈소스를 가져온 저장소 (커밋 3천 건, 저자 150명이 섞여 들어왔다)
- `EnCus` — HSF 활동 대상이 아님

**여러 저장소를 한 프로젝트로 묶는 건 수동이다.** 자동 등록은 저장소 하나당
프로젝트 하나로 만든다. 묶으려면:

```sql
UPDATE project SET slug='camticket', name='camticket' WHERE slug='camticket-fe';
UPDATE repository SET project_id=(SELECT id FROM project WHERE slug='camticket')
 WHERE name='camticket-be';
DELETE FROM project WHERE slug='camticket-be';
```

**개인정보는 저장하지 않는다.** 학번·학과는 설계 단계에서 넣었다가 제거했다(V4).
지금 다루는 건 GitHub 공개 활동 기록뿐이다.

**차트 색은 전체 기간 활동량 순으로 배정된다.** 화면에서 고른 기간으로 정렬하면
필터를 바꿀 때마다 색이 재배치돼서 같은 색이 다른 프로젝트를 가리키게 된다.
팔레트가 8색이라 9번째부터는 색을 돌려쓰지 않고 회색으로 둔다.

## 학기 정의

명단 화면의 학기 프리셋은 `src/main/resources/static/index.html` 상단의 `TERMS` 에 있다.
새 학기가 시작되면 여기에 한 줄 추가한다.

```js
const TERMS = [
  {code: '2026-1', name: '2026 1학기', from: '2026-03-02', to: '2026-06-19'},
  ...
];
```

## 이전 버전

v1(GitHub Actions + 정적 JSON)은 `v1-final` 태그에 보존되어 있다.

```bash
git checkout v1-final
```
