package com.example.backend.web;

import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.springframework.boot.tomcat.ConfigurableTomcatWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Has Tomcat pass an encoded slash ({@code %2F}) in a request path through, still encoded, so
 * that the request reaches the application's own refusal rather than Tomcat's.
 *
 * <p>Tomcat's default refuses such a request {@code 400} itself, before any filter runs, with its
 * own HTML error page: no {@link ErrorNamespace}'s document, so {@code GET
 * /api/patient/a%2F%2Fb} was answered as no other malformed id is. Passed through, the request
 * meets Spring Security's {@code StrictHttpFirewall}, which refuses every encoded slash on every
 * path, unchanged — its {@code 400} is a {@code sendError}, which {@link ErrorDocumentFilter}
 * writes as the namespace's document: the FHIR {@code OperationOutcome} under
 * {@value ErrorNamespaces#FHIR_PATH}, the {@link ApiError} elsewhere under {@code /api}.
 *
 * <p>Path security is not weakened, and the setting is safe app-wide. {@code passthrough} never
 * decodes {@code %2F} into a path separator — that would be {@code decode}, which this is not — so
 * no path a request could not reach before becomes reachable, and the firewall still refuses the
 * request before anything else on either security chain sees it. What changes is only which
 * layer answers, and so that the refusal is the application's document.
 */
@Component
class EncodedSlashPassthrough
        implements WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> {

    @Override
    public void customize(ConfigurableTomcatWebServerFactory factory) {
        factory.addConnectorCustomizers(connector -> connector.setEncodedSolidusHandling(
                EncodedSolidusHandling.PASS_THROUGH.getValue()));
    }
}
