package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.scim.domain.PasswordPolicy;
import com.example.backend.scim.domain.PasswordPolicyRefusedException;
import com.example.backend.scim.domain.ScimRequestBodyTooLargeException;
import com.example.backend.scim.domain.ScimValueTooLongException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import tools.jackson.databind.JsonNode;

/**
 * A body the JSON codec could not read is a SCIM error document: {@code 413} when it ran past the
 * size bound while streaming, {@code 400 invalidSyntax} otherwise — never the codec's own message,
 * which quotes the input.
 */
class ScimExceptionHandlerBodyTests {

    private final ScimExceptionHandler handler = new ScimExceptionHandler(new RecordingAuditTrail());

    @Test
    void the_real_codec_wraps_a_stream_that_crossed_the_bound_and_it_renders_as_413() {
        HttpMessageNotReadableException unreadable = readThrough(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new ScimRequestBodyTooLargeException();
            }
        });

        ResponseEntity<Map<String, Object>> rendered = handler.handle(unreadable);

        assertThat(rendered.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(rendered.getBody())
                .containsEntry("schemas", List.of(ScimSchemas.ERROR))
                .containsEntry("status", "413")
                .doesNotContainKey("scimType");
        assertThat(rendered.getHeaders().getFirst("Content-Type")).isEqualTo(ScimSchemas.MEDIA_TYPE);
    }

    @Test
    void malformed_json_is_invalid_syntax_and_the_input_is_not_echoed() {
        HttpMessageNotReadableException unreadable = readThrough(
                new java.io.ByteArrayInputStream("{\"userName\":\"secret-v".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)));

        ResponseEntity<Map<String, Object>> rendered = handler.handle(unreadable);

        assertThat(rendered.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rendered.getBody())
                .containsEntry("status", "400")
                .containsEntry("scimType", "invalidSyntax");
        assertThat(String.valueOf(rendered.getBody().get("detail"))).doesNotContain("secret");
    }

    @Test
    void the_size_bound_is_found_however_deep_in_the_cause_chain() {
        HttpMessageNotReadableException nested = new HttpMessageNotReadableException("outer",
                new IllegalStateException("middle", new RuntimeException("inner",
                        new ScimRequestBodyTooLargeException())), null);

        assertThat(handler.handle(nested).getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(handler.handle(new HttpMessageNotReadableException("no cause", null, null))
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void me_is_not_implemented_for_any_caller() {
        assertThatThrownBy(() -> new ScimMeController().me())
                .isInstanceOfSatisfying(ScimErrorException.class, refusal -> {
                    assertThat(refusal.status()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
                    assertThat(refusal.scimType()).isNull();
                    assertThat(refusal.detail()).contains("/Me");
                });
    }

    @ParameterizedTest
    @EnumSource(value = PasswordPolicy.Rule.class,
            names = {"TOO_SHORT", "TOO_LONG", "CONTAINS_USER_NAME"})
    void a_policy_refusal_is_invalid_value_naming_the_rule_and_its_requirement(
            PasswordPolicy.Rule rule) {
        ResponseEntity<Map<String, Object>> rendered =
                handler.handle(new PasswordPolicyRefusedException(rule));

        assertThat(rendered.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rendered.getBody())
                .containsEntry("status", "400")
                .containsEntry("scimType", "invalidValue")
                .containsEntry("detail", "The password does not satisfy the password policy ("
                        + rule.name() + "). " + rule.message() + ".");
    }

    @Test
    void an_over_length_value_is_invalid_value_naming_the_attribute_and_its_limit() {
        ResponseEntity<Map<String, Object>> rendered =
                handler.handle(new ScimValueTooLongException("emails.type", 32));

        assertThat(rendered.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rendered.getBody())
                .containsEntry("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:Error"))
                .containsEntry("status", "400")
                .containsEntry("scimType", "invalidValue")
                .containsEntry("detail", "emails.type must be at most 32 characters long.");
    }

    /**
     * An integrity violation no adapter translated is a server-side fault: {@code 500} in the SCIM
     * error document, with no {@code scimType} and none of the violation's message, which quotes
     * the statement and, for a unique key, the conflicting value.
     */
    @Test
    void an_unmapped_integrity_violation_is_a_scim_500_that_echoes_nothing() {
        String leaky = "duplicate key value violates unique constraint; Key (value)=(secret-value)";

        ResponseEntity<Map<String, Object>> rendered =
                handler.handle(new DataIntegrityViolationException(leaky));

        assertThat(rendered.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(rendered.getHeaders().getFirst("Content-Type"))
                .isEqualTo("application/scim+json");
        assertThat(rendered.getBody())
                .containsEntry("schemas", List.of("urn:ietf:params:scim:api:messages:2.0:Error"))
                .containsEntry("status", "500")
                .doesNotContainKey("scimType");
        assertThat((String) rendered.getBody().get("detail"))
                .isNotBlank()
                .doesNotContain("secret-value")
                .doesNotContain("constraint");
    }

    /**
     * The handler takes the fault away from the container's error logging, so it logs it itself:
     * one ERROR record of constant message, naming the most specific cause's type and nothing of
     * its message.
     */
    @Test
    void an_unmapped_integrity_violation_is_logged_as_its_cause_type_alone() {
        Logger logger = (Logger) LoggerFactory.getLogger(ScimExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        DataIntegrityViolationException violation = new DataIntegrityViolationException(
                "Key (value)=(secret-value)",
                new java.sql.SQLException("violates check constraint; secret-value"));
        StackTraceElement origin = new StackTraceElement("FaultOrigin", "write", "FaultOrigin.java", 7);
        violation.setStackTrace(new StackTraceElement[] {origin});
        try {
            handler.handle(violation);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("SCIM write refused by an unmapped integrity violation");
            assertThat(event.getKeyValuePairs())
                    .extracting(pair -> pair.key, pair -> String.valueOf(pair.value))
                    .containsExactly(
                            tuple("error_code", "500"),
                            tuple("error_category", "database"),
                            tuple("error_follow_up_action", "true"),
                            tuple("event.kind", "event"),
                            tuple("event.category", "[database]"),
                            tuple("event.type", "[error]"),
                            tuple("event.action", "user-provisioning"),
                            tuple("app.event.action", "scim.write"),
                            tuple("event.outcome", "failure"),
                            tuple("event.reason", "SQLException"));
            assertThat(event.getThrowableProxy()).as("the fault, redacted to its cause's type")
                    .isNotNull();
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("SQLException");
            assertThat(event.getThrowableProxy().getCause()).as("no cause chain").isNull();
            assertThat(event.getThrowableProxy().getStackTraceElementProxyArray())
                    .as("the stack is the fault's own, saying where it failed")
                    .extracting(element -> element.getStackTraceElement())
                    .containsExactly(origin);
        });
    }

    private static HttpMessageNotReadableException readThrough(InputStream body) {
        JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter();
        MockHttpInputMessage message = new MockHttpInputMessage(body);
        message.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            converter.read(JsonNode.class, message);
        } catch (HttpMessageNotReadableException expected) {
            return expected;
        } catch (IOException unexpected) {
            throw new AssertionError("the codec raised an IOException, not a read failure",
                    unexpected);
        }
        throw new AssertionError("the codec read a body it should have refused");
    }
}
