-- config/projects.yml, config/members.yml 에서 옮긴 초기 데이터

-- ── 프로젝트 & 저장소 ───────────────────────────────────────────
INSERT INTO project (slug, name, description, category, status, started_on) VALUES
  ('jchecker-engine', 'jChecker-Engine', '자동 채점 엔진',   'education', 'active', '2024-03-01'),
  ('histudy',         'histudy',         '스터디 그룹 관리', 'tooling',   'active', '2023-09-01'),
  ('sllmates',        'sLLMates',        '',                 'tooling',   'active', '2025-01-01');

INSERT INTO repository (project_id, owner, name)
SELECT p.id, r.owner, r.name
FROM (VALUES
  ('jchecker-engine', 'HandongSF', 'jChecker-Engine'),
  ('histudy',         'HandongSF', 'histudy-fe'),
  ('histudy',         'HandongSF', 'histudy-be'),
  ('sllmates',        'HandongSF', 'sLLMates')
) AS r(slug, owner, name)
JOIN project p ON p.slug = r.slug;

-- ── 회원 + GitHub 계정 ──────────────────────────────────────────
-- 학번/학과는 GitHub 에서 알 수 없다. 지금은 대부분 비어 있고 나중에 관리 화면에서 채운다.
-- avatar_url 은 저장하지 않는다. github_id 로 만들어 쓴다:
--   https://avatars.githubusercontent.com/u/{github_id}?v=4

WITH m AS (INSERT INTO member (name, student_id, role, joined_at)
           VALUES ('한시온', NULL, 'committer', '2025-06-19') RETURNING id)
INSERT INTO github_account (login, github_id, member_id)
SELECT 'zionhann', 45687157, id FROM m;

WITH m AS (INSERT INTO member (name, student_id, role, joined_at)
           VALUES ('Inhyuk Oh', NULL, 'maintainer', '2025-06-20') RETURNING id)
INSERT INTO github_account (login, github_id, member_id)
SELECT 'ohinhyuk', 49269218, id FROM m;

WITH m AS (INSERT INTO member (name, student_id, role, joined_at)
           VALUES ('Joshuaii3712', NULL, 'committer', '2025-08-19') RETURNING id)
INSERT INTO github_account (login, github_id, member_id)
SELECT 'Joshuaii3712', 162775297, id FROM m;

WITH m AS (INSERT INTO member (name, student_id, role, joined_at)
           VALUES ('Leo1010246', NULL, 'maintainer', '2025-10-13') RETURNING id)
INSERT INTO github_account (login, github_id, member_id)
SELECT 'Leo1010246', 53553877, id FROM m;

WITH m AS (INSERT INTO member (name, student_id, role, joined_at)
           VALUES ('Jaeslim', NULL, 'committer', '2025-10-13') RETURNING id)
INSERT INTO github_account (login, github_id, member_id)
SELECT 'Jaeslim', 203327047, id FROM m;

WITH m AS (INSERT INTO member (name, student_id, role, joined_at)
           VALUES ('권혁민', NULL, 'contributor', '2025-07-28') RETURNING id)
INSERT INTO github_account (login, github_id, member_id)
SELECT 'hyeokkiyaa', 111753951, id FROM m;

WITH m AS (INSERT INTO member (name, student_id, role, joined_at)
           VALUES ('여지현', '22400437', 'contributor', '2026-01-06') RETURNING id)
INSERT INTO github_account (login, github_id, member_id)
SELECT 'jixxxhyeon', 192617155, id FROM m;

WITH m AS (INSERT INTO member (name, student_id, role, joined_at)
           VALUES ('Kim yong hyeon', NULL, 'contributor', '2025-08-30') RETURNING id)
INSERT INTO github_account (login, github_id, member_id)
SELECT 'Cocomong98', 90203932, id FROM m;

WITH m AS (INSERT INTO member (name, student_id, role, joined_at)
           VALUES ('김동규', NULL, 'contributor', '2025-07-21') RETURNING id)
INSERT INTO github_account (login, github_id, member_id)
SELECT 'ehdrb01', 130060480, id FROM m;

WITH m AS (INSERT INTO member (name, student_id, role, joined_at)
           VALUES ('minzziPark', NULL, 'contributor', '2025-10-22') RETURNING id)
INSERT INTO github_account (login, github_id, member_id)
SELECT 'minzziPark', 86656147, id FROM m;

WITH m AS (INSERT INTO member (name, student_id, role, joined_at)
           VALUES ('나예원', NULL, 'contributor', '2022-09-05') RETURNING id)
INSERT INTO github_account (login, github_id, member_id)
SELECT 'nayeawon', 80326384, id FROM m;

-- ── 봇 계정 (집계에서 제외) ─────────────────────────────────────
INSERT INTO github_account (login, is_bot) VALUES
  ('gemini-code-assist',            TRUE),
  ('coderabbitai',                  TRUE),
  ('copilot-pull-request-reviewer', TRUE),
  ('chatgpt-codex-connector',       TRUE);

-- ── 커밋 이메일 매칭 ────────────────────────────────────────────
INSERT INTO commit_email (email, account_id)
SELECT e.email, ga.id
FROM (VALUES
  ('its.zionhan@gmail.com',    'zionhann'),
  ('8156217@naver.com',        'ohinhyuk'),
  ('joshuaii3712@gmail.com',   'Joshuaii3712'),
  ('leocho1126@gmail.com',     'Leo1010246'),
  ('wotjd1470@naver.com',      'Jaeslim'),
  ('khm38607574@gmail.com',    'hyeokkiyaa'),
  ('22400437@handong.ac.kr',   'jixxxhyeon')
) AS e(email, login)
JOIN github_account ga ON ga.login = e.login;
