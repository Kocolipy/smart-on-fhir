package com.example.backend.scim;

import static com.example.backend.TestJson.JSON;
import static com.example.backend.TestJson.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.backend.ContainerTestConfiguration;
import com.example.backend.CreatedScimRows;
import com.example.backend.observability.RequestIdFilter;
import com.example.backend.scim.application.ConnectorAdministrationService;
import jakarta.servlet.Filter;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.WebApplicationContext;

/**
 * The directory's uniqueness rules, against the SQL Server schema (ADR 0013; #68).
 *
 * <p>Under the database collation {@code Latin1_General_100_BIN2_UTF8} equality is
 * case-sensitive, so a case-insensitive rule is enforced on the application-normalized column
 * ({@code normalized_user_name}, {@code normalized_display_name}), never on SQL Server's
 * {@code LOWER}; and SQL Server pads trailing spaces in {@code =} and in every unique key, so
 * {@code ada} and {@code ada } would collide there. The service refuses edge whitespace on every
 * unique or lookup key before it reaches the database, so the padding never decides an outcome.
 * Each rule is asserted where a connector meets it — the SCIM API — and the constraint names the
 * refusal is translated by are the ones the schema declares.
 */
@SpringBootTest
@Import(ContainerTestConfiguration.class)
class ScimSchemaUniquenessIntegrationTests {

    private static final String USERS = "/scim/v2/Users";

    private static final String GROUPS = "/scim/v2/Groups";

    private static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    private static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";

    private static final String INSERT_EMAIL = """
            INSERT INTO scim_user_emails (resource_id, ordinal, value, type, is_primary)
            VALUES (?, ?, ?, ?, ?)""";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ConnectorAdministrationService connectors;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private final CreatedScimRows created = new CreatedScimRows();

    private ScimConnectorClient connector;

    private String unique;

    @BeforeEach
    void setUp() {
        connector = ScimConnectorClient.create(context, connectors, requestIdFilter,
                springSecurityFilterChain, "Uniqueness");
        unique = UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void removeOnlyWhatThisTestCreated() {
        created.deleteAll(jdbc);
    }

    // ---- userName ------------------------------------------------------------------------

    @Test
    void a_user_name_differing_only_in_case_is_a_uniqueness_conflict() throws Exception {
        assertThat(createUser("Ada.King-" + unique).getResponse().getStatus()).isEqualTo(201);

        assertUniquenessConflict(createUser("ADA.KING-" + unique.toUpperCase()));
        assertThat(storedUsers("ada.king-" + unique)).isEqualTo(1);
    }

    /** A supplementary-plane name compares by its normalized form exactly as an ASCII one. */
    @Test
    void a_non_ascii_user_name_differing_only_in_case_is_a_uniqueness_conflict() throws Exception {
        assertThat(createUser("Ünïcødé-𐐷-" + unique).getResponse().getStatus()).isEqualTo(201);

        assertUniquenessConflict(createUser("ÜNÏCØDÉ-𐐷-" + unique));
    }

    @Test
    void a_user_name_with_a_trailing_space_is_refused_before_the_padded_key_compares_it()
            throws Exception {
        assertThat(createUser("trailing-" + unique).getResponse().getStatus()).isEqualTo(201);

        assertInvalidValue(createUser("trailing-" + unique + " "));
        assertThat(storedUsers("trailing-" + unique)).isEqualTo(1);
    }

    /**
     * The tombstone a deletion leaves is never consulted for uniqueness: the former name, in any
     * case, is free at once.
     */
    @Test
    void a_deleted_users_name_is_free_again_in_any_case() throws Exception {
        MvcResult first = createUser("Former-" + unique);
        connector.perform(delete(USERS + "/" + id(first)));

        MvcResult again = createUser("FORMER-" + unique.toUpperCase());

        assertThat(again.getResponse().getStatus())
                .as(again.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM scim_tombstones WHERE resource_id = ?", Integer.class,
                id(first))).isEqualTo(1);
    }

    // ---- Group displayName ---------------------------------------------------------------

    @Test
    void a_group_display_name_differing_only_in_case_is_a_uniqueness_conflict() throws Exception {
        assertThat(createGroup("Ward Nine " + unique).getResponse().getStatus()).isEqualTo(201);

        assertUniquenessConflict(createGroup("WARD NINE " + unique.toUpperCase()));
    }

    @Test
    void a_group_display_name_with_a_trailing_space_is_refused() throws Exception {
        assertThat(createGroup("Ward Ten " + unique).getResponse().getStatus()).isEqualTo(201);

        assertInvalidValue(createGroup("Ward Ten " + unique + " "));
    }

