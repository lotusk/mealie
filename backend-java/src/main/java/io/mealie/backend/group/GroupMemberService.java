package io.mealie.backend.group;

import io.mealie.backend.auth.AuthUser;
import io.mealie.backend.query.Pagination;
import io.mealie.backend.query.PaginationQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupMemberService {

    private final GroupMemberRepository repository;

    public GroupMemberService(GroupMemberRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Pagination<GroupMemberResponse> list(AuthUser user, PaginationQuery query) {
        return repository.page(user.groupId(), query);
    }
}
