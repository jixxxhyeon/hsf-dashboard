package net.lifove.hsf.dashboard.sync;

import net.lifove.hsf.dashboard.config.HsfProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * GitHub GraphQL API 호출만 담당한다. 파싱과 저장은 CommitSyncService 가 한다.
 * 응답은 Jackson 타입이 아니라 Map 으로 받는다 (Json 클래스 주석 참고).
 */
@Component
public class GithubGraphQlClient {

    private static final Logger log = LoggerFactory.getLogger(GithubGraphQlClient.class);

    private static final int MAX_ATTEMPTS = 3;

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

    /**
     * 쿼리 1회 실행.
     * GitHub 은 대용량 저장소에서 간헐적으로 502 를 돌려주므로 몇 번 다시 시도한다.
     */
    public Map<String, Object> query(String query, Map<String, Object> variables) {
        RuntimeException lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return execute(query, variables);
            } catch (RestClientException e) {
                lastError = e;
                log.warn("GitHub 호출 실패 ({}/{}회): {}", attempt, MAX_ATTEMPTS, shortMessage(e));
                sleep(1000L * attempt);
            }
        }
        throw lastError;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query, Map<String, Object> variables) {
        Map<String, Object> body = client.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query, "variables", variables))
                .retrieve()
                .body(Map.class);

        if (body == null) {
            throw new IllegalStateException("GitHub 응답이 비어 있습니다.");
        }

        Object errors = body.get("errors");
        Map<String, Object> data = Json.obj(body, "data");

        // GraphQL 은 "일부만 실패"가 정상적인 응답이다.
        // 예: 변경량이 너무 큰 커밋은 additions 를 못 준다(SERVICE_UNAVAILABLE).
        // 이때 나머지 데이터는 멀쩡하므로 통째로 버리지 않고 경고만 남긴다.
        if (errors != null) {
            if (data.isEmpty()) {
                throw new IllegalStateException("GitHub GraphQL 오류: " + errors);
            }
            log.warn("GitHub 부분 오류 (받은 데이터는 그대로 사용): {}", errors);
        }
        return data;
    }

    private static String shortMessage(Exception e) {
        String m = e.getMessage();
        return (m != null && m.length() > 200) ? m.substring(0, 200) + "..." : m;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동기화가 중단되었습니다.", ie);
        }
    }
}
