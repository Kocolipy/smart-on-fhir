import { createHash, createPublicKey, verify } from "node:crypto";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { fakeBrowserProfile, type ProfileOptions } from "../fake-browser-profile.testHelpers";
import {
  browserClientStore,
  clientAssertion,
  type DynamicClient,
  newClientKey,
} from "./client-key";

// The tab's dynamic client key, on Node's own WebCrypto: generated
// non-extractable, its public JWK and thumbprint, the client assertion it
// signs, and the browser profile's store of it.

/** RFC 7638's own oracle: SHA-256 over the RSA key's required members, in order, unpadded base64url. */
const rfc7638 = ({ e, n }: { e: string; n: string }) =>
  createHash("sha256").update(`{"e":"${e}","kty":"RSA","n":"${n}"}`).digest("base64url");

/** The JWT's three parts: its header and claims parsed, and what its signature covers. */
function parts(jwt: string) {
  const [header, claims, signature] = jwt.split(".");
  const parsed = (part: string) =>
    JSON.parse(Buffer.from(part, "base64url").toString("utf8")) as Record<string, unknown>;
  return {
    claims: parsed(claims),
    header: parsed(header),
    signature: Buffer.from(signature, "base64url"),
    signingInput: `${header}.${claims}`,
  };
}

describe("the oracle for a JWK thumbprint", () => {
  it("is RFC 7638's own worked example", () => {
    // RFC 7638 §3.1.
    const n =
      "0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx4cbbfAAtVT86zwu1RK7aPFFxuhDR1L6tSoc_BJECPebWKRXjBZCiFV4n3oknjhMstn64tZ_2W-5JsGY4Hc5n9yBXArwl93lqt7_RN5w6Cf0h4QyQ5v-65YGjQR0_FDW2QvzqY368QQMicAtaSqzs8KJZgnYb9c7d0zgdAZHzu6qMQvRL5hajrn1n91CbOpbISD08qNLyrdkt-bFTWhAI4vMQFh6WeZu0fM4lFd2NcRwr3XPksINHaQ-G_xBniIqbw0Ls1jF44-csFCur-kEgU8awapJzKnqDKgw";

    expect(rfc7638({ e: "AQAB", n })).toBe("NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs");
  });
});

describe("a new client key", () => {
  it("keeps its private key non-extractable", async () => {
    const { privateKey } = await newClientKey();

    const exported = crypto.subtle.exportKey("pkcs8", privateKey).then(
      () => "exported",
      () => "refused",
    );

    expect([privateKey.extractable, await exported]).toEqual([false, "refused"]);
  });

  it("is an RS384 key of 2048 bits that signs", async () => {
    const { privateKey } = await newClientKey();

    expect([privateKey.algorithm, privateKey.usages]).toEqual([
      {
        hash: { name: "SHA-384" },
        modulusLength: 2048,
        name: "RSASSA-PKCS1-v1_5",
        publicExponent: new Uint8Array([1, 0, 1]),
      },
      ["sign"],
    ]);
  });

  it("names its public key as a JWK whose kid is its RFC 7638 thumbprint, and nothing private", async () => {
    const { publicJwk } = await newClientKey();

    expect(publicJwk).toEqual({
      alg: "RS384",
      e: "AQAB",
      kid: rfc7638(publicJwk),
      kty: "RSA",
      n: expect.stringMatching(/^[A-Za-z0-9_-]{342}$/) as string,
      use: "sig",
    });
  });

  it("is a new key each time", async () => {
    const [first, second] = await Promise.all([newClientKey(), newClientKey()]);

    expect(first.publicJwk.kid).not.toBe(second.publicJwk.kid);
  });
});

describe("a client assertion", () => {
  /** 2026-10-24T02:00:00Z, in whole seconds. */
  const NOW_SECONDS = 1_792_807_200;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(NOW_SECONDS * 1000);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /** A registered client, and the public key the hub holds for it. */
  async function registered() {
    const { privateKey, publicJwk } = await newClientKey();
    const client: DynamicClient = { clientId: "client-1", kid: publicJwk.kid, privateKey };
    return { client, publicKey: createPublicKey({ format: "jwk", key: { ...publicJwk } }) };
  }

  it("is signed RS384 by the client's key", async () => {
    const { client, publicKey } = await registered();
    const { signature, signingInput } = parts(await clientAssertion(client, "urn:hub"));

    expect(verify("sha384", Buffer.from(signingInput), publicKey, signature)).toBe(true);
  });

  it("names RS384 and the client's kid in its header", async () => {
    const { client } = await registered();

    expect(parts(await clientAssertion(client, "urn:hub")).header).toEqual({
      alg: "RS384",
      kid: client.kid,
      typ: "JWT",
    });
  });

  it("claims the client as iss and sub, the audience as aud, four minutes to run and a random jti", async () => {
    const { client } = await registered();

    expect(parts(await clientAssertion(client, "urn:hub")).claims).toEqual({
      aud: "urn:hub",
      exp: NOW_SECONDS + 240,
      iss: "client-1",
      jti: expect.stringMatching(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      ) as string,
      sub: "client-1",
    });
  });

  it("runs four minutes from whole seconds, rounding a fraction down", async () => {
    vi.setSystemTime(NOW_SECONDS * 1000 + 999);
    const { client } = await registered();

    expect(parts(await clientAssertion(client, "urn:hub")).claims.exp).toBe(NOW_SECONDS + 240);
  });

  it("is a compact JWT of three unpadded base64url parts", async () => {
    const { client } = await registered();

    expect(await clientAssertion(client, "urn:hub")).toMatch(
      /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/,
    );
  });

  it("carries a jti of its own each time", async () => {
    const { client } = await registered();

    const [first, second] = await Promise.all([
      clientAssertion(client, "urn:hub"),
      clientAssertion(client, "urn:hub"),
    ]);

    expect(parts(first).claims.jti).not.toBe(parts(second).claims.jti);
  });
});

