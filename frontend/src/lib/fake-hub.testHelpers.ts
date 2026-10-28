import { vi } from "vitest";

import { fakeBackend, serveOtherOrigin } from "./fake-backend.testHelpers";

// A fake of Epic's local FHIRcast hub, the other origin the FHIRcast module
// talks to: its `.well-known` metadata, RFC 7591 registration, the
// `jwt-bearer` token endpoint, the subscription and its unsubscription, RFC
// 7009 revocation, RFC 7592 deletion, and the websocket, which a
// test can push notifications down (`notify`) and read the page's
// acknowledgements from (`received`). The token endpoint's and the
// subscription's answers may be a sequence, one per request in turn, its last
// repeated; so may the sockets' behaviour, one per socket the page opens. It
// answers through the fake backend's other-origin hook (`serveOtherOrigin`),
// so it runs beside the backend a page suite fakes, and stubs the global
// `WebSocket`. Every request is recorded, as the hub saw it. It answers by
// method and path, whatever origin a request names: the module holds every
// origin to loopback before it sends anything.

/** The hub's URL, as a release names it. */
export const HUB_URL = "http://127.0.0.1:8103/fhircast/STU3";

export const REGISTRATION_ENDPOINT = "http://127.0.0.1:8103/dcr/register";

export const TOKEN_ENDPOINT = "http://127.0.0.1:8103/dcr/token";

/** RFC 7009's endpoint, which the hub's metadata names unless told otherwise. */
export const REVOCATION_ENDPOINT = "http://127.0.0.1:8103/dcr/revoke";

/** The `n`th subscription's channel endpoint, from 1. */
export const endpointOf = (n: number) => `ws://127.0.0.1:8103/ws/fhircast/endpoint-${n}`;

export const CHANNEL_ENDPOINT = endpointOf(1);

export const CLIENT_ID = "client-1";

/** Where RFC 7592 manages the registration: the registration's answer names it unless told otherwise. */
export const REGISTRATION_CLIENT_URI = `${REGISTRATION_ENDPOINT}/${CLIENT_ID}`;

export const REGISTRATION_ACCESS_TOKEN = `rat-${crypto.randomUUID()}`;

export const HUB_TOKEN = `hub-${crypto.randomUUID()}`;

/** The `n`th Hub Token the hub grants, from 1: {@link HUB_TOKEN} the first. */
export const hubTokenOf = (n: number) => (n === 1 ? HUB_TOKEN : `${HUB_TOKEN}-${n}`);

/** One request the hub was sent. */
export interface HubRequest {
  authorization: string | null;
  body: string;
  contentType: string | null;
  credentials: RequestCredentials | undefined;
  method: string;
  redirect: RequestRedirect | undefined;
  url: string;
}

/**
 * A hub answer: a status and a JSON body, a status and a body that is no JSON
 * at all, or a request that never reached the hub.
 */
export type HubAnswer =
  { status: number; body?: unknown } | { status: number; text: string } | "unreachable";

/**
 * What the socket does once open: confirm the subscription; close without a
 * word; say only other things, then close; say nothing and stay open; or deny
 * the subscription, as a hub does at an endpoint it no longer knows, then close.
 */
export type SocketBehaviour = "confirms" | "closes" | "says-otherwise" | "silent" | "denies";

/** One answer, or one per request in turn, the last repeated. */
type InTurn<T> = T | readonly T[];

