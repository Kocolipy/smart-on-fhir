import { Route, Routes } from "react-router-dom";

import {
  renderAsSession,
  type Answers,
  type StartingSession,
} from "@/auth/session-adapter.testHelpers";

import { SIGNED_IN_PAGES, type SignedInPage } from "./signed-in-pages";
import { SignedInShell } from "./signed-in-shell";

// A page suite renders its page where the app does: inside the signed-in
// layout, which owns the page's one error line. So the page's failures show on
// the layout's line, beside the layout's own Sign out, and no suite stands up a
// status or a line of its own.

/** A route beneath the signed-in layout: what renders, and at which path. */
export type LayoutRoute = Pick<SignedInPage, "element" | "path">;

/**
 * The signed-in layout at `path` as `session` sees it, with `routes` routed
 * beneath it. The backend answers as `answers` says (see `renderAsSession`).
 * A suite for one part of a page — a panel — routes that part alone.
 */
export function renderInLayout(
  routes: readonly LayoutRoute[],
  path: string,
  session: StartingSession,
  answers: Answers = {},
): ReturnType<typeof renderAsSession> {
  return renderAsSession(
    <Routes>
      <Route element={<SignedInShell />}>
        {routes.map(({ element, path: routePath }) => (
          <Route element={element} key={routePath} path={routePath} />
        ))}
      </Route>
    </Routes>,
    session,
    { answers, at: path },
  );
}

/**
 * The signed-in layout at `path` as `session` sees it, every signed-in page
 * routed beneath it as `App` routes them, less their Permission guards — a
 * suite states the session its page renders for. The backend answers as
 * `answers` says (see `renderAsSession`).
 */
export function renderSignedIn(
  path: string,
  session: StartingSession,
  answers: Answers = {},
): ReturnType<typeof renderAsSession> {
  return renderInLayout(SIGNED_IN_PAGES, path, session, answers);
}
