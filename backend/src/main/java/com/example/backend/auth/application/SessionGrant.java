package com.example.backend.auth.application;

import java.util.List;
import org.springframework.security.core.GrantedAuthority;

/**
 * What an accepted Login's session is issued with, read once, under the User's lock, in the
 * Login's decision ({@link LoginIdentityService#sessionGrant}): its authorities, and the display
 * names of the Groups the User is a direct member of. Both come from the one read of the User's
 * Groups, so the Groups a session reports always match the Permissions it holds; neither is
 * refreshed during the session.
 *
 * @param authorities what the session may do: {@code PASSWORD_CHANGE_REQUIRED} alone for a
 *                    confined one, and otherwise baseline access and what the User's Groups
 *                    confer
 * @param groups      the User's direct Groups by display name, as stored, sorted ignoring case;
 *                    empty for a User in no Group and for a confined session, which reads none
 */
public record SessionGrant(List<GrantedAuthority> authorities, List<String> groups) {

    public SessionGrant {
        authorities = List.copyOf(authorities);
        groups = List.copyOf(groups);
    }
}
