import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";

import { AuthProvider } from "@/auth/auth-context";
import { GuestRoute, ProtectedRoute } from "@/auth/route-guards";
import { LOGIN_PATH } from "@/auth/session-route";
import { ErrorBoundary } from "@/components/error-boundary";
import { TooltipProvider } from "@/components/ui/tooltip";
import { Login } from "@/pages/login/login";
import { SIGNED_IN_PAGES } from "@/shell/signed-in-pages";
import { SignedInShell } from "@/shell/signed-in-shell";

/**
 * The application root owns routing and the session-backed authentication state.
 *
 * Every route states what it requires of the session by its guard; the guards
 * share one transition table, so no page decides where a visitor goes — a
 * session with the change-required flag included, which that table confines to
 * the change-password route whatever path it asks for.
 *
 * `ErrorBoundary` is the outermost element, so a render error anywhere below it
 * (a page, a guard, the router or `AuthProvider`) shows a generic fallback
 * instead of a blank page. The signed-in pages are children of one layout route,
 * `SignedInShell`, which owns their header, their one error line (the page
 * status every page, panel and Sign out reports to, reset on every path), the
 * back link and Sign out. They are declared once in `SIGNED_IN_PAGES`; a page
 * that lists `permissions` keeps its own guard inside the shell.
 */
export function App() {
  return (
    <ErrorBoundary>
      <TooltipProvider>
        <BrowserRouter>
          <AuthProvider>
            <Routes>
              <Route
                path={LOGIN_PATH}
                element={
                  <GuestRoute>
                    <Login />
                  </GuestRoute>
                }
              />
              <Route
                element={
                  <ProtectedRoute>
                    <SignedInShell />
                  </ProtectedRoute>
                }
              >
                {SIGNED_IN_PAGES.map(({ element, path, permissions }) => (
                  <Route
                    key={path}
                    path={path}
                    element={
                      permissions ? (
                        <ProtectedRoute requiredPermissions={permissions}>{element}</ProtectedRoute>
                      ) : (
                        element
                      )
                    }
                  />
                ))}
              </Route>
              <Route path="*" element={<Navigate replace to="/" />} />
            </Routes>
          </AuthProvider>
        </BrowserRouter>
      </TooltipProvider>
    </ErrorBoundary>
  );
}
