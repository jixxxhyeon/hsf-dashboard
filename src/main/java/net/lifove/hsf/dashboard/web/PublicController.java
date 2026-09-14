package net.lifove.hsf.dashboard.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공개 화면(개요 / 멤버 / 멤버 상세)이 쓰는 데이터.
 *
 * 화면에서 쓰던 목업과 같은 모양으로 한 번에 내려준다. 개요·히트맵·활동 상태 계산은
 * 브라우저에서 그대로 하고, 서버는 원본만 넘긴다.
 *
 * 지금 규모(커밋 2천 건 미만)에서는 이게 가장 단순하다.
 * 커밋이 만 건을 넘어가면 이 방식 대신 집계 API 를 따로 두는 게 낫다.
 *
 * 학번·학과는 여기 들어가지 않는다. 그 정보는 /api/admin/** 로만 나간다.
 */
@RestController
@RequestMapping("/api")
public class PublicController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;

    public PublicController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/bootstrap")
    public Map<String, Object> bootstrap() {
        // 활동량이 많은 프로젝트가 앞에 온다. 화면은 이 순서대로 색을 배정한다.
        //
        // '전체 기간' 커밋 수로 정렬하는 게 핵심이다. 화면에서 고른 기간으로 정렬하면
        // 필터를 바꿀 때마다 색이 재배치돼서, 같은 색이 다른 프로젝트를 가리키게 된다.
        List<Map<String, Object>> projects = jdbc.queryForList("""
                SELECT p.slug, p.name, p.description,
                       count(c.id) AS commits,
                       string_agg(DISTINCT r.owner || '/' || r.name, '|'
                                  ORDER BY r.owner || '/' || r.name) AS repos
                  FROM project p
                  JOIN repository r      ON r.project_id = p.id AND NOT r.excluded
                  LEFT JOIN commit_log c ON c.repository_id = r.id
                 GROUP BY p.id, p.slug, p.name, p.description
                 ORDER BY count(c.id) DESC, p.slug
                """);
        projects.forEach(p -> p.put("repos", splitPipe((String) p.remove("repos"))));

        List<Map<String, Object>> members = jdbc.queryForList("""
                SELECT ga.login,
                       coalesce(m.name, ga.login) AS name,
                       m.role,
                       m.joined_at::text AS "joinedOn",
                       (m.id IS NOT NULL)  AS registered,
                       CASE WHEN ga.github_id IS NULL THEN NULL
                            ELSE 'https://avatars.githubusercontent.com/u/' || ga.github_id || '?v=4'
                       END AS "avatarUrl"
                  FROM github_account ga
                  LEFT JOIN member m ON m.id = ga.member_id
                 WHERE NOT ga.is_bot
                 ORDER BY ga.login
                """);

        List<Map<String, Object>> commits = jdbc.queryForList("""
                SELECT ga.login,
                       p.slug                       AS project,
                       r.owner || '/' || r.name     AS repo,
                       substring(c.sha, 1, 7)       AS sha,
                       to_char(c.committed_at AT TIME ZONE 'Asia/Seoul',
                               'YYYY-MM-DD"T"HH24:MI:SS+09:00') AS "committedAt",
                       c.message_head               AS message,
                       c.additions, c.deletions
                  FROM commit_log c
                  JOIN repository r      ON r.id  = c.repository_id AND NOT r.excluded
                  JOIN project p         ON p.id  = r.project_id
                  JOIN github_account ga ON ga.id = c.author_id
                 WHERE NOT ga.is_bot
                 ORDER BY c.committed_at
                """);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", LocalDate.now(KST).toString());
        out.put("projects", projects);
        out.put("members", members);
        out.put("commits", commits);
        return out;
    }

    private static List<String> splitPipe(String joined) {
        return (joined == null || joined.isBlank()) ? List.of() : List.of(joined.split("\\|"));
    }
}
