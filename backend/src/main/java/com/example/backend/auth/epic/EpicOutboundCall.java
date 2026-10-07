package com.example.backend.auth.epic;

import java.util.Optional;
import org.springframework.http.HttpRequest;
import org.springframework.web.client.RestClient;

/**
 * Which of the three Epic calls an outbound request is (D25): the {@code call} the outbound log
 * records and the {@code epic.outbound} meters are tagged with.
 *
 * <p>Each call site names its call on the request itself, as a request attribute
 * ({@link #on}), so the one interceptor on {@code epicRestClient} reads it rather than guessing
 * from a URL that discovery, not this service, decides.
 */
public enum EpicOutboundCall {

    /** OpenID Connect discovery, on the issuer. */
    DISCOVERY("discovery"),

    /** Epic's {@code id_token} signing keys, from the discovered {@code jwks_uri}. */
    JWKS("jwks"),

    /** The code redemption, at the discovered {@code token_endpoint}. */
    TOKEN("token");

    /** The request attribute a call site names its call under. */
    static final String ATTRIBUTE = EpicOutboundCall.class.getName();

    private final String tag;

    EpicOutboundCall(String tag) {
        this.tag = tag;
    }

    /** The call's name as the log field and the meter tag spell it. */
    public String tag() {
        return tag;
    }

    /** Names this call on {@code request}, for the outbound interceptor to read. */
    public <S extends RestClient.RequestHeadersSpec<?>> S on(S request) {
        request.attribute(ATTRIBUTE, this);
        return request;
    }

    /** The call {@code request} was named as, if a call site named it. */
    static Optional<EpicOutboundCall> of(HttpRequest request) {
        return request.getAttributes().get(ATTRIBUTE) instanceof EpicOutboundCall call
                ? Optional.of(call)
                : Optional.empty();
    }
}
