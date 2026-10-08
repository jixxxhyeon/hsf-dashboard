-- 참고용으로 포크만 해 둔 저장소는 HSF 활동으로 세지 않는다 (2026-10 운영진 결정).
-- 이미 등록돼 있으면 제외 표시하고 커밋을 지운다. 등록 단계에서도 걸러진다
-- (RepositoryImportService.NOT_HSF_ACTIVITY).
DELETE FROM commit_log
 WHERE repository_id IN (SELECT id FROM repository
                          WHERE owner = 'HandongSF'
                            AND name IN ('KoreanUnificationParallelCorpus', 'WICWIU'));

UPDATE repository
   SET excluded = TRUE, excluded_reason = '참고용 포크 — HSF 활동 아님', last_synced_at = NULL
 WHERE owner = 'HandongSF' AND name IN ('KoreanUnificationParallelCorpus', 'WICWIU');
