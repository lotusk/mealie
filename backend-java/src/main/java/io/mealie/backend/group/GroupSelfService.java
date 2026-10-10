package io.mealie.backend.group;

import io.mealie.backend.web.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GroupSelfService {

    private final GroupSelfRepository groups;

    public GroupSelfService(GroupSelfRepository groups) {
        this.groups = groups;
    }

    @Transactional(readOnly = true)
    public GroupSelfResponse get(UUID groupId) {
        return groups.findById(groupId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND));
    }
}
