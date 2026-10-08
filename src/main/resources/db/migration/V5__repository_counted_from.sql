-- 저장소별 "이 날짜부터 HSF 활동으로 센다".
--
-- 포크나 외부 오픈소스를 가져와 만든 저장소는 HSF 로 들어오기 전의 이력(남의 커밋)을 함께 갖고 있다.
-- 그 이력은 HSF 구성원의 활동이 아니므로 이 날짜 이전 커밋은 수집하지 않는다.
--   - 포크: 조직 저장소 등록 시 GitHub 저장소 생성일로 자동 설정 (RepositoryImportService)
--   - 그 외: 운영진이 판단해서 지정 (POST /api/admin/repositories/{id}/counted-from?date=...)
-- NULL 이면 전체 이력을 센다 (기존 동작).
ALTER TABLE repository ADD COLUMN counted_from DATE;

-- cloud_storage: 24-2~25-1 캡스톤. 외부 오픈소스(2012년~, 저자 150명) 위에서 시작해
-- 통째로 제외했었다. HSF 저장소가 만들어진 2025-01-07 이후 커밋만 세는 조건으로 다시 포함한다.
UPDATE repository
   SET counted_from = DATE '2025-01-07',
       excluded = FALSE, excluded_reason = NULL, last_synced_at = NULL
 WHERE owner = 'HandongSF' AND name = 'cloud_storage';

-- EnCus: 집계 대상으로 다시 포함 (2026-10 운영진 결정).
UPDATE repository
   SET excluded = FALSE, excluded_reason = NULL, last_synced_at = NULL
 WHERE owner = 'HandongSF' AND name = 'EnCus';
