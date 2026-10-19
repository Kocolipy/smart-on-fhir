package com.example.backend.scim.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.example.backend.audit.CapturedLog;
import com.example.backend.observability.LogEvent;
import com.example.backend.scim.application.ScimLogFields;
import com.example.backend.web.ErrorDocumentFilter;
import com.example.backend.web.ErrorNamespaces;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A refusal the dispatcher sends with {@code sendError} inside the SCIM namespace becomes a SCIM
 * error document, keeps the headers set before it, and never quotes the dispatcher's message —
 * through the one {@code sendError} adapter, with the SCIM namespace contributed as this module's
 * configuration contributes it.
 */
class ScimErrorNamespaceTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ErrorDocumentFilter filter = new ErrorDocumentFilter(
            new ErrorNamespaces(List.of(new ScimSecurityConfig().scimErrorNamespace())));

    /** A request in the SCIM namespace. */
    private static MockHttpServletRequest scimRequest() {
        return new MockHttpServletRequest("POST", "/scim/v2/Users");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "404 | No SCIM endpoint is served at this path.",
        "405 | This SCIM endpoint does not support that HTTP method.",
        "406 | This SCIM endpoint responds with application/scim+json or application/json only.",
        "413 | The request body exceeds the 1 MiB limit.",
        "415 | A SCIM request body must be application/scim+json or application/json.",
        "500 | The request could not be served."})
    void a_sent_error_is_rendered_as_a_scim_error_document(int status, String detail)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        // A container's own default, so the document's UTF-8 is shown to be set, not inherited.
        response.setDefaultCharacterEncoding("ISO-8859-1");

        filter.doFilter(scimRequest(), response, (req, res) -> {
            ((HttpServletResponse) res).setHeader("Allow", "GET, POST");
            ((HttpServletResponse) res).sendError(status, "Request method 'X<script>' is bad");
        });

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("application/scim+json");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        assertThat(response.getHeader("Content-Type")).contains("charset=UTF-8");
        assertThat(response.getHeader("Allow")).isEqualTo("GET, POST");
        assertThat(response.isCommitted()).isTrue();
        assertThat(response.getErrorMessage()).as("no container error dispatch").isNull();
        JsonNode error = JSON.readTree(response.getContentAsString());
        assertThat(error.get("schemas").get(0).asText())
                .isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");
        assertThat(error.get("status").asText()).isEqualTo(String.valueOf(status));
        assertThat(error.has("scimType")).isFalse();
        assertThat(error.get("detail").asText()).isEqualTo(detail);
        assertThat(response.getContentAsString()).doesNotContain("script");
    }

    /**
     * Each refusal the dispatcher sends is one {@code scim.refusal} record — the advice's record
     * shape — with a reason fixed by the status, and nothing of the dispatcher's message.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "404 | notFound             | WARN  | denied",
        "405 | methodNotAllowed     | WARN  | denied",
        "406 | notAcceptable        | WARN  | denied",
        "413 | payloadTooLarge      | WARN  | denied",
        "415 | unsupportedMediaType | WARN  | denied",
        "400 | requestRefused       | WARN  | denied",
        "500 | serverError          | ERROR | error",
        "503 | serverError          | ERROR | error"})
    void a_sent_error_is_one_scim_refusal_record(
            int status, String reason, String level, String type) throws Exception {
        try (CapturedLog captured = CapturedLog.attach()) {
            filter.doFilter(scimRequest(), new MockHttpServletResponse(),
                    (req, res) -> ((HttpServletResponse) res)
                            .sendError(status, "Request method 'X<script>' is bad"));

            List<ILoggingEvent> records =
                    captured.withAction(Level.TRACE, LogEvent.LOCAL_ACTION, "scim.refusal");
            assertThat(records).hasSize(1);
            ILoggingEvent record = records.getFirst();
            assertThat(record.getLevel()).isEqualTo(Level.toLevel(level));
            assertThat(record.getFormattedMessage()).isEqualTo("SCIM request refused");
            assertThat(CapturedLog.fields(record))
                    .containsEntry(LogEvent.ACTION, "user-provisioning")
                    .containsEntry(LogEvent.CATEGORY, List.of("process"))
                    .containsEntry(LogEvent.TYPE, List.of(type))
                    .containsEntry(LogEvent.OUTCOME, "failure")
                    .containsEntry(LogEvent.REASON, reason)
                    .containsEntry(LogEvent.HTTP_STATUS_CODE, status)
                    .doesNotContainKey(ScimLogFields.SCIM_RESOURCE_TYPE);
            assertThat(record.toString()).doesNotContain("script");
        }
    }

    @Test
    void a_response_that_sends_no_error_writes_no_record() throws Exception {
        try (CapturedLog captured = CapturedLog.attach()) {
            filter.doFilter(scimRequest(), new MockHttpServletResponse(),
                    (req, res) -> ((HttpServletResponse) res).setStatus(401));

            assertThat(captured.withAction(Level.TRACE, LogEvent.LOCAL_ACTION, "scim.refusal"))
                    .isEmpty();
        }
    }

    @Test
    void the_messageless_send_error_is_rendered_too() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(scimRequest(), response,
                (req, res) -> ((HttpServletResponse) res).sendError(405));

        assertThat(response.getStatus()).isEqualTo(405);
        assertThat(JSON.readTree(response.getContentAsString()).get("status").asText())
                .isEqualTo("405");
    }

    @Test
    void anything_written_before_the_error_is_discarded() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setBufferSize(1024);

        filter.doFilter(scimRequest(), response, (req, res) -> {
            res.getWriter().write("partial");
            ((HttpServletResponse) res).sendError(404);
        });

        assertThat(response.getContentAsString()).doesNotContain("partial").startsWith("{");
    }

    @Test
    void an_error_after_the_response_is_committed_is_refused_as_the_servlet_api_requires()
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(scimRequest(), response, (req, res) -> {
            res.flushBuffer();
            assertThatThrownBy(() -> ((HttpServletResponse) res).sendError(404))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ((HttpServletResponse) res).sendError(404, "x"))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void a_response_that_sends_no_error_is_left_alone() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(scimRequest(), response, (req, res) -> {
            ((HttpServletResponse) res).setStatus(401);
            ((HttpServletResponse) res).setHeader("WWW-Authenticate", "Bearer");
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }
}
