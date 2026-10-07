import { useState, type FormEvent } from "react";
import { useLocation } from "react-router-dom";

import { useAuth } from "@/auth/auth-context-value";
import type { SessionRouteState } from "@/auth/session-route";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/** Shown when the visitor arrives here because their session expired. */
const EXPIRED_MESSAGE = "Your session ended. Please sign in again.";

/** Shown when the visitor arrives here because the SPA signed them out for inactivity. */
const INACTIVE_MESSAGE = "You were signed out because you were inactive. Please sign in again.";

/** Shown when the visitor arrives here because their own password change ended the session. */
const CHANGED_CREDENTIAL_MESSAGE = "Your password was changed. Sign in with your new password.";

/**
 * Shown when an Epic launch landed here refused (`/?signin=refused`). Neutral on
 * purpose: the backend gives the browser no reason, and this page invents none.
 */
const EPIC_REFUSED_MESSAGE = "Sign-in from Epic was refused";

/**
 * Shown when an Epic launch landed here because Epic could not be reached
 * (`/?signin=unavailable`): a timeout or an Epic server error. Distinct from a
 * refusal, because relaunching shortly may well succeed.
 */
const EPIC_UNAVAILABLE_MESSAGE = "Sign-in from Epic is temporarily unavailable. Try again shortly.";

/** The notice for the `?signin=` marker an Epic launch landed with; any other says nothing. */
function epicNoticeFor(signin: string | null): string | null {
  switch (signin) {
    case "refused":
      return EPIC_REFUSED_MESSAGE;
    case "unavailable":
      return EPIC_UNAVAILABLE_MESSAGE;
    default:
      return null;
  }
}

/** Always shown: a clinician signs in by opening the application from Epic, not here. */
const CLINICIANS_LINE = "Clinicians: open this application from Epic.";

export function Login() {
  const { login } = useAuth();
  const location = useLocation();
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  // Where to go afterwards is the guest route's decision, not this page's: it
  // reads the same return destination and redirects once the status changes.
  const carried = location.state as SessionRouteState | null;
  // An Epic launch arrives by full-page navigation, so it carries a query
  // marker rather than router state; the two never coincide.
  const notice =
    epicNoticeFor(new URLSearchParams(location.search).get("signin")) ??
    (carried?.passwordChanged === true
      ? CHANGED_CREDENTIAL_MESSAGE
      : carried?.inactive === true
        ? INACTIVE_MESSAGE
        : carried?.expired === true
          ? EXPIRED_MESSAGE
          : null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setSubmitting(true);
    const data = new FormData(event.currentTarget);
    try {
      await login(String(data.get("username")), String(data.get("password")));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Unable to sign in. Please try again.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="mx-auto grid min-h-svh max-w-md place-items-center p-8">
      <Card className="w-full">
        <CardHeader>
          <CardTitle>Welcome back</CardTitle>
          <CardDescription>Sign in to continue.</CardDescription>
        </CardHeader>
        <CardContent>
          {notice && !error ? (
            <p className="mb-4 text-sm text-muted-foreground" role="status">
              {notice}
            </p>
          ) : null}
          <form className="space-y-4" onSubmit={(event) => void handleSubmit(event)}>
            <div className="space-y-2">
              <label className="text-sm font-medium" htmlFor="username">
                Username
              </label>
              <input
                autoComplete="username"
                className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
                id="username"
                name="username"
                required
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium" htmlFor="password">
                Password
              </label>
              <input
                autoComplete="current-password"
                className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
                id="password"
                name="password"
                required
                type="password"
              />
            </div>
            {error ? (
              <p className="text-sm text-destructive" role="alert">
                {error}
              </p>
            ) : null}
            <Button className="w-full" disabled={submitting} type="submit">
              {submitting ? "Signing in…" : "Sign in"}
            </Button>
          </form>
          <p className="mt-4 text-sm text-muted-foreground">{CLINICIANS_LINE}</p>
        </CardContent>
      </Card>
    </main>
  );
}
