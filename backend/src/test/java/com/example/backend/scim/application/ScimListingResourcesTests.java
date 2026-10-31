package com.example.backend.scim.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.paging.domain.PageRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A listing carries what it was built with, its resources always a list nothing can change. */
class ScimListingResourcesTests {

    private static final PageRequest PAGE = new PageRequest(11, 5);

    private static final ScimUserResource USER =
            new ScimUserResource(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    null, null, null, 0, null, null);

    private static final ScimGroupResource GROUP =
            new ScimGroupResource(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                    "Group", null, null, 0, null, null);

    @Test
    void aUserListingCarriesItsResourcesItsTotalAndItsPage() {
        ScimUserListing listing = new ScimUserListing(List.of(USER), 42, PAGE);

        assertThat(listing.resources()).containsExactly(USER);
        assertThat(listing.totalResults()).isEqualTo(42);
        assertThat(listing.page()).isEqualTo(new PageRequest(11, 5));
    }

    @Test
    void aGroupListingCarriesItsResourcesItsTotalAndItsPage() {
        ScimGroupListing listing = new ScimGroupListing(List.of(GROUP), 42, PAGE);

        assertThat(listing.resources()).containsExactly(GROUP);
        assertThat(listing.totalResults()).isEqualTo(42);
        assertThat(listing.page()).isEqualTo(new PageRequest(11, 5));
    }

    @Test
    void aUserListingBuiltWithoutResourcesCarriesNone() {
        assertThat(new ScimUserListing(null, 0, PAGE).resources()).isNotNull().isEmpty();
    }

    @Test
    void aGroupListingBuiltWithoutResourcesCarriesNone() {
        assertThat(new ScimGroupListing(null, 0, PAGE).resources()).isNotNull().isEmpty();
    }

    @Test
    void aUserListingHoldsItsOwnUnmodifiableCopy() {
        assertThat(new ScimUserListing(new ArrayList<>(), 0, PAGE).resources()).isUnmodifiable();
    }

    @Test
    void aGroupListingHoldsItsOwnUnmodifiableCopy() {
        assertThat(new ScimGroupListing(new ArrayList<>(), 0, PAGE).resources()).isUnmodifiable();
    }
}
