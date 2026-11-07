package com.example.backend.paging.domain;

import java.math.BigInteger;
import java.util.Objects;

/**
 * A {@code startIndex} or {@code count} as a client wrote it: an integer of any size, before
 * {@link PageRequest#of} coerces it into a page.
 *
 * <p>Of any size because RFC 7644 asks for an out-of-range paging value to be coerced, and a
 * value too large for an {@code int} is only further out of range: {@code count=99999999999} asks
 * for the largest page, not a malformed one. So the only paging value that is refused is one that
 * is not an integer at all.
 *
 * <p>A query parameter binds to this type through {@link #valueOf}, which Spring's conversion
 * finds by name: a value that is not an integer fails the binding, and the administrative reads
 * answer it with the binder's {@code 400}, as they answer any parameter that cannot be read as
 * its type. SCIM reads the same text through the same method and answers it in its own shape.
 *
 * @param value the integer asked for
 */
public record PageParameter(BigInteger value) {

    public PageParameter {
        Objects.requireNonNull(value, "value");
    }

    /**
     * The paging value a query parameter's text holds: an optionally signed decimal integer,
     * surrounding whitespace ignored, or {@code null} for a blank one, which is no value at all.
     *
     * @throws NumberFormatException when the text is not an integer — a fraction, an exponent,
     *     a word
     */
    public static PageParameter valueOf(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return new PageParameter(new BigInteger(text.strip()));
    }
}
