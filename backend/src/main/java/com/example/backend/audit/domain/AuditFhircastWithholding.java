package com.example.backend.audit.domain;

/**
 * Why a FHIRcast release answered the hub without the SMART access token (ADR 0016), as a closed
 * set: the reason a {@link AuditOperation#FHIRCAST_TOKEN_RELEASE} success records when it released
 * no token, the one thing that tells it from a release of the token.
 */
public enum AuditFhircastWithholding {

    /** The session's access token is past its expiry, so the hub is released without it. */
    TOKEN_EXPIRED
}
