package com.example.backend;

import static com.example.backend.SessionCsrf.withCsrf;
import static com.example.backend.TestJson.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The SPA's password login, for a MockMvc test: {@code POST /api/auth/login} with the
 * credentials as JSON and the CSRF token of the request's own session ({@link SessionCsrf}).
 *
 * <p>A test signs in with {@link #logIn}, which holds the login to {@code 200} and hands back the
 * session cookie the deployed service issues ({@link SessionCookie#NAME}), or with
 * {@link #logInSession} when its context runs no session repository filter and the session is a
 * container one. A test of a refused or audited login sends the attempt itself with
 * {@link #attemptLogIn} and asserts what it expects.
 *
 * <p>The RANDOM_PORT counterpart is {@link ServerSession}.
 */
public final class SessionLogin {

    /** The endpoint the SPA posts its credentials to. */
    public static final String PATH = "/api/auth/login";

    private SessionLogin() {
    }

    /** The login request for these credentials, carrying its session's CSRF token. */
    public static MockHttpServletRequestBuilder loginRequest(
            MockMvc mvc, String userName, String password) {
        return withCsrf(mvc, post(PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(userName, password));
    }

    /** The login body the SPA sends: {@code {"username":..., "password":...}}. */
    public static String credentials(String userName, String password) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", userName);
        body.put("password", password);
        return JSON.writeValueAsString(body);
    }

    /** Sends the login, whatever it is answered, for the test to assert on. */
    public static ResultActions attemptLogIn(MockMvc mvc, String userName, String password)
            throws Exception {
        return mvc.perform(loginRequest(mvc, userName, password));
    }

    /** Signs in as {@code userName} and returns the session cookie the login issued. */
    public static Cookie logIn(MockMvc mvc, String userName, String password) throws Exception {
        return logIn(mvc, userName, password, null);
    }

    /**
     * Signs in as {@code userName} from the browser {@code jar} describes — its cookie is sent
     * with the login when there is one — and returns the session cookie the login issued.
     */
    public static Cookie logIn(MockMvc mvc, String userName, String password, Cookie jar)
            throws Exception {
        MockHttpServletRequestBuilder request = loginRequest(mvc, userName, password);
        if (jar != null) {
            request.cookie(jar);
        }
        return sessionCookie(succeeded(mvc.perform(request).andReturn(), userName));
    }

    /** Signs in as {@code userName} and returns the container session the login established. */
    public static MockHttpSession logInSession(MockMvc mvc, String userName, String password)
            throws Exception {
        return logInSession(mvc, userName, password, null);
    }

    /**
     * Signs in as {@code userName} in {@code session} when one is given — a session a test made
     * itself, created at its own clock's instant — and returns the session the login established.
     */
    public static MockHttpSession logInSession(MockMvc mvc, String userName, String password,
            MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request = loginRequest(mvc, userName, password);
        if (session != null) {
            request.session(session);
        }
        MvcResult login = succeeded(mvc.perform(request).andReturn(), userName);
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    /** {@code login}, held to the {@code 200} a successful login is answered with. */
    public static MvcResult succeeded(MvcResult login, String userName) {
        assertThat(login.getResponse().getStatus()).as("login as %s", userName).isEqualTo(200);
        return login;
    }

    /** The session cookie {@code result} issued, as a browser would send it back. */
    public static Cookie sessionCookie(MvcResult result) {
        Cookie issued = result.getResponse().getCookie(SessionCookie.NAME);
        assertThat(issued).as("a session cookie was issued").isNotNull();
        return new Cookie(issued.getName(), issued.getValue());
    }
}
