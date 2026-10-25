import { Link, matchPath, Outlet, useLocation } from "react-router-dom";

import { useAuth } from "@/auth/auth-context-value";
import { PageStatusScope, useStatusReporter } from "@/auth/page-status";
import { useSessionAction } from "@/auth/use-session-action";
import { useSessionFhircast } from "@/auth/use-session-fhircast";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { HOME_PAGE, SIGNED_IN_PAGES } from "@/pages/signed-in-pages";

/**
 * The layout route of every signed-in page: the header (who is signed in, the
 * page title), the page's one error line, the back link and Sign out.
 *
 * The layout owns the page status (`page-status.ts`) and renders its line, the
 * only error line a signed-in page has. The page, its panels and Sign out each
 * report to it and none draws a line of its own; the latest failure shows, and
 * the line starts empty on every path, so a failure on one page never follows
 * the user to the next.
 *
 * A Sign out that fails, or comes back CSRF-expired, leaves the session
 * standing — the auth context's `logout` resolves that outcome without ending
 * anything, so the CSRF token stays with its session (ADR-0009) — and its
 * session action reports the line that says so to the page status. A
 * `signed-out` one has already ended the session, and the guards send the user
 * to login with no Sign-in reason. The Idle sign-out is a separate path that
 * always ends it.
 *
 * The layout also starts the session's FHIRcast connection while the tab
 * holds an EHR context (`useSessionFhircast`): it outlives every page beneath
 * it, so the connection keeps running across navigation and Home remounts, and
 * the FHIRcast card on Home is only a view of it.
 */
export function SignedInShell() {
  const { pathname } = useLocation();
  useSessionFhircast();
  return (
    <PageStatusScope resetKey={pathname}>
      {(error) => <SignedInLayout error={error} pathname={pathname} />}
    </PageStatusScope>
  );
}

/** The layout inside the page status, so Sign out reports to it like the page does. */
function SignedInLayout({ error, pathname }: { error: string | null; pathname: string }) {
  const { user } = useAuth();
  const signOut = useSessionAction("logout", useStatusReporter());
  const page = SIGNED_IN_PAGES.find((entry) => matchPath(entry.path, pathname)) ?? HOME_PAGE;
  const confined = user?.passwordChangeRequired === true;

  return (
    <main
      className={cn(
        "mx-auto flex min-h-svh flex-col gap-6 p-8",
        page.width,
        page.centered && "items-center justify-center",
      )}
    >
      <div className="flex w-full items-center justify-between gap-4">
        <div>
          <p className="text-sm text-muted-foreground">Signed in as {user?.username}</p>
          <h1 className="text-3xl font-semibold tracking-tight">{page.title}</h1>
        </div>
        <Button disabled={signOut.pending} variant="outline" onClick={() => void signOut.run()}>
          {signOut.pending ? "Signing out…" : "Sign out"}
        </Button>
      </div>
      {error ? (
        <p className="w-full text-sm text-destructive" role="alert">
          {error}
        </p>
      ) : null}

      <Outlet />

      {page.back && !confined ? (
        <Link className="text-sm font-medium underline underline-offset-4" to={page.back.to}>
          {page.back.label}
        </Link>
      ) : null}
    </main>
  );
}
