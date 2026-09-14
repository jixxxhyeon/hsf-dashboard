-- 집계에서 뺄 저장소를 표시한다.
--
-- 행을 지우지 않고 표시만 남기는 이유: 삭제하면 조직 저장소를 다시 등록할 때
-- 또 들어온다. 표시를 남겨두면 등록 단계에서 "이미 아는 저장소"로 걸러진다.
ALTER TABLE repository ADD COLUMN excluded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE repository ADD COLUMN excluded_reason TEXT;

CREATE INDEX idx_repository_active ON repository (excluded) WHERE NOT excluded;
