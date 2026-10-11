package com.example.backend.observability;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/**
 * Who the current request was authenticated as, carried out of the security chain to the
 * request record.
 *
 * <p>{@code user.id} and {@code scim.connector.id} are put in the logging context by filters
 * INSIDE the security chain, and each scope closes as the chain unwinds. The request record is
 * written by {@link RequestIdFilter}, which runs outside the chain so that a refusal by it is
 * recorded too — so by then both ids are gone from the context. The filter that resolves the
 * caller says so here as well, and {@link RequestIdFilter} puts the ids back in scope for the
 * one record it writes.
 *
 * <p>Kept on the request rather than in the logging context, for the reason
 * {@link RequestFault} is: the mark has to outlive the inner filter's scope. Only ids are
 * kept, already resolved by the filter that knows how — never a userName or anything of a
 * presented credential. A request that is anonymous, or refused before it authenticated,
 * never has either mark, so its record carries neither field.
 */
public final class RequestActor {

    static final String USER_ATTRIBUTE = RequestActor.class.getName() + ".userId";

    static final String CONNECTOR_ATTRIBUTE = RequestActor.class.getName() + ".connectorId";

    private RequestActor() {
    }

    /**
     * The request was authenticated by session as the User with this stable id; {@code null}
     * marks it as resolved to nobody, removing any earlier mark (a {@code null} attribute is
     * a removal, by the servlet contract).
     */
    public static void user(HttpServletRequest request, UUID userId) {
        request.setAttribute(USER_ATTRIBUTE, userId);
    }

    /**
     * The stable id of the User the request was authenticated as by session, or {@code null}.
     *
     * <p>Also how a web adapter outside {@code auth} — connector administration — names the
     * acting administrator: by the id its signed-in session holds, as the chain resolved it, and
     * never by the {@code userName} a rename can hand to another User.
     */
    public static UUID userOf(HttpServletRequest request) {
        return attribute(request, USER_ATTRIBUTE);
    }

    /** The request was authenticated by the bearer token of the connector with this id. */
    public static void connector(HttpServletRequest request, UUID connectorId) {
        request.setAttribute(CONNECTOR_ATTRIBUTE, connectorId);
    }

    /**
     * Puts the request's actor back in the logging context: {@code user.id} and
     * {@code scim.connector.id} as marked, and each removed where it was not, so the record
     * written inside the scope names whoever the request was authenticated as and nobody else.
     */
    static Scope inScope(HttpServletRequest request) {
        return new Scope(
                LogContext.userId(attribute(request, USER_ATTRIBUTE)),
                LogContext.connectorId(connectorValue(request)));
    }

    private static String connectorValue(HttpServletRequest request) {
        UUID connectorId = attribute(request, CONNECTOR_ATTRIBUTE);
        return connectorId == null ? null : connectorId.toString();
    }

    private static UUID attribute(HttpServletRequest request, String name) {
        return request.getAttribute(name) instanceof UUID id ? id : null;
    }

    /** Both context entries, closed together, the connector's first. */
    record Scope(LogContext.Scope user, LogContext.Scope connector) implements AutoCloseable {

        @Override
        public void close() {
            connector.close();
            user.close();
        }
    }
}
