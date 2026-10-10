package io.mealie.backend.organizers;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.recipe.RecipeToolSummary;
import org.springframework.stereotype.Service;

@Service
public class ToolService {

    private final ToolRepository repository;

    public ToolService(ToolRepository repository) {
        this.repository = repository;
    }

    public Pagination<RecipeToolSummary> list(AuthUser user, PaginationQuery query, String search) {
        return repository.page(user.groupId(), query, search);
    }
}
