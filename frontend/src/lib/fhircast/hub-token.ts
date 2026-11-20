/**
 * The hub token a connection subscribes with (ADR 0016, "The dynamic client
 * and the hub token"): the hub's configuration, then the browser profile's
 * dynamic client for the hub's audience — registered, with the SMART access
 * token, when the profile holds none or the hub has forgotten the one it
 * holds — and the token endpoint's answer to that client's assertion. Nothing
 * of it is kept but the client: the hub token is handed back, for the
 * connection to hold in memory.
 */

import { type ClientStore, clientAssertion, type DynamicClient, newClientKey } from "./client-key";
import { type Hub } from "./model";
import {
  configuration,
  type HubCredentials,
  HubFailure,
  type HubToken,
  InvalidClient,
  register,
  requestHubToken,
} from "./protocol";

/**
 * The hub knows no client of the profile's, and the tab cannot register one:
 * no SMART access token is left to register with, the hub is read-only, or
 * registering once more failed. Only a fresh launch from Epic connects again.
 * It carries no message: nothing about a failure is ever shown or logged.
 */
export class ClientLost extends Error {}

/**
 * The dynamic client for `audience`, under the profile's lock for it: the
 * client another tab kept meanwhile, if one did and it is not `forgotten`, so
 * concurrent tabs register once; otherwise `forgotten` forgotten, and a new
 * key registered with the SMART access token, then kept — or, with no SMART
 * access token in `credentials`, a {@link ClientLost} instead.
 */
function registered(
  credentials: HubCredentials,
  audience: string,
  store: ClientStore,
  forgotten: DynamicClient | null,
): Promise<DynamicClient> {
  return store.exclusively(audience, async () => {
    const keptMeanwhile = await store.read(audience);
    if (keptMeanwhile !== null && keptMeanwhile.clientId !== forgotten?.clientId) {
      return keptMeanwhile;
    }
    if (keptMeanwhile !== null) await store.forget(audience);
    const { accessToken } = credentials;
    if (accessToken === null) throw new ClientLost();
    const { privateKey, publicJwk } = await newClientKey();
    const clientId = await register({ ...credentials, accessToken }, publicJwk);
    const client = { clientId, kid: publicJwk.kid, privateKey };
    await store.keep(audience, client);
    return client;
  });
}

/**
 * Forgets `forgotten`, under the profile's lock for `audience`, unless
 * another tab kept a client in its place meanwhile.
 */
function forgetUnderLock(
  store: ClientStore,
  audience: string,
  forgotten: DynamicClient,
): Promise<void> {
  return store.exclusively(audience, async () => {
    const kept = await store.read(audience);
    if (kept?.clientId === forgotten.clientId) await store.forget(audience);
  });
}

/** The hub token `client` obtains at `credentials`' hub, for its assertion to `audience`. */
async function tokenFor(
  credentials: HubCredentials,
  client: DynamicClient,
  audience: string,
): Promise<HubToken> {
  return requestHubToken(credentials.hub, await clientAssertion(client, audience));
}

/**
 * A hub token from `credentials`' hub, the dynamic client read from and kept
 * in `store`: the client the profile holds for the hub's audience, or one
 * registered now — never at a read-only hub, which with no client kept is a
 * refusal. When the token endpoint answers `INVALID-CLIENT`, the hub has
 * forgotten that client: the profile forgets it too, and registers once more
 * with the SMART access token, unless another tab of the profile did
 * meanwhile. Rejects with a {@link ClientLost} when it cannot, or the hub
 * forgets that client too; with a {@link HubFailure} when the hub refuses any
 * other step, or leaves it unanswered for ten seconds.
 */
export async function hubToken(credentials: HubCredentials, store: ClientStore): Promise<HubToken> {
  const { audience, readOnly } = await configuration(credentials.hub);
  let client = await store.read(audience);
  if (client === null) {
    if (readOnly) throw new HubFailure(false);
    client = await registered(credentials, audience, store, null);
  }
  try {
    return await tokenFor(credentials, client, audience);
  } catch (failure) {
    if (!(failure instanceof InvalidClient)) throw failure;
  }
  const forgotten = client;
  // A read-only hub registers nothing, as with no SMART access token.
  const registering = readOnly ? { ...credentials, accessToken: null } : credentials;
  try {
    client = await registered(registering, audience, store, forgotten);
  } catch {
    throw new ClientLost();
  }
  try {
    return await tokenFor(credentials, client, audience);
  } catch (failure) {
    if (!(failure instanceof InvalidClient)) throw failure;
  }
  await forgetUnderLock(store, audience, client);
  throw new ClientLost();
}

/**
 * A hub token at `hub` for the dynamic client the profile holds for its
 * audience, writing nothing to `store`: it registers no client, and forgets
 * none, whatever the hub answers. What a teardown unsubscribes with, so the
 * next session in this browser profile finds the client as this one left it.
 * Rejects with a {@link HubFailure} when the profile holds no client, and with
 * whatever the hub's refusal is otherwise, `INVALID-CLIENT` included.
 */
export async function heldClientHubToken(hub: Hub, store: ClientStore): Promise<HubToken> {
  const { audience } = await configuration(hub);
  const client = await store.read(audience);
  if (client === null) throw new HubFailure(false);
  return requestHubToken(hub, await clientAssertion(client, audience));
}
