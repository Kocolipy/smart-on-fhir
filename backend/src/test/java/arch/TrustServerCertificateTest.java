package arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The service trusts the database's certificate blindly only locally and in the tests (ADR 0013,
 * dp-3; #68).
 *
 * <p>{@code trustServerCertificate=true} makes the driver encrypt without validating whom it is
 * talking to, so a connection with it is open to an interposed server. A local SQL Server and the
 * test container present a self-signed certificate and need it; a deployment validates RDS's
 * certificate against the driver-scoped trust store, and must never carry it. ArchUnit cannot read
 * configuration, so this reads the files:
 *
 * <ul>
 *   <li>every file under {@code src/main}, where it is allowed only inside a YAML document
 *       activated on the {@code local} or {@code test} profile and nothing else;
 *   <li>the build and image files ({@code pom.xml}, {@code Dockerfile}) and every script but the
 *       local ones, where it is never allowed.
 * </ul>
 *
 * <p>The local and test places are named, not scanned: {@code src/test}, {@code .env.example}
 * (the local {@code DATABASE_URL}), {@code compose.yaml} and the local init it runs. The deployed
 * stack's {@code DATABASE_URL} lives in {@code /infra}, outside this app, and is the
 * infrastructure's to keep clean.
 */
class TrustServerCertificateTest {

    private static final Pattern TRUST_ALL = Pattern.compile(
            "(?i)trustServerCertificate\\]?[\"']?\\s*[=:]\\s*[\"']?true");

    private static final Pattern ON_PROFILE =
            Pattern.compile("(?m)^\\s*on-profile:\\s*[\"']?([^\"'#\\n]+)");

    private static final Set<String> LOCAL_OR_TEST = Set.of("local", "test");

    private static final Path MAIN = Path.of("src/main");

    private static final List<Path> BUILD_FILES = List.of(Path.of("pom.xml"), Path.of("Dockerfile"));

    private static final Path SCRIPTS = Path.of("scripts");

    @Test
    void noMainSourceTrustsTheServerCertificateOutsideTheLocalAndTestProfiles() {
        assertThat(filesUnder(MAIN).stream()
                        .filter(file -> trustsOutsideLocalAndTest(read(file), isYaml(file)))
                        .toList())
                .isEmpty();
    }

    @Test
    void noBuildFileOrDeploymentScriptTrustsTheServerCertificate() {
        assertThat(Stream.concat(BUILD_FILES.stream(), filesUnder(SCRIPTS).stream()
                                .filter(file -> !file.getFileName().toString().startsWith("local-")))
                        .filter(Files::isRegularFile)
                        .filter(file -> TRUST_ALL.matcher(read(file)).find())
                        .toList())
                .isEmpty();
    }

    /** The local profile is where a local run gets it, so the guard's allowance is used. */
    @Test
    void theLocalProfileTrustsTheLocalServersCertificate() {
        String configuration = read(Path.of("src/main/resources/application.yaml"));
        assertThat(documents(configuration))
                .anyMatch(document -> TRUST_ALL.matcher(document).find()
                        && LOCAL_OR_TEST.containsAll(profiles(document)));
    }

    /** The guard reads what it claims to: a planted setting in a deployed document is found. */
    @Test
    void theGuardRefusesTheSettingInEveryDocumentButALocalOrTestOne() {
        assertThat(Stream.of(
                        "spring:\n  datasource:\n    url: jdbc:sqlserver://h;trustServerCertificate=true\n",
                        "spring:\n  config:\n    activate:\n      on-profile: migrate\n"
                                + "  datasource:\n    hikari:\n      data-source-properties:\n"
                                + "        \"[trustServerCertificate]\": true\n",
                        "spring:\n  config:\n    activate:\n      on-profile: local | migrate\n"
                                + "x: TrustServerCertificate = TRUE\n",
                        "spring:\n  config:\n    activate:\n      on-profile: local\n"
                                + "x: trustServerCertificate: true\n",
                        "spring:\n  config:\n    activate:\n      on-profile: local | test\n"
                                + "x: trustServerCertificate=true\n",
                        "x: trustServerCertificate=false\n")
                .map(yaml -> trustsOutsideLocalAndTest(yaml, true))
                .toList())
                .containsExactly(true, true, true, false, false, false);
    }

    @Test
    void theGuardReadsTheMainSourcesAndTheScripts() {
        assertThat(filesUnder(MAIN)).anyMatch(file -> file.endsWith("application.yaml"))
                .anyMatch(file -> file.toString().endsWith("DatabaseConnections.java"));
        assertThat(filesUnder(SCRIPTS)).anyMatch(file -> file.endsWith("verify.sh"));
    }

    private static boolean trustsOutsideLocalAndTest(String text, boolean yaml) {
        if (!yaml) {
            return TRUST_ALL.matcher(text).find();
        }
        return documents(text).stream()
                .filter(document -> TRUST_ALL.matcher(document).find())
                .anyMatch(document -> {
                    List<String> profiles = profiles(document);
                    return profiles.isEmpty() || !LOCAL_OR_TEST.containsAll(profiles);
                });
    }

    private static List<String> documents(String yaml) {
        return Arrays.asList(yaml.split("(?m)^---\\s*$"));
    }

    /** The profiles a document is activated on: none for a document every profile reads. */
    private static List<String> profiles(String document) {
        Matcher activation = ON_PROFILE.matcher(document);
        if (!activation.find()) {
            return List.of();
        }
        return Arrays.stream(activation.group(1).split("[|&,()]"))
                .map(String::strip)
                .filter(profile -> !profile.isEmpty())
                .toList();
    }

    private static boolean isYaml(Path file) {
        return file.toString().matches(".*\\.ya?ml$");
    }

    private static List<Path> filesUnder(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
