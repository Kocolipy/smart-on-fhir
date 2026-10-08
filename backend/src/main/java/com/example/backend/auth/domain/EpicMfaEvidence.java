package com.example.backend.auth.domain;

import com.example.backend.audit.domain.AuditMfaFactor;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * D17's MFA factor of an Epic Login: the one place it is decided whether the {@code id_token}
 * must carry MFA evidence, and which factor the Login records.
 *
 * <p>The evidence, read from {@code amr} (RFC 8176 Authentication Method Reference values) once
 * the switch requiring it is on, is a second factor: a possession or inherence method —
 * {@code otp}, {@code hwk}, {@code swk}, {@code sms}, {@code tel}, {@code sc}, {@code fpt},
 * {@code face}, {@code iris}, {@code retina} or {@code vbm} — or {@code mfa}, which says several
 * factors were used without naming them. Epic's own sign-in always takes the password, so a
 * second factor beside it is what makes the sign-in multi-factor; a password ({@code pwd}), a
 * PIN or a knowledge question alone is not evidence. The factor recorded is the first second
 * factor in the order {@code amr} lists them, or {@code mfa} when it names none.
 *
 * <p>{@code acr} is not read: its values are each deployment's own, so none says MFA everywhere,
 * and it names no factor to record.
 */
public final class EpicMfaEvidence {

    /** The {@code id_token} claim the methods come in. */
    public static final String AMR = "amr";

    /**
     * Every {@link AuditMfaFactor} an {@code amr} method names, by its RFC 8176 spelling, which
     * is the factor's recorded one: all of them but the attestation, which {@code amr} cannot
     * claim, and {@code mfa}, which names no factor and so only stands in when none is named.
     */
    private static final Map<String, AuditMfaFactor> SECOND_FACTORS =
            Arrays.stream(AuditMfaFactor.values())
                    .filter(factor -> factor != AuditMfaFactor.IDP_ATTESTED
                            && factor != AuditMfaFactor.MFA)
                    .collect(Collectors.toUnmodifiableMap(AuditMfaFactor::value,
                            Function.identity()));

    private EpicMfaEvidence() {
    }

    /**
     * The MFA factor a Login records (D17), or empty when it must be refused for want of one.
     * With {@code evidenceRequired} off the Epic organisation's MFA is attested,
     * {@link AuditMfaFactor#IDP_ATTESTED}, and {@code amr} is never read; with it on, the factor
     * is the one {@code amr} names ({@link #factorIn}).
     *
     * @param amr the {@code id_token}'s {@code amr}, read only when the evidence is required
     */
    public static Optional<AuditMfaFactor> factorOf(
            boolean evidenceRequired, Supplier<? extends Collection<String>> amr) {
        return evidenceRequired ? factorIn(amr.get()) : Optional.of(AuditMfaFactor.IDP_ATTESTED);
    }

    /**
     * The MFA factor {@code amr} is evidence of, or empty when it is none: absent, empty, or
     * naming no second factor and not {@code mfa}.
     */
    public static Optional<AuditMfaFactor> factorIn(Collection<String> amr) {
        if (amr == null) {
            return Optional.empty();
        }
        boolean mfa = false;
        for (String method : amr) {
            AuditMfaFactor factor = method == null ? null : SECOND_FACTORS.get(method);
            if (factor != null) {
                return Optional.of(factor);
            }
            mfa |= AuditMfaFactor.MFA.value().equals(method);
        }
        return mfa ? Optional.of(AuditMfaFactor.MFA) : Optional.empty();
    }
}
