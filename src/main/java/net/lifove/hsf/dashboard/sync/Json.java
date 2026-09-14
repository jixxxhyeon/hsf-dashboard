package net.lifove.hsf.dashboard.sync;

import java.util.List;
import java.util.Map;

/**
 * GraphQL 응답(중첩된 Map)을 안전하게 타고 들어가기 위한 도우미.
 *
 * Jackson 타입(JsonNode)을 쓰지 않는 이유: Spring Boot 4 에서 Jackson 3 로 올라가며
 * 패키지 경로가 바뀌었다. Map 으로 받으면 라이브러리 버전과 무관해진다.
 *
 * 없는 키를 물어보면 예외 대신 빈 값을 돌려준다. GraphQL 응답은 필드가 null 인 경우가 흔하다.
 */
final class Json {

    private Json() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object node, String key) {
        if (!(node instanceof Map<?, ?> m)) return Map.of();
        Object v = m.get(key);
        return (v instanceof Map<?, ?>) ? (Map<String, Object>) v : Map.of();
    }

    /** 여러 단계를 한 번에: obj(data, "repository", "defaultBranchRef") */
    static Map<String, Object> obj(Object node, String... keys) {
        Object cur = node;
        for (String k : keys) cur = obj(cur, k);
        return (cur instanceof Map<?, ?>) ? cast(cur) : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object node, String key) {
        if (!(node instanceof Map<?, ?> m)) return List.of();
        Object v = m.get(key);
        return (v instanceof List<?>) ? (List<Object>) v : List.of();
    }

    static String str(Object node, String key) {
        if (!(node instanceof Map<?, ?> m)) return null;
        Object v = m.get(key);
        return (v == null) ? null : String.valueOf(v);
    }

    static String str(Object node, String key, String fallback) {
        String v = str(node, key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    static int num(Object node, String key, int fallback) {
        if (!(node instanceof Map<?, ?> m)) return fallback;
        Object v = m.get(key);
        return (v instanceof Number n) ? n.intValue() : fallback;
    }

    static long num(Object node, String key, long fallback) {
        if (!(node instanceof Map<?, ?> m)) return fallback;
        Object v = m.get(key);
        return (v instanceof Number n) ? n.longValue() : fallback;
    }

    static boolean bool(Object node, String key) {
        if (!(node instanceof Map<?, ?> m)) return false;
        return Boolean.TRUE.equals(m.get(key));
    }

    /** 값이 실제로 있는지 (GraphQL 에서 author.user 는 봇/미연결 계정이면 null) */
    static boolean present(Object node, String key) {
        return node instanceof Map<?, ?> m && m.get(key) != null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }
}
