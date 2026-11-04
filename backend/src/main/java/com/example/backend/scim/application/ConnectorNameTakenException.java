package com.example.backend.scim.application;

/**
 * A connector create refused because a live connector already holds exactly the name it asked
 * for, once trimmed. A Deleted SCIM connector holds no name, so it is never the holder.
 *
 * <p>Decided by the database's filtered unique index, not by a read before the insert: the
 * insert's violation is what this reports, so of two creates racing for one name exactly one is
 * refused. The insert rolls back with the use case's transaction, so nothing was written.
 *
 * <p>Carries nothing, and no message naming the name: the caller sent it, and an exception message
 * is the shortest path into a log line. The administrative edge answers it {@code 409} with the
 * {@code CONNECTOR_NAME_TAKEN} code, and does not audit it — the create addressed no connector
 * that exists to record a refusal against, as a token request of a shape no token may have does
 * not.
 */
public final class ConnectorNameTakenException extends ConnectorAdministrationRefusedException {

    public ConnectorNameTakenException(Throwable cause) {
        super("A live connector already holds this name");
        initCause(cause);
    }
}
