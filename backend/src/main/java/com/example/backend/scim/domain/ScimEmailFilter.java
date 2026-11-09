package com.example.backend.scim.domain;

import java.util.List;
import java.util.Locale;

/**
 * Which {@code emails} values a PATCH value path selects: {@code emails[type eq "work"]}.
 *
 * <p>A conjunction of equality comparisons on the three {@code emails} sub-attributes, which is
 * the value-path form connectors send in practice. The full RFC 7644 filter grammar is the
 * {@code filter} query parameter's; the reader refuses any other operator as {@code invalidFilter}
 * rather than approximating it, so this type never has to represent a comparison it cannot
 * evaluate exactly.
 *
 * <p>{@code type} and {@code value} both compare case-insensitively, as the schema advertises
 * them and as a query filter on the same sub-attributes compares — one value path, one meaning.
 *
 * <p>{@link #ALL} selects every value and is what an unfiltered sub-attribute path such as
 * {@code emails.type} means. It is the one filter that may match nothing without being a
 * {@code noTarget}, because it named no particular value to find.
 *
 * @param conditions the comparisons every selected value satisfies; empty for {@link #ALL}
 */
public record ScimEmailFilter(List<Condition> conditions) {

    /** Every {@code emails} value. */
    public static final ScimEmailFilter ALL = new ScimEmailFilter(List.of());

    /** One {@code sub eq value} comparison. {@code expected} is a String, a Boolean, or null. */
    public record Condition(ScimEmailPart part, Object expected) {

        public Condition {
            if (part == null) {
                throw new IllegalArgumentException("a condition names a sub-attribute");
            }
            if (part == ScimEmailPart.PRIMARY ? !(expected instanceof Boolean)
                    : expected != null && !(expected instanceof String)) {
                throw new IllegalArgumentException("the compared value's type must match");
            }
        }

        boolean matches(ScimEmail email) {
            return switch (part) {
                case VALUE -> expected == null ? email.value() == null
                        : lower((String) expected).equals(lower(email.value()));
                case TYPE -> expected == null ? email.type() == null
                        : email.type() != null
                                && lower((String) expected).equals(lower(email.type()));
                case PRIMARY -> expected.equals(email.primary());
            };
        }

        private static String lower(String value) {
            return value.toLowerCase(Locale.ROOT);
        }
    }

    public ScimEmailFilter {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    /** Whether this filter names particular values rather than all of them. */
    public boolean selectsParticularValues() {
        return !conditions.isEmpty();
    }

    /** Whether this value satisfies every condition. */
    public boolean matches(ScimEmail email) {
        return conditions.stream().allMatch(condition -> condition.matches(email));
    }
}
