package com.example.backend.scim.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.observability.SqlFailures;
import com.example.backend.scim.domain.DuplicateUserNameException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.DataException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Only a violation that names the constraint is translated; every other integrity violation —
 * another constraint, or a refusal that names none, like a value too long for its column — comes
 * back unchanged, so the adapter rethrows it as the fault it is.
 *
 * <p>The violations are built the way Spring's JPA translation builds them: a
 * {@link DataIntegrityViolationException} wrapping Hibernate's exception, which wraps the
 * driver's. The integration tests observe the same rule against Postgres's own reports.
 */
class IntegrityViolationsTests {

    private static final String TAKEN = IntegrityViolations.USER_NAME_UNIQUE;

    @Test
    void the_named_constraint_is_translated_into_the_refusal_carrying_the_violation() {
        DataIntegrityViolationException violation = constraint(TAKEN);

        RuntimeException translated =
                IntegrityViolations.translated(violation, TAKEN, DuplicateUserNameException::new);

        assertThat(translated).isInstanceOf(DuplicateUserNameException.class)
                .hasCause(violation);
    }

    /** Postgres reports names in lower case; the migrations' spelling is matched either way. */
    @Test
    void the_constraint_name_is_matched_ignoring_case() {
        assertThat(IntegrityViolations.violates(constraint(TAKEN.toUpperCase()), TAKEN)).isTrue();
    }

    @Test
    void another_constraint_is_returned_unchanged() {
        DataIntegrityViolationException violation = constraint("uq_scim_user_emails_type_value");

        assertThat(IntegrityViolations.translated(
                violation, TAKEN, DuplicateUserNameException::new)).isSameAs(violation);
    }

    /** Value too long for its column: SQLSTATE 22001, which Hibernate reports as data, not a constraint. */
    @Test
    void a_violation_naming_no_constraint_is_returned_unchanged() {
        DataIntegrityViolationException tooLong = new DataIntegrityViolationException(
                "could not execute statement",
                new DataException("value too long", SqlFailures.VALUE_TOO_LONG.raised("value too long")));

        assertThat(IntegrityViolations.violates(tooLong, TAKEN)).isFalse();
        assertThat(IntegrityViolations.translated(
                tooLong, TAKEN, DuplicateUserNameException::new)).isSameAs(tooLong);
    }

    @Test
    void a_constraint_violation_with_no_reported_name_matches_nothing() {
        assertThat(IntegrityViolations.violates(constraint(null), TAKEN)).isFalse();
    }

    @Test
    void a_violation_with_no_cause_matches_nothing() {
        assertThat(IntegrityViolations.violates(
                new DataIntegrityViolationException("no cause"), TAKEN)).isFalse();
    }

    /** The name is found however deep Hibernate's exception sits in the chain. */
    @Test
    void the_constraint_is_found_below_an_intermediate_wrapper() {
        DataIntegrityViolationException nested = new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("wrapper", hibernate(TAKEN)));

        assertThat(IntegrityViolations.violates(nested, TAKEN)).isTrue();
    }

    private static DataIntegrityViolationException constraint(String name) {
        return new DataIntegrityViolationException("could not execute statement", hibernate(name));
    }

    private static ConstraintViolationException hibernate(String name) {
        return new ConstraintViolationException(
                "could not execute statement",
                SqlFailures.UNIQUE_VIOLATION.raised("duplicate key value violates unique constraint"),
                name);
    }
}
