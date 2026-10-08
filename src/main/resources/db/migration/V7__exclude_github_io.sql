-- HandongSF.github.io (조직 홈페이지, 테마 포크) 는 HSF 활동으로 세지 않는다 (2026-10-08 운영진 결정).
-- 등록 단계에서도 걸러진다 (RepositoryImportService.NOT_HSF_ACTIVITY).

-- 1. 이 저장소에서만 커밋했던, 동기화가 자동 등록한 회원을 먼저 골라 둔다 (커밋을 지우기 전에).
--    자동 등록 회원 = 이름이 GitHub 아이디 그대로이고 역할이 contributor.
--    실명을 채웠거나 역할을 바꾼 회원, 다른 저장소에도 커밋이 있는 회원은 건드리지 않는다.
CREATE TEMP TABLE gone_member AS
SELECT DISTINCT m.id
  FROM member m
  JOIN github_account ga ON ga.member_id = m.id AND ga.login = m.name
  JOIN commit_log c      ON c.author_id = ga.id
  JOIN repository r      ON r.id = c.repository_id
 WHERE m.role = 'contributor'
   AND r.owner = 'HandongSF' AND r.name = 'HandongSF.github.io'
   AND NOT EXISTS (SELECT 1 FROM github_account ga2
                     JOIN commit_log c2 ON c2.author_id = ga2.id
                     JOIN repository r2 ON r2.id = c2.repository_id
                    WHERE ga2.member_id = m.id
                      AND NOT (r2.owner = 'HandongSF' AND r2.name = 'HandongSF.github.io'));

-- 2. 커밋 삭제 + 제외 표시
DELETE FROM commit_log
 WHERE repository_id IN (SELECT id FROM repository
                          WHERE owner = 'HandongSF' AND name = 'HandongSF.github.io');

UPDATE repository
   SET excluded = TRUE, excluded_reason = '조직 홈페이지(테마 포크) — HSF 활동 아님', last_synced_at = NULL
 WHERE owner = 'HandongSF' AND name = 'HandongSF.github.io';

-- 3. 골라 둔 회원 삭제
UPDATE github_account SET member_id = NULL WHERE member_id IN (SELECT id FROM gone_member);
DELETE FROM member WHERE id IN (SELECT id FROM gone_member);
DROP TABLE gone_member;

-- 4. 커밋도 회원 연결도 없는 계정 정리 (commit_email 먼저 — 외래키)
DELETE FROM commit_email
 WHERE account_id IN (SELECT ga.id FROM github_account ga
                       WHERE ga.member_id IS NULL AND NOT ga.is_bot
                         AND NOT EXISTS (SELECT 1 FROM commit_log c WHERE c.author_id = ga.id)
                         AND NOT EXISTS (SELECT 1 FROM pull_request pr WHERE pr.author_id = ga.id));
DELETE FROM github_account ga
 WHERE ga.member_id IS NULL AND NOT ga.is_bot
   AND NOT EXISTS (SELECT 1 FROM commit_log c WHERE c.author_id = ga.id)
   AND NOT EXISTS (SELECT 1 FROM pull_request pr WHERE pr.author_id = ga.id);