export interface HubOptions {
  /**
   * The `.well-known/fhircast-configuration` answer: by default the
   * registration, token and revocation endpoints. The SMART one answers `404`
   * unless named.
   */
  fhircastConfiguration?: HubAnswer;
  smartConfiguration?: HubAnswer;
  /**
   * The registration's answer, now or once its promise settles: by default
   * {@link CLIENT_ID}, with {@link REGISTRATION_CLIENT_URI} and
   * {@link REGISTRATION_ACCESS_TOKEN} for RFC 7592.
   */
  registration?: HubAnswer | Promise<HubAnswer>;
  /**
   * The token endpoint's answers, each now or once its promise settles: by
   * default an hour's Hub Token, {@link HUB_TOKEN} the first.
   */
  token?: InTurn<HubAnswer | Promise<HubAnswer>>;
  /** The subscription's answers: by default `endpoint-1`, then `endpoint-2`, and so on. */
  subscription?: InTurn<HubAnswer>;
  socket?: InTurn<SocketBehaviour>;
  /** The answer to an unsubscription (`hub.mode` `unsubscribe`), now or once its promise settles: by default `202`. */
  unsubscription?: HubAnswer | Promise<HubAnswer>;
  /** The revocation endpoint's answer, now or once its promise settles: by default `200`. */
  revocation?: HubAnswer | Promise<HubAnswer>;
  /** The answer to a `DELETE` at {@link REGISTRATION_CLIENT_URI}, now or once its promise settles: by default `204`. */
  deletion?: HubAnswer | Promise<HubAnswer>;
  /** The `hub.lease_seconds` a confirmation grants; it names none unless given. */
  leaseSeconds?: number;
  /**
   * Holds the socket open and silent until this settles, then it behaves as
   * `socket` says; one per socket in turn, `null` holding that one not at all.
   */
  hold?: InTurn<Promise<void> | null>;
  /** The Local Network Access permission's state, as `navigator.permissions` reports it. */
  localNetworkAccess?: PermissionState;
}

export interface FakeHub {
  requests: () => readonly HubRequest[];
  /** The request sent to `url`, the first if there were several. */
  requestTo: (url: string) => HubRequest;
  /** Every request sent to `url`, in order. */
  requestsTo: (url: string) => readonly HubRequest[];
  /** Every websocket the page opened, by URL. */
  sockets: () => readonly string[];
  /** Closes every open socket from the hub's side. */
  dropSockets: () => void;
  /** How many sockets the page opened and has not closed. */
  openSockets: () => number;
  /** Sends `message` on every open socket from the hub's side: a string as it is, anything else as JSON. */
  notify: (message: unknown) => void;
  /** Everything the page sent on its sockets, in order, as sent. */
  received: () => readonly string[];
}

const json = (body: unknown, status = 200): { status: number; body?: unknown } => ({
  body,
  status,
});

const METADATA = json({
  registration_endpoint: REGISTRATION_ENDPOINT,
  revocation_endpoint: REVOCATION_ENDPOINT,
  token_endpoint: TOKEN_ENDPOINT,
});

const REGISTERED = json(
  {
    client_id: CLIENT_ID,
    client_id_issued_at: 1,
    registration_access_token: REGISTRATION_ACCESS_TOKEN,
    registration_client_uri: REGISTRATION_CLIENT_URI,
  },
  201,
);

/** The `n`th subscription's answer, from 1: a channel endpoint of its own, the first {@link CHANNEL_ENDPOINT}. */
const subscribed = (n: number) => json({ "hub.channel.endpoint": endpointOf(n) }, 202);

/** The `n`th token answer, from 1: a Hub Token of an hour, the first {@link HUB_TOKEN}. */
const granted = (n: number) =>
  json({
    access_token: hubTokenOf(n),
    expires_in: 3600,
    token_type: "Bearer",
  });

/** The `n`th of `answers`, from 1, its last repeated. */
function inTurn<T>(answers: InTurn<T>, n: number): T {
  if (!Array.isArray(answers)) return answers as T;
  const sequence = answers as readonly T[];
  return sequence[Math.min(n, sequence.length) - 1];
}

/** What a socket that says otherwise says: no JSON, no object, another mode. */
const OTHER_MESSAGES: unknown[] = [
  "not json",
  "null",
  "7",
  JSON.stringify({ "hub.mode": "unsubscribe" }),
  JSON.stringify({ "hub.topic": "topic-1" }),
];

type Listener = (event: { data?: unknown }) => void;

const pathOf = (url: string): string => new URL(url).pathname;

/** Statuses whose response carries no body at all. */
const NO_BODY = [204, 205, 304];

function toResponse(answer: Exclude<HubAnswer, "unreachable">): Response {
  if (NO_BODY.includes(answer.status)) return new Response(null, { status: answer.status });
  return "text" in answer
    ? new Response(answer.text, { status: answer.status })
    : Response.json(answer.body ?? {}, { status: answer.status });
}

