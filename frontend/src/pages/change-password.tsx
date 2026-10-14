import type { FormEvent } from "react";

import { useAuth } from "@/auth/auth-context-value";
import { useStatusReporter } from "@/auth/page-status";
import { PASSWORD_LENGTH } from "@/auth/password-policy";
import { useSessionAction } from "@/auth/use-session-action";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

const MISMATCH_MESSAGE = "The new password and its confirmation do not match.";

const inputClass =
  "flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm outline-none focus-visible:ring-2 focus-visible:ring-ring";

/**
 * The self-service password change, at `/change-password`.
 *
 * Where it is offered is the route guard's decision: every authenticated
 * visitor may open it, and a session with the change-required flag is offered
 * nothing else. On success the backend ends every session of the User, and on
 * a lockout it revokes them; either way the auth state is cleared and the guard
 * returns the visitor to login, which says why. Running the change is the
 * session action's (`useSessionAction`), which shows nothing for either; the
 * page owns only what comes before and after it — the confirmation check, and
 * clearing the fields after a refusal. Both the mismatch and the action's line
 * are reported to the page's one error line, which the signed-in layout owns
 * and renders; the page draws none of its own.
 *
 * The fields are uncontrolled on purpose. A controlled input mirrors its value
 * into the DOM `value` attribute, and a password belongs in no attribute; read
 * once from the form on submit, it lives only in the input and the request.
 */
export function ChangePassword() {
  const { user } = useAuth();
  // The action's line is the outcome's own copy, which never contains either submitted value.
  const change = useSessionAction("changePassword", useStatusReporter());
  // The page's own refusal, which sends nothing; it stands until the next submission.
  const mismatch = useStatusReporter();
  const confined = user?.passwordChangeRequired === true;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Captured before the first await: React clears `currentTarget` once the
    // handler has returned, and the form is still needed to clear the fields.
    const form = event.currentTarget;
    const data = new FormData(form);
    const newPassword = String(data.get("newPassword"));
    const mismatched = newPassword !== String(data.get("confirmPassword"));
    mismatch.report(mismatched ? MISMATCH_MESSAGE : null);
    if (mismatched) {
      form.reset();
      return;
    }

    // Only a refusal clears the fields: an ended session has the guard replace the page.
    const settled = await change.run(String(data.get("currentPassword")), newPassword);
    if (settled === "stayed") form.reset();
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
          <Button className="w-full" disabled={change.pending} type="submit">
            {change.pending ? "Changing password…" : "Change password"}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
