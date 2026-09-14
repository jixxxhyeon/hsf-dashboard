package net.lifove.hsf.dashboard.sync;

import net.lifove.hsf.dashboard.config.HsfProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * GitHub GraphQL API 호출만 담당한다. 파싱과 저장은 CommitSyncService 가 한다.
 * 응답은 Jackson 타입이 아니라 Map 으로 받는다 (Json 클래스 주석 참고).
 */
@Component
public class GithubGraphQlClient {

    private final RestClient client;

    public GithubGraphQlClient(HsfProperties props) {
        String token = props.github().token();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("""
                GitHub 토큰이 없습니다.
                터미널에서 export HSF_SYNC_TOKEN=... 로 넣고 다시 실행하세요.""");
        }
        this.client = RestClient.builder()
                .baseUrl(props.github().apiUrl())
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Accept", "application/vnd.github+json")
                .build();
    }

    /** 쿼리 1회 실행. GraphQL 은 오류도 HTTP 200 으로 오므로 errors 를 직접 확인한다. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> query(String query, Map<String, Object> variables) {
        Map<String, Object> body = client.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query, "variables", variables))
                .retrieve()
                .body(Map.class);

        if (body == null) {
            throw new IllegalStateException("GitHub 응답이 비어 있습니다.");
        }
        if (body.get("errors") != null) {
            throw new IllegalStateException("GitHub GraphQL 오류: " + body.get("errors"));
        }
        return Json.obj(body, "data");
    }
}
