package arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Nothing the service ships switches the database role on a connection (ADR 0015, "Runtime
 * model"; #66).
 *
 * <p>Each pool connects as a login of its own, a member of exactly the one role its work needs,
 * so the server enforces each pool's privileges. A role switch would undo that silently: a
 * {@code SET ROLE} on a pooled connection outlives the request that issued it, and a
 * {@code SET LOCAL ROLE} widens a transaction beyond its login. Neither is possible on SQL Server,
 * where this service is going. ArchUnit cannot read SQL, so this reads the sources: every Java
 * file and every configuration file under {@code src/main}, and every migration with its
 * {@code --} comments removed, since the frozen {@code V1} still describes the switch it used to
 * set up in prose.
 */
class NoRoleSwitchTest {

    private static final Pattern ROLE_SWITCH =
            Pattern.compile("(?i)\\bSET\\s+(LOCAL\\s+|SESSION\\s+)?ROLE\\b");

    private static final Path MAIN = Path.of("src/main");

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Test
    void noSourceOrConfigurationFileSwitchesTheDatabaseRole() {
        assertThat(filesUnder(MAIN).stream()
                        .filter(file -> !file.startsWith(MIGRATIONS))
                        .filter(file -> ROLE_SWITCH.matcher(read(file)).find())
                        .toList())
                .isEmpty();
    }

    @Test
    void noMigrationSwitchesTheDatabaseRole() {
        assertThat(filesUnder(MIGRATIONS).stream()
                        .filter(file -> ROLE_SWITCH.matcher(withoutComments(read(file))).find())
                        .toList())
                .isEmpty();
    }

    /** The guard reads what it claims to: a planted switch in either form is found. */
    @Test
    void theGuardFindsBothFormsOfTheSwitch() {
        assertThat(Stream.of("SET ROLE backend_app", "set local role backend_audit_retention",
                        "connection-init-sql: SET SESSION ROLE x")
                .filter(text -> ROLE_SWITCH.matcher(text).find())
                .count())
                .isEqualTo(3);
    }

    @Test
    void theGuardReadsTheMainSources() {
        assertThat(filesUnder(MAIN)).anyMatch(file -> file.endsWith("application.yaml"))
                .anyMatch(file -> file.toString().endsWith("AuditEventRetentionAdapter.java"))
                .anyMatch(file -> file.toString().endsWith("V3__separate_database_logins.sql"));
    }

    private static List<Path> filesUnder(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().matches(".*\\.(java|ya?ml|properties|sql)$"))
                    .toList();
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

    private static String withoutComments(String sql) {
        return sql.replaceAll("--[^\\n]*", "");
    }
}
