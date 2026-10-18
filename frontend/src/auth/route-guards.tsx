import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";

import { Button } from "@/components/ui/button";

import type { Permission, SessionCheckFailure } from "./api";
import { useAuthState } from "./auth-context-value";
import { requirementOf } from "./permissions";
import { sessionCheckMessage } from "./session-action-copy";
import {
  resolveSessionRoute,
  type SessionRequirement,
  type SessionRouteState,
} from "./session-route";

/** The one pending view: every route waits for the session check the same way. */
function CheckingSession() {
  return (
    <p className="grid min-h-svh place-items-center text-muted-foreground">
      Checking your session…
    </p>
  );
}

/**
 * The one view for a session check that did not answer: a single line saying
 * so, and a way to check again under the same rules. Nothing protected
 * renders and nobody is sent to login, because the session is unknown, not
 * ended.
 */
function UncheckedSession({ failure }: { failure: SessionCheckFailure }) {
  const { retrySessionCheck } = useAuthState();
  return (
    <div className="grid min-h-svh place-items-center">
      <div className="flex flex-col items-center gap-3">
        <p className="text-sm text-destructive" role="alert">
          {sessionCheckMessage(failure)}
        </p>
        <Button onClick={retrySessionCheck} type="button" variant="outline">
          Check again
        </Button>
      </div>
    </div>
  );
}

function SessionRoute({
  children,
  requires,
}: {
  children: ReactNode;
  requires: SessionRequirement;
}) {
  const session = useAuthState();
  const location = useLocation();
  const carried = location.state as SessionRouteState | null;

  const route = resolveSessionRoute({
    passwordChangeRequired: session.user?.passwordChangeRequired === true,
    pathname: location.pathname,
    permissions: session.user?.permissions,
    requires,
    returnTo: carried?.from,
    session,
  });

  switch (route.kind) {
    case "pending":
      return <CheckingSession />;
    case "unchecked":
      return <UncheckedSession failure={route.failure} />;
    case "render":
      return children;
    case "redirect":
      return <Navigate replace state={route.state} to={route.to} />;
  }
}

/**
 * A route only an authenticated visitor may see — and, given
 * `requiredPermissions`, only one holding at least one of them.
 */
export function ProtectedRoute({
  children,
  requiredPermissions,
}: {
  children: ReactNode;
  requiredPermissions?: readonly Permission[];
}) {
  return <SessionRoute requires={requirementOf(requiredPermissions)}>{children}</SessionRoute>;
}

/** A route only a guest may see; an authenticated visitor is sent onward. */
export function GuestRoute({ children }: { children: ReactNode }) {
  return <SessionRoute requires="guest">{children}</SessionRoute>;
}
