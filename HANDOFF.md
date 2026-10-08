# HSF Dashboard — 작업 인수인계

이 문서는 **다른 세션에서 이어서 작업할 때 필요한 모든 것**을 담았다.
무엇을 왜 그렇게 만들었는지, 실제로 어떤 문제를 겪었는지, 지금 어디까지 왔는지.

작성 기준일: 2026-10-01
저장소: `github.com/jixxxhyeon/hsf-dashboard` (브랜치 `main`)
운영 주소: `https://hsf.lifove.net`

---

# 1. 한눈에 보기

## 지금 상태

```
배포       완료 — https://hsf.lifove.net 에서 운영 중
데이터     프로젝트 9개 · 멤버 30명 · 커밋 1,733건
자동 갱신  꺼져 있음  ← 켜야 함 (11장 참고)
```

## 어디에 무엇이 있나

| | 경로 |
|---|---|
| 맥 작업 폴더 | `~/dev/hsf-dashboard` |
| 서버 | `hsf@hsf.lifove.net` (= ISEL-PC-01, Ubuntu 24.04, 203.252.112.11) |
| 서버 앱 | `/opt/hsf-dashboard/app.jar` |
| 서버 설정 | `/opt/hsf-dashboard/hsf-dashboard.env` (권한 600) |
| systemd | `/etc/systemd/system/hsf-dashboard.service` |
| nginx | `/etc/nginx/sites-available/hsf-dashboard` |
| DB | PostgreSQL 16, `hsf_dashboard`, 계정 `hsfdash` |

## 저장소에 이미 있는 문서

- `README.md` — 프로젝트 소개, 로컬 실행, API 목록
- `DEPLOY.md` — 배포 절차 (단, 하위 도메인 기준으로 쓰여 있음. 실제로는 기존 도메인을 넘겨받았다. 12장 참고)
- 이 문서 — 작업 맥락과 겪은 문제

---

# 2. 이 프로젝트가 왜 존재하나

HSF(HandongSF) 구성원의 오픈소스 활동을 모아 보여준다.
**가장 중요한 기능은 기간별 활동 참여자 명단**이다. 소중대 사업단이 마일리지를 취합할 때
"이 기간에 활동한 사람"을 제출해야 하는데, 그 작업을 자동화하려고 만들었다.

## v1의 한계 (이것이 재작성의 이유)

v1은 GitHub Actions가 6시간마다 통계를 계산해 JSON 파일로 저장소에 커밋하고,
정적 화면이 그 파일을 읽는 구조였다. 서버가 필요 없다는 장점이 있었지만:

**미리 계산해둔 기간만 볼 수 있었다.** 사업단이 "3월 2일~6월 19일"을 요구하면
그 기간은 파일에 없으니 스크립트를 고쳐서 다시 돌려야 했다.

## v2의 핵심 전환

**집계 결과 대신 커밋 원본을 DB에 쌓고, 조회 시점에 집계한다.**

```sql
WHERE committed_at >= ? AND committed_at < ?
```

이 한 줄을 위해 서버를 도입했다. 나머지 설계는 전부 여기서 파생된다.
**이 문장이 이 프로젝트의 전부**라고 봐도 된다.

## v1 자산 처리

- v1 상태는 `v1-final` 태그에 보존 (`git checkout v1-final`)
- `config/*.yml` — 유지. V2 시드 데이터의 출처
- `scripts/`, `data/`, `web/`, `package.json` — 삭제
- `.github/workflows/` — 삭제 (이걸 지우지 않으면 자동화가 계속 돌며 `data/` 커밋을 밀어넣는다)

---

# 3. 기술 스택과 선택 이유

```
Java 21 + Spring Boot 4.1.1
PostgreSQL 16
Flyway            스키마 마이그레이션
nginx             리버스 프록시
systemd           프로세스 관리
```

## JPA 엔티티를 쓰지 않았다

`spring-boot-starter-data-jpa` 의존성은 들어 있지만 **엔티티가 하나도 없다.**
전부 `JdbcTemplate` + 직접 쓴 SQL이다.

이 프로젝트는 읽기가 대부분이고 핵심이 집계 쿼리(여러 테이블 조인 + GROUP BY + HAVING)라서
ORM이 벌어주는 게 거의 없다. 엔티티를 두면 쿼리를 두 번 표현하게 된다.

`ddl-auto: validate`인데 엔티티가 없어서 검증할 게 없다 — 의도된 상태다.

## 프론트엔드에 프레임워크를 쓰지 않았다

`src/main/resources/static/index.html` **단일 파일**이다. 빌드 도구 없음.
화면 4개, 상태 관리 불필요, 전부 읽기 전용이라 React를 얹으면 배포 단계만 늘어난다.

