package net.lifove.hsf.dashboard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.yml 의 hsf.* 설정을 담는다.
 * 토큰은 파일에 적지 않고 환경변수 HSF_SYNC_TOKEN 으로 넣는다.
 */
@ConfigurationProperties(prefix = "hsf")
public record HsfProperties(Github github, Sync sync, Admin admin) {

    public record Github(String apiUrl, String token) {}

    public record Sync(boolean enabled, String cron, int overlapDays) {}

    /** 관리자 계정 하나. 비밀번호는 환경변수로만 넣는다. */
    public record Admin(String username, String password) {}
}
