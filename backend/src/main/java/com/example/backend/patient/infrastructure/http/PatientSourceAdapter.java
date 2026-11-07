package com.example.backend.patient.infrastructure.http;

import com.example.backend.observability.OutboundCallFailure;
import com.example.backend.observability.RedactedFaultException;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientId;
import com.example.backend.patient.domain.PatientStore;
import com.example.backend.patient.infrastructure.http.PatientSourceException.Failure;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Adapts the domain's {@link PatientStore} port onto the in-house patient source (ADR 0014):
 * {@code GET {base}/Patient/{id}}, which is assumed to answer FHIR R4 and to need no
 * authorisation, so the call carries no credential of any kind. The patient's id is its EHR's
 * FHIR id, sent unchanged.
 *
 * <ul>
 *   <li><b>Found</b>: the {@code 200}'s R4 {@code Patient}, copied into the domain's
 *       ({@link R4Patient}).
 *   <li><b>Not found</b>: a {@code 404} is no patient under the id, the ordinary answer the SPA
 *       reads as "No patient in context".
 *   <li><b>Anything else</b> — another status, no answer within the timeouts, a connection lost
 *       before the answer was in, the body's included, a body that is not a Patient the service
 *       can hold — is a {@link PatientSourceException}, a fault answered
 *       {@code 502}, naming the failure or the broken rule, whose message and cause carry neither
 *       the id nor anything of the body.
 * </ul>
 *
 * <p>No Patient is cached, in memory or in Redis: every read is a call to the source. The client is
 * built from Spring Boot's {@link RestClient.Builder}, so each call is observed as every client's
 * is ({@code http.client.requests}, under the URI template, never the id), on the JDK client with
 * redirects off and the timeouts below. Nothing here logs.
 */
@Component
class PatientSourceAdapter implements PatientStore {

    /** How long a connection to the source may take. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);

    /** How long the source may take to answer once connected. */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");

    private static final Set<String> SCHEMES = Set.of("http", "https");

    private final RestClient source;

    /** The client's read timeout, by which a body read that failed is told timed out. */
    private final Duration readTimeout;

    /**
     * @param baseUrl the source's FHIR base, {@code APP_PATIENT_SOURCE_BASE_URL}: required, with
     *                no default, so a deployment that forgot it fails to start
     */
    @Autowired
    PatientSourceAdapter(RestClient.Builder builder,
            @Value("${app.patient-source.base-url}") String baseUrl) {
        this(builder, baseUrl, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    PatientSourceAdapter(RestClient.Builder builder, String baseUrl, Duration connectTimeout,
            Duration readTimeout) {
        JdkClientHttpRequestFactory requests =
                new JdkClientHttpRequestFactory(httpClient(connectTimeout));
        requests.setReadTimeout(readTimeout);
        this.readTimeout = readTimeout;
        this.source = builder
                .baseUrl(base(baseUrl))
                .requestFactory(requests)
                .build();
    }

    @Override
    public Optional<Patient> findById(PatientId id) {
        long sent = System.nanoTime();
        try {
            return source.get()
                    .uri("/Patient/{id}", id.value())
                    .accept(FHIR_JSON)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                            return Optional.<Patient>empty();
                        }
                        if (!response.getStatusCode().isSameCodeAs(HttpStatus.OK)) {
                            throw PatientSourceException.answered(
                                    response.getStatusCode().value());
                        }
                        return Optional.of(response.bodyTo(R4Patient.class).toPatient(id));
                    });
        } catch (PatientSourceException fault) {
            throw fault;
        } catch (RuntimeException failed) {
            Duration waited = Duration.ofNanos(System.nanoTime() - sent);
            throw PatientSourceException.failed(failure(failed, waited), redacted(failed));
        }
    }

    /**
     * A call that failed with no status, after {@code waited}, as ADR 0012's table has it
     * ({@link OutboundCallFailure}): no answer — before the headers, or while reading the body —
     * timed out or found the source unreachable, and anything else was an answer that could not
     * be read. The call is timed, since Spring's JDK client cuts a stalled body off at the read
     * timeout as a bare I/O failure, and only the time tells that from a reset.
     */
    private Failure failure(RuntimeException failed, Duration waited) {
        return Failure.of(OutboundCallFailure.failed(
                failed, HttpTimeoutException.class, waited, readTimeout).reason());
    }

    /** {@code fault} redacted to its type: its message can quote the URL, and so the id. */
    private static RedactedFaultException redacted(RuntimeException fault) {
        return RedactedFaultException.of(fault, fault.getClass().getName());
    }

    /**
     * The JDK client every call is made on: {@code connectTimeout} to connect, and no redirect
     * followed, since a redirect away from the configured source is not its answer. Following
     * none is the JDK builder's own default, {@link HttpClient.Redirect#NEVER}, so the builder is
     * left at it: a redirect reaches {@link #findById} as the {@code 3xx} it is, a fault.
     */
    static HttpClient httpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
    }

    /**
     * The base URL, which must be an absolute http or https URL. A path is kept, and a trailing
     * slash is harmless: the client joins {@code /Patient/{id}} to it with one slash.
     */
    private static String base(String configured) {
        URI uri = parsed(configured);
        if (uri == null || !SCHEMES.contains(String.valueOf(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalStateException(
                    "APP_PATIENT_SOURCE_BASE_URL must be an absolute http or https URL");
        }
        return configured;
    }

    private static URI parsed(String configured) {
        if (configured == null) {
            return null;
        }
        try {
            return new URI(configured);
        } catch (URISyntaxException unparseable) {
            return null;
        }
    }
}
