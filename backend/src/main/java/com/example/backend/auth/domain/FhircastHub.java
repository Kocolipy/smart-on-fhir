package com.example.backend.auth.domain;

import java.io.Serial;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The **FHIRcast Hub** an Epic Login's token response named (/CONTEXT.md): its {@code hub.url}
 * and the clinician's {@code hub.topic}, kept on the signed-in session beside the
 * {@link EpicTokenSet} and released to the SPA with the SMART access token (ADR 0016).
 *
 * <p>Kept only when both members pass, so a session holds a usable hub or none:
 *
 * <ul>
 *   <li>{@code hub.url} is an {@code http} or {@code https} URL whose host is exactly
 *       {@code 127.0.0.1}, on any port, with no user-info — Epic's local hub runs on the
 *       clinician's own workstation, and the SPA may reach nothing else (the CSP's loopback
 *       {@code connect-src});
 *   <li>{@code hub.topic} is made of {@code [A-Za-z0-9\-._~]}, 1 to 128 of them — URL-safe, so it
 *       goes into the subscription's form body with nothing to escape.
 * </ul>
 *
 * <p>Serializable because the session store keeps it as a session attribute, as it keeps the
 * tokens; both fields are strings. Neither is a secret, but the topic names the clinician's
 * FHIRcast session, so neither is logged.
 */
public final class FhircastHub implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The token-response member naming the hub (FHIRcast STU3). */
    public static final String URL = "hub.url";

    /** The token-response member naming the clinician's topic (FHIRcast STU3). */
    public static final String TOPIC = "hub.topic";

    /** The one host a hub may be on: the loopback address, as Epic's local hub listens. */
    private static final String LOOPBACK = "127.0.0.1";

    private static final Set<String> SCHEMES = Set.of("http", "https");

    private static final Pattern TOPIC_GRAMMAR = Pattern.compile("[A-Za-z0-9\\-._~]{1,128}");

    private final String url;

    private final String topic;

    private FhircastHub(String url, String topic) {
        this.url = url;
        this.topic = topic;
    }

    /**
     * The hub {@code url} and {@code topic} name — each the token response's member, of whatever
     * JSON type Epic sent — when both pass.
     *
     * @return the hub; empty when either is missing, not a string, or outside its rule
     */
    public static Optional<FhircastHub> of(Object url, Object topic) {
        if (url instanceof String hubUrl && isLoopbackHttp(hubUrl)
                && topic instanceof String hubTopic && TOPIC_GRAMMAR.matcher(hubTopic).matches()) {
            return Optional.of(new FhircastHub(hubUrl, hubTopic));
        }
        return Optional.empty();
    }

    private static boolean isLoopbackHttp(String url) {
        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException notAUrl) {
            return false;
        }
        return parsed.getScheme() != null
                && SCHEMES.contains(parsed.getScheme().toLowerCase(Locale.ROOT))
                && parsed.getRawUserInfo() == null
                && LOOPBACK.equals(parsed.getHost());
    }

    /** The hub's URL, on {@code 127.0.0.1}. */
    public String url() {
        return url;
    }

    /** The clinician's FHIRcast topic. */
    public String topic() {
        return topic;
    }

    /** Names the hub by its URL alone: the topic names the clinician's FHIRcast session. */
    @Override
    public String toString() {
        return "FhircastHub[url=" + url + ", topic=<redacted>]";
    }
}
