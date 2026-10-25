import { randomBytes } from "node:crypto";

/**
 * Medplum, as its seeded super admin: the identity the shim introspects SMART
 * access tokens with and subscribes to the hub as. It signs in the way
 * Medplum's own client does (`POST /auth/login` with a PKCE challenge, then the
 * code at `/oauth2/token`), and signs in again shortly before the token expires.
 *
 * @param {string} medplumUrl Medplum's own URL, as the caller reaches it
 * @param {{email: string, password: string}} admin
 */
export function medplumAdmin(medplumUrl, admin) {
  let cached;

  async function token() {
    if (cached && cached.refreshAt > Date.now()) return cached.accessToken;
    const verifier = randomBytes(32).toString("base64url");
    const login = await call("/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email: admin.email,
        password: admin.password,
        scope: "openid",
        codeChallenge: verifier,
        codeChallengeMethod: "plain",
      }),
    });
    const tokens = await call("/oauth2/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "authorization_code",
        code: login.code,
        code_verifier: verifier,
      }),
    });
    // A minute early, so a token never expires between this check and its use.
    cached = {
      accessToken: tokens.access_token,
      refreshAt: Date.now() + (tokens.expires_in - 60) * 1000,
    };
    return cached.accessToken;
  }

  async function call(path, init) {
    const answer = await fetch(new URL(path, medplumUrl), init);
    if (!answer.ok)
      throw new Error(`Medplum answered ${answer.status} on ${path}`);
    return answer.json();
  }

  async function request(path, { method = "GET", body } = {}) {
    return call(path, {
      method,
      headers: {
        Authorization: `Bearer ${await token()}`,
        ...(body === undefined
          ? {}
          : { "Content-Type": "application/fhir+json" }),
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
  }

  return {
    token,
    /** Whether Medplum's introspection calls `accessToken` active. */
    async isActive(accessToken) {
      const answer = await call("/oauth2/introspect", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${await token()}`,
          "Content-Type": "application/x-www-form-urlencoded",
        },
        body: new URLSearchParams({ token: accessToken }),
      });
      return answer.active === true;
    },
    /**
     * A call as the super admin, answering its JSON body; a body is sent as
     * JSON. Any status but 2xx is an error naming the path and the status.
     */
    request,
    /** Every resource a FHIR R4 search for `query` answers, across pages. */
    async search(query) {
      const found = [];
      let next = `/fhir/R4/${query}`;
      while (next) {
        const bundle = await request(next);
        found.push(...(bundle.entry ?? []).map(({ resource }) => resource));
        const link = bundle.link?.find(
          ({ relation }) => relation === "next",
        )?.url;
        next = link ? new URL(link).pathname + new URL(link).search : undefined;
      }
      return found;
    },
  };
}
