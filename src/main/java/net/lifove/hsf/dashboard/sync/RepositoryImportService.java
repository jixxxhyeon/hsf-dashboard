package net.lifove.hsf.dashboard.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.lifove.hsf.dashboard.sync.Json.*;

/**
 * 조직(HandongSF)의 저장소를 통째로 등록한다.
 *
 * 이미 등록된 저장소는 건드리지 않는다. 그래서 histudy-fe / histudy-be 처럼
 * 손으로 묶어둔 프로젝트 구성이 이 작업 때문에 흐트러지지 않는다.
 */
@Service
public class RepositoryImportService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryImportService.class);

    private static final String REPOS_QUERY = """
        query($org:String!, $cursor:String) {
          organization(login:$org) {
            repositories(first:100, after:$cursor, orderBy:{field:NAME, direction:ASC}) {
              pageInfo { hasNextPage endCursor }
              nodes {
                name description isFork isArchived isEmpty isPrivate
                defaultBranchRef { name }
              }
            }
          }
        }
        """;

    private final JdbcTemplate jdbc;
    private final GithubGraphQlClient github;

    public RepositoryImportService(JdbcTemplate jdbc, GithubGraphQlClient github) {
        this.jdbc = jdbc;
        this.github = github;
    }

    /**
     * @param org              조직 이름 (HandongSF)
     * @param includeArchived  보관 처리된 저장소도 등록할지. 과거 기여 이력이 필요하면 true.
     */
    public Map<String, Object> importOrg(String org, boolean includeArchived) {
        List<String> added = new ArrayList<>();
        List<String> already = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        String cursor = null;
        boolean hasNext = true;

        while (hasNext) {
            Map<String, Object> vars = new HashMap<>();
            vars.put("org", org);
            vars.put("cursor", cursor);

            Map<String, Object> data = github.query(REPOS_QUERY, vars);
            Map<String, Object> repos = obj(data, "organization", "repositories");
            if (repos.isEmpty()) {
                throw new IllegalStateException(
                        "조직 '" + org + "' 의 저장소를 읽을 수 없습니다. 이름이 맞는지, 토큰 권한이 있는지 확인하세요.");
            }

            for (Object r : list(repos, "nodes")) {
                String name = str(r, "name");

                // 포크는 남의 코드라 기여 집계 대상이 아니다
                if (bool(r, "isFork")) { skipped.add(name + " (포크)"); continue; }
                // 커밋이 하나도 없는 저장소는 등록해도 볼 게 없다
                if (bool(r, "isEmpty")) { skipped.add(name + " (빈 저장소)"); continue; }
                if (!includeArchived && bool(r, "isArchived")) { skipped.add(name + " (보관됨)"); continue; }

                if (registerRepository(org, name, r)) added.add(name);
                else already.add(name);
            }

            Map<String, Object> page = obj(repos, "pageInfo");
            hasNext = bool(page, "hasNextPage");
            cursor = str(page, "endCursor");
        }

        log.info("{} 저장소 등록: 신규 {}건, 기존 {}건, 제외 {}건", org, added.size(), already.size(), skipped.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("added", added);
        result.put("alreadyRegistered", already);
        result.put("skipped", skipped);
        result.put("totalRegistered", jdbc.queryForObject("SELECT count(*) FROM repository", Integer.class));
        return result;
    }

    /** @return 새로 등록했으면 true, 이미 있었으면 false */
    private boolean registerRepository(String owner, String name, Object node) {
        // count(*) 로 세는 이유: rs -> rs.next() 는 JdbcTemplate 의 두 오버로드에 모두 맞아
        // 컴파일러가 어느 쪽인지 고르지 못한다.
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM repository WHERE owner = ? AND name = ?",
                Integer.class, owner, name);
        if (found != null && found > 0) return false;

        // 저장소 하나당 프로젝트 하나로 만든다.
        // 여러 저장소를 한 프로젝트로 묶고 싶으면 등록 후 SQL 로 project_id 만 바꾸면 된다.
        String slug = slugify(name);
        Long projectId = jdbc.query("SELECT id FROM project WHERE slug = ?",
                rs -> rs.next() ? rs.getLong(1) : null, slug);

        if (projectId == null) {
            projectId = jdbc.queryForObject("""
                    INSERT INTO project (slug, name, description, status)
                    VALUES (?,?,?,?) RETURNING id
                    """, Long.class,
                    slug, name, str(node, "description"),
                    bool(node, "isArchived") ? "archived" : "active");
        }

        jdbc.update("""
                INSERT INTO repository (project_id, owner, name, default_branch)
                VALUES (?,?,?,?)
                """, projectId, owner, name, str(obj(node, "defaultBranchRef"), "name", "main"));
        return true;
    }

    /** jChecker-Engine → jchecker-engine */
    private static String slugify(String name) {
        String s = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isBlank() ? name.toLowerCase() : s;
    }
}
