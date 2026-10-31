package com.example.backend.scim.application;

import com.example.backend.paging.domain.PageRequest;
import java.util.List;

/**
 * One page of Groups, with the total the page was taken from.
 *
 * <p>{@code totalResults} is the count of every matching resource and not of the page,
 * because that is what SCIM's {@code ListResponse} means by it and what a client pages
 * against. The two are carried together so a renderer cannot report the page's size as the
 * total — which would make the first page look like the whole directory.
 *
 * @param resources    the page, in the port's stable order
 * @param totalResults how many Groups exist, irrespective of the page
 * @param page         the coerced paging parameters the page was taken with
 */
public record ScimGroupListing(
        List<ScimGroupResource> resources, long totalResults, PageRequest page) {

    public ScimGroupListing {
        resources = resources == null ? List.of() : List.copyOf(resources);
    }
}
