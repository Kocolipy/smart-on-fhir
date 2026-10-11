import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";

import { AuthProvider } from "@/auth/auth-context";
import { ADMINISTRATION_PERMISSIONS } from "@/auth/permissions";
import { GuestRoute, ProtectedRoute } from "@/auth/route-guards";
import { CREDENTIAL_CHANGE_PATH } from "@/auth/session-route";
import { ErrorBoundary } from "@/components/error-boundary";
import { Accounts } from "@/pages/accounts";
import { Audit } from "@/pages/audit";
import { ChangePassword } from "@/pages/change-password";
import { Login } from "@/pages/login";
import { Showcase } from "@/pages/showcase";
import { SignedInShell } from "@/pages/signed-in-shell";

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
 * `SignedInShell`, which owns their header, back link and Sign out; the
 * Permission-guarded pages keep their own guard inside it.
 */
export function App() {
  return (
    <ErrorBoundary>
      <BrowserRouter>
        <AuthProvider>
          <Routes>
            <Route
              path="/"
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
              <Route path="/showcase" element={<Showcase />} />
              <Route
                path="/accounts"
                element={
                  <ProtectedRoute requiredPermissions={ADMINISTRATION_PERMISSIONS}>
                    <Accounts />
                  </ProtectedRoute>
                }
              />
              <Route
                path="/audit"
                element={
                  <ProtectedRoute requiredPermissions={["audit:read"]}>
                    <Audit />
                  </ProtectedRoute>
                }
              />
              <Route path={CREDENTIAL_CHANGE_PATH} element={<ChangePassword />} />
            </Route>
            <Route path="*" element={<Navigate replace to="/" />} />
          </Routes>
        </AuthProvider>
      </BrowserRouter>
    </ErrorBoundary>
  );
}
