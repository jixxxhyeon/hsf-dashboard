# 시작하기

## 비용 — 전부 0원입니다

| 항목 | 비용 |
|---|---|
| GitHub API (REST/GraphQL) | **무료.** 요금이 아니라 시간당 호출 한도로 제한할 뿐입니다 |
| GitHub Actions — **public** 저장소 | **무료, 무제한** |
| GitHub Actions — **private** 저장소 | Free 플랜 월 2,000분 |
| GitHub Pages | 무료 (단, private 저장소는 Pro 이상 필요) |
| Vercel / Netlify | Hobby 플랜 무료 (private 저장소도 됨) |

### private 저장소면 cron 주기를 조심하세요

```
매시간   730회/월 × 약 2분 = 1,460분  → 무료 2,000분의 73%.  다른 워크플로를 못 돌립니다
6시간마다 120회/월 × 약 2분 =   240분  → 12%.  안전
```

`.github/workflows/sync.yml`의 cron을 `7 */6 * * *`로 바꾸면 됩니다.
어차피 이 대시보드에 1시간 단위 신선도는 필요 없습니다.

> 한동대 재학생이면 **GitHub Student Developer Pack**으로 Pro를 무료로 받을 수 있습니다.
> 학교 이메일로 인증하면 private 저장소 + Pages 조합이 열립니다.

### 저장소 공개 여부

| | public | private |
|---|---|---|
| Actions | 무제한 무료 | 월 2,000분 |
| Pages | 무료 | Pro 필요 |
| 개인정보 | 이름·학과가 인터넷에 공개 | 안전 |

**권장: private 저장소 + 6시간 cron + Vercel 무료 배포.**
학생 이름과 학과가 검색엔진에 올라가는 건 되돌릴 수 없습니다. 공개는 나중에 동의를 받고 하세요.

학번은 기본적으로 `data/`에 내보내지 않습니다. 화면에서 쓰지 않으니까요.
(`config/members.yml`에는 남아 있고, 정말 필요하면 `EMIT_STUDENT_NO=1`)

---

## Day 1 — 여기까지만 하면 화면이 뜹니다 (약 40분)

### 1. 저장소 준비

```bash
gh repo create hsf-foundation/hsf-dashboard --private
git init && git add . && git commit -m "init"
git push -u origin main
```

### 2. 토큰 발급

<https://github.com/settings/personal-access-tokens/new?name=HSF+Dashboard+Sync&contents=read&issues=read&pull_requests=read&members=read&expires_in=366>

- Resource owner를 **HSF org**로
- Repository access → Only select repositories → HSF 저장소들
- 발급된 `github_pat_…`을 저장소 `Settings → Secrets and variables → Actions`에
  `HSF_SYNC_TOKEN` 으로 등록

### 3. 실제 저장소 등록

`config/projects.yml`을 실제 경로로 고칩니다. **처음엔 1~2개만 넣으세요.**
전부 넣으면 뭐가 잘못됐는지 찾기 어렵습니다.

```yaml
- slug: jchecker-engine
  name: jChecker-Engine
  category: education
  status: active
  repos:
    - hsf-foundation/jchecker-engine   # ← 실제 경로
```

### 4. 회원 명부 생성

```bash
npm ci
export GITHUB_TOKEN=github_pat_...

node scripts/bootstrap-members.mjs            # 미리보기 → members.generated.yml
node scripts/bootstrap-members.mjs --write    # 확인했으면 적용
```

`needsReview`가 붙은 항목(학과, 한글 이름, role)만 손으로 채우면 됩니다.

### 5. 동기화

```bash
npm run sync
```

`data/`에 JSON이 생깁니다. 마지막 줄의 `포인트 xxx / 5000`으로 한도 여유를 확인하세요.

### 6. 화면 확인

```bash
npx serve .
# → http://localhost:3000/web/contributor.html?login=<github-login>
```

**여기서 본인 이름과 진짜 커밋 기록이 뜨면 성공입니다.** 나머지는 이걸 늘려가는 일입니다.

---

## Day 2~ — 화면 늘리기

`web/contributor.html`을 복사해서 파일을 늘리세요. **빌드 도구를 먼저 세우지 마세요.**

```
web/
  contributor.html   ✓ 완료
  contributors.html  ← 다음. contributors.json 하나 읽어서 목록 + 검색
  overview.html      ← overview.json. attention 카드 포함
  rankings.html      ← rankings/{학기}.json. 필터 + 페이지네이션
  project.html       ← projects/{slug}.json
```

각 파일은 사이드바·탑바 HTML이 같고 `<div class="wrap">` 안쪽만 다릅니다.
JSON은 이미 화면 단위로 계산이 끝나 있으니, 하는 일은 **읽어서 그리기**뿐입니다.

### 순서를 이렇게 잡는 이유

1. **contributors.html** — 목록에서 상세로 넘어가면 화면 두 개가 연결됩니다. 성취감이 큽니다
2. **overview.html** — 관리 기능의 본체(attention)가 여기 있습니다
3. **rankings.html** — 필터·정렬 UI라 손이 제일 많이 갑니다. 나중에
4. **project.html** — 정보량이 많아 디자인 결정이 필요합니다. 마지막

### React로 옮기는 시점

사이드바를 네 번째로 복붙할 때입니다. 그 전엔 이득이 없습니다.
Vite + React로 옮겨도 `render()` 함수가 그대로 JSX가 되므로 반나절이면 됩니다.

---

## 배포

```bash
npm i -g vercel && vercel     # 정적 파일이라 설정 없이 붙습니다
```

또는 저장소를 public으로 돌리고 `Settings → Pages → Deploy from branch: main / (root)`.

---

## 자주 막히는 곳

| 증상 | 원인 |
|---|---|
| `저장소를 찾을 수 없음` | 토큰의 Resource owner가 org가 아니거나 repo 미선택 |
| 토큰이 `pending` | org가 승인 정책을 켜둠. 관리자 승인 필요 |
| 기여자가 `random-outsider`처럼 뜸 | `members.yml` 미등록. `meta.json`의 `unregistered` 확인 |
| 커밋은 있는데 사람이 안 잡힘 | 커밋 이메일이 GitHub 계정에 등록 안 됨 → `aliases.emails`에 추가 |
| 어느 날부터 갱신이 멈춤 | 토큰 만료 (최대 366일) 또는 60일 무활동으로 스케줄 자동 중지 |
