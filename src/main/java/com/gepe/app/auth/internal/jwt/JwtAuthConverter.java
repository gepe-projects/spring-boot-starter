package com.gepe.app.auth.internal.jwt;

import com.gepe.app.platform.web.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId = JwtClaims.getUserId(jwt);
        String email = JwtClaims.getEmail(jwt);
        List<String> roles = JwtClaims.getRoles(jwt);

        AuthenticatedUser principal = new AuthenticatedUser(userId, email, List.copyOf(roles));

        var authorities = roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .toList();

        return new JwtAuthenticationToken(principal, authorities);
    }
}
