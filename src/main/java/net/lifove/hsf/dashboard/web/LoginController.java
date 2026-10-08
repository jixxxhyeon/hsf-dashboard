package net.lifove.hsf.dashboard.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 로그인 화면.
 *
 * Thymeleaf 템플릿으로 두는 이유: 로그인 폼에는 CSRF 토큰이 들어가야 하는데,
 * 정적 HTML 에는 그 값을 넣을 수 없다.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /**
     * 화면 주소 → index.html.
     *
     * 화면은 '#' 없는 실제 주소(/members, /members/{login}, /admin/report …)를 쓴다.
     * 그 주소로 바로 들어오거나 새로고침하면 서버가 요청을 받으므로, 여기서 같은 index.html 을 돌려주고
     * 어느 화면을 그릴지는 브라우저가 주소를 보고 정한다.
     *
     * /admin 아래는 SecurityConfig 가 로그인을 강제한다 — 로그인 안 했으면 여기까지 오지 못하고 /login 으로 간다.
     * /api, /login, /logout 과 겹치지 않도록 화면 경로만 정확히 적는다.
     */
    @GetMapping({"/members", "/members/", "/members/{login}",
                 "/admin", "/admin/", "/admin/overview", "/admin/report",
                 "/admin/members", "/admin/members/", "/admin/members/{login}"})
    public String page() {
        return "forward:/index.html";
    }
}
