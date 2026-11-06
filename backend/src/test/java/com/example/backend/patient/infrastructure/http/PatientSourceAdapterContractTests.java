package com.example.backend.patient.infrastructure.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.example.backend.observability.RedactedFaultException;
import com.example.backend.patient.PatientSourceStubs;
import com.example.backend.patient.PatientStoreContract;
import com.example.backend.patient.domain.AdministrativeGender;
import com.example.backend.patient.domain.Patient;
import com.example.backend.patient.domain.PatientId;
import com.example.backend.patient.domain.PatientName;
import com.example.backend.patient.domain.PatientStore;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The production patient port — the HTTP adapter over the in-house patient source — read against
 * the patient source's mock, in process, serving the stub files the local mock serves; and what
 * the adapter alone decides: the call it makes, that nothing is cached, what a failure of the
 * source is, and that it says nothing of the patient when it is one.
 */
class PatientSourceAdapterContractTests extends PatientStoreContract {

    @RegisterExtension
    static final WireMockExtension SOURCE = PatientSourceStubs.extension();

    /** Tan Wei Ming's id: a fixture the stub files serve. */
    private static final PatientId TAN = new PatientId("eq081-VQEgP8drUUqCWzHfw3");

    /** An id no stub file names, for a case's own stub. */
    private static final PatientId PROBE = new PatientId("probe-Q7xKd2.v9");

    /** A Patient body whose every value is one no message could hold by chance. */
    private static final String PROBE_BODY = """
            {"resourceType": "Patient", "id": "probe-Q7xKd2.v9",
             "identifier": [{"system": "https://fhir.synapxe.sg/NamingSystem/nric",
                             "value": "T7391046Q"}],
             "name": [{"use": "official", "text": "Quah Seow Peng"}],
             "gender": "female", "birthDate": "%s"}""";

    @Override
    protected PatientStore patients() {
        return new PatientSourceAdapter(RestClient.builder(), SOURCE.baseUrl());
    }

    private static PatientSourceAdapter at(String baseUrl) {
        return new PatientSourceAdapter(RestClient.builder(), baseUrl);
    }

    private static void answering(PatientId id, String body) {
        SOURCE.stubFor(get(urlPathEqualTo("/Patient/" + id.value())).willReturn(okJson(body)));
    }

