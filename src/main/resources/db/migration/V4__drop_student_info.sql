-- 학번·학과를 서비스에서 제거한다.
--
-- 이 대시보드는 GitHub 활동만 다루고, 개인 식별 정보는 보관하지 않기로 했다.
-- 컬럼을 지우면 API·화면·CSV 어디에도 학번이 나올 수 없다.
ALTER TABLE member DROP COLUMN IF EXISTS student_id;
ALTER TABLE member DROP COLUMN IF EXISTS department;
