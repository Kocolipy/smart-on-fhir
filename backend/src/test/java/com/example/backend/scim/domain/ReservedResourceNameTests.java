package com.example.backend.scim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The marker that makes a resource one the deployment reserves for its own recovery.
 *
 * <p>The stored values are pinned by literal, not derived from {@link Enum#name()}, because the
 * database's own CHECK constraint lists exactly these two strings: a Java rename that changed the
 * column's contents would make every existing reserved row unrecognisable, which is a silent loss
 * of both the write protection and the lockout exemption.
 */
class ReservedResourceNameTests {

    @Test
    void the_stored_values_are_the_two_strings_the_schema_constrains_the_column_to() {
        assertThat(ReservedResourceName.BOOTSTRAP_ADMIN.storedValue()).isEqualTo("bootstrap-admin");
        assertThat(ReservedResourceName.ADMIN_GROUP.storedValue()).isEqualTo("admin-group");
    }

    @Test
    void a_null_column_is_an_ordinary_unreserved_resource() {
        assertThat(ReservedResourceName.ofStoredValue(null)).isEmpty();
    }

    @Test
    void a_stored_value_round_trips_to_its_reservation() {
        for (ReservedResourceName reserved : ReservedResourceName.values()) {
            assertThat(ReservedResourceName.ofStoredValue(reserved.storedValue()))
                    .contains(reserved);
        }
    }

    /**
     * An unrecognised non-null value must NOT read as "not protected". The column is constrained to
     * two strings, so a third one means the schema and this enum have diverged — and treating it as
     * unreserved would silently open the recovery resources to every SCIM write.
     */
    @Test
    void an_unrecognised_reservation_throws_rather_than_reading_as_unprotected() {
        assertThatThrownBy(() -> ReservedResourceName.ofStoredValue("something-else"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not know");
    }

    @Test
    void the_lookup_is_exact_rather_than_case_insensitive() {
        assertThatThrownBy(() -> ReservedResourceName.ofStoredValue("BOOTSTRAP-ADMIN"))
                .isInstanceOf(IllegalStateException.class);
    }
}