    /** What a log record attaching {@code failure} would print: every message in its chain. */
    private static String printed(Throwable failure) {
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    // ---- the call ------------------------------------------------------------------------

    /** The source is assumed to need no authorisation: a read presents nothing to it. */
    @Test
    void aReadPresentsNoCredentialToTheSource() {
        patients().findById(TAN);

        LoggedRequest read = SOURCE.findAll(getRequestedFor(urlPathEqualTo("/Patient/" + TAN)))
                .getFirst();
        assertThat(List.of(read.containsHeader("Authorization"), read.containsHeader("Cookie")))
                .containsExactly(false, false);
    }

    @Test
    void aReadAsksForFhirJson() {
        patients().findById(TAN);

        SOURCE.verify(getRequestedFor(urlPathEqualTo("/Patient/" + TAN))
                .withHeader("Accept", equalTo("application/fhir+json")));
    }

    /** No Patient is cached: every read, of the same patient too, is a call to the source. */
    @Test
    void everyReadIsACallToTheSource() {
        PatientStore patients = patients();

        patients.findById(TAN);
        patients.findById(TAN);

        SOURCE.verify(2, getRequestedFor(urlPathEqualTo("/Patient/" + TAN)));
    }

    /** A base with a path of its own, written with its trailing slash, keeps its path. */
    @Test
    void aBaseUrlWithAPathIsReadBeneathItsPath() {
        SOURCE.stubFor(get(urlPathEqualTo("/fhir/r4/Patient/" + PROBE))
                .willReturn(okJson(PROBE_BODY.formatted("1963-07-29"))));

        assertThat(at(SOURCE.baseUrl() + "/fhir/r4/").findById(PROBE)).isPresent();
    }

    /** The timeouts ADR 0012's outbound calls take, as the configuration docs state them. */
    @Test
    void theSourceIsGivenTwoSecondsToConnectAndFiveToAnswer() {
        assertThat(List.of(PatientSourceAdapter.CONNECT_TIMEOUT, PatientSourceAdapter.READ_TIMEOUT))
                .containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    // ---- the base URL --------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "patients.internal/fhir", "ftp://patients.internal/fhir",
        "http:///fhir", "http://patients internal/fhir"})
    void aBaseUrlThatIsNotAnAbsoluteHttpUrlIsRefused(String baseUrl) {
        assertThatThrownBy(() -> at(baseUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APP_PATIENT_SOURCE_BASE_URL must be an absolute http or https URL");
    }

    @Test
    void anUnsetBaseUrlIsRefused() {
        assertThatThrownBy(() -> at(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("APP_PATIENT_SOURCE_BASE_URL must be an absolute http or https URL");
    }

    /** The setting has no default: a start without it fails, and a start with it reads it. */
    @Test
    void theServiceDoesNotStartWithoutTheBaseUrl() {
        ApplicationContextRunner contexts = new ApplicationContextRunner()
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withUserConfiguration(PatientSourceAdapter.class);

        contexts.run(context -> assertThat(context).hasFailed());
        contexts.withPropertyValues("app.patient-source.base-url=" + SOURCE.baseUrl())
                .run(context -> assertThat(context.getBean(PatientStore.class).findById(TAN))
                        .isPresent());
    }

    // ---- a failure of the source ---------------------------------------------------------

    /** Only a {@code 404} is no patient: any other answer is a fault, not an absent patient. */
    @ParameterizedTest
    @ValueSource(ints = {201, 204, 400, 401, 403, 410, 500, 503})
    void anAnswerOtherThanAPatientOrA404IsAFault(int status) {
        SOURCE.stubFor(get(urlPathEqualTo("/Patient/" + PROBE))
                .willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> patients().findById(PROBE))
                .isInstanceOf(PatientSourceException.class)
                .hasMessage("The patient source answered " + status);
    }

    /**
     * A redirect is never followed, even to a Patient on the source itself: the read is the
     * {@code 302} it got, a fault, and the place it points at is never asked.
     */
    @Test
    void aRedirectIsAFaultAndIsNotFollowed() {
        SOURCE.stubFor(get(urlPathEqualTo("/Patient/" + PROBE)).willReturn(aResponse()
                .withStatus(302).withHeader("Location", SOURCE.baseUrl() + "/elsewhere")));
        SOURCE.stubFor(get(urlPathEqualTo("/elsewhere"))
                .willReturn(okJson(PROBE_BODY.formatted("1963-07-29"))));

        assertThatThrownBy(() -> patients().findById(PROBE))
                .isInstanceOf(PatientSourceException.class)
                .hasMessage("The patient source answered 302");
    }

    @Test
    void aSourceThatDoesNotAnswerIsAFault() {
        assertThatThrownBy(() -> at(closedPort()).findById(TAN))
                .isInstanceOf(PatientSourceException.class)
                .hasMessage("The patient source could not be read");
    }

    @Test
    void aSourceSlowerThanTheReadTimeoutIsAFault() {
        SOURCE.stubFor(get(urlPathEqualTo("/Patient/" + PROBE))
                .willReturn(okJson(PROBE_BODY.formatted("1963-07-29")).withFixedDelay(2_000)));
        PatientSourceAdapter impatient = new PatientSourceAdapter(RestClient.builder(),
                SOURCE.baseUrl(), Duration.ofSeconds(2), Duration.ofMillis(200));

        assertThatThrownBy(() -> impatient.findById(PROBE))
                .isInstanceOf(PatientSourceException.class)
                .hasMessage("The patient source could not be read");
    }

    /** A Patient body under the probe's id, its elements given as JSON members. */
    private static String probe(String members) {
        return "{\"id\": \"probe-Q7xKd2.v9\", " + members + "}";
    }

    private static final String NRIC_IDENTIFIER = """
            "identifier": [{"system": "https://fhir.synapxe.sg/NamingSystem/nric",
                            "value": "T7391046Q"}]""";

    private static final String OFFICIAL_NAME =
            "\"name\": [{\"use\": \"official\", \"text\": \"Quah Seow Peng\"}]";

    private static final String GENDER = "\"gender\": \"female\"";

    private static final String BIRTH_DATE = "\"birthDate\": \"1963-07-29\"";

    private static String joined(String... members) {
        return String.join(", ", members);
    }

    /**
     * A {@code 200} whose body is not a Patient the service can hold, each with what its fault
     * says: another patient's, one with no NRIC (none at all, one under another system, or the
     * NRIC's with no value), no official name (none at all, or another use only), no gender or one
     * outside the four codes, no birth date — or, with nothing the adapter names, an official name
     * with no text, a birth date not to the day, no Patient at all, and no JSON at all.
     */
    static Stream<Arguments> bodiesTheServiceCannotHold() {
        String unreadable = "The patient source could not be read";
        return Stream.of(
                arguments("{\"id\": \"another-patient\", " + joined(NRIC_IDENTIFIER,
                        OFFICIAL_NAME, GENDER, BIRTH_DATE) + "}",
                        "The patient source answered with another resource"),
                arguments(probe(joined(OFFICIAL_NAME, GENDER, BIRTH_DATE)),
                        "The patient source's Patient has no NRIC"),
                arguments(probe(joined(
                        "\"identifier\": [{\"system\": \"urn:oid:1.2.3\", \"value\": \"T7391046Q\"}]",
                        OFFICIAL_NAME, GENDER, BIRTH_DATE)),
                        "The patient source's Patient has no NRIC"),
                arguments(probe(joined(
                        "\"identifier\": [{\"system\": \"https://fhir.synapxe.sg/NamingSystem/nric\"}]",
                        OFFICIAL_NAME, GENDER, BIRTH_DATE)),
                        "The patient source's Patient has no NRIC"),
                arguments(probe(joined(NRIC_IDENTIFIER, GENDER, BIRTH_DATE)),
                        "The patient source's Patient has no official name"),
                arguments(probe(joined(NRIC_IDENTIFIER,
                        "\"name\": [{\"use\": \"usual\", \"text\": \"Quah Seow Peng\"}]",
                        GENDER, BIRTH_DATE)),
                        "The patient source's Patient has no official name"),
                arguments(probe(joined(NRIC_IDENTIFIER, OFFICIAL_NAME, BIRTH_DATE)),
                        "The patient source's Patient has no gender"),
                arguments(probe(joined(NRIC_IDENTIFIER, OFFICIAL_NAME, "\"gender\": \"F\"",
                        BIRTH_DATE)),
                        "The patient source's Patient has no gender"),
                arguments(probe(joined(NRIC_IDENTIFIER, OFFICIAL_NAME, GENDER)),
                        "The patient source's Patient has no birth date"),
                arguments(probe(joined(NRIC_IDENTIFIER,
                        "\"name\": [{\"use\": \"official\", \"family\": \"Quah\"}]",
                        GENDER, BIRTH_DATE)), unreadable),
                arguments(probe(joined(NRIC_IDENTIFIER, OFFICIAL_NAME, GENDER,
                        "\"birthDate\": \"1963-07\"")), unreadable),
                arguments("{\"resourceType\": \"OperationOutcome\"}",
                        "The patient source answered with another resource"),
                arguments("not JSON: T7391046Q", unreadable));
    }

    @ParameterizedTest
    @MethodSource("bodiesTheServiceCannotHold")
    void aBodyThatIsNotAPatientTheServiceCanHoldIsAFault(String body, String says) {
        answering(PROBE, body);

        assertThatThrownBy(() -> patients().findById(PROBE))
                .isInstanceOf(PatientSourceException.class)
                .hasMessage(says);
    }

    /** The body above with every element a Patient needs is one: the cases differ by one each. */
    @Test
    void aBodyWithEveryElementAPatientNeedsIsAPatient() {
        answering(PROBE, probe(joined(NRIC_IDENTIFIER, OFFICIAL_NAME, GENDER, BIRTH_DATE)));

        assertThat(patients().findById(PROBE)).contains(new Patient(PROBE, "T7391046Q",
                new PatientName("Quah Seow Peng", null, List.of()), AdministrativeGender.FEMALE,
                LocalDate.of(1963, 7, 29)));
    }

    /**
     * Nothing of a failed read reaches its exception, whose messages a fault record would print:
     * not the id, which a client's message quotes in the URL, and nothing of the body, which a
     * parser's message quotes — here a date not to the day, and a body that is not JSON.
     */
    @ParameterizedTest
    @ValueSource(strings = {"12/03/1985 T7391046Q Quah Seow Peng",
        "\"} T7391046Q Quah Seow Peng"})
    void aFaultsExceptionSaysNothingOfThePatientOrTheBody(String birthDate) {
        answering(PROBE, PROBE_BODY.formatted(birthDate));

        Throwable fault = catchThrowable(() -> patients().findById(PROBE));

        assertThat(printed(fault)).contains(PatientSourceException.class.getName())
                .doesNotContain("T7391046Q", "Quah", "12/03/1985", PROBE.value());
    }

    @Test
    void aSourceThatDoesNotAnswerSaysNothingOfTheIdOrWhereItWent() {
        String base = closedPort();

        Throwable fault = catchThrowable(() -> at(base).findById(PROBE));

        assertThat(printed(fault)).contains(PatientSourceException.class.getName())
                .doesNotContain(PROBE.value(), base);
    }

    @Test
    void anErrorAnswersBodyIsNeverRead() {
        SOURCE.stubFor(get(urlPathEqualTo("/Patient/" + PROBE))
                .willReturn(aResponse().withStatus(500).withBody("T7391046Q Quah Seow Peng")));

        Throwable fault = catchThrowable(() -> patients().findById(PROBE));

        assertThat(printed(fault)).doesNotContain("T7391046Q", "Quah", PROBE.value());
    }

    /**
     * The fault keeps what failed, for the record, as its type alone: the client's own exception,
     * whose message names the URL, redacted to its class name.
     */
    @Test
    void aFaultKeepsTheFailuresTypeAsItsRedactedCause() {
        Throwable fault = catchThrowable(() -> at(closedPort()).findById(PROBE));

        assertThat(fault.getCause()).isInstanceOf(RedactedFaultException.class)
                .hasMessage(ResourceAccessException.class.getName());
    }

    /** The client connects within the connect timeout and follows no redirect. */
    @Test
    void theClientConnectsWithinTheConnectTimeoutAndFollowsNoRedirect() {
        HttpClient client = PatientSourceAdapter.httpClient(Duration.ofMillis(1_500));

        assertThat(List.of(client.connectTimeout(), client.followRedirects()))
                .containsExactly(Optional.of(Duration.ofMillis(1_500)), HttpClient.Redirect.NEVER);
    }

    /** A base URL nothing listens on: a mock's, once it has stopped. */
    private static String closedPort() {
        WireMockServer stopped = new WireMockServer(PatientSourceStubs.options());
        stopped.start();
        String base = stopped.baseUrl();
        stopped.stop();
        return base;
    }
}
