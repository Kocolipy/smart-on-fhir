/**
 * The tab's dynamic client at the FHIRcast Hub, as the browser profile keeps
 * it: its RS384 key, generated non-extractable so the private key never
 * leaves WebCrypto, the `client_id` the hub registered it under, and the
 * client assertion that key signs for a hub token (ADR 0016, "The dynamic
 * client and the hub token"). The only module allowed `crypto.subtle`,
 * `indexedDB` and the Web Locks API (`test/arch/browserKeys.test.ts`).
 *
 * One client per hub `audience`, kept in IndexedDB ({@link browserClientStore})
 * and shared by every tab of the browser profile, whichever User is signed
 * in — an accepted deviation (ADR 0011, "FHIRcast in the browser"). What is
 * kept is decoded, never trusted: anything malformed is taken for no client.
 */

import { memberOf } from "./model";

/** A dynamic client the hub registered: its `client_id`, its key's `kid` and its private key. */
export interface DynamicClient {
  clientId: string;
  kid: string;
  /** Non-extractable: it signs, and nothing reads it out. */
  privateKey: CryptoKey;
}

/**
 * Where the browser profile keeps its dynamic clients, one per hub audience,
 * and the profile-wide lock that registering one is held under.
 */
export interface ClientStore {
  /** The client kept for `audience`, or `null` for none. */
  read: (audience: string) => Promise<DynamicClient | null>;
  /** Keeps `client` as `audience`'s, in place of any before it. */
  keep: (audience: string, client: DynamicClient) => Promise<void>;
  /** Forgets the client kept for `audience`, if any: the hub no longer knows it. */
  forget: (audience: string) => Promise<void>;
  /** `task`, run holding the lock named for `audience`, which every tab of the profile shares. */
  exclusively: <T>(audience: string, task: () => Promise<T>) => Promise<T>;
}

/** The public JWK the hub registers: an RSA key's modulus and exponent, its `kid`, `alg` and `use`. */
export interface PublicJwk {
  alg: "RS384";
  e: string;
  kid: string;
  kty: "RSA";
  n: string;
  use: "sig";
}

/** A key generated for a registration: the private key, non-extractable, and the public JWK. */
export interface ClientKey {
  privateKey: CryptoKey;
  publicJwk: PublicJwk;
}

/** RS384: RSASSA-PKCS1-v1_5 with SHA-384. */
const RS384: RsaHashedKeyGenParams = {
  hash: "SHA-384",
  modulusLength: 2048,
  name: "RSASSA-PKCS1-v1_5",
  publicExponent: new Uint8Array([1, 0, 1]),
};

/** How far ahead a client assertion's `exp` is: four minutes, under the hub's five. */
const ASSERTION_LIFETIME_SECONDS = 240;

/** `bytes` in base64url, unpadded. */
function base64url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** `text`, UTF-8 encoded, in base64url. */
const base64urlText = (text: string): string => base64url(new TextEncoder().encode(text));

/**
 * RFC 7638: the JWK thumbprint of the RSA public key `n`, `e` — SHA-256 over
 * its required members in lexicographic order, with no whitespace.
 */
async function thumbprint(n: string, e: string): Promise<string> {
  const canonical = JSON.stringify({ e, kty: "RSA", n });
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(canonical));
  return base64url(new Uint8Array(digest));
}

/**
 * A new RS384 key pair for a registration (RSASSA-PKCS1-v1_5, 2048-bit,
 * SHA-384): the private key non-extractable, and the public key as the JWK
 * the hub registers, its `kid` its RFC 7638 thumbprint.
 */
export async function newClientKey(): Promise<ClientKey> {
  const pair = await crypto.subtle.generateKey(RS384, false, ["sign", "verify"]);
  const exported = await crypto.subtle.exportKey("jwk", pair.publicKey);
  // An RSA public key always exports its exponent and modulus.
  const [e, n] = [String(exported.e), String(exported.n)];
  return {
    privateKey: pair.privateKey,
    publicJwk: { alg: "RS384", e, kid: await thumbprint(n, e), kty: "RSA", n, use: "sig" },
  };
}

