package io.mealie.backend.persistence.mapper;

import io.mealie.backend.persistence.model.UserAuthRow;
import io.mealie.backend.persistence.model.LoginUserRow;
import org.apache.ibatis.annotations.Param;

public interface UserAuthMapper {

    UserAuthRow findById(@Param("id") Object id);

    UserAuthRow findByApiToken(@Param("token") String token, @Param("userId") Object userId);

    LoginUserRow findLoginUserByUsername(@Param("username") String username);

    LoginUserRow findLoginUserByEmail(@Param("email") String email);

    int recordFailedLogin(@Param("id") Object id, @Param("maxAttempts") int maxAttempts,
            @Param("lockedAt") Object lockedAt);

    int resetLoginAttempts(@Param("id") Object id);
}
