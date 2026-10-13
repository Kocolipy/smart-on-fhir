package com.example.backend.scim.domain;

import com.example.backend.scim.domain.ScimValueControlCharacterException.Forbidden;

/**
 * What each stored SCIM attribute accepts — its longest value and the characters it refuses —
 * and the check that refuses anything else before it is written.
 *
 * <p>The lengths are the {@code VARCHAR} widths of the columns in the Flyway migrations, and
 * this class exists so a value that does not fit is refused as what it is — a {@code 400
 * invalidValue} naming the attribute — rather than reaching the database, whose refusal is an
 * integrity violation or a driver error indistinguishable, from outside, from a conflict or a
 * fault. RFC 7643 has no {@code maxLength} characteristic, so the rules are advertised in
 * {@code docs/openapi.yaml} and in the refusal's {@code detail}, not in {@code /Schemas}.
 *
 * <p>Lengths are counted in Unicode code points, which is what PostgreSQL's
 * {@code VARCHAR(n)} counts in a UTF-8 database. Counting UTF-16 units instead would refuse a
 * 256-character name made of supplementary characters that the column stores without
 * complaint.
 *
 * <p>The two unique names are also checked in their NORMALIZED form, because that is a column
 * of the same width too: NFKC can expand a character into several (U+FDFA becomes eighteen), so
 * a {@code userName} that fits can normalize into one that does not.
 *
 * <p>No stored string may contain U+0000: JSON can carry it, but PostgreSQL refuses it in any
 * {@code text} or {@code varchar}, so the write would fail below every check. {@code userName} and
 * {@code displayName}, of a User and of a Group, additionally refuse every other C0 control and
 * DEL. PostgreSQL stores those, but these two are the names shown to an administrator and written
 * into logs, where a newline or an escape sequence forges a line or repaints a terminal. The
 * other attributes keep them, because a connector may legitimately send, say, a tab in a
 * formatted name, and nothing renders those as a heading.
 *
 * <p>The keys — {@code userName}, {@code displayName} of a User and of a Group,
 * {@code emails.value}, {@code emails.type} and {@code externalId}, every attribute that is unique
 * or looked up — also refuse whitespace at either edge, for the reason {@link EdgeWhitespace}
 * gives. Whitespace inside a key, and whitespace anywhere in the other attributes, is stored as
 * sent.
 */
public final class ScimAttributeLimits {

    /** {@code scim_users.user_name} and {@code normalized_user_name}. */
    public static final int USER_NAME = 256;

    /** {@code scim_users.display_name}, and a Group's {@code display_name} and normalized form. */
    public static final int DISPLAY_NAME = 256;

    /** {@code scim_external_ids.external_id}, for Users and Groups alike. */
    public static final int EXTERNAL_ID = 256;

    /** Each {@code name} sub-attribute's column. */
    public static final int NAME_PART = 256;

    /** {@code preferred_language}, {@code locale} and {@code timezone}. */
    public static final int LOCALIZATION = 64;

    /** {@code scim_user_emails.value}. */
    public static final int EMAIL_VALUE = 256;

    /** {@code scim_user_emails.type}. */
    public static final int EMAIL_TYPE = 32;

    private ScimAttributeLimits() {
    }

    /**
     * Refuses a User profile holding any value its column cannot hold.
     *
     * @throws ScimAttributeValueException naming the first attribute found unacceptable
     */
    public static void requireWithin(ScimUserProfile profile) {
        requireKey("userName", profile.userName(), USER_NAME, Forbidden.CONTROL);
        requireLength("userName", profile.normalizedUserName().value(), USER_NAME);
        ScimName name = profile.name();
        require("name.formatted", name.formatted(), NAME_PART, Forbidden.NUL);
        require("name.familyName", name.familyName(), NAME_PART, Forbidden.NUL);
        require("name.givenName", name.givenName(), NAME_PART, Forbidden.NUL);
        require("name.middleName", name.middleName(), NAME_PART, Forbidden.NUL);
        require("name.honorificPrefix", name.honorificPrefix(), NAME_PART, Forbidden.NUL);
        require("name.honorificSuffix", name.honorificSuffix(), NAME_PART, Forbidden.NUL);
        requireKey("displayName", profile.displayName(), DISPLAY_NAME, Forbidden.CONTROL);
        require("preferredLanguage", profile.preferredLanguage(), LOCALIZATION, Forbidden.NUL);
        require("locale", profile.locale(), LOCALIZATION, Forbidden.NUL);
        require("timezone", profile.timezone(), LOCALIZATION, Forbidden.NUL);
        for (ScimEmail email : profile.emails()) {
            requireKey("emails.value", email.value(), EMAIL_VALUE, Forbidden.NUL);
            requireKey("emails.type", email.type(), EMAIL_TYPE, Forbidden.NUL);
        }
    }

    /**
     * Refuses a Group {@code displayName} longer than its column, as submitted or normalized,
     * holding a control character, or beginning or ending with whitespace.
     *
     * @throws ScimAttributeValueException naming {@code displayName}
     */
    public static void requireGroupDisplayNameWithin(String displayName) {
        requireKey("displayName", displayName, DISPLAY_NAME, Forbidden.CONTROL);
        requireLength("displayName", NormalizedDisplayName.of(displayName).value(), DISPLAY_NAME);
    }

    /**
     * Refuses an {@code externalId} longer than its column, holding U+0000, or beginning or
     * ending with whitespace. An absent one is within every rule.
     *
     * @throws ScimAttributeValueException naming {@code externalId}
     */
    public static void requireExternalIdWithin(String externalId) {
        requireKey("externalId", externalId, EXTERNAL_ID, Forbidden.NUL);
    }

    /**
     * {@link #require}, for a unique or looked-up attribute, which also refuses whitespace at
     * either edge. Checked after the forbidden characters, so a name ending in a tab is still
     * named for the control it holds, and before the length.
     */
    private static void requireKey(String attribute, String value, int limit, Forbidden forbidden) {
        requireAllowedCharacters(attribute, value, forbidden);
        if (EdgeWhitespace.borders(value)) {
            throw new ScimValueEdgeWhitespaceException(attribute);
        }
        requireLength(attribute, value, limit);
    }

    private static void require(String attribute, String value, int limit, Forbidden forbidden) {
        requireAllowedCharacters(attribute, value, forbidden);
        requireLength(attribute, value, limit);
    }

    private static void requireAllowedCharacters(
            String attribute, String value, Forbidden forbidden) {
        if (value != null && value.codePoints().anyMatch(forbidden::includes)) {
            throw new ScimValueControlCharacterException(attribute, forbidden);
        }
    }

    private static void requireLength(String attribute, String value, int limit) {
        if (value != null && value.codePointCount(0, value.length()) > limit) {
            throw new ScimValueTooLongException(attribute, limit);
        }
    }
}