해시 라우팅(`#/`, `#/members`, ...)을 쓰므로 nginx 설정에 SPA 폴백이 필요 없다.

## 데이터를 통째로 브라우저에 보낸다

`/api/bootstrap` 이 커밋 1,733건을 전부 내려주고, 개요·히트맵·활동 상태 계산은 브라우저가 한다.
지금 규모에서는 이게 가장 단순하다.

**커밋이 1만 건을 넘어가면** 이 방식을 버리고 집계 API를 따로 두어야 한다.
그때 `PublicController`를 쪼개면 된다.

---

# 4. 파일 구조와 역할

```
src/main/java/net/lifove/hsf/dashboard/
├─ HsfDashboardApplication.java     @ConfigurationPropertiesScan, @EnableScheduling
├─ config/
│  ├─ HsfProperties.java            hsf.* 설정 바인딩 (github / admin / sync)
│  └─ SecurityConfig.java           접근 제어, 폼 로그인 + Basic 인증
├─ sync/
│  ├─ Json.java                     GraphQL 응답(중첩 Map) 탐색 도우미
│  ├─ GithubGraphQlClient.java      GitHub API 호출, 재시도, 부분 오류 처리
│  ├─ CommitSyncService.java        커밋 수집 → DB 적재 (핵심)
│  ├─ RepositoryImportService.java  조직 저장소 일괄 등록
│  └─ SyncController.java           동기화 / 저장소 관리 API
├─ report/
│  └─ ReportController.java         기간별 명단 + CSV (이 프로젝트의 목적)
├─ member/
│  └─ MemberController.java         회원 명부 관리
└─ web/
   ├─ PublicController.java         화면용 공개 데이터
   └─ LoginController.java          로그인 화면 뷰

src/main/resources/
├─ application.yml
├─ db/migration/V1~V4.sql
├─ static/index.html                대시보드 화면 전체 (단일 파일)
└─ templates/login.html             로그인 화면 (Thymeleaf — CSRF 토큰 때문)
```

---

# 5. 데이터 모델

## 테이블

| 테이블 | 역할 |
|---|---|
| `project` | 저장소 묶음. histudy-fe + histudy-be = 프로젝트 하나 |
| `repository` | 실제 GitHub 저장소. `excluded` 플래그로 집계에서 제외 |
| `member` | 사람. GitHub이 모르는 정보(실명, 역할) |
| `github_account` | GitHub 계정. 한 사람이 계정 2개일 수 있어 `member`와 N:1 |
| `commit_email` | 커밋 이메일 → 계정 매칭 보조 |
| `commit_log` | **커밋 원본. 이 프로젝트의 핵심** |
| `pull_request` | PR (스키마만 있고 아직 수집·사용 안 함) |
| `sync_log` | 동기화 이력. 문제 생기면 여기부터 본다 |

## 마이그레이션 이력

| | 내용 |
|---|---|
| V1 | 초기 스키마 8개 테이블 |
| V2 | `config/*.yml`에서 옮긴 시드 (프로젝트 3, 저장소 4, 회원 11, 봇 4) |
| V3 | `repository.excluded`, `excluded_reason` 추가 |
| V4 | `member.student_id`, `member.department` **삭제** |

## 이름이 `commit`이 아니라 `commit_log`인 이유

`commit`은 SQL 키워드라 쿼리나 ORM에서 따옴표를 계속 붙여야 하는 상황이 생긴다.
설계 문서에는 `commit`으로 적혀 있지만 실제 구현은 `commit_log`다.

## `member`와 `github_account`를 분리한 이유

한 사람이 학교 계정/개인 계정으로 커밋을 나눠 하는 경우가 실제로 있다.
분리해두면 "이 계정 둘은 같은 사람" 처리가 `UPDATE` 한 줄이다.
`POST /api/admin/members/{keep}/merge/{merge}` 가 그 기능이다.

## 학번·학과는 없다

설계 단계에서 넣었다가 **V4에서 컬럼째 삭제했다.**
이 서비스는 GitHub 공개 활동만 다루고 개인 식별 정보를 보관하지 않는다.

당시에 커밋 이메일(`학번@handong.ac.kr`)에서 학번을 자동 추출하는 기능이 있었는데
그것도 같이 제거했다. 되살리려면 `CommitSyncService.linkEmail()` 주변을 보면 된다.

**주의:** `config/members.yml`에는 아직 `studentNo: "22400437"` 이 남아 있고 저장소는 공개다.
지금 코드는 이 값을 읽지 않지만, 파일 자체를 정리하는 게 좋다. (미처리 항목)

