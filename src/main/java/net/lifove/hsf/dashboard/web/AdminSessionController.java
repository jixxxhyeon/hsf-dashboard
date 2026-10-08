package net.lifove.hsf.dashboard.web;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 모드 확인용.
 *
 * 화면은 처음 뜰 때 이 API 를 불러 본다.
 *   200 → 로그인된 상태. 사이드바에 관리 메뉴(명단 뽑기)를 연다.
 *   401 → 공개 모드. 관리 메뉴를 숨기고 "관리자 모드" 진입 버튼만 둔다.
 *
 * /api/admin/** 아래에 두었기 때문에 인증 규칙은 SecurityConfig 가 그대로 적용한다.
 */
@RestController
public class AdminSessionController {

    private final JdbcTemplate jdbc;

    public AdminSessionController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/admin/me")
    public Map<String, Object> me(Principal principal) {
        return Map.of("username", principal.getName());
    }

    /**
     * GitHub 아이디 → 실명. 관리자 모드 화면이 공개 데이터에 실명을 덧입힐 때 쓴다.
     * 공개 API(/api/bootstrap)에는 실명이 없으므로, 실명은 이 경로로만 나간다.
     */
    @GetMapping("/api/admin/names")
    public List<Map<String, Object>> names() {
        return jdbc.queryForList("""
                SELECT ga.login, m.name
                  FROM github_account ga
                  JOIN member m ON m.id = ga.member_id
                 WHERE m.name IS NOT NULL AND m.name <> ''
                 ORDER BY ga.login
                """);
    }
}
