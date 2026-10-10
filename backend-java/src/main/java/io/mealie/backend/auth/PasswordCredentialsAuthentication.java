package io.mealie.backend.auth;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** Password login request and its issued Mealie session token. */
public final class PasswordCredentialsAuthentication extends AbstractAuthenticationToken {

    private final String username;
    private String password;
    private final boolean rememberMe;
    private final PasswordLoginService.LoginResult result;

    private PasswordCredentialsAuthentication(String username, String password, boolean rememberMe) {
        super(List.of());
        this.username = username;
        this.password = password;
        this.rememberMe = rememberMe;
        this.result = null;
        setAuthenticated(false);
    }

    private PasswordCredentialsAuthentication(
            String username, boolean rememberMe, PasswordLoginService.LoginResult result) {
        super(List.of());
        this.username = username;
        this.password = null;
        this.rememberMe = rememberMe;
        this.result = result;
        setAuthenticated(true);
    }

    public static PasswordCredentialsAuthentication unauthenticated(
            String username, String password, boolean rememberMe) {
        return new PasswordCredentialsAuthentication(username, password, rememberMe);
    }

    public static PasswordCredentialsAuthentication authenticated(
            String username, boolean rememberMe, PasswordLoginService.LoginResult result) {
        return new PasswordCredentialsAuthentication(username, rememberMe, result);
    }

    @Override
    public String getPrincipal() {
        return username;
    }

    @Override
    public String getCredentials() {
        return password;
    }

    public boolean rememberMe() {
        return rememberMe;
    }

    public PasswordLoginService.LoginResult result() {
        return result;
    }

    @Override
    public void eraseCredentials() {
        super.eraseCredentials();
        password = null;
    }
}
