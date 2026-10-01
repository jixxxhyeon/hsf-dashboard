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
     * 관리자 화면. 공개 화면과 같은 index.html 을 쓰고, 화면이 주소(/admin)를 보고 관리자 메뉴를 연다.
     * 로그인 강제는 SecurityConfig 가 한다 — 로그인 안 했으면 여기까지 오지 못하고 /login 으로 간다.
     */
    @GetMapping({"/admin", "/admin/"})
    public String admin() {
        return "forward:/index.html";
    }
}
