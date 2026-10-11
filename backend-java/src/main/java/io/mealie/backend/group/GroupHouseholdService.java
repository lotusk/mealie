package io.mealie.backend.group;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import io.mealie.backend.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupHouseholdService {

    private final GroupHouseholdRepository repository;

    public GroupHouseholdService(GroupHouseholdRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Pagination<GroupHouseholdResponse> list(AuthUser user, PaginationQuery query) {
        return repository.page(user.groupId(), query);
    }

    @Transactional(readOnly = true)
    public GroupHouseholdResponse get(AuthUser user, String slugOrId) {
        return repository.findBySlugOrId(user.groupId(), slugOrId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Household not found", Map.of()));
    }
}
