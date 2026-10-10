import { Link } from "react-router-dom";

import { useAuth } from "@/auth/auth-context-value";
import { ADMINISTRATION_PERMISSIONS, holds, holdsAny, WRITE_PERMISSIONS } from "@/auth/permissions";
import { CREDENTIAL_CHANGE_PATH } from "@/auth/session-route";
import { useGatedRead } from "@/auth/use-gated-read";
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
    count: reading.data ?? 0,
    error: write.error,
    increment: () => void updateCount("/api/count/increment"),
    isUpdating: reading.loading || write.pending,
    reset: () => void updateCount("/api/count/reset"),
  };
}

/** The count for `counter:read`; for a session without it, a note that it has none. */
function CounterReading({ canRead, count }: { canRead: boolean; count: number }) {
  if (!canRead) {
    return (
      <p className="text-sm text-muted-foreground">
        You are signed in. Your account has no access to the counter.
      </p>
    );
  }
  return (
    <p className="text-sm text-muted-foreground" data-testid="count">
      Clicked {count} {count === 1 ? "time" : "times"}
    </p>
  );
}

/** Increment and Reset, offered only with `counter:write`. */
function CounterControls({
  canWrite,
  count,
  increment,
  isUpdating,
  reset,
}: {
  canWrite: boolean;
  count: number;
  increment: () => void;
  isUpdating: boolean;
  reset: () => void;
}) {
  if (!canWrite) return null;
  return (
    <>
      <Button onClick={increment} disabled={isUpdating}>
        Increment
      </Button>
      <Button variant="outline" onClick={reset} disabled={count === 0 || isUpdating}>
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
  const { logout, user } = useAuth();
  const canRead = holds(user, "counter:read");
  const { count, error, increment, isUpdating, reset } = useCounter();

  return (
    <main className="mx-auto flex min-h-svh max-w-2xl flex-col items-center justify-center gap-6 p-8">
      <div className="flex w-full items-center justify-between gap-4">
        <div>
          <p className="text-sm text-muted-foreground">Signed in as {user?.username}</p>
          <h1 className="text-3xl font-semibold tracking-tight">Front End</h1>
        </div>
        <Button variant="outline" onClick={() => void logout()}>
          Sign out
        </Button>
      </div>

      <Card className="w-full">
        <CardHeader>
          <CardTitle>Baseline is live</CardTitle>
          <CardDescription>
            React + Vite + Tailwind, with the tooling gates wired up.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <CounterReading canRead={canRead} count={count} />
          {error ? (
            <p className="mt-2 text-sm text-destructive" role="alert">
              {error}
            </p>
          ) : null}
        </CardContent>
        <CardFooter className="gap-2">
          <CounterControls
            canWrite={holds(user, WRITE_PERMISSIONS.counter)}
            count={count}
            increment={increment}
            isUpdating={isUpdating}
            reset={reset}
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
    </main>
  );
}
