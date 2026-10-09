package io.mealie.backend.tags;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.model.TagRow;
import io.mealie.backend.web.ApiException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TagService {
    private final TagRepository repo;
    private final SqlDialect dialect;
    public TagService(TagRepository repo, SqlDialect dialect) { this.repo = repo; this.dialect = dialect; }

    @Transactional(readOnly = true)
    public Map<String, Object> list(AuthUser user, Map<String, String> params) {
        TagQuery query = new TagQuery(dialect, user.groupId(), params);
        long total = repo.count(query.sql);
        long perPage = query.requestedPerPage == -1 ? total : query.requestedPerPage;
        long pages = perPage == 0 ? 0 : (long) Math.ceil((double) total / perPage);
        long page = Math.max(1, query.requestedPage == -1 ? pages : query.requestedPage);
        List<TagRow> rows;
        if ("random".equals(params.get("orderBy"))) {
            rows = new ArrayList<>(repo.list(query.sql));
            PythonRandom random = new PythonRandom(params.get("paginationSeed"));
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) order.add(i);
            random.shuffle(order);
            // Python assigns shuffled rank values to the original id sequence, then sorts by rank.
            List<TagRow> original = rows;
            rows = java.util.stream.IntStream.range(0, rows.size()).boxed()
                    .sorted(java.util.Comparator.comparing(order::get)).map(original::get).toList();
            int start = (int) Math.min(rows.size(), (page - 1) * perPage);
            int end = query.requestedPerPage < 0 ? rows.size() : (int) Math.min(rows.size(), start + perPage);
            rows = rows.subList(start, Math.max(start, end));
        } else {
            if (query.requestedPerPage >= 0) {
                query.sql.put("limit", perPage);
                query.sql.put("offset", (page - 1) * perPage);
            }
            rows = repo.list(query.sql);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", page); result.put("per_page", perPage); result.put("total", total); result.put("total_pages", pages);
        result.put("items", rows.stream().map(this::out).toList());
        result.put("next", page >= pages ? null : guide(params, page + 1));
        result.put("previous", page <= 1 ? null : guide(params, page - 1));
        return result;
    }
    private String guide(Map<String, String> params, long page) {
        Map<String, String> query = new LinkedHashMap<>();
        for (String key : List.of("orderBy", "orderByNullPosition", "orderDirection", "queryFilter", "paginationSeed", "page", "perPage")) {
            query.put(key, params.getOrDefault(key, key.equals("orderDirection") ? "desc" : key.equals("page") ? "1" : key.equals("perPage") ? "50" : "None"));
        }
        query.put("page", Long.toString(page));
        return "/tags?" + String.join("&", query.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).toList());
    }
    @Transactional(readOnly = true)
    public List<Map<String, Object>> empty(AuthUser user) {
        return repo.list(Map.of("groupId", dialect.uuid(user.groupId()), "empty", true)).stream().map(row -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", row.id()); out.put("group_id", row.groupId()); out.put("name", row.name()); out.put("slug", row.slug());
            out.put("created_at", naive(row.createdAt())); out.put("update_at", naive(row.updateAt())); out.put("recipe_count", 0);
            return out;
        }).toList();
    }
    private String naive(java.time.OffsetDateTime date) {
        return date == null ? null : TagRepository.format(date.toLocalDateTime()) + "+00:00";
    }
    @Transactional(readOnly = true)
    public Map<String, Object> get(AuthUser user, UUID id, String slug) {
        TagRow row = required(user, id, slug);
        return response(row, slug == null ? List.of() : repo.recipes(user.groupId(), row.id(), row.recipeCount()));
    }
    @Transactional
    public Map<String, Object> create(AuthUser user, String name) {
        canOrganize(user);
        UUID id = UUID.randomUUID();
        try {
            repo.save(user.groupId(), id, name.strip(), true);
        } catch (DataAccessException e) {
            if (dialect.isUniqueViolation(e)) throw conflict(e);
            throw TagQuery.bad("An unexpected error occurred.");
        }
        return out(required(user, id, null));
    }
    @Transactional
    public Map<String, Object> update(AuthUser user, UUID id, String name) {
        canOrganize(user);
        required(user, id, null);
        try {
            repo.save(user.groupId(), id, name.strip(), false);
        } catch (DataAccessException e) {
            if (dialect.isUniqueViolation(e)) throw conflict(e);
            throw TagQuery.bad("An unexpected error occurred.");
        }
        return response(required(user, id, null), List.of());
    }
    @Transactional
    public TagRow delete(AuthUser user, UUID id) {
        canOrganize(user);
        TagRow row = repo.find(user.groupId(), id, null);
        if (row == null) throw new ApiException(HttpStatus.BAD_REQUEST);
        try { repo.delete(user.groupId(), id); }
        catch (DataAccessException e) { throw new ApiException(HttpStatus.BAD_REQUEST); }
        return row;
    }
    @Transactional
    public Map<String, Object> merge(AuthUser user, UUID from, UUID to) {
        canOrganize(user);
        if (from.equals(to)) throw TagQuery.bad("from_id and to_id must be different");
        if (repo.find(user.groupId(), from, null) == null) throw new ApiException(HttpStatus.NOT_FOUND, "from_id tag not found", Map.of());
        if (repo.find(user.groupId(), to, null) == null) throw new ApiException(HttpStatus.NOT_FOUND, "to_id tag not found", Map.of());
        repo.merge(user.groupId(), from, to);
        return out(required(user, to, null));
    }
    private void canOrganize(AuthUser user) {
        if (!repo.canOrganize(user.id())) throw new ApiException(HttpStatus.FORBIDDEN);
    }
    private TagRow required(AuthUser user, UUID id, String slug) {
        TagRow row = repo.find(user.groupId(), id, slug);
        if (row == null) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("message", "Not found."); detail.put("error", true); detail.put("exception", null);
            throw new TagApiException(HttpStatus.NOT_FOUND, detail);
        }
        return row;
    }
    private TagApiException conflict(Exception error) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("message", "This item already exists."); detail.put("error", true); detail.put("exception", error.getMessage());
        return new TagApiException(HttpStatus.CONFLICT, detail);
    }
    Map<String, Object> out(TagRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.id()); out.put("groupId", row.groupId()); out.put("name", row.name()); out.put("slug", row.slug());
        out.put("recipeCount", row.recipeCount());
        return out;
    }
    private Map<String, Object> response(TagRow row, List<Map<String, Object>> recipes) {
        Map<String, Object> out = out(row); out.remove("recipeCount"); out.put("recipes", recipes); return out;
    }
}