/** A request as the hub saw it. */
function recorded(url: string, init: RequestInit = {}): HubRequest {
  const headers = new Headers(init.headers);
  return {
    authorization: headers.get("Authorization"),
    body: typeof init.body === "string" ? init.body : "",
    contentType: headers.get("Content-Type"),
    credentials: init.credentials,
    method: (init.method ?? "GET").toUpperCase(),
    redirect: init.redirect,
    url,
  };
}

/** What the hub answers: the answer given, once it settles; a `404` for a path it does not serve. */
async function answer(given: HubAnswer | Promise<HubAnswer> | undefined): Promise<Response> {
  const settled = await given;
  if (settled === "unreachable") throw new TypeError("Failed to fetch");
  return toResponse(settled ?? json({ error: "not_found" }, 404));
}

/** What the hub answers on each route, given which of the route's requests it is, from 1. */
function routes(
  options: HubOptions,
): Record<string, (n: number) => HubAnswer | Promise<HubAnswer>> {
  const hubPath = pathOf(HUB_URL);
  return {
    [`GET ${hubPath}/.well-known/fhircast-configuration`]: () =>
      options.fhircastConfiguration ?? METADATA,
    [`GET ${hubPath}/.well-known/smart-configuration`]: () =>
      options.smartConfiguration ?? json({}, 404),
    [`POST ${pathOf(REGISTRATION_ENDPOINT)}`]: () => options.registration ?? REGISTERED,
    [`POST ${pathOf(TOKEN_ENDPOINT)}`]: (n) =>
      options.token === undefined ? granted(n) : inTurn(options.token, n),
    [`POST ${hubPath}`]: (n) =>
      options.subscription === undefined ? subscribed(n) : inTurn(options.subscription, n),
    [`POST ${hubPath} unsubscribe`]: () => options.unsubscription ?? json({}, 202),
    [`POST ${pathOf(REVOCATION_ENDPOINT)}`]: () => options.revocation ?? json({}),
    [`DELETE ${pathOf(REGISTRATION_CLIENT_URI)}`]: () => options.deletion ?? { status: 204 },
  };
}

/** A request's route: its method and path, an unsubscription apart from the subscriptions. */
const routeOf = ({ body, method, url }: HubRequest) => {
  const route = `${method} ${pathOf(url)}`;
  return new URLSearchParams(body).get("hub.mode") === "unsubscribe"
    ? `${route} unsubscribe`
    : route;
};

/** One hub's socket side: its options, every socket the page opened, and everything the page sent on them. */
interface SocketSide {
  options: HubOptions;
  sockets: FakeSocket[];
  fromPage: string[];
}

class FakeSocket {
  private readonly listeners = new Map<string, Listener[]>();

  closed = false;

  constructor(
    readonly url: string,
    private readonly side: SocketSide,
  ) {
    side.sockets.push(this);
    const behaviour = inTurn(side.options.socket ?? "confirms", side.sockets.length);
    const hold = inTurn(side.options.hold ?? null, side.sockets.length) ?? Promise.resolve();
    // Once the page has listened, on no timer: a fake clock stays where a test put it.
    void hold.then(() => this.behave(behaviour));
  }

  private behave(behaviour: SocketBehaviour) {
    this.emit("open", {});
    switch (behaviour) {
      case "confirms":
        this.emit("message", {
          data: JSON.stringify({
            "hub.mode": "subscribe",
            "hub.topic": "topic-1",
            ...(this.side.options.leaseSeconds === undefined
              ? {}
              : { "hub.lease_seconds": this.side.options.leaseSeconds }),
          }),
        });
        return;
      case "denies":
        this.emit("message", {
          data: JSON.stringify({ "hub.mode": "denied", "hub.reason": "invalid endpoint" }),
        });
        this.close();
        return;
      case "says-otherwise":
        for (const data of OTHER_MESSAGES) this.emit("message", { data });
        this.close();
        return;
      case "closes":
        this.close();
        return;
      case "silent":
        return;
    }
  }

