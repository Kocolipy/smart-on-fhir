/**
 * Signs a user out of the SPA after a period of inactivity.
 *
 * The backend ends an idle session by itself, but nothing tells the page: an
 * unattended Accounts view would stay on screen until the next request came
 * back `401`. This component closes that gap from the SPA's side.
 *
 * - **The limit is the backend's.** It is the session's own idle timeout, read
 *   from `GET /api/auth/me` (or the login response), never a constant of ours.
 * - **Activity is user input only** — pointer, key, touch, wheel and scroll.
 *   A request does not count, so no background request or poll can keep an
 *   unattended page signed in.
 * - **Every tab shares one clock.** Input in one tab is broadcast to the others
 *   over a `BroadcastChannel`, so a background tab never signs out a user who
 *   is active in another.
 * - **A warning comes first.** Shortly before the limit an `alertdialog` offers
 *   to keep the session. Staying makes a real authenticated request, which also
 *   renews the backend's idle clock; passive input while it is open does not
 *   dismiss it, because that would leave the backend's clock running down.
 *
 * A session the backend has already ended is unaffected: its `401` still takes
 * the ordinary expiry path in `useSessionRequest`.
 */

import { useCallback, useEffect, useRef, useState } from "react";

import { Button } from "@/components/ui/button";

import { ME_PATH } from "./api";
import { useAuthState } from "./auth-context-value";
import { useSessionRequest } from "./use-session-request";

/** How long before the limit the warning opens: about a minute, per the session contract. */
const WARNING_LEAD_MS = 60_000;

/** Input this soon after the last recorded activity changes nothing, so a pointer drag costs one reset. */
const ACTIVITY_THROTTLE_MS = 1_000;

/** The channel every tab of this origin shares its activity on. */
const ACTIVITY_CHANNEL = "mb-session-activity";

/** The user input that counts as activity. Captured, so scrolling an inner element counts too. */
const ACTIVITY_EVENTS = [
  "keydown",
  "pointerdown",
  "pointermove",
  "scroll",
  "touchstart",
  "wheel",
] as const;

/**
 * The idle clock: when it runs out the session is signed out, and in the last
 * stretch before that it reports `warning`.
 *
 * `markActive` restarts it explicitly and tells the other tabs, for an action
 * that has already renewed the backend's clock.
 */
function useIdleClock(limitMs: number, onIdle: () => void) {
  const [warning, setWarning] = useState(false);
  const markActive = useRef<() => void>(() => undefined);

  useEffect(() => {
    // A session with no idle bound never idles out on the backend either.
    if (!(limitMs > 0)) return;

    const warnAtMs = limitMs - Math.min(WARNING_LEAD_MS, limitMs / 2);
    const channel =
      typeof BroadcastChannel === "undefined" ? null : new BroadcastChannel(ACTIVITY_CHANNEL);
    let lastActivity = Date.now();
    let warned = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    // Measured against the wall clock rather than counted down, so a timer a
    // backgrounded or sleeping machine fired late still lands in the right phase.
    const check = () => {
      const idleMs = Date.now() - lastActivity;
      if (idleMs >= limitMs) {
        onIdle();
        return;
      }
      warned = idleMs >= warnAtMs;
      setWarning(warned);
      timer = setTimeout(check, (warned ? limitMs : warnAtMs) - idleMs);
    };

    const restart = () => {
      clearTimeout(timer);
      lastActivity = Date.now();
      check();
    };

    const onInput = () => {
      if (warned || Date.now() - lastActivity < ACTIVITY_THROTTLE_MS) return;
      restart();
      // The message's arrival is the whole signal; it carries nothing.
      channel?.postMessage(null);
    };

    markActive.current = () => {
      restart();
      channel?.postMessage(null);
    };
    if (channel) channel.onmessage = restart;
    for (const type of ACTIVITY_EVENTS) {
      document.addEventListener(type, onInput, { capture: true, passive: true });
    }
    check();

    return () => {
      clearTimeout(timer);
      channel?.close();
      for (const type of ACTIVITY_EVENTS) {
        document.removeEventListener(type, onInput, { capture: true });
      }
    };
  }, [limitMs, onIdle]);

  return { markActive: useCallback(() => markActive.current(), []), warning };
}

export function IdleSignOut({ idleTimeoutSeconds }: { idleTimeoutSeconds: number }) {
  const { logout, signOutForInactivity } = useAuthState();
  const request = useSessionRequest();
  const onIdle = useCallback(() => void signOutForInactivity(), [signOutForInactivity]);
  const { markActive, warning } = useIdleClock(idleTimeoutSeconds * 1000, onIdle);
  const [staying, setStaying] = useState(false);

  /**
   * Keeps the session with one authenticated request, which renews the
   * backend's idle clock too. It goes through the request seam like any other,
   * so a session that turns out to have ended already is expired there, by the
   * one `401` rule. Any other answer leaves the warning up rather than claim
   * the session was kept, and the limit decides.
   */
  const stay = useCallback(async () => {
    setStaying(true);
    const result = await request(ME_PATH);
    setStaying(false);
    if (result.kind === "ok") markActive();
  }, [markActive, request]);

  if (!warning) return null;
  return (
    <IdleWarning
      onSignOut={() => void logout().catch(() => undefined)}
      onStay={() => void stay()}
      staying={staying}
    />
  );
}

function IdleWarning({
  onSignOut,
  onStay,
  staying,
}: {
  onSignOut: () => void;
  onStay: () => void;
  staying: boolean;
}) {
  const dialog = useRef<HTMLDialogElement>(null);

  // A modal dialog makes the page behind it inert and traps focus; focus moves
  // to the dialog itself, so a screen reader announces it on arrival. There is
  // no close(): the dialog leaves the DOM when the warning ends, which ends its
  // modality, and a second showModal() on an open modal (StrictMode) is a no-op.
  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    element.showModal();
    element.focus();
  }, []);

  return (
    <dialog
      aria-describedby="idle-warning-description"
      aria-labelledby="idle-warning-title"
      className="m-auto w-full max-w-sm rounded-lg border bg-card p-6 text-card-foreground shadow-lg outline-none backdrop:bg-background/80"
      onCancel={(event) => {
        // Escape means "not now", which here means staying: the dialog closes only by a choice.
        event.preventDefault();
        onStay();
      }}
      ref={dialog}
      role="alertdialog"
      tabIndex={-1}
    >
      <h2 className="text-lg font-semibold" id="idle-warning-title">
        Are you still there?
      </h2>
      <p className="mt-2 text-sm text-muted-foreground" id="idle-warning-description">
        You will be signed out soon because you have been inactive.
      </p>
      <div className="mt-6 flex justify-end gap-2">
        <Button onClick={onSignOut} type="button" variant="outline">
          Sign out
        </Button>
        <Button disabled={staying} onClick={onStay} type="button">
          {staying ? "Staying signed in…" : "Stay signed in"}
        </Button>
      </div>
    </dialog>
  );
}
