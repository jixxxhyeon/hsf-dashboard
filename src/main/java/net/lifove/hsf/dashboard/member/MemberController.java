package net.lifove.hsf.dashboard.member;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 회원 명부 관리.
 *
 * GitHub 에서 알 수 없는 정보(학번, 학과, 실명)를 채우는 곳이다.
 * 커밋한 사람의 GitHub 계정은 동기화가 자동으로 등록하지만,
 * "이 계정이 누구인지"는 사람이 연결해줘야 한다.
 */
@RestController
@RequestMapping("/api/admin/members")
public class MemberController {

    private final JdbcTemplate jdbc;

    public MemberController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 회원 목록. 활동이 많은 사람이 위로 온다. */
    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT m.id, m.name, m.role, m.joined_at::text AS joined_at,
                       string_agg(ga.login, ', ' ORDER BY ga.login) AS accounts,
                       coalesce(sum(stat.commits), 0) AS commits
                  FROM member m
                  LEFT JOIN github_account ga ON ga.member_id = m.id
                  LEFT JOIN LATERAL (
                        SELECT count(*) AS commits
                          FROM commit_log c
                          JOIN repository r ON r.id = c.repository_id AND NOT r.excluded
                         WHERE c.author_id = ga.id
                  ) stat ON TRUE
                 GROUP BY m.id, m.name, m.role, m.joined_at
                 ORDER BY commits DESC, m.name
                """);
    }

    /**
     * 회원 명부에 없는 계정을 회원으로 등록한다.
     *
     * 이름은 일단 GitHub 로그인으로 넣어둔다. 실명은 나중에 수정할 수 있다.
     * 봇과 커밋이 하나도 없는 계정은 건너뛴다.
     */
    @PostMapping("/from-accounts")
    public Map<String, Object> createFromAccounts() {
        List<Map<String, Object>> orphans = jdbc.queryForList("""
                SELECT ga.id, ga.login, count(c.id) AS commits,
                       min(c.committed_at)::date AS first_commit
                  FROM github_account ga
                  JOIN commit_log c      ON c.author_id = ga.id
                  JOIN repository r      ON r.id = c.repository_id AND NOT r.excluded
                 WHERE ga.member_id IS NULL AND NOT ga.is_bot
                 GROUP BY ga.id, ga.login
                 ORDER BY count(c.id) DESC
                """);

        List<String> created = new ArrayList<>();
        for (Map<String, Object> o : orphans) {
            long accountId = ((Number) o.get("id")).longValue();
            String login = (String) o.get("login");

            Long memberId = jdbc.queryForObject("""
                    INSERT INTO member (name, role, joined_at) VALUES (?, 'contributor', ?)
                    RETURNING id
                    """, Long.class, login, o.get("first_commit"));

            jdbc.update("UPDATE github_account SET member_id = ? WHERE id = ?", memberId, accountId);
            created.add(login);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("count", created.size());
        out.put("stillUnlinked", jdbc.queryForObject(
                "SELECT count(*) FROM github_account WHERE member_id IS NULL AND NOT is_bot",
                Integer.class));
        return out;
    }

    /** 실명·역할 수정. 보낸 항목만 바뀐다. */
    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        for (String[] pair : new String[][]{
                {"name", "name"}, {"role", "role"}}) {
            if (body.containsKey(pair[0])) {
                sets.add(pair[1] + " = CAST(? AS TEXT)");
                Object v = body.get(pair[0]);
                args.add((v == null || String.valueOf(v).isBlank()) ? null : String.valueOf(v));
            }
        }
        if (sets.isEmpty()) return Map.of("updated", 0);

        args.add(id);
        int n = jdbc.update("UPDATE member SET " + String.join(", ", sets) + " WHERE id = ?",
                args.toArray());
        return Map.of("updated", n, "id", id);
    }

    /** 계정 두 개가 같은 사람일 때 합친다. 잘못 만들어진 회원 행은 지워진다. */
    @PostMapping("/{keepId}/merge/{mergeId}")
    public Map<String, Object> merge(@PathVariable long keepId, @PathVariable long mergeId) {
        if (keepId == mergeId) return Map.of("merged", 0);
        int moved = jdbc.update("UPDATE github_account SET member_id = ? WHERE member_id = ?",
                keepId, mergeId);
        jdbc.update("DELETE FROM member WHERE id = ?", mergeId);
        return Map.of("movedAccounts", moved, "keptMember", keepId, "deletedMember", mergeId);
    }
}