  addEventListener(type: string, listener: Listener) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  removeEventListener(type: string, listener: Listener) {
    this.listeners.set(
      type,
      (this.listeners.get(type) ?? []).filter((candidate) => candidate !== listener),
    );
  }

  send(data: string) {
    this.side.fromPage.push(data);
  }

  /** A message from the hub's side. */
  deliver(data: string) {
    this.emit("message", { data });
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    this.emit("close", {});
    this.listeners.clear();
  }

  private emit(type: string, event: { data?: unknown }) {
    for (const listener of this.listeners.get(type) ?? []) listener(event);
  }
}

/** Stubs `navigator.permissions` to report the Local Network Access permission as `state`. */
function stubLocalNetworkAccess(state: PermissionState) {
  vi.stubGlobal("navigator", {
    ...navigator,
    permissions: {
      query: ({ name }: { name: string }) =>
        name === "local-network-access"
          ? Promise.resolve({ state })
          : Promise.reject(new TypeError(`${name} is not a permission name`)),
    },
  });
}

/**
 * Installs the hub for the rest of the test, the fake backend's other-origin
 * hook with it. A page suite has already installed the backend (through
 * `renderAsSession`) or installs it after: either order works.
 */
export function fakeHub(options: HubOptions = {}): FakeHub {
  const sent: HubRequest[] = [];
  const side: SocketSide = { fromPage: [], options, sockets: [] };
  const { fromPage, sockets } = side;
  const answers = routes(options);

  serveOtherOrigin((url, init) => {
    const request = recorded(url, init);
    sent.push(request);
    const route = routeOf(request);
    const n = sent.filter((earlier) => routeOf(earlier) === route).length;
    return answer(answers[route]?.(n));
  });

  vi.stubGlobal(
    "WebSocket",
    class extends FakeSocket {
      constructor(url: string) {
        super(url, side);
      }
    },
  );
  stubLocalNetworkAccess(options.localNetworkAccess ?? "prompt");

  return {
    dropSockets: () => {
      for (const socket of sockets) socket.close();
    },
    notify: (message) => {
      const data = typeof message === "string" ? message : JSON.stringify(message);
      for (const socket of sockets.filter((open) => !open.closed)) socket.deliver(data);
    },
    openSockets: () => sockets.filter((socket) => !socket.closed).length,
    received: () => [...fromPage],
    requestTo: (url) => {
      const found = sent.find((request) => request.url === url);
      if (found === undefined) throw new Error(`the hub was sent nothing at ${url}`);
      return found;
    },
    requests: () => [...sent],
    requestsTo: (url) => sent.filter((request) => request.url === url),
    sockets: () => sockets.map((socket) => socket.url),
  };
}

/**
 * The release the backend answers an Epic session that kept this hub
 * (`GET /api/auth/epic/fhircast`), for a page suite to answer it with.
 */
export const HUB_RELEASE = {
  accessToken: `smart-${crypto.randomUUID()}`,
  expiresAt: "2026-10-24T10:00:00Z",
  hubTopic: "topic-1",
  hubUrl: HUB_URL,
  scope: ["fhircast/Patient-close.read", "fhircast/Patient-open.read", "openid"],
};

/**
 * A FHIRcast STU3 event notification, as the hub sends one on the socket:
 * `event` for Patient `patientId`, on `topic` — `topic-1` by default, the
 * topic the backend releases to a page suite's session.
 */
export const patientNotification = (
  event: string,
  {
    id = "event-1",
    patientId = "patient-1",
    timestamp = "2026-10-24T02:00:05Z",
    topic = "topic-1",
  } = {},
) => ({
  event: {
    context: [{ key: "patient", resource: { id: patientId, resourceType: "Patient" } }],
    "hub.event": event,
    "hub.topic": topic,
  },
  id,
  timestamp,
});

/** The hub with no backend page around it: the fake backend installed for its hook alone. */
export function hubOnly(options: HubOptions = {}): FakeHub {
  fakeBackend();
  return fakeHub(options);
}

/** Every request `hub` is sent from now on, in order, each time it is called. */
export function requestsSince(hub: FakeHub): () => readonly HubRequest[] {
  const before = hub.requests().length;
  return () => hub.requests().slice(before);
}
