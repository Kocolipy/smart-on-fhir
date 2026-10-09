package com.example.backend.auth.epic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The clinician's browser in an Epic Login, played by a test: each redirect followed by hand, our
 * routes through MockMvc over the real filter chain and session store, and Epic's
 * {@code /authorize} over HTTP to a {@link FakeEpic}.
 *
 * <p>The steps are separate so a test can stop anywhere and forge what a browser would send next:
 * a launch URL with any {@code iss} and {@code launch}, or a callback with any query.
 */
final class EpicBrowser {

    /** The {@code launch} an ordinary launch carries. */
    static final String LAUNCH = "launch-context-from-hyperspace";

    private final MockMvc mvc;

    private final FakeEpic epic;

    private final String sessionCookieName;

    private final String fhirBase;

    EpicBrowser(MockMvc mvc, FakeEpic epic, String sessionCookieName, String fhirBase) {
        this.mvc = mvc;
        this.epic = epic;
        this.sessionCookieName = sessionCookieName;
        this.fhirBase = fhirBase;
    }

    /** The launch session, and Epic's authorization URL the backend redirected it to. */
    record Launched(Cookie session, URI epicAuthorize) {
    }

    /** The launch session, and our authorize hop's answer to it, whatever that was. */
    record Hop(Cookie launched, MvcResult authorize) {
    }

    /** The callback's answer, the session it signed in, and the launch session before it. */
    record Landing(MvcResult callback, Cookie signedIn, Cookie launched) {
    }

    /**
     * Epic opens the launch URL with exactly these parameters — {@code null} leaves one out —
     * from {@code jar} when the browser already holds a session; the launch's own answer.
     */
    MvcResult open(String iss, String launch, Cookie jar) throws Exception {
        MockHttpServletRequestBuilder opened = get("/api/auth/epic/launch");
        if (iss != null) {
            opened.queryParam("iss", iss);
        }
        if (launch != null) {
            opened.queryParam("launch", launch);
        }
        if (jar != null) {
            opened.cookie(jar);
        }
        return mvc.perform(opened).andReturn();
    }

    /**
     * An ordinary launch carrying {@code launchValue} as {@code launch}, followed to our authorize
     * hop and stopped there, whatever the hop answers: for a test about the hop itself, such as
     * discovery failing before the browser can be sent anywhere.
     */
    Hop authorizeHop(String launchValue, Cookie jar) throws Exception {
        MvcResult launch = open(fhirBase, launchValue, jar);
        assertThat(launch.getResponse().getStatus()).as("the launch redirects").isEqualTo(302);
        Cookie session = cookieOf(launch);
        assertThat(session).as("a session cookie was issued").isNotNull();
        return new Hop(session, mvc.perform(
                get(launch.getResponse().getRedirectedUrl()).cookie(session)).andReturn());
    }

    /** An ordinary launch, through our authorize hop, up to the redirect to Epic. */
    Launched launch(Cookie jar) throws Exception {
        return launch(LAUNCH, jar);
    }

    /**
     * {@link #launch(Cookie)}, Epic's launch URL carrying {@code launchValue} as
     * {@code launch}.
     */
    Launched launch(String launchValue, Cookie jar) throws Exception {
        Hop hop = authorizeHop(launchValue, jar);
        MvcResult authorize = hop.authorize();
        assertThat(authorize.getResponse().getStatus()).as("the authorize hop redirects")
                .isEqualTo(302);
        return new Launched(hop.launched(), URI.create(authorize.getResponse().getRedirectedUrl()));
    }

    /**
     * The clinician authorizes at Epic, Epic's {@code id_token} naming {@code fhirUser}; the query
     * Epic redirects the browser back to our callback with.
     */
    Map<String, String> authorizeAtEpic(String fhirUser, Launched launched) throws Exception {
        epic.signInAs(fhirUser);
        HttpResponse<Void> atEpic = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(launched.epicAuthorize()).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        URI callback = URI.create(atEpic.headers().firstValue("Location").orElseThrow());
        assertThat(callback.getPath()).isEqualTo("/api/auth/epic/callback");
        return new LinkedHashMap<>(FakeEpic.queryOf(callback));
    }

    /** The browser follows Epic's redirect to our callback with {@code query}, from {@code jar}. */
    Landing callback(Map<String, String> query, Cookie jar) throws Exception {
        MockHttpServletRequestBuilder redirected = get("/api/auth/epic/callback");
        if (jar != null) {
            redirected.cookie(jar);
        }
        // Each parameter by itself: a raw query handed to MockMvc as a URI template would be
        // encoded a second time. As query parameters, so the request carries the query string a
        // browser's would, and a record that read it would show in a test's capture.
        query.forEach(redirected::queryParam);
        MvcResult result = mvc.perform(redirected).andReturn();
        return new Landing(result, cookieOf(result), jar);
    }

    /**
     * The rest of a {@code launched} Login: Epic's authorization as {@code fhirUser}, and the
     * browser following Epic's redirect back to our callback from the launch session.
     */
    Landing complete(String fhirUser, Launched launched) throws Exception {
        return callback(authorizeAtEpic(fhirUser, launched), launched.session());
    }

    /**
     * {@link #complete}, holding Epic to having accepted every token request we sent it. For a
     * test whose Login is meant to get past Epic's {@code /token}: a request of ours Epic refused —
     * a bad assertion, a code or PKCE verifier that does not match — also lands at a notice, and
     * must not pass for the outcome the test is about.
     */
    Landing completeAccepted(String fhirUser, Launched launched) throws Exception {
        Landing landing = complete(fhirUser, launched);
        assertThat(epic.tokenRefusals()).as("Epic accepted our token request").isEmpty();
        return landing;
    }

    /** A whole Login: launch, Epic's authorization as {@code fhirUser}, and our callback. */
    Landing signIn(String fhirUser, Cookie jar) throws Exception {
        return complete(fhirUser, launch(jar));
    }

    /** {@code {fhirBase}/Practitioner/{practitioner}}, the {@code fhirUser} Epic names one by. */
    String practitioner(String practitioner) {
        return fhirBase + "/Practitioner/" + practitioner;
    }

    private Cookie cookieOf(MvcResult result) {
        Cookie issued = result.getResponse().getCookie(sessionCookieName);
        return issued == null ? null : new Cookie(issued.getName(), issued.getValue());
    }
}
