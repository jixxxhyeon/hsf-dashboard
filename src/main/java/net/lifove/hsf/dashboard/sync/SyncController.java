package net.lifove.hsf.dashboard.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 저장소 등록 / 동기화 / 상태 확인.
 *
 * 주의: 아직 인증이 없다. 로컬 개발용이며, 배포 전에 반드시 관리자 권한을 건다.
 */
@RestController
@RequestMapping("/api/admin")
public class SyncController {

    private final CommitSyncService sync;
    private final RepositoryImportService importer;
    private final JdbcTemplate jdbc;

    public SyncController(CommitSyncService sync, RepositoryImportService importer, JdbcTemplate jdbc) {
        this.sync = sync;
        this.importer = importer;
        this.jdbc = jdbc;
    }

    /** 조직 저장소 전체 등록. 이미 등록된 것은 건드리지 않는다. */
    @PostMapping("/repositories/import")
    public Map<String, Object> importRepositories(
            @RequestParam(defaultValue = "HandongSF") String org,
            @RequestParam(defaultValue = "true") boolean includeArchived) {
        return importer.importOrg(org, includeArchived);
    }

    /** 등록된 저장소 목록. */
    @GetMapping("/repositories")
    public List<Map<String, Object>> repositories() {
        return jdbc.queryForList("""
                SELECT r.id, p.name AS project, r.owner || '/' || r.name AS repo,
                       r.default_branch, r.last_synced_at, r.excluded, r.excluded_reason,
                       r.counted_from::text AS counted_from
                  FROM repository r JOIN project p ON p.id = r.project_id
                 ORDER BY r.excluded, p.name, r.name
                """);
    }

    /**
     * 저장소를 집계에서 빼거나 되돌린다.
     * 뺄 때는 그 저장소의 커밋도 같이 지우고, 그 때문에 남게 된 외부 계정도 정리한다.
     */
    @PostMapping("/repositories/{id}/exclude")
    public Map<String, Object> exclude(@PathVariable long id,
                                       @RequestParam(defaultValue = "true") boolean excluded,
                                       @RequestParam(required = false) String reason) {
        int removedCommits = 0;
        int removedAccounts = 0;

        if (excluded) {
            removedCommits = jdbc.update("DELETE FROM commit_log WHERE repository_id = ?", id);

            // 고아 계정을 지우기 전에 그 계정을 가리키는 이메일 행을 먼저 지운다.
            // commit_email 이 github_account 를 참조하고 있어서, 순서를 지키지 않으면
            // 외래키 위반으로 삭제가 거부된다.
            String orphan = """
                    SELECT id FROM github_account ga
                     WHERE ga.member_id IS NULL
                       AND NOT ga.is_bot
                       AND NOT EXISTS (SELECT 1 FROM commit_log c WHERE c.author_id = ga.id)
                    """;
            jdbc.update("DELETE FROM commit_email WHERE account_id IN (" + orphan + ")");
            removedAccounts = jdbc.update("DELETE FROM github_account WHERE id IN (" + orphan + ")");
        }

        // CAST 를 씌우는 이유: reason 이 null 일 때 PostgreSQL 이 파라미터 타입을 정하지 못한다.
        jdbc.update("""
                UPDATE repository
                   SET excluded = ?, excluded_reason = CAST(? AS TEXT), last_synced_at = NULL
                 WHERE id = ?
                """, excluded, excluded ? reason : null, id);

        return Map.of("id", id,
                      "excluded", excluded,
                      "deletedCommits", removedCommits,
                      "deletedOrphanAccounts", removedAccounts);
    }