/**
 * The `jwt-bearer` client assertion `client` presents for a hub token at the
 * hub of `audience`: a JWT signed RS384 by its private key, naming its `kid`,
 * `iss` and `sub` its `client_id`, `aud` the audience, `exp` four minutes
 * from now and `jti` a random UUID.
 */
export async function clientAssertion(client: DynamicClient, audience: string): Promise<string> {
  const header = { alg: "RS384", kid: client.kid, typ: "JWT" };
  const claims = {
    aud: audience,
    exp: Math.floor(Date.now() / 1000) + ASSERTION_LIFETIME_SECONDS,
    iss: client.clientId,
    jti: crypto.randomUUID(),
    sub: client.clientId,
  };
  const signingInput = `${base64urlText(JSON.stringify(header))}.${base64urlText(JSON.stringify(claims))}`;
  const signature = await crypto.subtle.sign(
    RS384.name,
    client.privateKey,
    new TextEncoder().encode(signingInput),
  );
  return `${signingInput}.${base64url(new Uint8Array(signature))}`;
}

/** The IndexedDB database the profile's dynamic clients are kept in, and its one object store. */
const DATABASE = "fhircast-clients";
const CLIENTS = "clients";

/** The prefix of the Web Locks lock a registration for an audience is held under. */
const REGISTRATION_LOCK = "fhircast-registration:";

/**
 * IndexedDB refused a request or a write. It carries no message: a refusal is
 * `FHIRcast unavailable`, and nothing about it is shown or logged.
 */
class StorageRefused extends Error {}

/** The answer `request` settles with; a {@link StorageRefused} when IndexedDB refuses it. */
function settled<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(new StorageRefused());
  });
}

/** `task` on the profile's client database, opened — and made, the first time — for it alone. */
async function withDatabase<T>(task: (database: IDBDatabase) => Promise<T>): Promise<T> {
  const opening = indexedDB.open(DATABASE, 1);
  opening.onupgradeneeded = () => opening.result.createObjectStore(CLIENTS);
  const database = await settled(opening);
  try {
    return await task(database);
  } finally {
    database.close();
  }
}

/**
 * `write` on the profile's client store, in a transaction of its own: settled
 * once the transaction completes; a {@link StorageRefused} when it fails or is
 * aborted.
 */
function written(write: (clients: IDBObjectStore) => void): Promise<void> {
  return withDatabase(async (database) => {
    const writing = database.transaction(CLIENTS, "readwrite");
    const done = new Promise<void>((resolve, reject) => {
      writing.oncomplete = () => resolve();
      writing.onerror = () => reject(new StorageRefused());
      writing.onabort = () => reject(new StorageRefused());
    });
    write(writing.objectStore(CLIENTS));
    await done;
  });
}

/** The dynamic client `kept` holds, decoded: `null` for anything else. */
function decodedClient(kept: unknown): DynamicClient | null {
  const [clientId, kid, privateKey] = [
    memberOf(kept, "clientId"),
    memberOf(kept, "kid"),
    memberOf(kept, "privateKey"),
  ];
  if (typeof clientId !== "string" || clientId === "") return null;
  if (typeof kid !== "string" || !(privateKey instanceof CryptoKey)) return null;
  return { clientId, kid, privateKey };
}

/**
 * The browser profile's store: each client in IndexedDB under its audience,
 * the private key as the non-extractable `CryptoKey` itself, and the lock the
 * Web Locks API's, shared by every tab of the profile.
 */
export const browserClientStore: ClientStore = {
  exclusively: <T>(audience: string, task: () => Promise<T>) =>
    new Promise<T>((resolve, reject) => {
      // The lock is held until the task settles, and released then either way.
      navigator.locks
        .request(`${REGISTRATION_LOCK}${audience}`, () => task().then(resolve, reject))
        .catch(reject);
    }),
  forget: (audience) => written((clients) => clients.delete(audience)),
  keep: (audience, client) => written((clients) => clients.put({ ...client }, audience)),
  read: (audience) =>
    withDatabase(async (database) =>
      decodedClient(
        await settled(database.transaction(CLIENTS, "readonly").objectStore(CLIENTS).get(audience)),
      ),
    ),
};
