package com.example.backend.scim.application;

/**
 * A refusal of a connector or token operation an administrator drove, recorded nowhere by the use
 * case that refused. Nothing it refused is written: most kinds are thrown before any write, and
 * a name already taken ({@link ConnectorNameTakenException}) is the insert's own refusal, rolled
 * back with the use case's transaction.
 *
 * <p>Each kind carries what its record needs — the administrator, what the operation addressed and
 * why, by stable id and a closed-set reason — and nothing else. What a refusal is answered, logged
 * and audited as is decided once, at the edge of {@code /api/admin}, in a table exhaustive over
 * this sealed type, so a new kind does not compile without its entry. The edge appends the
 * refusal's {@code Audited.Refused} event after the use case's transaction has rolled back (ADR
 * 0004). The table is the {@code auth} module's, which reaches this module;
 * nothing here names it, so the {@code scim -> auth} direction stays closed.
 */
public abstract sealed class ConnectorAdministrationRefusedException extends RuntimeException
        permits UnknownConnectorException, ConnectorTokenNotRotatableException,
        ConnectorNameTakenException {

    ConnectorAdministrationRefusedException(String message) {
        super(message);
    }
}
