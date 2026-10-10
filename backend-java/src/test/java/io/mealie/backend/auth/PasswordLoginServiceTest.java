package io.mealie.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import io.mealie.backend.config.MealieEnv;
import io.mealie.backend.config.MealieSettings;
import io.mealie.backend.db.DbEngine;
import io.mealie.backend.persistence.model.LoginUserRow;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCrypt;

class PasswordLoginServiceTest {

    @Test
    void productionPasswordUsesBcryptBytesAndIssuesTheSharedJwt() {
        String password = "密码-pass";
        String hash = BCrypt.hashpw(password.getBytes(StandardCharsets.UTF_8), BCrypt.gensalt("$2b", 4));
        UUID id = UUID.randomUUID();
        PasswordLoginRepository users = mock(PasswordLoginRepository.class);
        when(users.findByUsernameOrEmail("user")).thenReturn(Optional.of(
                new LoginUserRow(id, "user@example.com", hash, "MEALIE", 1, null)));
        MealieSettings settings = new MealieSettings(false, false, Path.of("."), Path.of("."), DbEngine.SQLITE);
        MealieEnv env = new MealieEnv(Map.<String, String>of("TOKEN_TIME", "1")::get, Map.of());
        PasswordLoginService service = new PasswordLoginService(users, env, settings, new JwtSecret(settings));

        PasswordLoginService.LoginResult result = service.login("user", password, false);

        assertEquals(3600, result.expiresInSeconds());
        assertEquals(id.toString(), JWT.require(Algorithm.HMAC256(MealieSettings.NON_PRODUCTION_SECRET))
                .build().verify(result.token()).getSubject());
        assertFalse(JWT.decode(result.token()).getClaim("rme").asBoolean());
        verify(users).resetLoginAttempts(id);
    }
}
