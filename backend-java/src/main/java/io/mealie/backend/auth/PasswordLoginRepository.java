package io.mealie.backend.auth;

import io.mealie.backend.db.SqlDialect;
import io.mealie.backend.persistence.mapper.UserAuthMapper;
import io.mealie.backend.persistence.model.LoginUserRow;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class PasswordLoginRepository {

    private final UserAuthMapper mapper;
    private final SqlDialect dialect;

    public PasswordLoginRepository(UserAuthMapper mapper, SqlDialect dialect) {
        this.mapper = mapper;
        this.dialect = dialect;
    }

    public Optional<LoginUserRow> findByUsernameOrEmail(String login) {
        LoginUserRow user = mapper.findLoginUserByUsername(login);
        return Optional.ofNullable(user != null ? user : mapper.findLoginUserByEmail(login));
    }

    public void recordFailedLogin(UUID id, int maxAttempts, OffsetDateTime lockedAt) {
        mapper.recordFailedLogin(dialect.uuid(id), maxAttempts, dialect.timestamp(lockedAt));
    }

    public void resetLoginAttempts(UUID id) {
        mapper.resetLoginAttempts(dialect.uuid(id));
    }
}
