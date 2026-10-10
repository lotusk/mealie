package io.mealie.backend.persistence.mapper;

import io.mealie.backend.persistence.model.UserSelfRow;
import io.mealie.backend.persistence.model.UserSelfTokenRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface UserSelfMapper {

    UserSelfRow findById(@Param("id") Object id);

    List<UserSelfTokenRow> findTokens(@Param("userId") Object userId);
}
