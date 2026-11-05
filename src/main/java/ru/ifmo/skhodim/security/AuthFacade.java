package ru.ifmo.skhodim.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import ru.ifmo.skhodim.exception.UnauthorizedException;

@Component
public class AuthFacade {

    public SkhodimPrincipal require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof SkhodimPrincipal principal)) {
            throw new UnauthorizedException("Authentication required");
        }
        return principal;
    }

    public Long optionalUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SkhodimPrincipal principal) {
            return principal.id();
        }
        return null;
    }
}
