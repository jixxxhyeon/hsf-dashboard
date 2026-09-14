# HSF Dashboard 작업 기록 — 2026-08-20

## 목적

HSF에 참여하는 사람들의 오픈소스 기여 현황을 관리하기 위한 대시보드를 만들기 시작했다. Overview, Rankings, Projects, Contributors 네 화면으로 구성되며, 전부 읽기 전용이다. 이 문서는 오늘까지 진행한 작업과 결정 사항을 정리한 것이다.

## 아키텍처 결정

화면이 전부 읽기 전용이라는 점에 착안해 별도의 서버나 데이터베이스 없이 가는 구조로 설계했다. GitHub Actions가 6시간마다 자동으로 실행되면서 GitHub GraphQL API로 HSF 저장소들의 커밋·PR·리뷰·이슈 데이터를 수집하고, 이를 집계해 JSON 파일로 만든 뒤 저장소에 직접 커밋한다. 화면(프론트엔드)은 이 JSON 파일만 읽어서 그린다. 서버 비용이 들지 않고, 계산 결과가 전부 git 이력으로 남아 나중에 "왜 순위가 바뀌었는지" 같은 질문에 답할 수 있다는 장점이 있다.

회원의 학번·학과·역할처럼 GitHub API로는 알 수 없는 정보는 `config/members.yml`이라는 별도 파일로 관리한다. 이 파일은 자동화 스크립트가 GitHub 활동을 스캔해서 1차로 채우고, 학과처럼 자동으로 알 수 없는 항목만 사람이 손으로 보완하는 방식이다. 한 번 채운 값은 스크립트를 다시 돌려도 덮어써지지 않도록 만들어뒀다.

## 진행한 작업

로컬 환경에서 코드가 정상 동작하는지부터 확인했다. Node.js 설치 여부를 확인하고, 집계 로직을 검증하는 자동 테스트 15개를 전부 통과시켰으며, 샘플 데이터로 화면이 뜨는 것까지 확인했다.

이후 GitHub에 `hsf-dashboard`라는 이름으로 개인 계정 아래 저장소를 만들고 코드를 올렸다. 이 과정에서 GitHub Actions 워크플로 파일을 올리는 데 필요한 권한이 기본 로그인 토큰에는 없어서, GitHub 공식 CLI 도구(`gh`)로 다시 로그인해 권한 문제를 해결했다.

HSF 조직(HandongSF) 소유로 fine-grained personal access token을 발급했다. 저장소 읽기, 이슈 읽기, PR 읽기, 조직 멤버 읽기 권한만 부여했고, 쓰기 권한은 전혀 주지 않았다. 이 토큰은 GitHub Actions가 자동 실행될 때 쓸 수 있도록 저장소의 Secret(`HSF_SYNC_TOKEN`)으로 등록했다.

추적할 실제 저장소를 `config/projects.yml`에 등록했다. 처음엔 jChecker-Engine 하나만 넣어 파이프라인이 실제로 동작하는지 검증했고, 이후 histudy-fe, histudy-be, sLLMates를 추가했다. histudy-fe와 histudy-be는 프론트/백엔드 관계라 하나의 프로젝트로 묶었다.

회원 명부 자동 생성 스크립트를 돌려 실제 기여자들을 찾아냈다. 이 과정에서 학번이 커밋 이메일(`학번@handong.ac.kr`)에서 자동으로 추출되는 것을 확인했다. 또한 gemini-code-assist, coderabbitai, copilot-pull-request-reviewer, chatgpt-codex-connector 같은 자동 코드 리뷰 봇 계정 4개가 사람처럼 잡히는 것을 발견해 집계에서 제외 처리했다.

기여자 상세 화면(`web/contributor.html`)을 실제 데이터에 연결해 완성했다. 프로필, 최근 1년 기여 히트맵, 총 기여 통계, 라인 증감, 최근 커밋 이력을 보여준다. 로컬 정적 서버로 직접 열어 본인 계정(jixxxhyeon)의 실제 GitHub 활동이 정확히 표시되는 것까지 확인했다.

마지막으로 GitHub Actions 자동화를 켰다. 워크플로가 한 번 자동 실행되면서 자체적으로 데이터를 커밋했고, 그 사이 로컬에서도 새 저장소를 반영해 커밋하면서 두 이력이 갈라져 병합 충돌이 발생했다. 자동 생성 파일(`data/` 폴더)끼리의 충돌이라 로컬 버전을 그대로 채택하는 방식으로 해결했다.

## 현재 상태

- 추적 중인 저장소: jChecker-Engine, histudy-fe, histudy-be, sLLMates (4개)
- 등록된 실제 기여자: 11명 (봇 4개 제외)
- 완성된 화면: 기여자 상세 페이지
- 자동화: GitHub Actions가 6시간마다 자동 갱신, 정상 작동 확인됨

## 다음 단계

Overview, Rankings, Projects 화면은 아직 만들지 않았다. 다만 이 화면들에 필요한 JSON 데이터는 이미 자동으로 계산되어 나오고 있어서 (`data/overview.json`, `data/rankings/*.json`, `data/projects.json`), 기여자 상세 화면과 같은 구조로 화면만 추가로 만들면 된다. 그 외에 학번·학과가 비어 있는 일부 회원 정보를 채우는 작업이 남아 있다.
