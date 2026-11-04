package com.example.backend.scim.infrastructure.persistence;

import java.util.function.Function;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Tells which constraint an integrity violation broke, so an adapter translates only the one it
 * means.
 *
 * <p>{@link DataIntegrityViolationException} covers every refusal the database makes about the
 * data — a taken unique value, but also a value too long for its column, a missing NOT NULL value
 * and a dangling foreign key. Translating the exception TYPE to "the name is taken" reported each
 * of those to a connector as a {@code 409 uniqueness} it would then try to resolve by linking to
 * an account that does not exist. Matching the constraint's NAME is what makes the translation
 * true: the names are the ones the migrations declare, so a rename there is a change here.
 */
final class IntegrityViolations {

    /** The live-User {@code userName} uniqueness, declared on {@code scim_users}. */
    static final String USER_NAME_UNIQUE = "uq_scim_users_normalized_user_name";

    /** The live-Group {@code displayName} uniqueness, declared on {@code scim_groups}. */
    static final String DISPLAY_NAME_UNIQUE = "uq_scim_groups_normalized_display_name";

    /** The live-connector display name uniqueness, declared on {@code scim_connectors}. */
    static final String CONNECTOR_NAME_UNIQUE = "uq_scim_connectors_live_display_name";

    private IntegrityViolations() {
    }

    /**
     * The violation as the adapter's refusal when it is of the named constraint, and unchanged
     * otherwise — so the caller rethrows whichever comes back and a fault stays a fault.
     */
    static RuntimeException translated(
            DataIntegrityViolationException violation,
            String constraint,
            Function<Throwable, RuntimeException> refusal) {
        return violates(violation, constraint) ? refusal.apply(violation) : violation;
    }

    /**
     * Whether the violation is of the named constraint.
     *
     * <p>Read from Hibernate's {@link ConstraintViolationException} in the cause chain, which
     * carries the name the database reported. A violation with no constraint name — a value too
     * long for its column is one — names no constraint and so matches none.
     */
    static boolean violates(DataIntegrityViolationException violation, String constraint) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                return constraint.equalsIgnoreCase(constraintViolation.getConstraintName());
            }
        }
        return false;
    }
}
