package com.example.backend.auth.application;

import com.example.backend.auth.domain.EpicLoginFailureReason;
import org.springframework.security.core.AuthenticationException;

/**
 * An Epic Login the login decision refused ({@link LoginService#logInFromEpic}), already audited
 * and logged by the time it is thrown, carrying only the reason a caller may still need — for the
 * {@code epic.login} counter — and never the Practitioner ID.
 */
public class EpicLoginRefusedException extends AuthenticationException {

    private final EpicLoginFailureReason reason;

    public EpicLoginRefusedException(EpicLoginFailureReason reason) {
        super("Epic sign-in refused");
        this.reason = reason;
    }

    /** Why the Login was refused. */
    public EpicLoginFailureReason reason() {
        return reason;
    }
}
