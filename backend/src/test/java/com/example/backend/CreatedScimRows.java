package com.example.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.backend.scim.application.NewScimGroup;
import com.example.backend.scim.application.NewScimUser;
import com.example.backend.scim.application.ScimGroupService;
import com.example.backend.scim.application.ScimUserService;
import com.example.backend.scim.domain.AuthenticatedConnector;
import com.example.backend.scim.domain.ScimUserProfile;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The Users and Groups one test class created, and their removal afterwards — the
 * "remove only what you created" rule of {@code docs/TESTING_GUIDE.md} ("Full-context tests").
 *
 * <p>A class holds one instance as a field, registers each resource it creates, and calls
 * {@link #deleteAll} from its {@code @AfterEach}. The teardown deletes exactly the registered
 * ids, through {@link ScimTestRows#deleteUnreserved}, and nothing else: a database other classes
 * share, as the cached contexts and a single-JVM PIT run do, holds their rows too, and "every row
 * that is not reserved" would take theirs along.
 *
 * <p>It creates them too, registering each as it goes: through the application services
 * ({@link #createUser}, {@link #createGroup}), or as a connector over SCIM HTTP ({@link #post} for
 * a create that may be refused, {@link #create} for one that must succeed). A test that creates
 * some other way registers the answer with {@code track}, or the id with {@link #add}.
 *
 * <p>Registration is synchronized, so a test that creates from several threads at once records
 * every id.
 */
public final class CreatedScimRows {

    private final List<UUID> ids = new ArrayList<>();

    /** Registers {@code id} for removal and returns it. */
    public synchronized UUID add(UUID id) {
        ids.add(id);
        return id;
    }

    /**
     * Registers the resource a create answered with when the create succeeded ({@code 201}), and
     * returns the result either way, so a refused create can still be asserted on.
     */
    public MvcResult track(MvcResult result) throws Exception {
        if (result.getResponse().getStatus() == 201) {
            add(TestJson.id(result));
        }
        return result;
    }

    /**
     * Registers the resource a create over a running server answered with when it succeeded
     * ({@code 201}), and returns the response either way.
     */
    public HttpResponse<String> track(HttpResponse<String> response) {
        if (response.statusCode() == 201) {
            add(UUID.fromString(TestJson.JSON.readTree(response.body()).get("id").asText()));
        }
        return response;
    }

    /**
     * POSTs {@code body} as SCIM JSON to {@code collection} through {@code connector}, registers
     * the resource when the create succeeded, and returns the answer either way, so a refused
     * create can still be asserted on.
     */
    public MvcResult post(Connector connector, String collection, String body) throws Exception {
        return track(connector.perform(MockMvcRequestBuilders.post(collection)
                .contentType(ScimHttp.SCIM_JSON).content(body)));
    }

    /**
     * POSTs {@code body} as SCIM JSON to {@code collection} as the connector holding
     * {@code token}, registers the resource when the create succeeded, and returns the answer.
     */
    public MvcResult post(MockMvc mvc, String token, String collection, String body)
            throws Exception {
        return post(request -> mvc.perform(ScimHttp.asConnector(request, token)).andReturn(),
                collection, body);
    }

    /**
     * As {@link #post(MockMvc, String, String, String)}, for a create the test needs to succeed:
     * fails unless it is answered {@code 201}, and returns the created resource's id.
     */
    public UUID create(MockMvc mvc, String token, String collection, String body)
            throws Exception {
        MvcResult result = post(mvc, token, collection, body);
        assertThat(result.getResponse().getStatus())
                .as("create at %s answered %s", collection,
                        result.getResponse().getContentAsString())
                .isEqualTo(201);
        return TestJson.id(result);
    }

    /** How a test sends a SCIM request as its connector. */
    @FunctionalInterface
    public interface Connector {

        /** Performs {@code request} as the connector and returns the answer. */
        MvcResult perform(MockHttpServletRequestBuilder request) throws Exception;
    }

    /**
     * Creates an active User named {@code userName} through the application service, as
     * {@code connector}, with {@code password} (none when {@code null}) and no other attribute,
     * registers it and returns its id.
     */
    public UUID createUser(ScimUserService users, AuthenticatedConnector connector,
            String userName, String password) {
        ScimUserProfile profile =
                new ScimUserProfile(userName, null, null, null, null, null, true, List.of());
        return add(users.create(connector, new NewScimUser(profile, password, null)).id());
    }

    /**
     * Creates a Group named {@code displayName} with these direct {@code members} through the
     * application service, as {@code connector}, registers it and returns its id.
     */
    public UUID createGroup(ScimGroupService groups, AuthenticatedConnector connector,
            String displayName, List<UUID> members) {
        return add(groups.create(connector, new NewScimGroup(displayName, members, null)).id());
    }

    /** Removes every registered resource, with the memberships naming it, and forgets them. */
    public synchronized void deleteAll(JdbcTemplate jdbc) {
        for (UUID id : ids) {
            ScimTestRows.deleteUnreserved(jdbc, id);
        }
        ids.clear();
    }
}
