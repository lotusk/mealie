package io.mealie.backend.tags;

import io.mealie.backend.auth.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organizers/tags")
public class TagController {
    private final TagService service;
    private final TagEventPublisher events;
    public TagController(TagService service, TagEventPublisher events) { this.service = service; this.events = events; }

    @GetMapping({"", "/"})
    public Map<String, Object> list(AuthUser user, @RequestParam Map<String, String> query) { return service.list(user, query); }
    @GetMapping({"/empty", "/empty/"})
    public List<Map<String, Object>> empty(AuthUser user) { return service.empty(user); }
    @GetMapping("/{id}")
    public Map<String, Object> get(AuthUser user, @PathVariable String id) { return service.get(user, id(id, "path", "item_id"), null); }
    @GetMapping("/slug/{slug}")
    public Map<String, Object> slug(AuthUser user, @PathVariable String slug) { return service.get(user, null, slug); }
    @PostMapping({"", "/"})
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(AuthUser user, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        var result = service.create(user, name(body));
        events.publish("create", result, request);
        return result;
    }
    @PutMapping("/{id}")
    public Map<String, Object> update(AuthUser user, @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        var result = service.update(user, id(id, "path", "item_id"), name(body));
        events.publish("update", result, request);
        return result;
    }
    @DeleteMapping(value = "/{id}", produces = "application/json")
    public String delete(AuthUser user, @PathVariable String id, HttpServletRequest request) {
        var row = service.delete(user, id(id, "path", "item_id"));
        events.publish("delete", service.out(row), request);
        return "null";
    }
    @PostMapping({"/merge", "/merge/"})
    public Map<String, Object> merge(AuthUser user, @RequestBody Map<String, Object> body) {
        return service.merge(user, id(value(body, "fromId", "from_id"), "body", "fromId"),
                id(value(body, "toId", "to_id"), "body", "toId"));
    }
    private Object value(Map<String, Object> body, String alias, String field) { return body.containsKey(alias) ? body.get(alias) : body.get(field); }
    private String name(Map<String, Object> body) {
        Object value = body.get("name");
        if (!(value instanceof String text)) throw validation("body", "name", value == null ? "missing" : "string_type",
                value == null ? "Field required" : "Input should be a valid string", value);
        if (text.strip().isEmpty()) throw TagQuery.bad("An unexpected error occurred.");
        return text;
    }
    private UUID id(Object value, String location, String field) {
        try {
            UUID id = TagRepository.uuid(value);
            if (id == null || id.version() != 4) throw new IllegalArgumentException();
            return id;
        } catch (RuntimeException e) {
            throw validation(location, field, value == null ? "missing" : "uuid_parsing",
                    value == null ? "Field required" : "Input should be a valid UUID", value);
        }
    }
    private TagApiException validation(String location, String field, String type, String message, Object input) {
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("type", type); detail.put("loc", List.of(location, field)); detail.put("msg", message); detail.put("input", input);
        return new TagApiException(HttpStatus.UNPROCESSABLE_ENTITY, List.of(detail));
    }
}
