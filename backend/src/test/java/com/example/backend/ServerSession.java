package com.example.backend;

import static com.example.backend.TestJson.JSON;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The SPA's session against a running server, for a test that boots one on a port
 * ({@code WebEnvironment.RANDOM_PORT}) and drives it over real HTTP: a client keeping the cookies
 * a browser would, the login, and the CSRF handshake.
 *
 * <p>The MockMvc counterparts are {@link SessionLogin} and {@link SessionCsrf}.
 */
public final class ServerSession {

    private ServerSession() {
    }

    /**
     * A fresh client, signed in as {@code username}: it fetched a CSRF token, posted the
     * credentials with it and keeps the session cookie the login issued.
     *
     * @throws AssertionError when the login is not answered {@code 200}
     */
    public static HttpClient logIn(int port, String username, String password) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        HttpResponse<String> response = send(client,
                withCsrf(client, port, request(port, "/api/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(
                                Map.of("username", username, "password", password)))));
        if (response.statusCode() != 200) {
            throw new AssertionError("login of " + username + " answered "
                    + response.statusCode());
        }
        return client;
    }

    /** {@code request} with the token of {@code client}'s session, fetched as the SPA does. */
    public static HttpRequest.Builder withCsrf(
            HttpClient client, int port, HttpRequest.Builder request) throws Exception {
        JsonNode csrf = JSON.readTree(
                send(client, request(port, SessionCsrf.PATH).GET()).body());
        return request.header(csrf.get("headerName").asText(), csrf.get("token").asText());
    }

    private static HttpRequest.Builder request(int port, String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
            throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
