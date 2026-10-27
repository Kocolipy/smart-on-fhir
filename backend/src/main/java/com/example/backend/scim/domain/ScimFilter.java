package com.example.backend.scim.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A parsed, validated RFC 7644 §3.4.2.2 filter.
 *
 * <p>Produced only by {@link ScimFilterParser}, which has already checked every path against
 * the vocabulary of the resource types being queried and every operator and value against the
 * attribute's type. So a translation of this tree never meets an invalid combination and never
 * sees a string a client typed as anything but a bound value: attribute names are
 * {@link ScimFilterPath} constants, operators are {@link Operator} constants, and a literal is a
 * {@link String}, {@link Boolean}, {@link Instant} or {@code null} carried beside them.
 *
 * <p><strong>Two-valued semantics.</strong> An attribute with no value satisfies no comparison
 * except {@code ne}, and {@code ne} is defined as {@code not eq} throughout — including over a
 * multi-valued attribute, where it means "no value equals". So {@code not} is ordinary boolean
 * negation and the translation never has to reason about SQL's third truth value.
 */
public sealed interface ScimFilter {

    /**
     * The Users who are direct members of the Group — {@code groups.value eq "<id>"} — built as a
     * value rather than parsed from filter text, so a Group id never passes through the grammar.
     * For a query over Users; a Group has no {@code groups}.
     */
    static ScimFilter directMemberOf(UUID groupId) {
        return new Comparison(
                new AttributeRef(ScimFilterPath.GROUPS_VALUE, null), Operator.EQ, groupId.toString());
    }

    /**
     * Whether any condition in this filter tests a path that satisfies {@code test}. A value
     * path's own attribute only scopes its conditions, so it is their paths that are tested.
     */
    boolean testsAnyPath(Predicate<ScimFilterPath> test);

    /** RFC 7644 comparison operators. {@code pr} is {@link Presence}, not one of these. */
    enum Operator {
        EQ, NE, CO, SW, EW, GT, GE, LT, LE;

        /** The operator as the RFC spells it. */
        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Whether this is one of the substring operators, which only text supports. */
        public boolean isSubstring() {
            return this == CO || this == SW || this == EW;
        }

        /** Whether this is an ordering operator. */
        public boolean isOrdering() {
            return this == GT || this == GE || this == LT || this == LE;
        }
    }

    /**
     * A path as the filter named it.
     *
     * @param path   the canonical path
     * @param schema the resource type whose schema URI qualified the path, or {@code null} when
     *               it was unqualified. A path qualified with the Group schema has no value on a
     *               User even when a User has an attribute of the same name.
     */
    record AttributeRef(ScimFilterPath path, ScimResourceType schema) {

        /** Whether this reference can denote an attribute of that resource type at all. */
        public boolean appliesTo(ScimResourceType type) {
            return schema == null || schema == type;
        }
    }

    /**
     * {@code attr op value}.
     *
     * @param value a {@link String} for text, a {@link Boolean}, an {@link Instant} for a
     *              dateTime, or {@code null} — which only {@code eq} and {@code ne} accept, and
     *              which means "has no value" and "has a value" respectively
     */
    record Comparison(AttributeRef attribute, Operator operator, Object value) implements ScimFilter {

        @Override
        public boolean testsAnyPath(Predicate<ScimFilterPath> test) {
            return test.test(attribute.path());
        }
    }

    /** {@code attr pr}: the attribute has a non-empty value. */
    record Presence(AttributeRef attribute) implements ScimFilter {

        @Override
        public boolean testsAnyPath(Predicate<ScimFilterPath> test) {
            return test.test(attribute.path());
        }
    }

    /** {@code left and right}. */
    record And(ScimFilter left, ScimFilter right) implements ScimFilter {

        @Override
        public boolean testsAnyPath(Predicate<ScimFilterPath> test) {
            return left.testsAnyPath(test) || right.testsAnyPath(test);
        }
    }

    /** {@code left or right}. */
    record Or(ScimFilter left, ScimFilter right) implements ScimFilter {

        @Override
        public boolean testsAnyPath(Predicate<ScimFilterPath> test) {
            return left.testsAnyPath(test) || right.testsAnyPath(test);
        }
    }

    /** {@code not (inner)}. */
    record Not(ScimFilter inner) implements ScimFilter {

        @Override
        public boolean testsAnyPath(Predicate<ScimFilterPath> test) {
            return inner.testsAnyPath(test);
        }
    }

    /**
     * {@code attr[inner]}: some single value of a multi-valued attribute satisfies {@code inner}
     * as a whole — every condition inside the brackets is about the SAME value, which is what
     * distinguishes {@code emails[type eq "work" and value co "x"]} from
     * {@code emails.type eq "work" and emails.value co "x"}.
     *
     * @param inner a filter whose every path is a sub-attribute of {@code attribute}
     */
    record ValuePath(AttributeRef attribute, ScimFilter inner) implements ScimFilter {

        @Override
        public boolean testsAnyPath(Predicate<ScimFilterPath> test) {
            return inner.testsAnyPath(test);
        }
    }
}
