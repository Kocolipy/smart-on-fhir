import { useState } from "react";
import { Link, matchPath, Outlet, useLocation } from "react-router-dom";

import { SIGN_OUT_FAILED_MESSAGE } from "@/auth/api";
import { useAuth } from "@/auth/auth-context-value";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { SHOWCASE_PAGE, SIGNED_IN_PAGES } from "@/pages/signed-in-pages";

/**
 * The layout route of every signed-in page: the header (who is signed in, the
 * page title), the back link and Sign out.
 *
 * A Sign out that fails, or comes back CSRF-expired, leaves the session
 * standing — the auth context's `logout` throws before it ends anything, so the
 * CSRF token stays with its session (ADR-0009) — and the shell says so. A
 * successful one ends the session and the guards send the user to login with no
 * Sign-in reason. The Idle sign-out is a separate path that always ends it.
 */
export function SignedInShell() {
  const { logout, user } = useAuth();
  const { pathname } = useLocation();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const page = SIGNED_IN_PAGES.find((entry) => matchPath(entry.path, pathname)) ?? SHOWCASE_PAGE;
  const confined = user?.passwordChangeRequired === true;

  async function signOut() {
    setPending(true);
    setError("");
    try {
      await logout();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : SIGN_OUT_FAILED_MESSAGE);
      setPending(false);
    }
  }

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
        <Button disabled={pending} variant="outline" onClick={() => void signOut()}>
          {pending ? "Signing out…" : "Sign out"}
        </Button>
      </div>
      {error ? (
        <p className="w-full text-sm text-destructive" role="alert">
          {error} You are still signed in.
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