    /**
     * "이 날짜부터 HSF 활동으로 센다" 지정. 외부 오픈소스를 가져와 시작한 저장소에 쓴다.
     * 지정하면 그 이전 커밋과, 그 때문에 남게 된 외부 계정을 지운다.
     * date 를 비우면 해제(전체 이력). 어느 쪽이든 다음 동기화 때 처음부터 다시 훑는다.
     *
     *   curl -n -X POST "http://localhost:8081/api/admin/repositories/12/counted-from?date=2025-01-07"
     */
    @PostMapping("/repositories/{id}/counted-from")
    public Map<String, Object> countedFrom(@PathVariable long id,
                                           @RequestParam(required = false)
                                           @org.springframework.format.annotation.DateTimeFormat(
                                                   iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                                           java.time.LocalDate date) {
        int removedCommits = 0;
        int removedAccounts = 0;
        if (date != null) {
            removedCommits = jdbc.update("""
                    DELETE FROM commit_log
                     WHERE repository_id = ?
                       AND committed_at < (CAST(? AS DATE)::timestamp AT TIME ZONE 'Asia/Seoul')
                    """, id, java.sql.Date.valueOf(date));
            removedAccounts = deleteOrphanAccounts();
        }
        jdbc.update("UPDATE repository SET counted_from = CAST(? AS DATE), last_synced_at = NULL WHERE id = ?",
                date == null ? null : java.sql.Date.valueOf(date), id);
        return Map.of("id", id,
                      "countedFrom", date == null ? "전체 이력" : date.toString(),
                      "deletedCommits", removedCommits,
                      "deletedOrphanAccounts", removedAccounts);
    }

    /** 커밋도 회원 연결도 없는 계정 정리. commit_email 을 먼저 지워야 외래키에 걸리지 않는다. */
    private int deleteOrphanAccounts() {
        String orphan = """
                SELECT id FROM github_account ga
                 WHERE ga.member_id IS NULL
                   AND NOT ga.is_bot
                   AND NOT EXISTS (SELECT 1 FROM commit_log c WHERE c.author_id = ga.id)
                """;
        jdbc.update("DELETE FROM commit_email WHERE account_id IN (" + orphan + ")");
        return jdbc.update("DELETE FROM github_account WHERE id IN (" + orphan + ")");
    }

    /* ── 관리자 화면의 "데이터 갱신" 버튼 ──────────────────────────────────
     * 조직 저장소 등록 → 전체 동기화 → 새 기여자 회원 등록을 한 번에 한다.
     * 큰 저장소가 있으면 몇 분 걸려 nginx 시간 제한(60초)을 넘기므로, 백그라운드로 돌리고
     * 화면은 /refresh/status 를 몇 초마다 확인한다. 동시에 두 번 돌지 않는다.
     */
    private final ExecutorService refresher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hsf-refresh");
        t.setDaemon(true);
        return t;
    });
    private volatile Map<String, Object> refreshState = Map.of("running", false);

    @PostMapping("/refresh")
    public synchronized Map<String, Object> refresh() {
        if (Boolean.TRUE.equals(refreshState.get("running"))) return refreshState;
        String startedAt = OffsetDateTime.now().toString();
        refreshState = Map.of("running", true, "startedAt", startedAt);
        refresher.submit(() -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("running", false);
            s.put("startedAt", startedAt);
            try {
                s.put("import", importer.importOrg("HandongSF", true));
                s.put("sync", sync.syncAll());
                s.put("ok", true);
            } catch (Exception e) {
                s.put("ok", false);
                s.put("error", String.valueOf(e.getMessage()));
            }
            s.put("finishedAt", OffsetDateTime.now().toString());
            refreshState = s;
        });
        return refreshState;
    }

    @GetMapping("/refresh/status")
    public Map<String, Object> refreshStatus() {
        return refreshState;
    }

    /** 지금 바로 동기화. 명단 요청이 왔을 때 최신 상태로 만들고 뽑기 위한 용도. */
    @PostMapping("/sync")
    public Map<String, Object> sync() {
        return sync.syncAll();
    }

    /** 최근 동기화 이력. 문제가 생기면 여기부터 본다. */
    @GetMapping("/sync/status")
    public List<Map<String, Object>> status() {
        return jdbc.queryForList("""
                SELECT s.id, r.owner || '/' || r.name AS repo, s.status,
                       s.started_at, s.finished_at, s.new_commits, s.error_message
                  FROM sync_log s
                  LEFT JOIN repository r ON r.id = s.repository_id
                 ORDER BY s.id DESC
                 LIMIT 30
                """);
    }

    /** 저장소별로 잘 들어갔는지 빠르게 보는 용도. */
    @GetMapping("/sync/summary")
    public List<Map<String, Object>> summary() {
        return jdbc.queryForList("""
                SELECT r.owner || '/' || r.name       AS repo,
                       COUNT(c.id)                    AS commits,
                       MIN(c.committed_at)::date::text AS first_commit,
                       MAX(c.committed_at)::date::text AS last_commit,
                       COUNT(DISTINCT c.author_id)    AS authors
                  FROM repository r
                  LEFT JOIN commit_log c ON c.repository_id = r.id
                 GROUP BY r.id, repo
                 ORDER BY commits DESC
                """);
    }
}