    // ---- emails --------------------------------------------------------------------------

    /**
     * Two addresses differing only in case are one email to the directory: the domain collapses
     * them before writing, so the first one sent is what is stored.
     */
    @Test
    void email_values_differing_only_in_case_are_stored_once() throws Exception {
        MvcResult user = createUser("mail-case-" + unique, """
                [{"value":"Ada@Example.org","type":"work"},{"value":"ada@example.org","type":"work"}]""");

        assertThat(user.getResponse().getStatus())
                .as(user.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(jdbc.queryForList(
                "SELECT value FROM scim_user_emails WHERE resource_id = ? ORDER BY ordinal",
                String.class, id(user))).containsExactly("Ada@Example.org");
    }

    /**
     * The database compares the stored value exactly, under the binary collation: a case variant
     * is a different key to the index, so the case rule is the domain's alone and no collation
     * second-guesses it.
     */
    @Test
    void the_type_value_index_compares_the_value_case_exactly() throws Exception {
        UUID user = id(createUser("mail-exact-" + unique, """
                [{"value":"Ada@Example.org","type":"work"}]"""));

        jdbc.update(INSERT_EMAIL, user, 1, "ada@example.org", "work", false);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM scim_user_emails WHERE resource_id = ?", Integer.class, user))
                .isEqualTo(2);
    }

    @Test
    void an_email_value_with_a_trailing_space_is_refused() throws Exception {
        assertInvalidValue(createUser("mail-space-" + unique, """
                [{"value":"ada@example.org ","type":"work"}]"""));
    }

    /**
     * The {@code (user, type, value)} rule, with an absent type counted as a value of its own:
     * two typeless copies of one address are refused by the database, under the index's name.
     */
    @Test
    void two_typeless_copies_of_one_address_violate_the_type_value_index() throws Exception {
        UUID user = id(createUser("mail-typeless-" + unique, """
                [{"value":"ada@example.org"}]"""));

        assertThatThrownBy(() -> jdbc.update(INSERT_EMAIL, user, 1, "ada@example.org", null, false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_scim_user_emails_type_value");
    }

    /** The same address under two types is two emails. */
    @Test
    void one_address_under_two_types_is_two_emails() throws Exception {
        UUID user = id(createUser("mail-types-" + unique, """
                [{"value":"ada@example.org","type":"work"}]"""));

        jdbc.update(INSERT_EMAIL, user, 1, "ada@example.org", "home", false);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM scim_user_emails WHERE resource_id = ?", Integer.class, user))
                .isEqualTo(2);
    }

    /** At most one primary email per User, by the filtered index; non-primaries are unbounded. */
    @Test
    void a_second_primary_email_violates_the_one_primary_index() throws Exception {
        UUID user = id(createUser("mail-primary-" + unique, """
                [{"value":"ada@example.org","primary":true},{"value":"ada@example.net"}]"""));

        jdbc.update(INSERT_EMAIL, user, 2, "ada@example.com", null, false);
        assertThatThrownBy(() -> jdbc.update(INSERT_EMAIL, user, 3, "ada@example.edu", null, true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_scim_user_emails_one_primary");
    }

    // ---- harness -------------------------------------------------------------------------

    private void assertUniquenessConflict(MvcResult result) throws Exception {
        String raw = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(raw).isEqualTo(409);
        assertThat(JSON.readTree(raw).get("scimType").asText()).isEqualTo("uniqueness");
    }

    private void assertInvalidValue(MvcResult result) throws Exception {
        String raw = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(raw).isEqualTo(400);
        assertThat(JSON.readTree(raw).get("scimType").asText()).isEqualTo("invalidValue");
    }

    private int storedUsers(String normalizedUserName) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM scim_users WHERE normalized_user_name = ?", Integer.class,
                normalizedUserName);
    }

    private MvcResult createUser(String userName) throws Exception {
        return created.post(connector::perform, USERS, """
                {"schemas":["%s"],"userName":"%s"}""".formatted(USER_SCHEMA, userName));
    }

    private MvcResult createUser(String userName, String emails) throws Exception {
        return created.post(connector::perform, USERS, """
                {"schemas":["%s"],"userName":"%s","emails":%s}"""
                        .formatted(USER_SCHEMA, userName, emails));
    }

    private MvcResult createGroup(String displayName) throws Exception {
        return created.post(connector::perform, GROUPS, """
                {"schemas":["%s"],"displayName":"%s"}""".formatted(GROUP_SCHEMA, displayName));
    }
}
