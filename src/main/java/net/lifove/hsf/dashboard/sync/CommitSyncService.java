package net.lifove.hsf.dashboard.sync;

import net.lifove.hsf.dashboard.config.HsfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.lifove.hsf.dashboard.sync.Json.*;

/**
 * GitHub → DB 커밋 동기화.
 *
 * JPA 엔티티 대신 JdbcTemplate + SQL 을 쓴다. 이 프로젝트는
 * "커밋을 그대로 쌓고 조회 시점에 SQL 로 집계"하는 구조라 ORM 이 벌어주는 게 거의 없다.
 */
@Service
public class CommitSyncService {

    private static final Logger log = LoggerFactory.getLogger(CommitSyncService.class);

    /** 학번은 커밋 이메일에서 뽑는다: 22400437@handong.ac.kr → 22400437 */
    private static final Pattern STUDENT_ID = Pattern.compile("^(\\d{8})@handong\\.ac\\.kr$");

    private static final String HISTORY_QUERY = """
        query($owner:String!, $name:String!, $since:GitTimestamp!, $cursor:String) {
          repository(owner:$owner, name:$name) {
            defaultBranchRef {
              name
              target { ... on Commit {
                history(since:$since, first:100, after:$cursor) {
                  pageInfo { hasNextPage endCursor }
                  nodes {
                    oid committedDate messageHeadline
                    additions deletions changedFilesIfAvailable
                    parents { totalCount }
                    author { name email user { login databaseId } }
                  }
                }
              }}
            }
          }
        }
        """;

    private final JdbcTemplate jdbc;
    private final GithubGraphQlClient github;
    private final HsfProperties props;

    public CommitSyncService(JdbcTemplate jdbc, GithubGraphQlClient github, HsfProperties props) {
        this.jdbc = jdbc;
        this.github = github;
        this.props = props;
    }

    /** 스케줄 실행. application.yml 의 hsf.sync.enabled 가 true 일 때만 동작. */
    @Scheduled(cron = "${hsf.sync.cron}", zone = "Asia/Seoul")
    public void scheduledSync() {
        if (!props.sync().enabled()) return;
        syncAll();
    }

    /** 등록된 저장소 전부 동기화. 하나가 실패해도 나머지는 계속 진행한다. */
    public Map<String, Object> syncAll() {
        List<Map<String, Object>> repos = jdbc.queryForList(
                "SELECT id, owner, name, last_synced_at FROM repository ORDER BY id");

        int total = 0;
        List<String> failed = new ArrayList<>();
        for (Map<String, Object> r : repos) {
            String full = r.get("owner") + "/" + r.get("name");
            try {
                total += syncRepository(
                        ((Number) r.get("id")).longValue(),
                        (String) r.get("owner"),
                        (String) r.get("name"),
                        (OffsetDateTime) r.get("last_synced_at"));
            } catch (Exception e) {
                log.error("동기화 실패: {}", full, e);
                failed.add(full + " — " + e.getMessage());
            }
        }
        return Map.of("repositories", repos.size(), "newCommits", total, "failed", failed);
    }

    /** 저장소 1개 동기화. 저장(또는 갱신)된 커밋 수를 돌려준다. */
    @Transactional
    public int syncRepository(long repoId, String owner, String name, OffsetDateTime lastSynced) {
        // 마지막 동기화보다 하루 앞에서부터 다시 훑는다.
        // 늦게 푸시된 커밋을 놓치지 않기 위해서다. sha 기준 upsert 라 중복은 생기지 않는다.
        OffsetDateTime since = (lastSynced != null)
                ? lastSynced.minusDays(props.sync().overlapDays())
                : OffsetDateTime.parse("2000-01-01T00:00:00Z");

        Long syncLogId = jdbc.queryForObject(
                "INSERT INTO sync_log (repository_id, status) VALUES (?, 'RUNNING') RETURNING id",
                Long.class, repoId);

        try {
            int saved = 0;
            String cursor = null;
            boolean hasNext = true;

            while (hasNext) {
                Map<String, Object> vars = new HashMap<>();
                vars.put("owner", owner);
                vars.put("name", name);
                vars.put("since", since.toString());
                vars.put("cursor", cursor);

                Map<String, Object> data = github.query(HISTORY_QUERY, vars);
                Map<String, Object> branch = obj(data, "repository", "defaultBranchRef");
                if (branch.isEmpty()) {
                    log.warn("{}/{}: 기본 브랜치를 찾을 수 없음 (빈 저장소이거나 접근 권한 없음)", owner, name);
                    break;
                }
                jdbc.update("UPDATE repository SET default_branch = ? WHERE id = ?",
                        str(branch, "name", "main"), repoId);

                Map<String, Object> history = obj(branch, "target", "history");
                for (Object c : list(history, "nodes")) {
                    saved += saveCommit(repoId, c);
                }

                Map<String, Object> page = obj(history, "pageInfo");
                hasNext = bool(page, "hasNextPage");
                cursor = str(page, "endCursor");
            }

            jdbc.update("UPDATE repository SET last_synced_at = now() WHERE id = ?", repoId);
            jdbc.update("UPDATE sync_log SET status='OK', finished_at=now(), new_commits=? WHERE id=?",
                    saved, syncLogId);
            log.info("{}/{}: 커밋 {}건 저장", owner, name, saved);
            return saved;

        } catch (Exception e) {
            jdbc.update("UPDATE sync_log SET status='FAILED', finished_at=now(), error_message=? WHERE id=?",
                    e.getMessage(), syncLogId);
            throw e;
        }
    }