---

# 6. 동기화 동작

## 흐름

```
repository 테이블에서 excluded 아닌 것들을 돌면서
  since = last_synced_at - 1일  (없으면 2000-01-01)
  GraphQL history(since:) 로 100건씩 페이지네이션
  sha 기준 UPSERT
  last_synced_at = now()
  sync_log 기록
```

## 알아야 할 것

**기본 브랜치에 올라온 커밋만 잡힌다.** 머지 안 된 브랜치는 안 들어온다.
마일리지 기준으로는 "머지된 기여"만 세는 게 오히려 타당해서 이대로 두고 있다.

**하루치를 겹쳐서 다시 훑는다.** 늦게 푸시된 커밋을 놓치지 않기 위해서다.
`sha` 기준 UPSERT라 중복은 생기지 않는다.

**GitHub은 간헐적으로 502를 준다.** 대용량 저장소에서 자주 난다. 3회까지 재시도한다.

**GraphQL은 "일부만 실패"가 정상 응답이다.** 변경량이 너무 큰 커밋은 `additions`를
못 준다(`SERVICE_UNAVAILABLE`). `data`가 있으면 경고만 남기고 진행한다.

**계정은 자동으로 등록된다.** 커밋한 사람의 GitHub 계정이 `github_account`에 자동 생성된다.
봇으로 보이면(`[bot]`, `-bot`, `bot`으로 끝나면) `is_bot=true`로 표시해 집계에서 뺀다.

## 자동 등록이 안 하는 것

- "이 계정이 누구인지" 연결 — `POST /api/admin/members/from-accounts` 로 일괄 생성 후 실명은 수기
- 여러 저장소를 한 프로젝트로 묶기 — SQL로 직접 (9장 참고)
- 제외할 저장소 판단 — 사람이 보고 결정

---

# 7. API 전체

