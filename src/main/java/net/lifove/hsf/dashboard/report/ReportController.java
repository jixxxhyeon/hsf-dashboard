package net.lifove.hsf.dashboard.report;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기간별 활동 참여자 명단 — 이 프로젝트를 만든 이유.
 *
 * 기존 구조에서는 미리 계산해둔 기간만 볼 수 있었다. 커밋 원본을 그대로 쌓아두니
 * 아래 한 줄로 임의 기간이 해결된다:  WHERE committed_at >= ? AND committed_at < ?
 */
@RestController
@RequestMapping("/api/admin/reports")
public class ReportController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;

    public ReportController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param from     시작일 (포함)
     * @param to       종료일 (포함) — 내부에서 +1일 미만으로 바꾼다
     * @param projects 프로젝트 slug 목록. 비우면 전체
     * @param minCommits 이 기간에 커밋이 몇 건 이상이어야 명단에 넣을지
     */
    @GetMapping("/active-members")
    public Map<String, Object> activeMembers(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String projects,
            @RequestParam(defaultValue = "1") int minCommits) {

        List<String> slugs = parseSlugs(projects);
        List<Map<String, Object>> rows = query(from, to, slugs, minCommits);

        int totalCommits = rows.stream()
                .mapToInt(r -> ((Number) r.get("commits")).intValue()).sum();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("projects", slugs);
        out.put("minCommits", minCommits);
        out.put("totalCommits", totalCommits);
        out.put("rows", rows);
        return out;
    }

    /** 같은 명단을 CSV 로. 엑셀에서 한글이 깨지지 않도록 BOM 을 붙인다. */
    @GetMapping("/active-members.csv")
    public ResponseEntity<byte[]> activeMembersCsv(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String projects,
            @RequestParam(defaultValue = "1") int minCommits) {

        List<Map<String, Object>> rows = query(from, to, parseSlugs(projects), minCommits);

        // 사업단 제출 양식: GitHub 아이디와 활동 프로젝트명 두 칸만.
        // 화면에는 커밋 수 같은 지표가 더 나오지만, 파일에는 제출에 필요한 것만 담는다.
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("GitHub 아이디,활동 프로젝트\r\n");
        for (Map<String, Object> r : rows) {
            sb.append(csv(r.get("login"))).append(',')
              .append(csv(String.join(" / ", asList(r.get("projects"))))).append("\r\n");
        }

        String filename = "HSF_활동명단_" + from + "_" + to + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(
                                filename, StandardCharsets.UTF_8).replace("+", "%20"))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ── 내부 ──────────────────────────────────────────────────────

    private List<Map<String, Object>> query(LocalDate from, LocalDate to,
                                            List<String> slugs, int minCommits) {
        // 종료일 '당일'을 포함시키기 위해 +1일 미만으로 잡는다.
        // BETWEEN 을 그대로 쓰면 종료일 하루가 통째로 빠진다.
        OffsetDateTime start = from.atStartOfDay(KST).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(KST).toOffsetDateTime();

        List<Object> args = new ArrayList<>(List.of(start, end));
        String projectFilter = "";
        if (!slugs.isEmpty()) {
            projectFilter = " AND p.slug IN (" + "?,".repeat(slugs.size() - 1) + "?)";
            args.addAll(slugs);
        }
        args.add(minCommits);

        String sql = """
                SELECT ga.login,
                       ga.github_id,
                       m.id           AS member_id,
                       m.name         AS member_name,
                       m.role,
                       count(*)                               AS commits,
                       count(DISTINCT (c.committed_at AT TIME ZONE 'Asia/Seoul')::date) AS active_days,
                       min(c.committed_at AT TIME ZONE 'Asia/Seoul')::date::text        AS first_commit,
                       max(c.committed_at AT TIME ZONE 'Asia/Seoul')::date::text        AS last_commit,
                       coalesce(sum(c.additions), 0)          AS additions,
                       coalesce(sum(c.deletions), 0)          AS deletions,
                       string_agg(DISTINCT p.slug, '|')       AS project_slugs,
                       string_agg(DISTINCT p.name, '|')       AS project_names
                  FROM commit_log c
                  JOIN repository r      ON r.id  = c.repository_id AND NOT r.excluded
                  JOIN project p         ON p.id  = r.project_id
                  JOIN github_account ga ON ga.id = c.author_id
                  LEFT JOIN member m     ON m.id  = ga.member_id
                 WHERE c.committed_at >= ?
                   AND c.committed_at <  ?
                   AND NOT ga.is_bot
                """ + projectFilter + """
                 GROUP BY ga.id, ga.login, ga.github_id, m.id, m.name, m.role
                HAVING count(*) >= ?
                 ORDER BY commits DESC, ga.login
                """;

        return jdbc.query(sql, (rs, i) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            String login = rs.getString("login");
            long githubId = rs.getLong("github_id");
            String memberName = rs.getString("member_name");

            r.put("login", login);
            r.put("name", memberName != null ? memberName : login);
            r.put("role", rs.getString("role"));
            r.put("registered", rs.getObject("member_id") != null);
            r.put("avatarUrl", githubId > 0
                    ? "https://avatars.githubusercontent.com/u/" + githubId + "?v=4" : null);
            r.put("projectSlugs", split(rs.getString("project_slugs")));
            r.put("projects", split(rs.getString("project_names")));
            r.put("commits", rs.getInt("commits"));
            r.put("activeDays", rs.getInt("active_days"));
            r.put("first", rs.getString("first_commit"));
            r.put("last", rs.getString("last_commit"));
            r.put("additions", rs.getLong("additions"));
            r.put("deletions", rs.getLong("deletions"));
            return r;
        }, args.toArray());
    }

    private static List<String> parseSlugs(String projects) {
        if (projects == null || projects.isBlank()) return List.of();
        return Arrays.stream(projects.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static List<String> split(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        return Arrays.asList(joined.split("\\|"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object o) {
        return (o instanceof List<?> l) ? (List<String>) l : List.of();
    }

    private static String csv(Object v) {
        String s = (v == null) ? "" : String.valueOf(v);
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
