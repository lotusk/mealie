package io.mealie.backend.organizers;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.compat.PySlugify;
import io.mealie.backend.compat.PyStr;
import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.events.EventBridge;
import io.mealie.backend.persistence.model.CategoryRow;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.recipe.RecipeRepository;
import io.mealie.backend.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CategoryService {
    static final String LIST_ROUTE = "/categories";
    public record CategorySummary(UUID id, String slug, String name) {}
    public record CategoryBase(String name, UUID id, UUID groupId, String slug) {}
    public record CategoryResponse(String name, UUID id, UUID groupId, String slug, long recipeCount) {}
    public record SlugResponse(String name, UUID id, UUID groupId, String slug, List<Map<String,Object>> recipes) {}

    private final CategoryRepository repository;
    private final RecipeRepository recipes;
    private final EventBridge events;
    private final TransactionTemplate transaction;

    public CategoryService(CategoryRepository repository, RecipeRepository recipes, EventBridge events,
            TransactionTemplate transaction, SqlDialect dialect) {
        this.repository=repository; this.recipes=recipes; this.events=events; this.transaction=transaction;
    }

    public Pagination<CategoryResponse> list(AuthUser user, PaginationQuery query, String search) {
        return repository.page(user.groupId(),query,search,LIST_ROUTE,CategoryService::response);
    }
    public List<CategoryBase> empty(AuthUser user) {
        return repository.findEmpty(user.groupId()).stream().map(r -> new CategoryBase(r.name(),r.id(),r.groupId(),r.slug())).toList();
    }
    public CategorySummary get(AuthUser user, UUID id) {
        return repository.findById(user.groupId(),id).map(CategoryService::summary)
                .orElseThrow(() -> ApiException.errorResponse(HttpStatus.NOT_FOUND,"Not found.",null));
    }
    public SlugResponse getBySlug(AuthUser user,String slug) {
        CategoryRow row=repository.findBySlug(user.groupId(),slug)
                .orElseThrow(() -> ApiException.errorResponse(HttpStatus.NOT_FOUND,"Not found.",null));
        return new SlugResponse(row.name(),row.id(),null,row.slug(),recipes.summaries(repository.recipeIds(user.groupId(),row.id()),user.groupId()));
    }
    public CategoryResponse create(AuthUser user,String raw,String language) {
        requireOrganizer(user); String name=PyStr.strip(raw);
        if(name.isEmpty()) throw ApiException.errorResponse(HttpStatus.BAD_REQUEST,"An unexpected error occurred.","");
        CategoryRow row;
        try { row=transaction.execute(s -> repository.insert(user.groupId(),name,PySlugify.slugify(name))); }
        catch(RuntimeException e) { throw ApiException.errorResponse(HttpStatus.CONFLICT,"This item already exists.",e.getMessage()); }
        publish(user,"category_created","create",row,"notifications.generic-created-with-url",true,language);
        return response(row);
    }
    public CategoryResponse update(AuthUser user,UUID id,String raw,String language) {
        requireOrganizer(user); String name=PyStr.strip(raw);
        if(name.isEmpty()) throw ApiException.errorResponse(HttpStatus.BAD_REQUEST,"An unexpected error occurred.","");
        CategoryRow updated;
        try { updated=transaction.execute(s -> {
            CategoryRow old=repository.findById(user.groupId(),id).orElseThrow(() -> ApiException.errorResponse(HttpStatus.NOT_FOUND,"Not found.",null));
            String slug=PySlugify.slugify(name);
            if(!old.name().equals(name)||!old.slug().equals(slug)) repository.update(id,user.groupId(),name,slug);
            return new CategoryRow(old.id(),old.groupId(),name,slug,old.createdAt(),old.updateAt(),old.recipeCount());
        }); } catch(RuntimeException e) { if(e instanceof ApiException) throw e; throw ApiException.errorResponse(HttpStatus.CONFLICT,"This item already exists.",e.getMessage()); }
        publish(user,"category_updated","update",updated,"notifications.generic-updated-with-url",true,language);
        return response(updated);
    }
    public void delete(AuthUser user,UUID id,String language) {
        requireOrganizer(user);
        CategoryRow row;
        try { row=transaction.execute(s -> {
            CategoryRow found=repository.findById(user.groupId(),id).orElseThrow(() -> new IllegalStateException("No row was found when one was required"));
            repository.delete(id); return found;
        }); } catch(RuntimeException e) {
            if(e.getMessage()!=null && e.getMessage().contains("No row was found")) throw ApiException.errorResponse(HttpStatus.NOT_FOUND,"An unexpected error occurred.","No row was found when one was required");
            throw ApiException.errorResponse(HttpStatus.CONFLICT,"An unexpected error occurred.",e.getMessage());
        }
        publish(user,"category_deleted","delete",row,"notifications.generic-deleted",false,language);
    }
    public CategoryResponse merge(AuthUser user,UUID from,UUID to) {
        requireOrganizer(user);
        if(from.equals(to)) throw new ApiException(HttpStatus.BAD_REQUEST,"from_id and to_id must be different",Map.of());
        if(repository.findById(user.groupId(),from).isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"from_id category not found",Map.of());
        if(repository.findById(user.groupId(),to).isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"to_id category not found",Map.of());
        return transaction.execute(s -> { repository.merge(from,to); return response(repository.findById(user.groupId(),to).orElseThrow()); });
    }
    private static void requireOrganizer(AuthUser u) { if(!u.canOrganize()) throw new ApiException(HttpStatus.FORBIDDEN); }
    private void publish(AuthUser u,String type,String op,CategoryRow r,String key,boolean url,String language) {
        Map<String,Object> data=new LinkedHashMap<>(); data.put("documentType","category");data.put("operation",op);data.put("categoryId",r.id());
        events.publish(new EventBridge.Event(u.integrationId(),r.groupId(),null,type,data,
                new EventBridge.Message(key,Map.of("name",r.name()),url?"category":null,url?r.slug():null)),language);
    }
    private static CategorySummary summary(CategoryRow r) { return new CategorySummary(r.id(),r.slug(),r.name()); }
    private static CategoryResponse response(CategoryRow r) { return new CategoryResponse(r.name(),r.id(),r.groupId(),r.slug(),r.recipeCount()); }
}
