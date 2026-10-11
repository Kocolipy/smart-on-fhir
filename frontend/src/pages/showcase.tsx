import { Link } from "react-router-dom";

import { useAuth } from "@/auth/auth-context-value";
import { ADMINISTRATION_PERMISSIONS, holds, holdsAny, WRITE_PERMISSIONS } from "@/auth/permissions";
import { CREDENTIAL_CHANGE_PATH } from "@/auth/session-route";
import { type ReadView, useGatedRead } from "@/auth/use-gated-read";
import { useGatedWrite } from "@/auth/use-gated-write";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { jsonDecoder, readObject } from "@/lib/decode";

/** The counter endpoints' `CountResponse`, read down to its one field. */
const decodeCount = jsonDecoder((body: unknown): number =>
  readObject(body, "CountResponse").integer("count"),
);

const LINK_CLASS = "text-sm font-medium underline underline-offset-4";

/**
 * The counter's state and the two changes to it. The current count is read
 * once, on mount, and only with `counter:read` — without it the read would
 * only be refused, so it is never asked for. A change's own refusal supersedes
 * the read's on the error line.
 */
function useCounter() {
  const reading = useGatedRead({
    decode: decodeCount,
    failureMessage: "Unable to load the counter. Please try again.",
    path: "/api/count",
    permission: "counter:read",
  });
  const write = useGatedWrite({ supersedes: [reading] });

  const updateCount = (path: string) =>
    write.run(
      { decode: decodeCount, method: "POST", path, permission: WRITE_PERMISSIONS.counter },
      {
        messages: { default: "Unable to update the counter. Please try again." },
        onOk: (data) => reading.update(() => data),
      },
    );

  return {
    error: write.error,
    increment: () => void updateCount("/api/count/increment"),
    isUpdating: write.pending,
    reset: () => void updateCount("/api/count/reset"),
    view: reading.view,
  };
}

const NOTE_CLASS = "text-sm text-muted-foreground";

/**
 * The count, once read. Nothing else ever stands in for it: while the first
 * read is in flight the page says so, and after a failed one the error line
 * is all there is — a count the page has not read is never shown.
 */
function CounterReading({ view }: { view: ReadView<number> }) {
  switch (view.kind) {
    case "not-permitted":
      return (
        <p className={NOTE_CLASS}>You are signed in. Your account has no access to the counter.</p>
      );
    case "loading":
      return <p className={NOTE_CLASS}>Loading the counter…</p>;
    case "failed":
      return null;
    case "ready":
    case "stale":
      return (
        <p className={NOTE_CLASS} data-testid="count">
          Clicked {view.data} {view.data === 1 ? "time" : "times"}
        </p>
      );
  }
}

/**
 * Increment and Reset, offered only with `counter:write` and only against a
 * count: disabled while the first read is in flight, and not offered at all
 * when it failed, since the count they would change is unknown.
 */
function CounterControls({
  canWrite,
  increment,
  isUpdating,
  reset,
  view,
}: {
  canWrite: boolean;
  increment: () => void;
  isUpdating: boolean;
  reset: () => void;
  view: ReadView<number>;
}) {
  if (!canWrite || view.kind === "not-permitted" || view.kind === "failed") return null;
  const count = view.kind === "loading" ? null : view.data;
  const disabled = isUpdating || count === null;
  return (
    <>
      <Button onClick={increment} disabled={disabled}>
        Increment
      </Button>
      <Button variant="outline" onClick={reset} disabled={disabled || count === 0}>
        Reset
      </Button>
    </>
  );
}

/**
 * The original home page, now available to authenticated users at /showcase.
 *
 * Every signed-in User lands here; what it shows follows the session's
 * Permissions. The counter is read only with `counter:read` and changed only
 * with `counter:write` — baseline Permissions every active User holds, so in
 * practice every signed-in User has the counter — and the link to the Accounts
 * page appears only for a User who may see one of its views.
 */
export function Showcase() {
  const { user } = useAuth();
  const { error, increment, isUpdating, reset, view } = useCounter();

  return (
    <Card className="w-full">
      <CardHeader>
        <CardTitle>Baseline is live</CardTitle>
        <CardDescription>React + Vite + Tailwind, with the tooling gates wired up.</CardDescription>
      </CardHeader>
      <CardContent>
        <CounterReading view={view} />
        {error ? (
          <p className="mt-2 text-sm text-destructive" role="alert">
            {error}
          </p>
        ) : null}
      </CardContent>
      <CardFooter className="gap-2">
        <CounterControls
          canWrite={holds(user, WRITE_PERMISSIONS.counter)}
          increment={increment}
          isUpdating={isUpdating}
          reset={reset}
          view={view}
        />
        <div className="ml-auto flex gap-4">
          {holdsAny(user, ADMINISTRATION_PERMISSIONS) ? (
            <Link className={LINK_CLASS} to="/accounts">
              Manage accounts
            </Link>
          ) : null}
          {holds(user, "audit:read") ? (
            <Link className={LINK_CLASS} to="/audit">
              View audit log
            </Link>
          ) : null}
          <Link className={LINK_CLASS} to={CREDENTIAL_CHANGE_PATH}>
            Change password
          </Link>
        </div>
      </CardFooter>
    </Card>
  );
}
