import { useState, type FormEvent } from "react";

import type { PasswordChangeOutcome } from "@/auth/api";
import { useAuth } from "@/auth/auth-context-value";
import { PASSWORD_LENGTH } from "@/auth/password-policy";
import { sessionEndReasonFor } from "@/auth/sign-in-reason";
import { CSRF_EXPIRED_MESSAGE, FORBIDDEN_MESSAGE } from "@/auth/use-session-request";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

const MISMATCH_MESSAGE = "The new password and its confirmation do not match.";

/**
 * The outcomes that end the session, which the login page explains instead.
 * Which those are is `sessionEndReasonFor`'s rule; this type only narrows.
 */
type SessionEnding = Extract<PasswordChangeOutcome, { kind: "changed" | "locked" }>;

const endsSession = (outcome: PasswordChangeOutcome): outcome is SessionEnding =>
  sessionEndReasonFor(outcome) !== null;

/**
 * What a refused change means to the User, while the session stands. None of
 * these contain either submitted value: a policy refusal shows the backend's
 * statement of the rule, which names the rule and never the password.
 */
function refusalMessage(outcome: Exclude<PasswordChangeOutcome, SessionEnding>): string {
  switch (outcome.kind) {
    case "policy-violation":
      return outcome.message;
    case "current-password-rejected":
      return "The current password is incorrect.";
    case "forbidden":
      return FORBIDDEN_MESSAGE;
    case "csrf-expired":
      return CSRF_EXPIRED_MESSAGE;
    case "failed":
      return "Unable to change the password. Please try again.";
  }
}

const inputClass =
  "flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring";

/**
 * The self-service password change, at `/change-password`.
 *
 * Where it is offered is the route guard's decision: every authenticated
 * visitor may open it, and a session with the change-required flag is offered
 * nothing else. On success the backend ends every session of the User, and on
 * a lockout it revokes them; either way the auth state is cleared and the guard
 * returns the visitor to login, which says why.
 *
 * The fields are uncontrolled on purpose. A controlled input mirrors its value
 * into the DOM `value` attribute, and a password belongs in no attribute; read
 * once from the form on submit, it lives only in the input and the request.
 */
export function ChangePassword() {
  const { changePassword, user } = useAuth();
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const confined = user?.passwordChangeRequired === true;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Captured before the first await: React clears `currentTarget` once the
    // handler has returned, and the form is still needed to clear the fields.
    const form = event.currentTarget;
    const data = new FormData(form);
    const currentPassword = String(data.get("currentPassword"));
    const newPassword = String(data.get("newPassword"));
    setError("");

    if (newPassword !== String(data.get("confirmPassword"))) {
      form.reset();
      setError(MISMATCH_MESSAGE);
      return;
    }

    setSubmitting(true);
    try {
      const outcome = await changePassword(currentPassword, newPassword);
      // A change or a lockout ended the session, and the guard has already
      // moved on to login, which says which; there is nothing to show here.
      if (endsSession(outcome)) return;
      form.reset();
      setError(refusalMessage(outcome));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Card className="w-full">
      <CardHeader>
        <CardTitle>Change your password</CardTitle>
        <CardDescription>
          {confined
            ? "Your password must be replaced before you can continue."
            : "Choose a new password for your account."}{" "}
          Every session you hold ends when it changes, so you will sign in again.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form className="space-y-4" onSubmit={(event) => void handleSubmit(event)}>
          <div className="space-y-2">
            <label className="text-sm font-medium" htmlFor="currentPassword">
              Current password
            </label>
            <input
              autoComplete="current-password"
              className={inputClass}
              id="currentPassword"
              name="currentPassword"
              required
              type="password"
            />
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium" htmlFor="newPassword">
              New password
            </label>
            <input
              aria-describedby="newPasswordRequirements"
              autoComplete="new-password"
              className={inputClass}
              id="newPassword"
              maxLength={PASSWORD_LENGTH.max}
              minLength={PASSWORD_LENGTH.min}
              name="newPassword"
              required
              type="password"
            />
            {/* The backend's rules, stated up front; it remains the authority. */}
            <ul
              className="list-disc space-y-1 pl-5 text-sm text-muted-foreground"
              id="newPasswordRequirements"
            >
              <li>
                {PASSWORD_LENGTH.min} to {PASSWORD_LENGTH.max} characters long
              </li>
              <li>Must not contain your user name</li>
              <li>Must not reuse your current or recent passwords</li>
            </ul>
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium" htmlFor="confirmPassword">
              Confirm new password
            </label>
            <input
              autoComplete="new-password"
              className={inputClass}
              id="confirmPassword"
              maxLength={PASSWORD_LENGTH.max}
              minLength={PASSWORD_LENGTH.min}
              name="confirmPassword"
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
            {submitting ? "Changing password…" : "Change password"}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
