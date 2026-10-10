package io.mealie.backend.organizers;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.compat.PyValidate;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.web.validation.PyRequestBody;
import io.mealie.backend.web.validation.PythonEndpoint;
import io.mealie.backend.web.validation.ValidationErrors;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/organizers/categories")
public class CategoryController {
    private static final String SOURCE="mealie/routes/organizers/controller_categories.py";
    private final CategoryService service;
    public CategoryController(CategoryService service) { this.service=service; }
    @GetMapping @PythonEndpoint(file=SOURCE,line=40,function="get_all")
    Pagination<CategoryService.CategoryResponse> getAll(AuthUser user,HttpServletRequest request) {
        ValidationErrors e=new ValidationErrors(); PaginationQuery q=PaginationQuery.parse(request,e);e.throwIfAny();
        return service.list(user,q,PaginationQuery.last(request,"search"));
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @PythonEndpoint(file=SOURCE,line=52,function="create_one")
    CategoryService.CategoryResponse create(PyRequestBody body,AuthUser user,HttpServletRequest request) {
        ValidationErrors e=new ValidationErrors();String name=e.bodyField(e.bodyObject(body),"name","name",PyValidate::str);e.throwIfAny();
        return service.create(user,name,request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
    }
    @GetMapping("/empty") @PythonEndpoint(file=SOURCE,line=73,function="get_all_empty")
    java.util.List<CategoryService.CategoryBase> empty(AuthUser user) { return service.empty(user); }
    @PostMapping("/merge") @PythonEndpoint(file=SOURCE,line=78,function="merge_categories")
    CategoryService.CategoryResponse merge(PyRequestBody body,AuthUser user) {
        ValidationErrors e=new ValidationErrors();Map<String,Object> f=e.bodyObject(body);
        UUID from=e.bodyField(f,"fromId","from_id",PyValidate::uuid4);UUID to=e.bodyField(f,"toId","to_id",PyValidate::uuid4);e.throwIfAny();return service.merge(user,from,to);
    }
    @GetMapping("/{item_id}") @PythonEndpoint(file=SOURCE,line=93,function="get_one")
    CategoryService.CategorySummary get(AuthUser user,@PathVariable("item_id") String value) { ValidationErrors e=new ValidationErrors();UUID id=e.path("item_id",value,PyValidate::uuid4);e.throwIfAny();return service.get(user,id); }
    @PutMapping("/{item_id}") @PythonEndpoint(file=SOURCE,line=100,function="update_one")
    CategoryService.CategorySummary update(PyRequestBody body,AuthUser user,@PathVariable("item_id") String value,HttpServletRequest request) {
        ValidationErrors e=new ValidationErrors();UUID id=e.path("item_id",value,PyValidate::uuid4);String name=e.bodyField(e.bodyObject(body),"name","name",PyValidate::str);e.throwIfAny();service.update(user,id,name,request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));return service.get(user,id);
    }
    @DeleteMapping("/{item_id}") @PythonEndpoint(file=SOURCE,line=122,function="delete_one")
    ResponseEntity<String> delete(AuthUser user,@PathVariable("item_id") String value,HttpServletRequest request) { ValidationErrors e=new ValidationErrors();UUID id=e.path("item_id",value,PyValidate::uuid4);e.throwIfAny();service.delete(user,id,request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("null"); }
    @GetMapping("/slug/{category_slug}") @PythonEndpoint(file=SOURCE,line=142,function="get_one_by_slug")
    CategoryService.SlugResponse slug(AuthUser user,@PathVariable("category_slug") String slug) { return service.getBySlug(user,slug); }
}
