package com.example.backend.scim.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * One RFC 7644 §3.5.2 PATCH operation on a User, already resolved from its {@code op}, its
 * {@code path} and its {@code value} to what it does.
 *
 * <p>The web adapter parses; this type decides. Each variant is a function from the edit as it
 * stands to the edit as it should become, and {@link #fold} applies a whole request in order,
 * in memory. Nothing is written while that happens, so a request whose last operation is refused
 * leaves the first ones unapplied — atomic by construction, not by rollback.
 *
 * <p>The refusals that depend on the stored state are raised here, as
 * {@link ScimPatchRefusedException}: removing the required {@code userName} or an email's
 * required {@code value} is {@code mutability}, and an {@code add} or {@code replace} whose filter
 * selected particular values and found none is {@code noTarget} (RFC 7644 §3.5.2.3); a filtered
 * {@code remove} that finds none changes nothing. The refusals that depend only on the request —
 * a malformed path, an unsupported attribute, a value of the wrong JSON type — are the adapter's,
 * and never reach here.
 *
 * <p>An operation that leaves the edit equal to what it was is a no-op, not an error. Whether the
 * whole request changed anything is decided afterwards by comparing, which is what lets a no-op
 * PATCH leave the version and {@code meta.lastModified} alone.
 */
public sealed interface ScimUserPatchOperation {

    /** The operation applied to the edit. */
    ScimUserEdit applyTo(ScimUserEdit edit);

    /** Applies every operation in order and returns the result; the input is not changed. */
    static ScimUserEdit fold(ScimUserEdit start, List<ScimUserPatchOperation> operations) {
        ScimUserEdit edit = start;
        for (ScimUserPatchOperation operation : operations) {
            edit = operation.applyTo(edit);
        }
        return edit;
    }

    /** The single-valued string attributes a PATCH sets, clears or replaces. */
    enum TextAttribute {
        USER_NAME,
        DISPLAY_NAME,
        PREFERRED_LANGUAGE,
        LOCALE,
        TIMEZONE
    }

    /** {@code add} or {@code replace} of a single-valued string attribute — the same thing. */
    record SetText(TextAttribute attribute, String value) implements ScimUserPatchOperation {

        public SetText {
            if (attribute == null || value == null) {
                throw new IllegalArgumentException("a set names an attribute and a value");
            }
        }

        @Override
        public ScimUserEdit applyTo(ScimUserEdit edit) {
            return text(edit, attribute, value);
        }
    }

    /** {@code remove} of a single-valued string attribute; refused for the required userName. */
    record RemoveText(TextAttribute attribute) implements ScimUserPatchOperation {

        @Override
        public ScimUserEdit applyTo(ScimUserEdit edit) {
            if (attribute == TextAttribute.USER_NAME) {
                throw new ScimPatchRefusedException(
                        ScimPatchRefusedException.Reason.MUTABILITY,
                        "userName is required and cannot be removed.");
            }
            return text(edit, attribute, null);
        }
    }

    /**
     * {@code add} or {@code replace} of {@code externalId} — the calling connector's alias. The
     * attribute is single-valued, so adding to it replaces it, as for any other string.
     */
    record SetExternalId(String externalId) implements ScimUserPatchOperation {

        public SetExternalId {
            if (externalId == null) {
                throw new IllegalArgumentException("an externalId set carries a value");
            }
        }

        @Override
        public ScimUserEdit applyTo(ScimUserEdit edit) {
            return edit.withExternalId(externalId);
        }
    }

    /** {@code remove} of {@code externalId}: the calling connector no longer holds an alias. */
    record RemoveExternalId() implements ScimUserPatchOperation {

        @Override
        public ScimUserEdit applyTo(ScimUserEdit edit) {
            return edit.withExternalId(null);
        }
    }

    /** {@code add} or {@code replace} of {@code active}. */
    record SetActive(boolean active) implements ScimUserPatchOperation {

        @Override
        public ScimUserEdit applyTo(ScimUserEdit edit) {
            return profile(edit, p -> copy(p.userName(), p.name(), p.displayName(),
                    p.preferredLanguage(), p.locale(), p.timezone(), active, p.emails()));
        }
    }

    /** {@code add} or {@code replace} of {@code password}. */
    record SetPassword(String password) implements ScimUserPatchOperation {

        public SetPassword {
            if (password == null) {
                throw new IllegalArgumentException("a password set carries a password");
            }
        }

        @Override
        public ScimUserEdit applyTo(ScimUserEdit edit) {
            return edit.withPassword(ScimPasswordChange.set(password));
        }

        /** Redacted: the plaintext never appears. */
        @Override
        public String toString() {
            return "SetPassword[redacted]";
        }
    }

    /** {@code remove} of {@code password}: the User becomes credentialless. */
    record RemovePassword() implements ScimUserPatchOperation {

        @Override
        public ScimUserEdit applyTo(ScimUserEdit edit) {
            return edit.withPassword(ScimPasswordChange.CLEAR);
        }
    }

    /**
     * {@code add} or {@code replace} on {@code name} or one of its sub-attributes.
     *
     * <p>Merges: every non-null component of {@code parts} replaces the stored one and the rest
     * are kept, which is what RFC 7644 §3.5.2.1 and §3.5.2.3 both say of a complex value.
     */
    record MergeName(ScimName parts) implements ScimUserPatchOperation {

        @Override
        public ScimUserEdit applyTo(ScimUserEdit edit) {
            ScimName stored = edit.profile().name();
            ScimName merged = new ScimName(
                    parts.formatted() != null ? parts.formatted() : stored.formatted(),
                    parts.familyName() != null ? parts.familyName() : stored.familyName(),
                    parts.givenName() != null ? parts.givenName() : stored.givenName(),
                    parts.middleName() != null ? parts.middleName() : stored.middleName(),
                    parts.honorificPrefix() != null
                            ? parts.honorificPrefix() : stored.honorificPrefix(),
                    parts.honorificSuffix() != null
                            ? parts.honorificSuffix() : stored.honorificSuffix());
            return withName(edit, merged);
        }
    }

    /** {@code remove} of {@code name}. */
    record RemoveName() implements ScimUserPatchOperation {

        @Override
        public ScimUserEdit applyTo(ScimUserEdit edit) {
            return withName(edit, ScimName.NONE);
        }
    }

    /** The sub-attributes of {@code name}. */
    enum NamePart {
        FORMATTED,
        FAMILY_NAME,
        GIVEN_NAME,
        MIDDLE_NAME,
        HONORIFIC_PREFIX,
        HONORIFIC_SUFFIX
    }

    /** {@code remove} of one {@code name} sub-attribute. */
    record RemoveNamePart(NamePart part) implements ScimUserPatchOperation {

        @Override
        public ScimUserEdit applyTo(ScimUserEdit edit) {
            ScimName n = edit.profile().name();
            ScimName cleared = new ScimName(
                    part == NamePart.FORMATTED ? null : n.formatted(),
                    part == NamePart.FAMILY_NAME ? null : n.familyName(),
                    part == NamePart.GIVEN_NAME ? null : n.givenName(),
                    part == NamePart.MIDDLE_NAME ? null : n.middleName(),
                    part == NamePart.HONORIFIC_PREFIX ? null : n.honorificPrefix(),
                    part == NamePart.HONORIFIC_SUFFIX ? null : n.honorificSuffix());
            return withName(edit, cleared);
        }
    }

    /**
     * {@code add} to {@code emails}: the values are appended, and a value already present is not
     * added twice.
     *
     * <p>An added value marked {@code primary} takes primacy from any stored one. RFC 7643 allows
     * one primary value, and the canonical form would otherwise keep the STORED primary and demote
     * the new one — the opposite of what a client asking for a new primary address meant.
     */
    record AddEmails(List<ScimEmail> emails) implements ScimUserPatchOperation {

        public AddEmails {
            emails = List.copyOf(emails);
        }

        @Override
        public ScimUserEdit applyTo(ScimUserEdit edit) {
            List<ScimEmail> newPrimaries = emails.stream().filter(ScimEmail::primary).toList();
            List<ScimEmail> combined = new ArrayList<>();
            for (ScimEmail stored : edit.profile().emails()) {
                // A stored value that IS one of the added primaries is kept as it is: demoting it
                // would make the de-duplication keep the demoted stored copy and drop the added
                // one, so re-adding the current primary would silently take its primacy away.
                combined.add(newPrimaries.isEmpty() || newPrimaries.contains(stored)
                        ? stored
                        : demoted(stored));
            }
            combined.addAll(emails);
            return withEmails(edit, combined);
        }
    }

    /** {@code replace} of {@code emails} as a whole. */
    record ReplaceEmails(List<ScimEmail> emails) implements ScimUserPatchOperation {

        public ReplaceEmails {
            emails = List.copyOf(emails);
        }

        @Override
        public ScimUserEdit applyTo(ScimUserEdit edit) {
            return withEmails(edit, emails);
        }
    }

    /**
     * {@code remove} of {@code emails} values: every value, or those a filter selects.
     *
     * <p>A filter that selects nothing removes nothing, and is not an error: the practical SCIM
     * protocol profile's rule, as RFC 7644 §3.5.2.2 says of removing a non-member from a Group.
     */
    record RemoveEmails(ScimEmailFilter filter) implements ScimUserPatchOperation {

        @Override
        public ScimUserEdit applyTo(ScimUserEdit edit) {
            List<ScimEmail> stored = edit.profile().emails();
            return withEmails(edit, stored.stream().filter(email -> !filter.matches(email)).toList());
        }
    }

    /**
     * Sub-attributes to write onto the {@code emails} values a filter selects. A null component
     * is one the operation did not name and is left as stored.
     */
    record EmailUpdate(String value, String type, Boolean primary) {
    }

    /**
     * {@code add} or {@code replace} on selected {@code emails} values —
     * {@code emails[type eq "work"]} with an object value, or {@code emails[type eq "work"].value}
     * with a single one.
     *
     * <p>Setting {@code primary} to true on a selected value takes primacy from every other one,
     * for the reason {@link AddEmails} gives.
     */
    record UpdateEmails(ScimEmailFilter filter, EmailUpdate update)
            implements ScimUserPatchOperation {

        @Override
        public ScimUserEdit applyTo(ScimUserEdit edit) {
            List<ScimEmail> stored = edit.profile().emails();
            requireTarget(filter, stored);
            boolean promoting = Boolean.TRUE.equals(update.primary());
            List<ScimEmail> updated = new ArrayList<>(stored.size());
            for (ScimEmail email : stored) {
                if (filter.matches(email)) {
                    updated.add(new ScimEmail(
                            update.value() != null ? update.value() : email.value(),
                            update.type() != null ? update.type() : email.type(),
                            update.primary() != null ? update.primary() : email.primary()));
                } else {
                    updated.add(promoting ? demoted(email) : email);
                }
            }
            return withEmails(edit, updated);
        }
    }

    /**
     * {@code remove} of one sub-attribute from selected {@code emails} values.
     *
     * <p>{@code value} is required on an email, so removing it is {@code mutability}; removing
     * {@code primary} makes the value non-primary, which is what an unassigned boolean reads as. A
     * filter that selects nothing removes nothing, as it does for {@link RemoveEmails}.
     */
    record RemoveEmailPart(ScimEmailFilter filter, ScimEmailPart part)
            implements ScimUserPatchOperation {

        @Override
        public ScimUserEdit applyTo(ScimUserEdit edit) {
            if (part == ScimEmailPart.VALUE) {
                throw new ScimPatchRefusedException(
                        ScimPatchRefusedException.Reason.MUTABILITY,
                        "emails.value is required and cannot be removed; remove the email instead.");
            }
            List<ScimEmail> stored = edit.profile().emails();
            return withEmails(edit, stored.stream()
                    .map(email -> !filter.matches(email) ? email
                            : part == ScimEmailPart.TYPE
                                    ? new ScimEmail(email.value(), null, email.primary())
                                    : demoted(email))
                    .toList());
        }
    }

    private static void requireTarget(ScimEmailFilter filter, List<ScimEmail> stored) {
        if (filter.selectsParticularValues() && stored.stream().noneMatch(filter::matches)) {
            throw new ScimPatchRefusedException(
                    ScimPatchRefusedException.Reason.NO_TARGET,
                    "The emails filter matched no value.");
        }
    }

    private static ScimEmail demoted(ScimEmail email) {
        return email.primary() ? new ScimEmail(email.value(), email.type(), false) : email;
    }

    private static ScimUserEdit text(ScimUserEdit edit, TextAttribute attribute, String value) {
        return profile(edit, p -> copy(
                attribute == TextAttribute.USER_NAME ? value : p.userName(),
                p.name(),
                attribute == TextAttribute.DISPLAY_NAME ? value : p.displayName(),
                attribute == TextAttribute.PREFERRED_LANGUAGE ? value : p.preferredLanguage(),
                attribute == TextAttribute.LOCALE ? value : p.locale(),
                attribute == TextAttribute.TIMEZONE ? value : p.timezone(),
                p.active(),
                p.emails()));
    }

    private static ScimUserEdit withName(ScimUserEdit edit, ScimName name) {
        return profile(edit, p -> copy(p.userName(), name, p.displayName(),
                p.preferredLanguage(), p.locale(), p.timezone(), p.active(), p.emails()));
    }

    private static ScimUserEdit withEmails(ScimUserEdit edit, List<ScimEmail> emails) {
        return profile(edit, p -> copy(p.userName(), p.name(), p.displayName(),
                p.preferredLanguage(), p.locale(), p.timezone(), p.active(), emails));
    }

    private static ScimUserEdit profile(ScimUserEdit edit, UnaryOperator<ScimUserProfile> change) {
        return edit.withProfile(change.apply(edit.profile()));
    }

    private static ScimUserProfile copy(
            String userName,
            ScimName name,
            String displayName,
            String preferredLanguage,
            String locale,
            String timezone,
            boolean active,
            List<ScimEmail> emails) {
        return new ScimUserProfile(
                userName, name, displayName, preferredLanguage, locale, timezone, active, emails);
    }
}