describe("the browser profile's client store", () => {
  /** The profile's IndexedDB and Web Locks stubbed for the rest of the test. */
  function profile(options: ProfileOptions = {}) {
    const faked = fakeBrowserProfile(options);
    vi.stubGlobal("navigator", { ...navigator, locks: faked.locks });
    return faked;
  }

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /** The records of the profile's client store, made by a first read. */
  async function clientRecords(faked: ReturnType<typeof profile>) {
    await browserClientStore.read("urn:none");
    return faked.store("fhircast-clients", "clients") ?? new Map<IDBValidKey, unknown>();
  }

  /** A client to keep, its key a real non-extractable one. */
  async function aClient(clientId = "client-1"): Promise<DynamicClient> {
    const { privateKey, publicJwk } = await newClientKey();
    return { clientId, kid: publicJwk.kid, privateKey };
  }

  it("reads no client for an audience it keeps none for", async () => {
    profile();

    expect(await browserClientStore.read("urn:hub")).toBeNull();
  });

  it("reads back the client it kept for an audience", async () => {
    profile();
    const client = await aClient();
    await browserClientStore.keep("urn:hub", client);

    expect(await browserClientStore.read("urn:hub")).toEqual(client);
  });

  it("keeps a client under its audience alone", async () => {
    profile();
    await browserClientStore.keep("urn:hub", await aClient());

    expect(await browserClientStore.read("urn:another-hub")).toBeNull();
  });

  it("keeps the newest client for an audience in place of the one before", async () => {
    profile();
    await browserClientStore.keep("urn:hub", await aClient("client-1"));
    await browserClientStore.keep("urn:hub", await aClient("client-2"));

    expect((await browserClientStore.read("urn:hub"))?.clientId).toBe("client-2");
  });

  it("keeps each client in the fhircast-clients database's clients store, the private key as the CryptoKey itself", async () => {
    const faked = profile();
    const client = await aClient();
    await browserClientStore.keep("urn:hub", client);

    expect(faked.store("fhircast-clients", "clients")?.get("urn:hub")).toEqual({
      clientId: "client-1",
      kid: client.kid,
      privateKey: client.privateKey,
    });
  });

  it.each<[string, (privateKey: CryptoKey) => unknown]>([
    ["no object", () => "client-1"],
    ["null", () => null],
    ["no client_id", (privateKey) => ({ kid: "kid-1", privateKey })],
    ["an empty client_id", (privateKey) => ({ clientId: "", kid: "kid-1", privateKey })],
    ["no kid", (privateKey) => ({ clientId: "client-1", privateKey })],
    [
      "a private key that is no CryptoKey",
      () => ({ clientId: "client-1", kid: "kid-1", privateKey: {} }),
    ],
  ])("reads a record that is %s as no client", async (_name, record) => {
    const records = await clientRecords(profile());
    records.set("urn:hub", record((await aClient()).privateKey));

    expect(await browserClientStore.read("urn:hub")).toBeNull();
  });

  it("fails to read when the profile refuses storage", async () => {
    profile({ refusesStorage: true });

    await expect(browserClientStore.read("urn:hub")).rejects.toThrow();
  });

  it("fails to keep when the profile refuses storage", async () => {
    profile({ refusesStorage: true });

    await expect(browserClientStore.keep("urn:hub", await aClient())).rejects.toThrow();
  });

  it.each(["error", "abort"] as const)(
    "fails to keep when the profile's write ends in an %s",
    async (failsWrites) => {
      profile({ failsWrites });

      await expect(browserClientStore.keep("urn:hub", await aClient())).rejects.toThrow();
    },
  );

  it("closes the database once each read and keep is done", async () => {
    const faked = profile();
    await browserClientStore.keep("urn:hub", await aClient());
    await browserClientStore.read("urn:hub");

    expect(faked.openConnections()).toBe(0);
  });

  it("holds the lock named for the audience", async () => {
    const faked = profile();

    await browserClientStore.exclusively("urn:hub", () => Promise.resolve());

    expect(faked.locks.requested).toEqual(["fhircast-registration:urn:hub"]);
  });

  it("answers what its task answers, and fails as its task fails", async () => {
    profile();

    const answered = await browserClientStore.exclusively("urn:hub", () => Promise.resolve(7));
    const failed = browserClientStore.exclusively("urn:hub", () => Promise.reject(new Error("x")));

    await expect(failed).rejects.toThrow("x");
    expect(answered).toBe(7);
  });

  it("runs one task at a time under an audience's lock", async () => {
    profile();
    const steps: string[] = [];
    let release: () => void = () => undefined;
    const first = browserClientStore.exclusively("urn:hub", async () => {
      steps.push("first begins");
      await new Promise<void>((resolve) => (release = resolve));
      steps.push("first ends");
    });
    const second = browserClientStore.exclusively("urn:hub", () => {
      steps.push("second begins");
      return Promise.resolve();
    });
    await Promise.resolve();
    release();
    await Promise.all([first, second]);

    expect(steps).toEqual(["first begins", "first ends", "second begins"]);
  });
});
