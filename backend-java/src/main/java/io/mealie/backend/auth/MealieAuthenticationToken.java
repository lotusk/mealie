package io.mealie.backend.auth;

import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Spring Security authentication input and authenticated principal for Mealie JWTs. */
public final class MealieAuthenticationToken extends AbstractAuthenticationToken {

    private final AuthUser principal;
    private String credentials;

    private MealieAuthenticationToken(String credentials) {
        super(List.of());
        this.principal = null;
        this.credentials = credentials;
        setAuthenticated(false);
    }

    private MealieAuthenticationToken(AuthUser principal) {
        super(authorities(principal));
        this.principal = principal;
        this.credentials = null;
        setAuthenticated(true);
    }

    public static MealieAuthenticationToken unauthenticated(String credentials) {
        return new MealieAuthenticationToken(credentials);
    }

    public static MealieAuthenticationToken authenticated(AuthUser principal) {
        return new MealieAuthenticationToken(principal);
    }

    @Override
    public String getCredentials() {
        return credentials;
    }

    @Override
    public AuthUser getPrincipal() {
        return principal;
    }

    @Override
    public void eraseCredentials() {
        super.eraseCredentials();
        credentials = null;
    }

    private static List<SimpleGrantedAuthority> authorities(AuthUser user) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (user.admin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return List.copyOf(authorities);
    }
}
