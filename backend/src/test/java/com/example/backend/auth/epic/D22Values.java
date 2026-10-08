package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The values ADR 0013's D22 says are never logged or audited, as one Epic Login actually handled
 * them, gathered by kind from both ends of the Login: what the test's browser sent, and what
 * {@link FakeEpic} was sent and answered with.
 *
 * <p>Each value is unique to the Login, so finding one in an output is never a coincidence. A
 * value too short to be told apart from ordinary text (an empty {@code code}, say) is not kept.
 */
final class D22Values {

    /** Shorter than this, a value could appear in an output by chance. */
    private static final int DISTINCTIVE = 8;

    private final Map<String, Set<String>> byKind = new TreeMap<>();

    /** {@code value}, handled as a D22 value of {@code kind}. */
    D22Values add(String kind, String value) {
        if (value != null && value.length() >= DISTINCTIVE) {
            byKind.computeIfAbsent(kind, ignored -> new LinkedHashSet<>()).add(value);
        }
        return this;
    }

    /** A fresh, valid {@code launch} value for one Login, recorded as handled. */
    String launch() {
        String launch = "launch-" + UUID.randomUUID();
        add("launch", launch);
        return launch;
    }

    /** Epic's redirect back to our callback: its {@code code} and {@code state}. */
    D22Values callback(Map<String, String> query) {
        return add("code", query.get("code")).add("state", query.get("state"));
    }

    /**
     * The private key material of {@code pair}, as {@code kind}: the PKCS#8 body of its PEM, whole
     * and line by line, and the private scalar {@code d} as a JWK would write it.
     */
    D22Values signingKey(String kind, KeyPair pair) {
        add(kind, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        for (String line : EpicTestKeys.pem(pair).split("\n")) {
            if (!line.startsWith("-----")) {
                add(kind, line);
            }
        }
        return add(kind, EpicTestKeys.base64Url48(((ECPrivateKey) pair.getPrivate()).getS()));
    }

    /** The kinds of value the Login handled. */
    Set<String> kinds() {
        return byKind.keySet();
    }

    /** Holds {@code output}, named {@code what}, to carrying none of the values. */
    void assertNoneIn(String what, String output) {
        byKind.forEach((kind, values) -> values.forEach(value -> assertThat(output)
                .as("%s carries no %s", what, kind)
                .doesNotContain(value)));
    }

    /**
     * Holds the audit trail to carrying none of the values: every row's every column, as text —
     * the whole trail an administrator could read.
     */
    void assertNoneInTheAuditTrail(JdbcTemplate jdbc) {
        assertNoneIn("the audit trail", jdbc.queryForList("SELECT * FROM audit_events").toString());
    }
}
