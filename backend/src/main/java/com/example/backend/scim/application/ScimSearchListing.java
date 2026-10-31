package com.example.backend.scim.application;

import com.example.backend.paging.domain.PageRequest;
import java.util.List;

/**
 * One page of a base search across Users and Groups, in the query's order.
 *
 * @param resources    the page's Users and Groups, interleaved as the query ordered them
 * @param totalResults how many resources of either type matched
 * @param page         the page that was asked for
 */
public record ScimSearchListing(
        List<ScimListedResource> resources, long totalResults, PageRequest page) {

    public ScimSearchListing {
        resources = List.copyOf(resources);
    }
}
