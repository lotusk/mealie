package io.mealie.backend.household;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ShoppingListService {
    private final ShoppingListRepository repository;

    public ShoppingListService(ShoppingListRepository repository) {
        this.repository = repository;
    }

    public Pagination<Map<String, Object>> list(AuthUser user, PaginationQuery query) {
        return repository.page(user, query);
    }
}
