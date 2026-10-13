package com.example.backend.scim.domain;

/**
 * A password a connector submitted breaks an intrinsic rule of {@link PasswordPolicy} — too short,
 * too long, or containing the {@code userName}. Reuse is {@link PasswordReusedException}'s.
 *
 * <p>Rendered as {@code 400 invalidValue} naming the rule. It carries the rule and nothing else:
 * the submitted value never reaches this object, so it cannot reach its message, a log line or a
 * response built from it.
 */
public final class PasswordPolicyRefusedException extends ScimRequestRefusedException {

    private final transient PasswordPolicy.Rule rule;

    public PasswordPolicyRefusedException(PasswordPolicy.Rule rule) {
        super(rule.message());
        this.rule = rule;
    }

    /** The unmet rule. */
    public PasswordPolicy.Rule rule() {
        return rule;
    }
}
