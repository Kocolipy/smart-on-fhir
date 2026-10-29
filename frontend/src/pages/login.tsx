import type { FormEvent } from "react";
import { useLocation } from "react-router-dom";

import { signInNoticeFor } from "@/auth/sign-in-reason";
import { useSessionAction } from "@/auth/use-session-action";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/** Always shown: a clinician signs in by opening the application from Epic, not here. */
const CLINICIANS_LINE = "Clinicians: open this application from Epic.";

export function Login() {
  const location = useLocation();
  // Signed in, the guest route moves on; anything else is the line it hands back.
  const { error, pending, run } = useSessionAction("login");

  // Why the Guest is here is the sign-in reason's to say, and where to go
  // afterwards is the guest route's: it reads the return destination and
  // redirects once the status changes.
  const notice = signInNoticeFor(location);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    void run(String(data.get("username")), String(data.get("password")));
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
          <form className="space-y-4" onSubmit={handleSubmit}>
            <div className="space-y-2">
              <Label htmlFor="username">Username</Label>
              <Input autoComplete="username" id="username" name="username" required />
            </div>
            <div className="space-y-2">
              <Label htmlFor="password">Password</Label>
              <Input
                autoComplete="current-password"
                id="password"
                name="password"
                required
                type="password"
              />
            </div>
            {error ? (
              <Alert variant="destructive">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            ) : null}
            <Button className="w-full" disabled={pending} type="submit">
              {pending ? "Signing in…" : "Sign in"}
            </Button>
          </form>
          <p className="mt-4 text-sm text-muted-foreground">{CLINICIANS_LINE}</p>
        </CardContent>
      </Card>
    </main>
  );
}
