package com.example.backend.scim.controller;

import java.util.Locale;

/**
 * A client-supplied name or path as a SCIM refusal may echo it: the one sanitising every User and
 * Group request reader applies before a submitted name reaches a response or a log line.
 */
final class ScimEcho {

    /** The most code points of a submitted name an echo carries. */
    private static final int MAX_ECHOED = 64;

    private ScimEcho() {
    }

    /**
     * A client-supplied name or path, reduced to something safe to put in a response and in the
     * log line an error may produce.
     *
     * <p>Control characters and line breaks are what a crafted value would carry to forge a log
     * record (CWE-117), and the length cap keeps a megabyte-long key from being echoed at all. The
     * text is lower-cased so the echo cannot be mistaken for a canonical spelling this service
     * recognises.
     */
    static String sanitized(String text) {
        return text.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(MAX_ECHOED)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .toLowerCase(Locale.ROOT);
    }
}
