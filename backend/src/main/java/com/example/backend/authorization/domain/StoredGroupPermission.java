package com.example.backend.authorization.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One Permission a Group carries, as storage holds it: the Group's stable id and the Permission's
 * NAME, so a value the vocabulary no longer has can be reported rather than lost in reading.
 *
 * @param groupId the Group carrying it
 * @param value   the Permission's name, as {@link Permission#value()} spells one
 */
public record StoredGroupPermission(UUID groupId, String value) {

    public StoredGroupPermission {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(value, "value");
    }
}
