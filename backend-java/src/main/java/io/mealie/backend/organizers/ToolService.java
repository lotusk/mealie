package io.mealie.backend.organizers;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.compat.PySlugify;
import io.mealie.backend.db.IntegrityViolation;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.recipe.RecipeToolSummary;
import io.mealie.backend.web.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ToolService {

    private final ToolRepository repository;
    private final TransactionTemplate transaction;

    public ToolService(ToolRepository repository, TransactionTemplate transaction) {
        this.repository = repository;
        this.transaction = transaction;
    }

    /** Python create_one requires authentication, but does not check can_organize or trim the name. */
    public RecipeToolSummary create(AuthUser user, String name, List<String> households) {
        try {
            return transaction.execute(status -> repository.insert(user.groupId(), name,
                    PySlugify.slugify(name), households));
        } catch (RuntimeException error) {
            var violation = IntegrityViolation.of(error);
            if (violation.isPresent()) {
                throw ApiException.errorResponse(HttpStatus.CONFLICT,
                        violation.get().unique() ? "This item already exists." : "An unexpected error occurred.",
                        violation.get().driverError());
            }
            throw ApiException.errorResponse(HttpStatus.BAD_REQUEST, "An unexpected error occurred.",
                    String.valueOf(error.getMessage()));
        }
    }

    public Pagination<RecipeToolSummary> list(AuthUser user, PaginationQuery query, String search) {
        return repository.page(user.groupId(), query, search);
    }
}