## 공개

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/bootstrap` | 화면이 쓰는 데이터 전부. 학번 없음 |

## 운영진 (`/api/admin/**`, 로그인 필요)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/admin` | 관리자 화면 (index.html 을 그대로 forward, 로그인 필요) |
| GET | `/api/admin/names` | GitHub 아이디 → 실명. 관리자 모드 화면 전용 (공개 `/api/bootstrap`에는 실명 없음) |
| POST | `/api/admin/repositories/{id}/counted-from?date=YYYY-MM-DD` | 이 날짜 이전 커밋은 HSF 활동이 아님 (이전 커밋 삭제, 비우면 해제) |
| GET | `/api/admin/me` | 관리자 모드 확인 (200 로그인됨 / 401). `/admin` 화면에서만 호출 |
| GET | `/api/admin/reports/active-members` | 기간별 참여자 명단 |
| GET | `/api/admin/reports/active-members.csv` | 같은 명단, CSV |
| POST | `/api/admin/repositories/import` | 조직 저장소 일괄 등록 |
| GET | `/api/admin/repositories` | 등록된 저장소 목록 |
| POST | `/api/admin/repositories/{id}/exclude` | 집계에서 빼기 (`?excluded=false`로 복귀) |
| POST | `/api/admin/sync` | 지금 바로 동기화 |
| GET | `/api/admin/sync/status` | 최근 동기화 이력 |
| GET | `/api/admin/sync/summary` | 저장소별 커밋 현황 |
| GET | `/api/admin/members` | 회원 목록 |
| POST | `/api/admin/members/from-accounts` | 미등록 계정을 회원으로 일괄 등록 |
| PATCH | `/api/admin/members/{id}` | 실명·역할 수정 |
| POST | `/api/admin/members/{keep}/merge/{merge}` | 계정 두 개 합치기 |

## 명단 조회 파라미터

```
from         시작일 (포함)
to           종료일 (포함)
projects     프로젝트 slug, 쉼표 구분. 비우면 전체
minCommits   최소 커밋 수, 기본 1
```

## 기간 경계 — 가장 틀리기 쉬운 부분

`to`는 **포함**이다. 내부에서 `to + 1일 미만`으로 변환한다.

```java
OffsetDateTime end = to.plusDays(1).atStartOfDay(KST).toOffsetDateTime();
```

`BETWEEN`을 그대로 쓰면 **종료일 하루가 통째로 빠진다.**
수정할 일이 생기면 반드시 단일일 조회로 검증할 것:

```bash
curl -n "http://localhost:8081/api/admin/reports/active-members?from=2026-09-08&to=2026-09-08"
```

## CSV 양식

사업단 제출용. **GitHub 아이디, 활동 프로젝트 두 칸만.**
엑셀에서 한글이 깨지지 않도록 BOM(`﻿`)을 붙인다.

---

# 8. 화면

`src/main/resources/static/index.html` 단일 파일. 해시 라우팅.

| 경로 | 화면 | 접근 |
|---|---|---|
| `#/` | 개요 — 커밋 추이, 프로젝트 현황, 최근 커밋 | 공개 |
| `#/members` | 멤버 — 활동량, 상태 뱃지, 최근 12주 스파크라인 | 공개 |
| `#/members/{login}` | 멤버 상세 — 1년 히트맵, 월별, 프로젝트별 | 공개 |
| `/admin` (`/admin#/report`) | 관리자 모드 — 명단 뽑기(기간·프로젝트 필터, CSV, 인쇄). **주소를 직접 쳐야만 들어온다.** 공개 화면(`/`)에는 버튼·메뉴가 아예 없다. 미로그인이면 서버가 `/login`으로 보내고, 로그인 후 `/admin`으로 돌아온다 | 운영진 |
| `/login` | 로그인 (Thymeleaf 템플릿) | 공개 |

## 구조

```js
const API_BASE = '/api';
const TERMS = [...]              // 학기 정의. 새 학기마다 여기에 추가
loadBootstrap()                  // /api/bootstrap → DB 객체 채움
fetchReport(s)                   // 명단만 관리자 API 직접 호출
slice/activeMembers/buckets/statusOf   // 브라우저 집계 함수들
```

## 차트 색 규칙 (2026-10-01 변경 — 공식으로 생성)

예전에는 8색 팔레트를 커밋 순위대로 나눠주고 9번째부터 회색이었다. 지금은 `assignProjectColors()`가 공식으로 만든다.

```
① 색상(hue)  = slug 의 FNV-1a 해시 % 360        같은 이름 → 언제나 같은 색
② 겹침 방지  = 이미 쓴 색과 30° 이내면 황금각(137.5°)씩 돌림, 끝내 없으면 가장 먼 색상
               배정 순서는 slug 알파벳순 → 커밋 순위가 바뀌어도 색이 뒤섞이지 않음
③ 진하기     = 전체 기간 누적 커밋 단계 (0 / 50 / 100 / 200 / 500건)
               라이트: 커밋 많을수록 진하고 선명 (L 0.72 → 0.52)
               다크  : 커밋 많을수록 밝고 선명   (L 0.54 → 0.66)
④ 검정 방지  = OKLCH 명도·채도 하한. OKLCH 의 L 은 체감 밝기라 파랑·보라도 어둡게 뭉개지지 않음
```

- 단계 경계값과 명도는 `COLOR_TIERS`, 최소 색상 간격은 `HUE_GAP`에서 바꾼다.
- 화면에서 고른 기간이 아니라 **전체 기간** 커밋 기준이라 필터를 바꿔도 색이 바뀌지 않는다.
- 커밋이 쌓여 단계 경계(50·100·200·500)를 넘으면 그 프로젝트의 색 진하기가 한 단계 바뀐다.
- 주의: 공식으로 만든 9색은 색각 이상(적록) 구분이 약하다. 범례·툴팁·"표로 보기"로 보완한다.

히트맵은 파랑 단일 순차 스케일. 다크 모드는 방향이 반대(어두움→밝음).

## 활동 상태 판정

```
최근 30일 커밋 있음                    → 활동 중
직전 30일 대비 50% 이상 감소           → 둔화
30일 이상 커밋 없음                    → 휴면
```

v1의 `config/score-policy.yml` 규칙을 그대로 가져왔다.

---

# 9. 운영 데이터 현황

## 프로젝트 9개 (전체 기간 커밋 순)

```
737  histudy                  (histudy-fe + histudy-be)
226  camticket                (camticket-fe + camticket-be)
214  hlri-iua-motirobotics
171  hlri-ao
143  jChecker-Engine
123  ByteTok
 86  hlri-isa-cgv
 64  sLLMates
  8  hlri-ioa
```

`hlri-*` 4개는 **같은 산학이지만 다른 프로젝트**라 묶지 않기로 결정했다.

## 제외한 저장소

| 저장소 | 이유 |
|---|---|
| `cloud_storage` | 외부 오픈소스를 가져온 저장소. 커밋 2,984건, 저자 150명, 첫 커밋 2012년. fork 표시가 없어 자동 필터를 통과했다 |
| `EnCus` | HSF 활동 대상 아님 |

**지우지 말고 `exclude`를 쓸 것.** 지우면 조직 저장소를 다시 등록할 때 또 들어온다.

자동으로 걸러진 포크: `broken-filename-fixer`, `HandongSF.github.io`,
`KoreanUnificationParallelCorpus`, `WICWIU`

## 프로젝트 묶는 방법 (수동)

```sql
UPDATE project SET slug='camticket', name='camticket' WHERE slug='camticket-fe';
UPDATE repository SET project_id=(SELECT id FROM project WHERE slug='camticket')
 WHERE name='camticket-be';
DELETE FROM project WHERE slug='camticket-be';
```

자동 등록은 저장소 하나당 프로젝트 하나로 만든다. 묶음은 사람이 정한 것이라
저장소 행을 지웠다 다시 등록하면 복원되지 않는다.

## 멤버 30명

커밋한 사람은 전부 회원으로 등록돼 있다. 다만 **상당수의 이름이 GitHub 아이디 그대로**다
(`ChoHyeongmin1225`, `hahyun8587` 등). 명단에 그대로 나가면 사업단에서 알아보기 어렵다.

실명 채우기:

```bash
curl -n -X PATCH http://localhost:8081/api/admin/members/12 \
  -H "Content-Type: application/json" -d '{"name":"조형민"}'
```

관리 화면은 아직 없다. (미처리 항목)

---

# 10. 설정

## 환경변수

| 이름 | 기본값 | 설명 |
|---|---|---|
| `SERVER_PORT` | 8080 | 서버에서는 **8081** (8000은 기존 서비스) |
| `LOG_LEVEL` | debug | 서버에서는 info |
| `DB_USER` | yeojihyeon | 서버에서는 `hsfdash` |
| `DB_PASSWORD` | (빈값) | |
| `HSF_SYNC_TOKEN` | (빈값) | GitHub 읽기 토큰. **없으면 앱이 뜨지 않음** |
| `HSF_ADMIN_USER` | hsf | |
| `HSF_ADMIN_PASSWORD` | (빈값) | **없으면 앱이 뜨지 않음** |
| `HSF_SYNC_ENABLED` | false | 자동 갱신 on/off |

토큰과 비밀번호가 없으면 앱이 의도적으로 기동 실패한다.
무방비 상태로 배포되는 사고를 막기 위해서다.

## GitHub 토큰

HandongSF 저장소가 **모두 공개**라서 조직 소유 토큰이 필요 없다.

```
Resource owner:      본인 계정
Repository access:   Public Repositories (read-only)
```

조직 소유 토큰은 승인 절차가 필요하므로 굳이 쓰지 않는다.
토큰 값은 발급 화면에서 한 번만 보여준다. 잃어버리면 Regenerate.

## 인증 구조

```
공개         /, /api/bootstrap, /login, 정적 파일
운영진       /api/admin/**
```

- 브라우저 → 폼 로그인 (`templates/login.html`)
- curl/스크립트 → HTTP Basic (`curl -u hsf` 또는 `-n`)
- CSRF: `/api/**`와 `/logout`은 면제, **로그인 폼은 유지**
- 401은 `WWW-Authenticate` 헤더 없이 보낸다 → 브라우저 기본 로그인 팝업이 안 뜬다

서버에서 curl 할 때는 `~/.netrc`에 자격증명을 넣어두고 `-n`을 쓰면 편하다.

```
machine localhost login hsf password ...
```

---

# 11. 아직 안 한 것

## 중요 — 자동 갱신이 꺼져 있다

지금 데이터는 수동으로 한 번 가져온 것이고 **이후 커밋은 들어오지 않는다.**

```bash
nano /opt/hsf-dashboard/hsf-dashboard.env
```
```
HSF_SYNC_ENABLED=true
```
```bash
sudo systemctl restart hsf-dashboard
```

켜면 6시간마다(00/06/12/18시 KST) 자동 갱신된다.

## 나머지

| | 설명 |
|---|---|
| 회원 실명 채우기 | 30명 중 상당수가 GitHub 아이디 그대로 |
| 회원 관리 화면 | 지금은 API로만 수정 가능 |
| DB 백업 예약 | crontab 미등록. **회원 실명·프로젝트 묶음은 복구 불가능한 수기 데이터** |
| Django 중지 | 기존 서비스가 아직 실행 중 (요청은 안 감) |
| `config/members.yml` 정리 | 학번이 남아 있고 저장소는 공개 |
| `START.md`, `WORKLOG.md` | v1 시절 문서. 정리 필요 |
| `DEPLOY.md` 갱신 | 하위 도메인 기준으로 쓰여 있음. 실제는 기존 도메인 인계 |
| 미커밋 변경 | `application.yml` 수정분, `DEPLOY.md` 미추적 |
| PR 수집 | `pull_request` 테이블만 있고 수집 로직 없음 |

---

# 12. 배포 구조

## 서버 상황

`hsf.lifove.net` = `ISEL-PC-01` = `203.252.112.11` — **전부 같은 기계다.**
작업 초반에 이 셋을 다른 것으로 착각했었다.

```
Ubuntu 24.04
├─ nginx 1.24          80 / 443, Let's Encrypt 인증서
├─ hsf-dashboard       127.0.0.1:8081  ← 우리 것
├─ Django (gunicorn)   127.0.0.1:8000  ← 기존 SKKU-OSP, 실행 중이지만 요청 안 감
├─ MySQL               3306            ← 기존 서비스용
└─ PostgreSQL          5432            ← 우리 것
```

## 왜 기존 도메인을 넘겨받았나

처음엔 `dashboard.hsf.lifove.net` 하위 도메인을 쓰려 했다.
**기존 서비스가 `/api/`와 `/admin/` 경로를 이미 쓰고 있어서** 같은 주소에
경로로 얹으면 충돌하기 때문이다.

그런데 "기존 서비스를 더 이상 쓰지 않는다"로 방향이 바뀌면서
**하위 도메인 대신 기존 주소를 그대로 넘겨받았다.** DNS 변경은 필요 없었고
인증서도 기존 것을 그대로 쓴다.

## 전환 방식 — 아무것도 지우지 않았다

```
nginx        sites-enabled 심볼릭 링크만 교체 (기존 파일 보존)
Django       실행 중 (nginx가 요청을 안 보낼 뿐)
MySQL        데이터 그대로 + 덤프 백업
React 빌드    /home/hsf/SKKU-OSP/frontend/dist 그대로
```

백업 위치:
- `~/skku-osp-mysql-2026-09-15.sql.gz`
- `~/nginx-default-2026-09-15.bak`

## 되돌리기 (3줄)

```bash
sudo rm /etc/nginx/sites-enabled/hsf-dashboard
sudo ln -s /etc/nginx/sites-available/default /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

## 다시 배포하기

```bash
cd ~/dev/hsf-dashboard
./gradlew clean bootJar
scp build/libs/hsf-dashboard-0.0.1-SNAPSHOT.jar hsf@hsf.lifove.net:/opt/hsf-dashboard/app.jar
ssh hsf@hsf.lifove.net "sudo systemctl restart hsf-dashboard"
```

## nginx 설정 요지

```nginx
server {                                    # 80 → 443 리디렉션
    listen 80 default_server;
    server_name hsf.lifove.net www.hsf.lifove.net;
    location ^~ /.well-known/acme-challenge/ { root /var/www/html; }
    location / { return 301 https://hsf.lifove.net$request_uri; }
}
server {                                    # 443 → 앱
    listen 443 ssl default_server;
    server_name hsf.lifove.net www.hsf.lifove.net;
    ssl_certificate /etc/letsencrypt/live/hsf.lifove.net/fullchain.pem;
    ...
    location / { proxy_pass http://127.0.0.1:8081; ... }
}
```

`.well-known/acme-challenge` 위치를 반드시 유지할 것. 빠지면 인증서 자동 갱신이 실패한다.

**`sudo nginx -t`가 통과하지 않으면 절대 reload 하지 말 것.**
잘못된 설정으로 reload하면 nginx 전체가 영향을 받는다.

---

# 13. 실제로 겪은 문제들

같은 함정을 다시 밟지 않도록. **이 장이 이 문서에서 가장 쓸모 있는 부분일 수 있다.**

## Spring Boot 4 = Jackson 3, 패키지 경로가 바뀜

```
Jackson 2:  com.fasterxml.jackson.databind.JsonNode
Jackson 3:  tools.jackson.databind.JsonNode
```

프로젝트 생성 시 `bootVersion`을 지정하지 않아 최신(4.1.1)이 잡혔는데,
Jackson 2 기준으로 쓴 코드가 컴파일되지 않았다.

**해결:** Jackson 타입을 아예 쓰지 않고 `Map`으로 받는다. 라이브러리 버전과 무관해진다.
`Json.java`가 중첩 Map 탐색을 담당한다.

## GitTimestamp가 초 없는 시각을 거부

`OffsetDateTime.toString()`은 초가 0이면 초를 생략해 `2000-01-01T00:00Z`를 만든다.
GitHub GraphQL의 `GitTimestamp`는 이 형식을 거부한다.

**해결:** `since.toInstant().toString()` — `Instant`는 항상 초를 포함한다.

## JdbcTemplate의 `rs -> rs.next()`가 모호함

`ResultSetExtractor<Boolean>`과 `RowCallbackHandler` 양쪽에 다 맞아서 컴파일러가 고르지 못한다.

**해결:** `count(*)`로 세거나, 람다 본문을 표현식이 아닌 형태로 만든다.

## `timestamptz`가 `java.sql.Timestamp`로 옴

`queryForList`로 읽은 `last_synced_at`을 `OffsetDateTime`으로 바로 캐스팅하면 터진다.
**처음에는 값이 전부 null이라 드러나지 않다가**, 한 번 동기화한 뒤에 터졌다.

**해결:** `CommitSyncService.toOffset()` — 어떤 타입으로 오든 받아서 변환.

## 외래키 삭제 순서

`commit_email`이 `github_account`를 참조하고 있어서, 고아 계정을 먼저 지우려 하면
외래키 위반으로 거부된다. 그런데 그 앞의 `DELETE FROM commit_log`는 이미 커밋된 뒤라
**데이터는 지워졌는데 계정만 남는** 어중간한 상태가 됐다.

**해결:** `commit_email` → `github_account` 순서로 삭제.

## PostgreSQL이 null 파라미터 타입을 못 정함

`UPDATE ... SET excluded_reason = ?` 에 null을 넣으면 타입 추론 실패.

**해결:** `CAST(? AS TEXT)`.

## API 응답 키가 snake_case로 나감

SQL 별칭을 그냥 쓰면 PostgreSQL이 소문자로 내린다. 화면은 `committedAt`을 기대하는데
서버는 `committed_at`을 보냈다. **화면이 "데이터를 불러오지 못했습니다"만 띄워서
원인 파악이 오래 걸렸다.**

**해결:** 큰따옴표로 별칭 고정 — `AS "committedAt"`.

**교훈:** 이때 실제로 도움이 된 건 서버 응답을 파일로 떨궈서 직접 본 것이다.

```bash
curl -s localhost:8081/api/bootstrap > bootstrap-debug.json
```

## Spring Security 기본 로그인 페이지가 사라짐

최신 버전에서 자동 생성 로그인 화면이 없어져 `/login`이 404였다.

**해결:** `templates/login.html`을 직접 만들고 `LoginController`로 연결.
Thymeleaf를 쓴 이유는 **CSRF 토큰** 때문이다 — 정적 HTML에는 토큰을 넣을 수 없다.

## 폼 로그인만으로는 curl이 안 됨

`curl -u`는 HTTP Basic인데 폼 로그인만 켜져 있어 401이 났다.

**해결:** `httpBasic()`도 함께 켜되, `WWW-Authenticate` 헤더를 보내지 않도록 해서
브라우저 기본 팝업이 뜨지 않게 했다.

## GitHub Actions가 계속 main을 밀어올림

v2 브랜치에서만 작업하는 동안 **main의 워크플로가 살아 있어서** 6시간마다
`data/` 커밋이 쌓였다. 머지하려니 "v2는 지웠는데 main은 수정했다" 충돌이 파일마다 났다.

**해결:**
```bash
git merge -s ours origin/main -m "..."
```
저쪽을 이력상 합쳤다고 기록하되 파일은 내 쪽을 쓴다. 충돌 없음.
머지 후 워크플로가 삭제되면서 자동화도 멈췄다.

## 그 외 환경 문제

| 증상 | 원인 |
|---|---|
| `brew install`이 그냥 끝남 | 확인 프롬프트 입력 실패. `NONINTERACTIVE=1` 붙이면 됨 |
| 터미널에 `>`만 계속 나옴 | 따옴표 미완성 상태. **Ctrl+C로 빠져나와야** 함 |
| `!` → `event not found` | 셸 히스토리 확장. 비밀번호는 영문+숫자만 쓰는 게 안전 |
| `(END)` | 페이저. `q`로 나감 |
| `Task '#' not found` | 명령어에 설명 주석까지 붙여넣음 |
| 사이트 무한로딩 | **학교 와이파이에서 해당 서버로 못 나감.** 외부망에서는 정상 |

마지막 항목으로 꽤 시간을 썼다. 서버 안에서는 200/0.006초로 멀쩡했는데
브라우저만 멈췄고, 시크릿 창에서도 같아서 캐시도 아니었다.
`curl -v`로 **TCP 연결 단계에서 멈춘다**는 걸 보고 네트워크 문제로 좁혔다.

---

# 14. 검증 방법

## 서버가 사는지

```bash
sudo systemctl status hsf-dashboard
curl -s localhost:8081/api/bootstrap | head -c 120
```

## 데이터가 맞는지

```bash
curl -s https://hsf.lifove.net/api/bootstrap | python3 -c "
import json,sys; d=json.load(sys.stdin)
print('프로젝트',len(d['projects']),'/ 멤버',len(d['members']),'/ 커밋',len(d['commits']))"
```

기준값: **프로젝트 9 / 멤버 30 / 커밋 1,733** (동기화하면 늘어남)

## 명단 기능

```bash
curl -n "http://localhost:8081/api/admin/reports/active-members?from=2026-03-02&to=2026-06-19"
curl -n "http://localhost:8081/api/admin/reports/active-members?from=2026-09-08&to=2026-09-08"
```

두 번째는 **종료일 경계** 검증용이다. 하루만 지정했을 때 그날 커밋이 나와야 한다.

## 인증

```bash
curl -s -o /dev/null -w "%{http_code}\n" localhost:8081/api/bootstrap                      # 200
curl -s -o /dev/null -w "%{http_code}\n" "localhost:8081/api/admin/sync/summary"            # 401
curl -n -s -o /dev/null -w "%{http_code}\n" "localhost:8081/api/admin/sync/summary"         # 200
```

## 문제 생겼을 때 보는 순서

```bash
sudo systemctl status hsf-dashboard
sudo journalctl -u hsf-dashboard -n 100 --no-pager
curl -s -o /dev/null -w "%{http_code}\n" localhost:8081/
sudo nginx -t
sudo tail -50 /var/log/nginx/error.log
curl -n http://localhost:8081/api/admin/sync/status
```

**앱이 계속 재시작하고 있으면** `hsf-dashboard.env`에 토큰·비밀번호가 있는지부터 확인.

---

# 15. 다음 세션에서 먼저 할 것

```
1. 미커밋 변경 정리
     cd ~/dev/hsf-dashboard && git status
     application.yml 수정분, DEPLOY.md 가 안 올라가 있음

2. 자동 갱신 켜기 (11장)

3. DB 백업 crontab 등록
     회원 실명과 프로젝트 묶음은 복구 불가능한 수기 데이터

4. 회원 실명 채우기 + 관리 화면
```

로컬에서 작업할 때는:

```bash
cd ~/dev/hsf-dashboard
export HSF_SYNC_TOKEN=...
export HSF_ADMIN_PASSWORD=...
./gradlew bootRun
```

`http://localhost:8080` — 로컬 DB(`hsf_dashboard`)에도 같은 데이터가 들어 있다.


---

# 16. 2026-10-08 변경 — 저장소 범위 확대 · 공개 화면 아이디 표시

## 저장소 17개 전부 수집
- 포크도 등록한다. 대신 `repository.counted_from` = 포크 생성일, 그 이전(원본 저장소 이력)은 수집하지 않는다.
- `cloud_storage` 다시 포함, `counted_from = 2025-01-07` (HSF 저장소 생성일). 외부 오픈소스 이력 2,984건은 들어오지 않는다.
- `EnCus` 다시 포함 (전체 이력).
- 위 두 건은 V5 마이그레이션이 처리한다. 포크 4개는 배포 후 `POST /api/admin/repositories/import` 로 등록된다.
- 포크 이후에 원본에서 병합해 온 커밋(외부 저자)은 걸러지지 않는다. 명단의 "회원 명부에 없는 기여자"로 확인할 것.

## 공개 화면 = GitHub 아이디 + 프로필 사진, 관리자 모드 = 실명
- `/api/bootstrap` 에서 실명(`name`)을 뺐다. 공개 API 로는 실명이 나가지 않는다.
- `/admin` 에서 로그인하면 `/api/admin/names` 로 실명을 받아 덮어쓴다 (`displayName()`).
- 프로필 사진: `avatarUrl`(github_id 기반) → 없으면 `github.com/{login}.png` → 그것도 실패하면 이니셜.

## 수집 대상 15개 · 관리자 화면 "데이터 갱신" 버튼
- `KoreanUnificationParallelCorpus`, `WICWIU`는 참고용 포크라 제외 (등록 단계 `NOT_HSF_ACTIVITY` + V6 마이그레이션).
- `/admin` 사이드바의 **데이터 갱신** = 조직 저장소 등록 → 전체 동기화 → 새 기여자 회원 자동 등록. 백그라운드 실행(`POST /api/admin/refresh`, 상태 `GET /api/admin/refresh/status`).
- 동기화가 끝날 때마다 회원이 아닌 기여자를 회원으로 자동 등록한다(이름 = GitHub 아이디). 명단은 회원만 세기 때문.
- GitHub 이 502 를 주면 같은 위치에서 페이지 크기를 100 → 50 → 25 → 12 로 줄여 다시 요청한다.
