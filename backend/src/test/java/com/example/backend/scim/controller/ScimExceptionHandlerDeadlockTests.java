package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import com.example.backend.audit.CapturedLog;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.observability.ExhaustedRetry;
import com.example.backend.observability.LogContext;
import com.example.backend.observability.LogEvent;
import com.example.backend.observability.RedactedFaultException;
import com.example.backend.observability.SqlFailures;
import com.example.backend.scim.application.ScimLogFields;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/**
 * ADR 0013's as-13 second deadlock as the SCIM namespace's advice answers it: in the SCIM error
 * document, {@code 503} with {@code Retry-After: 1} and the one {@code database} {@code ERROR}.
 * Any other failure of the kinds a deadlock arrives as is left unanswered, as before. Connector
 * administration's is not this advice's: it serves {@code /api}, whose advice answers it
 * ({@code NamespaceExceptionResolverTests}).
 */
class ScimExceptionHandlerDeadlockTests {

    /** The driver's text, which must reach neither the body nor the record's message. */
    private static final String DRIVER_TEXT = "deadlock detected on relation scim_resources";

    private final ScimExceptionHandler handler = new ScimExceptionHandler(new RecordingAuditTrail());

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private final UUID user = UUID.randomUUID();

    private CapturedLog logs;

    @BeforeEach
    void setUp() {
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/scim/v2/Users/{id}");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("id", user.toString()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        logs = CapturedLog.attach();
    }

    @AfterEach
    void tearDown() {
        logs.close();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void a_scim_writes_second_deadlock_is_a_503_error_document_with_retry_after() {
        ExhaustedRetry.recorded();

        ResponseEntity<?> answer = handler.handle(deadlock());

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(answer.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(answer.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE))
                .isEqualTo(ScimSchemas.MEDIA_TYPE);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) answer.getBody();
        assertThat(body)
                .containsEntry("schemas", List.of(ScimSchemas.ERROR))
                .containsEntry("status", "503")
                .doesNotContainKey("scimType");
        assertThat(body.get("detail").toString())
                .isNotBlank()
                .doesNotContain(DRIVER_TEXT, "CannotAcquireLockException", "40P01");
    }

    @Test
    void the_second_deadlock_is_one_database_error_naming_the_resource() {
        ExhaustedRetry.recorded();

        handler.handle(deadlock());

        assertThat(logs.withAction(Level.TRACE, LogEvent.KIND, "event")).singleElement()
                .satisfies(record -> {
                    assertThat(record.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(record.getThrowableProxy().getClassName())
                            .isEqualTo(RedactedFaultException.class.getName());
                    assertThat(record.getThrowableProxy().getMessage())
                            .as("named by its most specific cause, quoting nothing")
                            .isEqualTo("SQLException");
                    assertThat(CapturedLog.fields(record))
                            .containsEntry(LogEvent.ERROR_CODE, 503)
                            .containsEntry(LogEvent.HTTP_STATUS_CODE, 503)
                            .containsEntry(LogEvent.ERROR_CATEGORY, "database")
                            .containsEntry(LogEvent.ERROR_FOLLOW_UP_ACTION, true)
                            .containsEntry(LogEvent.REASON, "CannotAcquireLockException")
                            .containsEntry(ScimLogFields.SCIM_RESOURCE_TYPE, "User");
                    assertThat(record.getMDCPropertyMap())
                            .containsEntry(LogContext.RESOURCE_ID, user.toString());
                });
        assertThat(request.getAttribute(
                "com.example.backend.observability.RequestFault.recorded"))
                .as("the request record that follows is not a second ERROR")
                .isEqualTo(Boolean.TRUE);
    }

    /** A deadlock the transaction layer raised, at commit, is answered and recorded alike. */
    @Test
    void a_second_deadlock_raised_by_the_transaction_layer_is_the_same_503() {
        ExhaustedRetry.recorded();

        ResponseEntity<?> answer = handler.handle(new TransactionSystemException(
                "commit failed", SqlFailures.DEADLOCK_VICTIM.raised(DRIVER_TEXT)));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(logs.withAction(Level.TRACE, LogEvent.KIND, "event")).singleElement()
                .satisfies(record -> assertThat(record.getThrowableProxy().getMessage())
                        .isEqualTo("TransactionSystemException"));
    }

    /** A deadlock nothing retried is not as-13's second one, and is left unanswered as before. */
    @Test
    void a_deadlock_no_retry_met_is_left_unanswered() {
        CannotAcquireLockException deadlock = deadlock();

        assertThatThrownBy(() -> handler.handle(deadlock)).isSameAs(deadlock);
        assertThat(logs.withAction(Level.TRACE, LogEvent.KIND, "event")).isEmpty();
    }

    @Test
    void another_database_failure_of_an_exhausted_retrys_request_is_left_unanswered() {
        ExhaustedRetry.recorded();
        CannotCreateTransactionException other = new CannotCreateTransactionException("no pool");

        assertThatThrownBy(() -> handler.handle(other)).isSameAs(other);
    }

    /** The integrity violation keeps its own handler and its own 500. */
    @Test
    void an_integrity_violation_is_still_its_own_500() {
        ExhaustedRetry.recorded();

        assertThat(handler.handle(new DataIntegrityViolationException("x",
                        new java.sql.SQLException("y"))).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ---- harness ------------------------------------------------------------------------------

    private static CannotAcquireLockException deadlock() {
        return new CannotAcquireLockException("could not execute statement: " + DRIVER_TEXT,
                SqlFailures.DEADLOCK_VICTIM.raised(DRIVER_TEXT));
    }
}
