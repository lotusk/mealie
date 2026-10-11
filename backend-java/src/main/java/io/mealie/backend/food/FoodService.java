package io.mealie.backend.food;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import org.springframework.stereotype.Service;

@Service
public class FoodService {
    private final FoodRepository repository;

    public FoodService(FoodRepository repository) {
        this.repository = repository;
    }

    public Pagination<FoodResponse> list(AuthUser user, PaginationQuery query, String search) {
        return repository.page(user.groupId(), query, search);
    }
}
