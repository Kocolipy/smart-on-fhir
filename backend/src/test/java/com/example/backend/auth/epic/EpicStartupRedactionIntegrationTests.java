package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.backend.BackendApplication;
import com.example.backend.ContainerTestConfiguration;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * D22, end to end: a startup refused for a malformed key names the variable and never prints
 * the value — not in the log, not in the failure report Spring Boot writes to the console, and not
 * anywhere in the exception that ended startup.
 *
 * <p>The real application, started as a deployment starts it, so every path a value could take to
 * the output is the shipped one: the ECS console encoder, Boot's failure analyzers and its
 * "Application run failed" report with the stack trace. The key is a distinctive sentinel inside
 * PEM armour, so its absence is unambiguous.
 */
@ExtendWith(OutputCaptureExtension.class)
class EpicStartupRedactionIntegrationTests {

    private static final String SENTINEL = "D22-MALFORMED-PEM-SENTINEL-7f3a9c";

    @Test
    void aMalformedKeyFailsStartupWithoutItsValueReachingAnyOutput(CapturedOutput output) {
        String malformed = "-----BEGIN PRIVATE KEY-----\n" + SENTINEL + "\n-----END PRIVATE KEY-----";

        Throwable failure = catchThrowable(() -> start(
                "--app.epic.enabled=true",
                "--app.epic.fhir-base=https://fhir.example.org/api/FHIR/R4",
                "--app.epic.oauth-issuer=https://fhir.example.org/oauth2",
                "--app.epic.client-id=epic-client-id",
                "--app.epic.redirect-uri=https://app.example.org/api/auth/epic/callback",
                "--app.epic.client-key=" + malformed,
                "--app.epic.client-key-id=active-kid"));

        assertThat(failure).as("startup failed").isNotNull();
        assertThat(output.getAll())
                .as("the console and the log name the variable")
                .contains("APP_EPIC_CLIENT_KEY")
                .as("and never its value")
                .doesNotContain(SENTINEL);
        assertThat(stackTraceOf(failure)).doesNotContain(SENTINEL);
    }

    private static void start(String... arguments) {
        ConfigurableApplicationContext application = new SpringApplicationBuilder(
                        BackendApplication.class, ContainerTestConfiguration.class)
                .run(append(arguments, "--server.port=0"));
        application.close();
    }

    private static String[] append(String[] arguments, String last) {
        String[] all = java.util.Arrays.copyOf(arguments, arguments.length + 1);
        all[arguments.length] = last;
        return all;
    }

    /** Every message, cause and suppressed exception, as a stack trace prints them. */
    private static String stackTraceOf(Throwable failure) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        return trace.toString();
    }
}
