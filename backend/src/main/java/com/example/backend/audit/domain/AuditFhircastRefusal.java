package com.example.backend.audit.domain;

/**
 * Why a FHIRcast release was refused (ADR 0016), as a closed set: the reason a
 * {@link AuditOperation#FHIRCAST_TOKEN_RELEASE} failure records, and the one thing about it a
 * later query groups by. Each is answered {@code 404}, the same to the caller whichever it was.
 */
public enum AuditFhircastRefusal {

    /** The session holds no Epic tokens: a password Login's, or one that has ended. */
    NO_EPIC_TOKENS,

    /** The Epic session kept no FHIRcast hub: its token response named none this service keeps. */
    NO_FHIRCAST_HUB,

    /** The session's access token is past its expiry, so there is nothing valid to release. */
    TOKEN_EXPIRED
}
