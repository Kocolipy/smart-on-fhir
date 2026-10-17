package com.example.backend.scim.domain;

/**
 * Whether a value begins or ends with whitespace — the one definition the directory's keys, the
 * password Login's username and the Epic {@code id_token}'s {@code iss} and {@code sub} are all
 * held to (ADR 0013, "Leading or trailing whitespace is refused on every unique or lookup key").
 *
 * <p>The reason is the database underneath, not the value: SQL Server pads {@code =} comparisons
 * and unique constraints, so {@code alice} and {@code alice } would collide as keys and match each
 * other in a lookup. Refusing edge whitespace on every key keeps a key meaning exactly the value
 * its holder sent. Whitespace inside a value is not at an edge and is accepted.
 *
 * <p><b>Whitespace</b> is Unicode's {@code White_Space} property, exactly: the space separators
 * ({@code Zs}, U+0020, U+00A0, U+1680, U+2000 to U+200A, U+202F, U+205F, U+3000), the line and
 * paragraph separators (U+2028, U+2029), the ASCII controls tab to carriage return (U+0009 to
 * U+000D), and NEL (U+0085). Neither {@link String#strip()} nor {@link Character#isWhitespace}
 * gives that set: both leave out the no-break spaces U+00A0, U+2007 and U+202F, which a
 * copy-pasted name is likeliest to carry, and both count the information separators U+001C to
 * U+001F, which Unicode does not. Format characters that merely render as nothing, such as the
 * zero-width space U+200B, are not whitespace.
 */
public final class EdgeWhitespace {

    private EdgeWhitespace() {
    }

    /**
     * Whether {@code value}'s first or last character is whitespace. An absent or empty value has
     * no edge, so carries none.
     */
    public static boolean borders(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return isWhitespace(value.codePointAt(0))
                || isWhitespace(value.codePointBefore(value.length()));
    }

    /** Unicode {@code White_Space}: {@code Zs}, {@code Zl}, {@code Zp}, U+0009 to U+000D, U+0085. */
    private static boolean isWhitespace(int codePoint) {
        return Character.isSpaceChar(codePoint)
                || (codePoint >= '\t' && codePoint <= '\r')
                || codePoint == 0x85;
    }
}
