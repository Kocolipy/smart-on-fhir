import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { App } from "@/App";
import { adoptLaunchLanding } from "@/auth/ehr-context";
import "@/index.css";

const container = document.getElementById("root");

if (!container) {
  throw new Error("index.html is missing #root — nothing to mount into.");
}

// Before the router reads the URL: an Epic landing's fragment becomes the
// Launch context seeding the tab's EHR context, and leaves the address bar.
adoptLaunchLanding();

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
