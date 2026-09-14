package net.lifove.hsf.dashboard.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 동기화 수동 실행 / 상태 확인.
 *
 * 주의: 아직 인증이 없다. 로컬 개발용이며, 배포 전에 반드시 관리자 권한을 건다.
 */
@RestController
@RequestMapping("/api/admin")
public class SyncController {

    private final CommitSyncService sync;
    private final JdbcTemplate jdbc;

    public SyncController(CommitSyncService sync, JdbcTemplate jdbc) {
        this.sync = sync;
        this.jdbc = jdbc;
    }

    /** 지금 바로 동기화. 사업단 명단 요청이 왔을 때 최신 상태로 만들고 뽑기 위한 용도. */
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
                 LIMIT 20
                """);
    }

    /** 잘 들어갔는지 빠르게 보는 용도. */
    @GetMapping("/sync/summary")
    public List<Map<String, Object>> summary() {
        return jdbc.queryForList("""
                SELECT r.owner || '/' || r.name AS repo,
                       COUNT(c.id)              AS commits,
                       MIN(c.committed_at)::date AS first_commit,
                       MAX(c.committed_at)::date AS last_commit,
                       COUNT(DISTINCT c.author_id) AS authors
                  FROM repository r
                  LEFT JOIN commit_log c ON c.repository_id = r.id
                 GROUP BY r.id, repo
                 ORDER BY repo
                """);
    }
}
