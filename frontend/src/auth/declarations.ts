/**
 * A gated request as data: everything {@link useGatedRead} or
 * {@link useGatedWrite} needs to send it, declared once by the API module that
 * owns the endpoint.
 *
 * A page picks a declaration and uses it twice — `holds(user,
 * declaration.permission)` decides whether a control is offered, and the hook
 * sends the request — so the path, the decoder and the Permission are paired
 * in one place and cannot drift apart on a page. A declaration is module-level
 * data, so its decoder is one stable reference without a page hoisting it.
 *
 * The Permission is the client's mirror of what the backend requires (ADR
 * 0010); the backend still decides every request on its own.
 *
 * A path is built from the call's arguments inside the API module — `A` is
 * their tuple, empty for an endpoint with a fixed path — so no page ever
 * concatenates one.
 */

import type { ApiDecoder } from "@/lib/http";

import type { Permission } from "./api";
import type { Requirement } from "./permissions";

/** How a hook is handed a declaration's arguments: omitted for an endpoint that takes none. */
export type DeclarationArgs<A extends readonly unknown[]> = A extends readonly []
  ? { args?: A }
  : { args: A };

/**
 * A gated `GET`, answering a body read by `decode`.
 *
 * `N` says whether a `404` is an ordinary answer for this endpoint — nothing
 * is there, and that is not an error — rather than a failure. It is opted into
 * with `absentOnNotFound: true`, which makes {@link useGatedRead} show the
 * `404` as `absent`; a declaration that leaves it out reads a `404` as
 * `failed`, and its pages never have an `absent` view to handle.
 */
export interface ReadDeclaration<T, A extends readonly unknown[] = [], N extends boolean = false> {
  /** The Permission the backend requires; without it the read is never sent. */
  readonly permission: Permission;
  readonly path: (...args: A) => string;
  readonly decode: ApiDecoder<T>;
  /** A `404` means nothing is there, shown as `absent`, rather than a failure. */
  readonly absentOnNotFound?: N;
}

interface WriteTarget<A extends readonly unknown[]> {
  /**
   * The Permission — or the Superuser — the backend requires; without it the
   * write is never sent.
   */
  readonly permission: Requirement;
  readonly method: "POST" | "PUT" | "DELETE";
  readonly path: (...args: A) => string;
  /** The JSON body, when the endpoint takes one; `apiFetch` adds the CSRF header itself. */
  readonly body?: (...args: A) => unknown;
}

/**
 * A gated write. One answering a body (`T` other than `void`) names the
 * decoder that reads it; one answering no content names none.
 */
export type WriteDeclaration<A extends readonly unknown[] = [], T = void> = WriteTarget<A> &
  ([T] extends [void] ? { readonly decode?: undefined } : { readonly decode: ApiDecoder<T> });
