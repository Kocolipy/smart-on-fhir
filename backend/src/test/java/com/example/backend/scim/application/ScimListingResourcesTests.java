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
    void aUserListingBuiltWithoutResourcesCarriesNone() {
        assertThat(new ScimUserListing(null, 0, PAGE).resources()).isNotNull().isEmpty();
    }

    @Test
    void aGroupListingBuiltWithoutResourcesCarriesNone() {
        assertThat(new ScimGroupListing(null, 0, PAGE).resources()).isNotNull().isEmpty();
    }

    /** A caller's list changing afterwards changes nothing, and the listing's own cannot change. */
    @Test
    void aUserListingHoldsItsOwnUnmodifiableCopy() {
        List<ScimUserResource> given = new ArrayList<>(List.of(USER));
        ScimUserListing listing = new ScimUserListing(given, 1, PAGE);

        given.clear();

        assertThat(listing.resources()).containsExactly(USER).isUnmodifiable();
    }

    @Test
    void aGroupListingHoldsItsOwnUnmodifiableCopy() {
        List<ScimGroupResource> given = new ArrayList<>(List.of(GROUP));
        ScimGroupListing listing = new ScimGroupListing(given, 1, PAGE);

        given.clear();

        assertThat(listing.resources()).containsExactly(GROUP).isUnmodifiable();
    }
}
