/**
 * The hub token a connection subscribes with (ADR 0016, "The dynamic client
 * and the hub token"): the hub's configuration, then the browser profile's
 * dynamic client for the hub's audience — registered, with the SMART access
 * token, when the profile holds none — and the token endpoint's answer to
 * that client's assertion. Nothing of it is kept but the client: the hub
 * token is handed back, for the connection to hold in memory.
 */

import { type ClientStore, clientAssertion, type DynamicClient, newClientKey } from "./client-key";
import {
  configuration,
  type HubCredentials,
  HubFailure,
  register,
  requestHubToken,
} from "./protocol";

/**
 * A new dynamic client for `audience`, under the profile's lock for it: the
 * client another tab kept meanwhile, if one did, so concurrent tabs register
 * once; otherwise a new key registered with the SMART access token, then kept.
 */
function registered(
  credentials: HubCredentials,
  audience: string,
  store: ClientStore,
): Promise<DynamicClient> {
  return store.exclusively(audience, async () => {
    const keptMeanwhile = await store.read(audience);
    if (keptMeanwhile !== null) return keptMeanwhile;
    const { privateKey, publicJwk } = await newClientKey();
    const clientId = await register(credentials, publicJwk);
    const client = { clientId, kid: publicJwk.kid, privateKey };
    await store.keep(audience, client);
    return client;
  });
}

/**
 * A hub token from `credentials`' hub, the dynamic client read from and kept
 * in `store`: the client the profile holds for the hub's audience, or one
 * registered now — never at a read-only hub, which with no client kept is a
 * refusal. Rejects with a {@link HubFailure} when the hub refuses any step,
 * or leaves it unanswered for ten seconds.
 */
export async function hubToken(credentials: HubCredentials, store: ClientStore): Promise<string> {
  const { audience, readOnly } = await configuration(credentials.hub);
  let client = await store.read(audience);
  if (client === null) {
    if (readOnly) throw new HubFailure(false);
    client = await registered(credentials, audience, store);
  }
  return requestHubToken(credentials.hub, await clientAssertion(client, audience));
}
