package com.example.backend.scim.domain;

import java.util.function.IntPredicate;

/**
 * A submitted attribute value contains a character its attribute does not accept: U+0000 in any
 * stored string, refused everywhere as input hygiene (ADR 0013), or any C0 control (or DEL) in a
 * {@code userName} or {@code displayName}, which are shown to people and written to logs.
 *
 * <p>Rendered as {@code 400 invalidValue} naming the attribute and the forbidden characters. The
 * value, and the position of the offending character in it, never reach this object.
 */
public final class ScimValueControlCharacterException extends ScimAttributeValueException {

    /** Which characters an attribute refuses. */
    public enum Forbidden {

        /** U+0000 alone: the one character refused in every stored string. */
        NUL("the NUL character (U+0000)", codePoint -> codePoint == 0),

        /** Every C0 control, U+0000 to U+001F, and DEL, U+007F. */
        CONTROL("control characters (U+0000 to U+001F, U+007F)",
                codePoint -> codePoint <= 0x1F || codePoint == 0x7F);

        private final String description;

        private final IntPredicate members;

        Forbidden(String description, IntPredicate members) {
            this.description = description;
            this.members = members;
        }

        /** How a refusal names these characters, e.g. {@code the NUL character (U+0000)}. */
        public String description() {
            return description;
        }

        /** Whether {@code codePoint} is one of these characters. */
        public boolean includes(int codePoint) {
            return members.test(codePoint);
        }
    }

    private final Forbidden forbidden;

    public ScimValueControlCharacterException(String attribute, Forbidden forbidden) {
        super(attribute, attribute + " must not contain " + forbidden.description() + ".");
        this.forbidden = forbidden;
    }

    /** The characters the attribute refuses, one of which the value contained. */
    public Forbidden forbidden() {
        return forbidden;
    }
}
