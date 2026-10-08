package com.example.backend.auth.application;

import com.example.backend.auth.domain.EpicInputField;
import com.example.backend.auth.domain.EpicInputRule;
import com.example.backend.auth.domain.EpicLoginFailureReason;
import java.util.Optional;
import org.springframework.security.core.AuthenticationException;

/**
 * An Epic Login refused by a protocol check — the launch, the callback, the {@code id_token} or
 * its {@code fhirUser} — before any User was looked up, and not yet recorded: the Epic failure
 * handler records it, once, from the reason this carries (flow step 8).
 *
 * <p>An input refused for D18 or D10 also names the {@link EpicInputField} and the
 * {@link EpicInputRule} it broke, for the log, which names them and never the value (D22).
 * Neither is a value the browser sent: both come from this service's own vocabulary.
 */
public class EpicSignInRefusedException extends AuthenticationException {

    private final EpicLoginFailureReason reason;

    private final EpicInputField field;

    private final EpicInputRule rule;

    /** A refusal that names no input. */
    public EpicSignInRefusedException(EpicLoginFailureReason reason) {
        this(reason, null, null);
    }

    /** A refusal of the input {@code field} for breaking {@code rule}. */
    public EpicSignInRefusedException(
            EpicLoginFailureReason reason, EpicInputField field, EpicInputRule rule) {
        super("Epic sign-in refused");
        this.reason = reason;
        this.field = field;
        this.rule = rule;
    }

    /** Why the Login was refused. */
    public EpicLoginFailureReason reason() {
        return reason;
    }

    /** The input refused, if the refusal was of one. */
    public Optional<EpicInputField> field() {
        return Optional.ofNullable(field);
    }

    /** The rule the input broke, if the refusal was of one. */
    public Optional<EpicInputRule> rule() {
        return Optional.ofNullable(rule);
    }
}
