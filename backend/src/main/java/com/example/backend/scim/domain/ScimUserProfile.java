package com.example.backend.scim.domain;

import java.util.List;

/**
 * Everything a SCIM User is apart from its identity, its credential and its
 * version: the profile attributes a connector writes and reads back.
 *
 * <p>Grouped into a record of its own, and not spread across {@link ScimUser}'s
 * components, because this is exactly the set a replacement writes. A PUT replaces
 * the profile and leaves the id, the version lineage and the password alone; having
 * one value that means "the profile" is what lets that be stated as one assignment
 * rather than as a list of fields a future edit can fall out of step with.
 *
 * <p>The canonical constructor is where the multi-valued and complex attributes are
 * brought into their stored form, so no caller can construct a profile that the
 * database would refuse: {@code emails} is de-duplicated and reduced to at most one
 * primary, {@code name} becomes {@link ScimName#NONE} rather than null, and an
 * absent {@code emails} becomes an empty list rather than null. A domain object that
 * can hold a shape the schema forbids is a defect waiting for a caller.
 *
 * @param userName          the login and lookup attribute, as submitted
 * @param name              the complex name attribute, never null
 * @param displayName       primary human-readable label, or null
 * @param preferredLanguage language-priority value, or null
 * @param locale            language tag, or null
 * @param timezone          IANA time-zone identifier, or null
 * @param active            whether the User may authenticate
 * @param emails            canonical email values, never null
 */
public record ScimUserProfile(
        String userName,
        ScimName name,
        String displayName,
        String preferredLanguage,
        String locale,
        String timezone,
        boolean active,
        List<ScimEmail> emails) {

    public ScimUserProfile {
        if (userName == null || userName.isBlank()) {
            throw new IllegalArgumentException("a SCIM User has a userName");
        }
        name = name == null ? ScimName.NONE : name;
        emails = ScimEmail.canonical(emails);
    }

    /** The form {@code userName} uniqueness is decided on. */
    public NormalizedUserName normalizedUserName() {
        return NormalizedUserName.of(userName);
    }

    /**
     * The label a Group renders this User with among its members ({@code members.display}).
     *
     * @see #memberLabel(String, String)
     */
    public String memberLabel() {
        return memberLabel(displayName, userName);
    }

    /**
     * The member label rule, from a User's two bare attributes: its {@code displayName} when it
     * has one, else its {@code userName}. SCIM's {@code members.display} is a human-readable
     * label for the referenced resource (RFC 7643 §4.2), a {@code displayName} is that label
     * when there is one, and {@code userName} is the one attribute every User is guaranteed to
     * have.
     *
     * <p>The one Java copy of the rule. It decides whether a User write changed what its Groups
     * show, and so whether {@link ResourceVersionAdvance#userReplaced} advances them; the
     * adapters and the in-memory fakes all ask it. The JPA projection that renders a Group's
     * members states the same rule in its query, as {@code coalesce(displayName, userName)},
     * because it resolves the label in the statement that reads the membership.
     */
    public static String memberLabel(String displayName, String userName) {
        return displayName != null ? displayName : userName;
    }
}
