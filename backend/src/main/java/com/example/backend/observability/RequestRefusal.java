package com.example.backend.observability;

import com.example.backend.observability.LogEvent.ErrorCategory;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * How the current request's refusal is classified, when the handler that answered it writes no
 * record of its own.
 *
 * <p>A handler that classifies a refusal rather than recording it says so here, and the request
 * record {@link RequestIdFilter} writes at the end of the same request carries the
 * {@code Log_Schema.md} §Error classification — the status as {@code error.code}, the category
 * given, no follow-up — so the one record the request has is the classified one. The FHIR path's
 * {@code 400} and {@code 404} are answered this way: a read of a patient adds no record to the
 * request's own (/docs/domain/patient.md, "Logging").
 *
 * <p>Kept on the request rather than in the logging context, as {@link RequestFault} is: the
 * mark has to outlive the handler's scope and be read by the filter after the chain returns.
 */
public final class RequestRefusal {

    static final String CATEGORY_ATTRIBUTE = RequestRefusal.class.getName() + ".category";

    private RequestRefusal() {
    }

    /** Classifies the refusal of the request being served on this thread, if there is one. */
    public static void classified(ErrorCategory category) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes current) {
            current.getRequest().setAttribute(CATEGORY_ATTRIBUTE, category);
        }
    }

    /** The category a handler classified the request's refusal as, or {@code null}. */
    static @Nullable ErrorCategory of(HttpServletRequest request) {
        return request.getAttribute(CATEGORY_ATTRIBUTE) instanceof ErrorCategory category
                ? category
                : null;
    }
}
