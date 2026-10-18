package com.example.backend.scim;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.backend.scim.domain.DuplicateDisplayNameException;
import com.example.backend.scim.domain.ReservedResourceName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The in-memory Group store refuses a reserved create the way the SQL Server adapter does
 * ({@code ScimGroupPersistenceAdapterIntegrationTests} pins the adapter's side). The Group store
 * has no contract of its own yet, so this holds the one rule the fake was found to break: it
 * raised a taken {@code displayName} for a reservation already held, which the adapter reports as
 * the store's integrity violation.
 */
class InMemoryScimGroupRepositoryTests {

    private final InMemoryScimGroupRepository groups =
            new InMemoryScimGroupRepository(new InMemoryScimUserRepository());

    @Test
    void a_reserved_create_for_a_reservation_already_held_fails_with_the_integrity_violation() {
        groups.createReserved(ScimIdentities.group("Admins"), ReservedResourceName.ADMIN_GROUP);

        assertThatThrownBy(() -> groups.createReserved(
                        ScimIdentities.group("Other admins"), ReservedResourceName.ADMIN_GROUP))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void a_reserved_create_for_a_display_name_already_held_fails_as_the_duplicate_it_is() {
        groups.create(ScimIdentities.group("Admins"));

        assertThatThrownBy(() -> groups.createReserved(
                        ScimIdentities.group("Admins"), ReservedResourceName.ADMIN_GROUP))
                .isInstanceOf(DuplicateDisplayNameException.class);
    }
}
