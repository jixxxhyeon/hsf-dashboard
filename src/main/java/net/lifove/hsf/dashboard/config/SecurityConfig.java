package net.lifove.hsf.dashboard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 접근 제어.
 *
 *  - 공개: 대시보드 화면, /api/bootstrap
 *  - 관리자: /api/admin/**  (명단, 동기화, 회원 관리)
 *
 * 관리자 계정은 하나뿐이고 환경변수로 넣는다.
 *   export HSF_ADMIN_USER=hsf
 *   export HSF_ADMIN_PASSWORD=...
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService adminUser(HsfProperties props, PasswordEncoder encoder) {
        String password = props.admin().password();
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("""
                관리자 비밀번호가 없습니다.
                터미널에서 export HSF_ADMIN_PASSWORD=... 로 넣고 다시 실행하세요.""");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(props.admin().username())
                    .password(encoder.encode(password))
                    .roles("ADMIN")
                    .build());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().permitAll())

            // 로그인 화면은 직접 만든 것을 쓴다 (templates/login.html).
            // Spring Security 가 만들어주던 기본 화면은 최신 버전에서 사라졌다.
            .formLogin(form -> form
                    .loginPage("/login")
                    .defaultSuccessUrl("/#/report", true)
                    .permitAll())
            .logout(out -> out.logoutSuccessUrl("/login?logout"))

            // 화면이 fetch 로 부르는 API 는 로그인 페이지로 넘기지 말고 401 을 준다.
            // 그래야 화면이 "로그인이 필요합니다" 를 직접 보여줄 수 있다.
            // 경로를 직접 보는 이유: 매처 클래스는 Spring Security 버전마다 바뀌어 왔다.
            .exceptionHandling(ex -> ex.authenticationEntryPoint((req, res, e) -> {
                if (req.getRequestURI().startsWith("/api/")) {
                    res.sendError(401, "로그인이 필요합니다");
                } else {
                    res.sendRedirect(req.getContextPath() + "/login");
                }
            }))

            // /api/** 는 curl 로도 부르므로 CSRF 를 면제한다.
            // /logout 도 면제 — 남이 로그아웃시켜봐야 피해가 없다.
            // 로그인 폼(POST /login)에는 CSRF 가 그대로 걸려 있다. 그게 실제로 중요한 쪽이다.
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/logout"));

        return http.build();
    }
}
