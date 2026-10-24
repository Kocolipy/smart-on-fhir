import { vi } from "vitest";

import { fakeBackend, serveOtherOrigin } from "./fake-backend.testHelpers";

// A fake of Epic's local FHIRcast hub, the other origin the FHIRcast module
// talks to: its `.well-known` metadata, RFC 7591 registration, the
// `jwt-bearer` token endpoint, the subscription, and the websocket, which a
// test can push notifications down (`notify`) and read the page's
// acknowledgements from (`received`). It answers
// through the fake backend's other-origin hook (`serveOtherOrigin`), so it runs
// beside the backend a page suite fakes, and stubs the global `WebSocket`.
// Every request is recorded, as the hub saw it. It answers by method and path,
// whatever origin a request names: the module holds every origin to loopback
// before it sends anything.

/** The hub's URL, as a release names it. */
export const HUB_URL = "http://127.0.0.1:8103/fhircast/STU3";

export const REGISTRATION_ENDPOINT = "http://127.0.0.1:8103/dcr/register";

export const TOKEN_ENDPOINT = "http://127.0.0.1:8103/dcr/token";

export const CHANNEL_ENDPOINT = "ws://127.0.0.1:8103/ws/fhircast/endpoint-1";

export const CLIENT_ID = "client-1";

export const HUB_TOKEN = `hub-${crypto.randomUUID()}`;

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
 * word; say only other things, then close; or say nothing and stay open.
 */
export type SocketBehaviour = "confirms" | "closes" | "says-otherwise" | "silent";

export interface HubOptions {
  /** The `.well-known/fhircast-configuration` answer; the SMART one answers `404` unless named. */
  fhircastConfiguration?: HubAnswer;
  smartConfiguration?: HubAnswer;
  registration?: HubAnswer;
  token?: HubAnswer;
  subscription?: HubAnswer;
  socket?: SocketBehaviour;
  /** Holds the socket open and silent until this settles, then it behaves as `socket` says. */
  hold?: Promise<void>;
  /** The Local Network Access permission's state, as `navigator.permissions` reports it. */
  localNetworkAccess?: PermissionState;
}

export interface FakeHub {
  requests: () => readonly HubRequest[];
  /** The request sent to `url`, the first if there were several. */
  requestTo: (url: string) => HubRequest;
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
  token_endpoint: TOKEN_ENDPOINT,
});

const SUBSCRIBED = json({ "hub.channel.endpoint": CHANNEL_ENDPOINT }, 202);

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

function toResponse(answer: Exclude<HubAnswer, "unreachable">): Response {
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

/** What the hub answers: the answer given, a `404` for a path it does not serve. */
function answer(given: HubAnswer | undefined): Promise<Response> {
  if (given === "unreachable") return Promise.reject(new TypeError("Failed to fetch"));
  return Promise.resolve(toResponse(given ?? json({ error: "not_found" }, 404)));
}

/**
 * Installs the hub for the rest of the test, the fake backend's other-origin
 * hook with it. A page suite has already installed the backend (through
 * `renderAsSession`) or installs it after: either order works.
 */
export function fakeHub(options: HubOptions = {}): FakeHub {
  const sent: HubRequest[] = [];
  const sockets: FakeSocket[] = [];
  const fromPage: string[] = [];
  const hubPath = pathOf(HUB_URL);
  const answers: Record<string, HubAnswer> = {
    [`GET ${hubPath}/.well-known/fhircast-configuration`]:
      options.fhircastConfiguration ?? METADATA,
    [`GET ${hubPath}/.well-known/smart-configuration`]: options.smartConfiguration ?? json({}, 404),
    [`POST ${pathOf(REGISTRATION_ENDPOINT)}`]:
      options.registration ?? json({ client_id: CLIENT_ID, client_id_issued_at: 1 }, 201),
    [`POST ${pathOf(TOKEN_ENDPOINT)}`]:
      options.token ?? json({ access_token: HUB_TOKEN, expires_in: 3600, token_type: "Bearer" }),
    [`POST ${hubPath}`]: options.subscription ?? SUBSCRIBED,
  };

  serveOtherOrigin((url, init) => {
    const request = recorded(url, init);
    sent.push(request);
    return answer(answers[`${request.method} ${pathOf(url)}`]);
  });

  class FakeSocket {
    private readonly listeners = new Map<string, Listener[]>();

    closed = false;

    constructor(readonly url: string) {
      sockets.push(this);
      setTimeout(() => {
        void (options.hold ?? Promise.resolve()).then(() =>
          this.behave(options.socket ?? "confirms"),
        );
      }, 0);
    }

    private behave(behaviour: SocketBehaviour) {
      this.emit("open", {});
      switch (behaviour) {
        case "confirms":
          this.emit("message", {
            data: JSON.stringify({ "hub.mode": "subscribe", "hub.topic": "topic-1" }),
          });
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
      fromPage.push(data);
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

  vi.stubGlobal("WebSocket", FakeSocket);
  vi.stubGlobal("navigator", {
    ...navigator,
    permissions: {
      query: ({ name }: { name: string }) =>
        name === "local-network-access"
          ? Promise.resolve({ state: options.localNetworkAccess ?? "prompt" })
          : Promise.reject(new TypeError(`${name} is not a permission name`)),
    },
  });

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
    sockets: () => sockets.map((socket) => socket.url),
  };
}

/** The hub with no backend page around it: the fake backend installed for its hook alone. */
export function hubOnly(options: HubOptions = {}): FakeHub {
  fakeBackend();
  return fakeHub(options);
}
