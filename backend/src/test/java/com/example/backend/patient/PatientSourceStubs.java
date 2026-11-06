package com.example.backend.patient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The in-house patient source's mock, in process: WireMock serving the very stub files the local
 * mock container serves ({@code patient-source-mock/mappings}, mounted by {@code compose.yaml}),
 * so the patient tests and the mock a local run and E2E read cannot drift apart
 * (/docs/domain/patient.md, "The patient source's fixtures").
 *
 * <p>A test class owning its server registers {@link #extension()}; the full-context tests
 * share {@link #shared()}, one per test JVM, which {@code ContainerTestConfiguration} points every
 * context's {@code app.patient-source.base-url} at.
 */
public final class PatientSourceStubs {

    /** The mock's root, with its {@code mappings}: the tests run from {@code backend/}. */
    public static final Path ROOT = Path.of("patient-source-mock");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static WireMockServer shared;

    private PatientSourceStubs() {
    }

    /** WireMock on a free loopback port, serving the stub files. */
    public static WireMockConfiguration options() {
        return WireMockConfiguration.options()
                .dynamicPort()
                .bindAddress("127.0.0.1")
                .usingFilesUnderDirectory(ROOT.toString());
    }

    /** A server of a test class's own, for its {@code @RegisterExtension}: the stub files. */
    public static WireMockExtension extension() {
        return WireMockExtension.newInstance().options(options()).build();
    }

    /** The one server every full-context test in this JVM reads through, started on first use. */
    public static synchronized WireMockServer shared() {
        if (shared == null) {
            WireMockServer server = new WireMockServer(options());
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            shared = server;
        }
        return shared;
    }

    /**
     * Every fixture the stub files serve: the R4 {@code Patient} body of each {@code 200} stub
     * that is no fault stub.
     */
    public static List<JsonNode> fixtures() {
        return stubs()
                .filter(stub -> stub.at(FAULT).isMissingNode())
                .filter(stub -> stub.at("/response/status").asInt() == 200)
                .map(stub -> stub.at("/response/jsonBody"))
                .toList();
    }

    /**
     * Every fault stub: a stub whose {@code metadata.fault} names the failure it serves — a delay
     * past the read timeout, a {@code 5xx}, or a Patient breaking one domain rule — under a
     * well-formed id no fixture uses.
     */
    public static List<FaultStub> faults() {
        return stubs()
                .filter(stub -> !stub.at(FAULT).isMissingNode())
                .map(stub -> new FaultStub(stub.at(FAULT).asString(),
                        stub.at("/request/urlPath").asString().substring("/Patient/".length()),
                        stub.at("/response")))
                .toList();
    }

    /**
     * One fault stub.
     *
     * @param fault    the failure it serves, its {@code metadata.fault}
     * @param id       the patient id it answers under
     * @param response the answer it gives, body and all
     */
    public record FaultStub(String fault, String id, JsonNode response) {

        /**
         * What a record of its failure must not quote: every identifier's value, every name
         * part, the birth date, and an error body's diagnostics.
         */
        public List<String> patientValues() {
            JsonNode body = response.at("/jsonBody");
            List<String> values = new ArrayList<>();
            body.at("/identifier").forEach(identifier -> values.add(identifier.at("/value").asString()));
            body.at("/name").forEach(name -> {
                name.optional("text").ifPresent(text -> values.add(text.asString()));
                name.optional("family").ifPresent(family -> values.add(family.asString()));
                name.at("/given").forEach(given -> values.add(given.asString()));
            });
            body.optional("birthDate").ifPresent(birthDate -> values.add(birthDate.asString()));
            body.at("/issue").forEach(issue -> values.add(issue.at("/diagnostics").asString()));
            return List.copyOf(values);
        }
    }

    private static final String FAULT = "/metadata/fault";

    private static Stream<JsonNode> stubs() {
        try (Stream<Path> files = Files.list(ROOT.resolve("mappings"))) {
            return files.filter(file -> file.toString().endsWith(".json"))
                    .sorted()
                    .map(PatientSourceStubs::read)
                    .toList()
                    .stream();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static JsonNode read(Path stub) {
        try {
            return JSON.readTree(Files.readString(stub));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