    /** 커밋 1건 저장. sha 가 이미 있으면 값만 갱신한다. */
    private int saveCommit(long repoId, Object commit) {
        Map<String, Object> author = obj(commit, "author");
        String email = str(author, "email");
        String authorName = str(author, "name");

        Long accountId = null;
        if (present(author, "user")) {
            Map<String, Object> user = obj(author, "user");
            accountId = resolveAccount(str(user, "login"), num(user, "databaseId", 0L));
            linkEmail(email, accountId);
        } else if (email != null) {
            // GitHub 계정과 연결되지 않은 커밋 → 전에 본 이메일이면 그 계정으로 붙인다
            accountId = jdbc.query("SELECT account_id FROM commit_email WHERE email = ?",
                    rs -> rs.next() ? rs.getLong("account_id") : null, email);
        }

        return jdbc.update("""
                INSERT INTO commit_log (repository_id, sha, author_id, author_email, author_name,
                                        committed_at, message_head, additions, deletions,
                                        changed_files, is_merge)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (repository_id, sha) DO UPDATE SET
                    author_id     = EXCLUDED.author_id,
                    message_head  = EXCLUDED.message_head,
                    additions     = EXCLUDED.additions,
                    deletions     = EXCLUDED.deletions,
                    changed_files = EXCLUDED.changed_files
                """,
                repoId,
                str(commit, "oid"),
                accountId,
                email,
                authorName,
                OffsetDateTime.parse(str(commit, "committedDate")),
                trim(str(commit, "messageHeadline", ""), 256),
                num(commit, "additions", 0),
                num(commit, "deletions", 0),
                num(commit, "changedFilesIfAvailable", 0),
                num(obj(commit, "parents"), "totalCount", 1) > 1);
    }

    /** 계정이 없으면 만든다. 봇으로 보이면 집계에서 빠지도록 표시해둔다. */
    private Long resolveAccount(String login, long githubId) {
        if (login == null || login.isBlank()) return null;

        Long id = jdbc.query("SELECT id FROM github_account WHERE login = ? OR github_id = ?",
                rs -> rs.next() ? rs.getLong("id") : null, login, githubId);
        if (id != null) {
            jdbc.update("UPDATE github_account SET github_id = ? WHERE id = ? AND github_id IS NULL",
                    githubId, id);
            return id;
        }
        String lower = login.toLowerCase();
        boolean bot = lower.endsWith("[bot]") || lower.endsWith("-bot") || lower.endsWith("bot");
        return jdbc.queryForObject(
                "INSERT INTO github_account (login, github_id, is_bot) VALUES (?,?,?) RETURNING id",
                Long.class, login, githubId, bot);
    }

    /** 이메일을 계정에 붙이고, 학교 메일이면 학번도 채운다. */
    private void linkEmail(String email, Long accountId) {
        if (email == null || email.isBlank() || accountId == null) return;

        jdbc.update("""
                INSERT INTO commit_email (email, account_id) VALUES (?,?)
                ON CONFLICT (email) DO NOTHING
                """, email, accountId);

        Matcher m = STUDENT_ID.matcher(email);
        if (m.matches()) {
            // 이미 들어 있는 학번은 덮어쓰지 않는다 (사람이 고쳐둔 값 보호)
            jdbc.update("""
                    UPDATE member SET student_id = ?
                     WHERE id = (SELECT member_id FROM github_account WHERE id = ?)
                       AND student_id IS NULL
                    """, m.group(1), accountId);
        }
    }

    private static String trim(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) : s;
    }
}
