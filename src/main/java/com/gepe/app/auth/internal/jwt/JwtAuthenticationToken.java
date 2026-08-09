package com.gepe.app.auth.internal.jwt;

import com.gepe.app.platform.web.security.AuthenticatedUser;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final AuthenticatedUser principal;

    JwtAuthenticationToken(
            AuthenticatedUser principal,
            Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return null;
    }
}
