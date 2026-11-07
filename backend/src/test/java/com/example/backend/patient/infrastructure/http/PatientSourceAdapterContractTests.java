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

import com.example.backend.observability.LogEvent.ErrorCategory;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

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

    /**
     * A fault as its record reports it: its fixed message, the failure it names and the
     * {@code error.category} it is reported under.
     */
    private record Fault(String message, String reason, ErrorCategory category) {

        static Fault of(Throwable thrown) {
            PatientSourceException fault = (PatientSourceException) thrown;
            return new Fault(fault.getMessage(), fault.reason(), fault.category());
        }
    }

    private static Fault faultReading(PatientStore patients, PatientId id) {
        return Fault.of(catchThrowable(() -> patients.findById(id)));
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

    /**
     * Only a {@code 404} is no patient: any other answer is a fault, not an absent patient — a
     * {@code 5xx} the source's own failure, reported as {@code server}, and any other status an
     * answer the service cannot use, reported as {@code data}.
     */
    @ParameterizedTest
    @CsvSource({
        "201, unexpected-status, DATA",
        "204, unexpected-status, DATA",
        "400, unexpected-status, DATA",
        "401, unexpected-status, DATA",
        "403, unexpected-status, DATA",
        "410, unexpected-status, DATA",
        "500, server-error, SERVER",
        "502, server-error, SERVER",
        "503, server-error, SERVER",
        "599, server-error, SERVER"})
    void anAnswerOtherThanAPatientOrA404IsAFault(int status, String reason,
            ErrorCategory category) {
        SOURCE.stubFor(get(urlPathEqualTo("/Patient/" + PROBE))
                .willReturn(aResponse().withStatus(status)));

        assertThat(faultReading(patients(), PROBE))
                .isEqualTo(new Fault("The patient source answered " + status, reason, category));
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

        assertThat(faultReading(patients(), PROBE)).isEqualTo(new Fault(
                "The patient source answered 302", "unexpected-status", ErrorCategory.DATA));
    }

    @Test
    void aSourceNothingListensOnIsUnreachable() {
        assertThat(faultReading(at(closedPort()), TAN)).isEqualTo(new Fault(
                "The patient source could not be reached", "unreachable", ErrorCategory.NETWORK));
    }

    /**
     * The fault stub that answers only after the read timeout: the read gives up and times out.
     * The stub waits past the production timeout; this adapter is given a shorter one, so the
     * case need not wait the five seconds.
     */
    @Test
    void theSlowFaultStubTimesOut() {
        PatientSourceAdapter impatient = new PatientSourceAdapter(RestClient.builder(),
                SOURCE.baseUrl(), Duration.ofSeconds(2), Duration.ofMillis(300));

        assertThat(faultReading(impatient, new PatientId("fault-slow"))).isEqualTo(new Fault(
                "The patient source did not answer in time", "timed-out", ErrorCategory.NETWORK));
    }

    /**
     * The read timeout is what gives up: a Patient the source answers in a second, read with a
     * tenth of one, is not waited for. Without the timeout the read would wait the second out and
     * get the Patient, so the case fails in that second rather than hanging on the slow stub.
     */
    @Test
    void anAnswerSlowerThanTheReadTimeoutIsNotWaitedFor() {
        SOURCE.stubFor(get(urlPathEqualTo("/Patient/" + PROBE)).willReturn(
                okJson(PROBE_BODY.formatted("1963-07-29")).withFixedDelay(1000)));
        PatientSourceAdapter impatient = new PatientSourceAdapter(RestClient.builder(),
                SOURCE.baseUrl(), Duration.ofSeconds(2), Duration.ofMillis(100));

        assertThat(faultReading(impatient, PROBE)).isEqualTo(new Fault(
                "The patient source did not answer in time", "timed-out", ErrorCategory.NETWORK));
    }

    /** The slow fault stub waits past the production read timeout, not merely the test's. */
    @Test
    void theSlowFaultStubWaitsPastTheReadTimeout() {
        PatientSourceStubs.FaultStub slow = PatientSourceStubs.faults().stream()
                .filter(fault -> fault.id().equals("fault-slow")).findFirst().orElseThrow();

        assertThat(Duration.ofMillis(slow.response().at("/fixedDelayMilliseconds").asLong()))
                .isGreaterThan(PatientSourceAdapter.READ_TIMEOUT);
    }

    /**
     * The fault stub whose headers come at once and whose body is dribbled out past the read
     * timeout: the read gives up on the body and times out, as it does on headers that never
     * come, rather than taking the cut-off body for an unreadable one.
     */
    @Test
    void theDribbledBodyFaultStubTimesOut() {
        PatientSourceAdapter impatient = new PatientSourceAdapter(RestClient.builder(),
                SOURCE.baseUrl(), Duration.ofSeconds(2), Duration.ofMillis(300));

        assertThat(faultReading(impatient, new PatientId("fault-slow-body"))).isEqualTo(new Fault(
                "The patient source did not answer in time", "timed-out", ErrorCategory.NETWORK));
    }

    /**
     * The dribbled stub spreads its body past the production read timeout, but sends its headers
     * with its first chunk, within a tenth of a second and with no delay before it: it is the
     * body, not the headers, that a read waits on, the impatient one above included.
     */
    @Test
    void theDribbledBodyFaultStubDribblesPastTheReadTimeout() {
        JsonNode response = PatientSourceStubs.faults().stream()
                .filter(fault -> fault.id().equals("fault-slow-body")).findFirst().orElseThrow()
                .response();
        Duration total = Duration.ofMillis(response.at("/chunkedDribbleDelay/totalDuration")
                .asLong());
        Duration eachChunk = total.dividedBy(response.at("/chunkedDribbleDelay/numberOfChunks")
                .asLong());

        assertThat(List.of(response.at("/fixedDelayMilliseconds").isMissingNode(),
                total.compareTo(PatientSourceAdapter.READ_TIMEOUT) > 0,
                eachChunk.compareTo(Duration.ofMillis(100)) <= 0))
                .containsExactly(true, true, true);
    }

    /**
     * A source whose connection is reset once the headers and part of the body are in gave no
     * answer: unreachable, as a source nothing listens on is, not an unreadable body.
     */
    @Test
    void aConnectionResetMidBodyIsUnreachable() throws IOException {
        // Test-only: a plain-HTTP source on loopback, as the patient source's mock is; a raw
        // socket because only one can reset a connection mid-body. Nothing it carries leaves the
        // host, and the adapter under test speaks plain HTTP to its mock, so TLS has nothing to
        // protect here.
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        try (ServerSocket source = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> resettingMidBody(source));

            assertThat(faultReading(at("http://127.0.0.1:" + source.getLocalPort()), PROBE))
                    .isEqualTo(new Fault("The patient source could not be reached",
                            "unreachable", ErrorCategory.NETWORK));
        }
    }

    /**
     * A source that never takes the connection gives up at the connect timeout, long before the
     * read timeout: timed out, not unreachable. The source's accept queue is full — it listens
     * but takes nothing, and two connections already wait in its queue of one — so the
     * adapter's connection is never completed.
     */
    @Test
    void aConnectionNotTakenWithinTheConnectTimeoutTimesOut() throws IOException {
        // Test-only: a plain-TCP listener on loopback that never accepts; nothing is sent over
        // it, so TLS has nothing to protect.
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        try (ServerSocket source = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                Socket first = queued(source);
                Socket second = queued(source)) {
            PatientSourceAdapter adapter = new PatientSourceAdapter(RestClient.builder(),
                    "http://127.0.0.1:" + source.getLocalPort(), Duration.ofMillis(200),
                    Duration.ofSeconds(30));

            assertThat(faultReading(adapter, PROBE)).isEqualTo(new Fault(
                    "The patient source did not answer in time", "timed-out",
                    ErrorCategory.NETWORK));
        }
    }

    /** A connection left waiting in {@code source}'s accept queue. */
    private static Socket queued(ServerSocket source) throws IOException {
        // Test-only: a loopback client socket that sends nothing, only fills the accept queue,
        // so TLS has nothing to protect.
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
        return new Socket(InetAddress.getLoopbackAddress(), source.getLocalPort());
    }

    /**
     * Answers one request with a {@code 200}'s headers and the start of its body, then resets
     * the connection: a close with no linger sends a TCP reset, not an orderly end.
     */
    private static void resettingMidBody(ServerSocket source) {
        try (Socket call = source.accept()) {
            InputStream request = call.getInputStream();
            String endOfHeaders = "\r\n\r\n";
            int matched = 0;
            while (matched < endOfHeaders.length()) {
                int next = request.read();
                if (next < 0) {
                    return;
                }
                matched = next == endOfHeaders.charAt(matched) ? matched + 1
                        : next == '\r' ? 1 : 0;
            }
            OutputStream answer = call.getOutputStream();
            answer.write(("HTTP/1.1 200 OK\r\nContent-Type: application/fhir+json\r\n"
                    + "Content-Length: 400\r\n\r\n{\"resourceType\": \"Patient\", ")
                    .getBytes(StandardCharsets.US_ASCII));
            answer.flush();
            Thread.sleep(100);
            call.setSoLinger(true, 0);
        } catch (IOException | InterruptedException ended) {
            // The case reads the failure on the client's side; the server's own is no part of it.
        }
    }

    /**
     * Each fault stub that answers at once, as the fault it is: its message, the failure or
     * the broken rule it names, and its category — a {@code 5xx} the source's failure, a Patient
     * breaking a domain rule an answer the service cannot use. The official name with no text is
     * a fault although it has a family and given names: the text is never rebuilt from them.
     */
    static Stream<Arguments> faultStubs() {
        return Stream.of(
                arguments("fault-server-error", new Fault(
                        "The patient source answered 503", "server-error", ErrorCategory.SERVER)),
                arguments("fault-no-nric", new Fault(
                        "The patient source's Patient has no NRIC", "no-nric", ErrorCategory.DATA)),
                arguments("fault-no-official-name", new Fault(
                        "The patient source's Patient has no official name", "no-official-name",
                        ErrorCategory.DATA)),
                arguments("fault-no-name-text", new Fault(
                        "The patient source's Patient's official name has no text",
                        "no-name-text", ErrorCategory.DATA)),
                arguments("fault-no-gender", new Fault(
                        "The patient source's Patient has no gender", "no-gender",
                        ErrorCategory.DATA)),
                arguments("fault-no-birth-date", new Fault(
                        "The patient source's Patient has no birth date", "no-birth-date",
                        ErrorCategory.DATA)));
    }

    @ParameterizedTest
    @MethodSource("faultStubs")
    void eachFaultStubIsTheFaultItServes(String id, Fault expected) {
        assertThat(faultReading(patients(), new PatientId(id))).isEqualTo(expected);
    }

    /** The cases above and the two slow stubs' are every fault stub the stub files hold. */
    @Test
    void everyFaultStubIsReadAbove() {
        Stream<String> covered = Stream.concat(Stream.of("fault-slow", "fault-slow-body"),
                faultStubs().map(arguments -> (String) arguments.get()[0]));

        assertThat(PatientSourceStubs.faults()).extracting(PatientSourceStubs.FaultStub::id)
                .containsExactlyInAnyOrderElementsOf(covered.toList());
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
     * A {@code 200} whose body is not a Patient the service can hold, each as its fault reports
     * it: another patient's, one with no NRIC (none at all, one under another system, or the
     * NRIC's with no value), no official name (none at all, or another use only), an official name
     * with no text (a blank one, or none beside a family name), no gender or one outside the four
     * codes, no birth date or one not to the day — each a broken rule, reported as {@code data} —
     * no Patient at all, and no JSON at all.
     */
    static Stream<Arguments> bodiesTheServiceCannotHold() {
        Fault anotherResource = rule("The patient source answered with another resource",
                "another-resource");
        Fault noNric = rule("The patient source's Patient has no NRIC", "no-nric");
        Fault noOfficialName = rule("The patient source's Patient has no official name",
                "no-official-name");
        Fault noNameText = rule("The patient source's Patient's official name has no text",
                "no-name-text");
        Fault noGender = rule("The patient source's Patient has no gender", "no-gender");
        return Stream.of(
                arguments("{\"id\": \"another-patient\", " + joined(NRIC_IDENTIFIER,
                        OFFICIAL_NAME, GENDER, BIRTH_DATE) + "}", anotherResource),
                arguments(probe(joined(OFFICIAL_NAME, GENDER, BIRTH_DATE)), noNric),
                arguments(probe(joined(
                        "\"identifier\": [{\"system\": \"urn:oid:1.2.3\", \"value\": \"T7391046Q\"}]",
                        OFFICIAL_NAME, GENDER, BIRTH_DATE)), noNric),
                arguments(probe(joined(
                        "\"identifier\": [{\"system\": \"https://fhir.synapxe.sg/NamingSystem/nric\"}]",
                        OFFICIAL_NAME, GENDER, BIRTH_DATE)), noNric),
                arguments(probe(joined(NRIC_IDENTIFIER, GENDER, BIRTH_DATE)), noOfficialName),
                arguments(probe(joined(NRIC_IDENTIFIER,
                        "\"name\": [{\"use\": \"usual\", \"text\": \"Quah Seow Peng\"}]",
                        GENDER, BIRTH_DATE)), noOfficialName),
                arguments(probe(joined(NRIC_IDENTIFIER,
                        "\"name\": [{\"use\": \"official\", \"family\": \"Quah\"}]",
                        GENDER, BIRTH_DATE)), noNameText),
                arguments(probe(joined(NRIC_IDENTIFIER,
                        "\"name\": [{\"use\": \"official\", \"text\": \" \", \"family\": \"Quah\"}]",
                        GENDER, BIRTH_DATE)), noNameText),
                arguments(probe(joined(NRIC_IDENTIFIER, OFFICIAL_NAME, BIRTH_DATE)), noGender),
                arguments(probe(joined(NRIC_IDENTIFIER, OFFICIAL_NAME, "\"gender\": \"F\"",
                        BIRTH_DATE)), noGender),
                arguments(probe(joined(NRIC_IDENTIFIER, OFFICIAL_NAME, GENDER)),
                        rule("The patient source's Patient has no birth date", "no-birth-date")),
                arguments(probe(joined(NRIC_IDENTIFIER, OFFICIAL_NAME, GENDER,
                        "\"birthDate\": \"1963-07\"")),
                        rule("The patient source's Patient's birth date is not to the day",
                                "birth-date-not-to-the-day")),
                arguments("{\"resourceType\": \"OperationOutcome\"}", anotherResource),
                arguments("not JSON: T7391046Q", rule("The patient source could not be read",
                        "unreadable")));
    }

    private static Fault rule(String message, String reason) {
        return new Fault(message, reason, ErrorCategory.DATA);
    }

    @ParameterizedTest
    @MethodSource("bodiesTheServiceCannotHold")
    void aBodyThatIsNotAPatientTheServiceCanHoldIsAFault(String body, Fault expected) {
        answering(PROBE, body);

        assertThat(faultReading(patients(), PROBE)).isEqualTo(expected);
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
     * whose message names the URL or quotes the body, redacted to its class name — whether the
     * source was unreachable, timed out, or answered something that is not JSON.
     */
    @Test
    void anUnreachableFaultKeepsTheFailuresTypeAsItsRedactedCause() {
        Throwable fault = catchThrowable(() -> at(closedPort()).findById(PROBE));

        assertThat(fault.getCause()).isInstanceOf(RedactedFaultException.class)
                .hasMessage(ResourceAccessException.class.getName());
    }

    @Test
    void aTimedOutFaultKeepsTheFailuresTypeAsItsRedactedCause() {
        PatientSourceAdapter impatient = new PatientSourceAdapter(RestClient.builder(),
                SOURCE.baseUrl(), Duration.ofSeconds(2), Duration.ofMillis(300));

        Throwable fault = catchThrowable(() -> impatient.findById(new PatientId("fault-slow")));

        assertThat(fault.getCause()).isInstanceOf(RedactedFaultException.class)
                .hasMessage(ResourceAccessException.class.getName());
    }

    @Test
    void anUnreadableFaultKeepsTheFailuresTypeAsItsRedactedCause() {
        answering(PROBE, "not JSON: T7391046Q");

        Throwable fault = catchThrowable(() -> patients().findById(PROBE));

        assertThat(fault.getCause()).isInstanceOf(RedactedFaultException.class)
                .hasMessage(RestClientException.class.getName());
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
