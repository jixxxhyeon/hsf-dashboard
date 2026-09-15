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
}
