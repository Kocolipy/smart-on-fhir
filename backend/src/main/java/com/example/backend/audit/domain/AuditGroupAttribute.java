package com.example.backend.audit.domain;

/**
 * The attributes of a Group a write can move, as a closed set.
 *
 * <p>An event's changed-path list is a field a reader filters on — "show me every write
 * that touched membership" — so it has to be a vocabulary and not free text. This is that
 * vocabulary for Groups: a caller says which attributes moved, and the audit slice turns
 * each into the path name it records.
 *
 * <p>Closed rather than a list of strings for the same reason the audit boundary declares no
 * {@code String} parameter at all: a path list assembled by a caller is a place a submitted
 * value could be written.
 */
public enum AuditGroupAttribute implements AuditAttribute {

    /** The Group's label changed. */
    DISPLAY_NAME("displayName"),

    /** Its membership changed — somebody was added, removed, or both. */
    MEMBERS("members"),

    /**
     * The calling connector's own {@code externalId} alias changed. The path is recorded; the
     * alias value never is.
     */
    EXTERNAL_ID("externalId");

    private final String path;

    AuditGroupAttribute(String path) {
        this.path = path;
    }

    @Override
    public String path() {
        return path;
    }
}
