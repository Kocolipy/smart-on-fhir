package com.example.backend.scim.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.audit.RecordingAuditTrail;
import com.example.backend.observability.MetricTag;
import com.example.backend.scim.domain.InvalidScimFilterException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * A SCIM refusal names its {@code scimType} on the request metric, so errors can be
 * counted "4xx by scimType".
 *
 * <p>{@code OperationalTelemetryIntegrationTests} proves the same thing end to end from a
 * real scrape, but it sends its traffic in {@code @BeforeAll}. PIT does not reliably
 * credit that code to any test method, so a mutant that removes the recording survived
 * one survey and was killed in another. This unit test calls the handler directly so
 * mutation testing sees the recording every time.
 */
class ScimExceptionHandlerMetricTests {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void a_refusal_records_its_scim_type_on_the_request_metric() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        ServerRequestObservationContext context =
                new ServerRequestObservationContext(request, new MockHttpServletResponse());
        request.setAttribute(
                ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, context);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        new ScimExceptionHandler(new RecordingAuditTrail()).handle(new InvalidScimFilterException("unparseable"));

        assertThat(context.getLowCardinalityKeyValue(MetricTag.SCIM_TYPE))
                .as("the scimType tag recorded on the in-flight observation")
                .isNotNull();
        assertThat(context.getLowCardinalityKeyValue(MetricTag.SCIM_TYPE).getValue())
                .isEqualTo("invalidFilter");
    }
}
