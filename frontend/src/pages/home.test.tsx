import { act, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { bare, holding, json, STILL_CHECKING } from "@/auth/session-adapter.testHelpers";
import type { Permission } from "@/auth/api";
import { adoptLaunchLanding, clearEhrContext } from "@/auth/ehr-context";

import { Home } from "./home";
import { TAN_WEI_MING, TAN_WEI_MING_READ } from "./patient.testHelpers";
import { renderInLayout, renderSignedIn } from "./signed-in-shell.testHelpers";

/** The tab loaded at an Epic landing with `patient` in context. */
const landedFromEpicWith = (patient: string) => {
  window.history.replaceState(null, "", `/#patient=${patient}`);
  adoptLaunchLanding();
};

/** The page for a session holding exactly `permissions`, inside the signed-in layout. */
const renderHolding = (permissions: Permission[]) => renderSignedIn("/", holding(permissions), {});

/**
 * The FHIRcast card's release, answered "nothing released": an Epic session's
 * Home asks for it beside the patient (`fhircast-card.test.tsx` has the card).
 */
const NO_FHIRCAST_RELEASE = { "GET /api/auth/epic/fhircast": bare(404) };

/** The patient reads among `sent`, the FHIRcast card's release left out. */
const patientReads = (sent: readonly { method: string; path: string }[]) =>
  sent.filter(({ path }) => path.startsWith("/api/patient/"));

/** Every baseline Permission a User holds. */
const BASELINE: Permission[] = ["patient:read"];

describe("Home", () => {
  it("links a page it is handed only for a session that may open it, with no edit of its own", async () => {
    const extra = {
      homeLink: "View reports",
      path: "/reports",
      permissions: ["ops:read"] as const,
    };
    const routes = [{ element: <Home pages={[extra]} />, path: "/" }];

    const { unmount } = await renderInLayout(routes, "/", holding(["ops:read"]), {});
    expect(screen.getByRole("link", { name: "View reports" })).toHaveAttribute("href", "/reports");
    unmount();

    await renderInLayout(routes, "/", holding(["audit:read"]), {});
    expect(screen.queryByRole("link", { name: "View reports" })).not.toBeInTheDocument();
  });

  it("links no page it is handed without a link label", async () => {
    const unlabelled = { path: "/reports" };
    await renderInLayout([{ element: <Home pages={[unlabelled]} />, path: "/" }], "/", holding([]));

    expect(screen.queryAllByRole("link")).toEqual([]);
  });

  // Each page Home links to, with the Permissions its route guard opens it for
  // (none: every signed-in session), as the registry declares them today.
  it.each([
    ["Manage accounts", "/accounts", ["user:read", "group:read", "connector:read"]],
    ["View audit log", "/audit", ["audit:read"]],
    ["Change password", "/change-password", []],
  ] as const)(
    "links %s exactly for a session its route guard would open it to",
    async (label, path, qualifying) => {
      /** Where the link labelled `label` leads for a session holding `held`, or null if absent. */
      const linkFor = async (held: readonly Permission[]) => {
        const { unmount } = await renderHolding([...held]);
        const href = screen.queryByRole("link", { name: label })?.getAttribute("href") ?? null;
        unmount();
        return href;
      };
      const forEverySession = qualifying.length === 0 ? path : null;

      // A Monitoring account's Permission opens none of the guarded pages.
      expect(await linkFor([])).toBe(forEverySession);
      expect(await linkFor(["ops:read"])).toBe(forEverySession);
      for (const permission of qualifying) expect(await linkFor([permission])).toBe(path);
      expect(await linkFor([...qualifying])).toBe(path);
    },
  );

  it("lists the pages it links to in registry order, and not itself", async () => {
    await renderHolding(["user:read", "group:read", "connector:read", "audit:read"]);

    expect(screen.getAllByRole("link").map((link) => link.textContent)).toEqual([
      "Manage accounts",
      "View audit log",
      "Change password",
    ]);
  });

  it("styles a page link as an underlined text link", async () => {
    await renderHolding([]);

    expect(screen.getByRole("link", { name: "Change password" })).toHaveClass(
      "text-sm",
      "font-medium",
      "underline",
    );
  });

  it("shows no patient in context, and asks for nothing, for a session holding the baseline Permissions", async () => {
    const { requests } = await renderHolding(BASELINE);

    expect(screen.getByText("No patient in context")).toBeInTheDocument();
    expect(requests()).toEqual([]);
  });

  it("shows the patient an EHR launch put in context on the Patient card", async () => {
    landedFromEpicWith(TAN_WEI_MING.id);

    await renderSignedIn("/", holding(BASELINE), {
      [TAN_WEI_MING_READ]: json(TAN_WEI_MING),
      ...NO_FHIRCAST_RELEASE,
    });

    expect(await screen.findByText("Tan Wei Ming")).toBeInTheDocument();
  });

  it("shows no patient in context when the patient an EHR launch named is not there", async () => {
    landedFromEpicWith(TAN_WEI_MING.id);

    const { requests } = await renderSignedIn("/", holding(BASELINE), {
      [TAN_WEI_MING_READ]: bare(404),
      ...NO_FHIRCAST_RELEASE,
    });

    expect(await screen.findByText("No patient in context")).toBeInTheDocument();
    expect(patientReads(requests())).toEqual([
      { method: "GET", path: `/api/patient/${TAN_WEI_MING.id}` },
    ]);
  });

  it("asks for no patient after an EHR launch that put none in context", async () => {
    window.history.replaceState(null, "", "/#encounter=enc-1");
    adoptLaunchLanding();

    const { requests } = await renderSignedIn("/", holding(BASELINE), NO_FHIRCAST_RELEASE);

    expect(screen.getByText("No patient in context")).toBeInTheDocument();
    expect(patientReads(requests())).toEqual([]);
  });

  it("shows the FHIRcast card after an EHR launch that put no patient in context", async () => {
    window.history.replaceState(null, "", "/#launch");
    adoptLaunchLanding();

    await renderSignedIn("/", holding(BASELINE), NO_FHIRCAST_RELEASE);

    expect([
      screen.getByText("No patient in context") !== null,
      screen.getByRole("heading", { name: "FHIRcast" }) !== null,
    ]).toEqual([true, true]);
  });

  it("shows no FHIRcast card in a session no EHR launch began", async () => {
    await renderHolding(BASELINE);

    expect(screen.queryByRole("heading", { name: "FHIRcast" })).toBeNull();
  });

  it("drops the patient and the FHIRcast card as soon as the tab's EHR context is cleared", async () => {
    landedFromEpicWith(TAN_WEI_MING.id);
    await renderSignedIn("/", holding(BASELINE), {
      [TAN_WEI_MING_READ]: json(TAN_WEI_MING),
      ...NO_FHIRCAST_RELEASE,
    });
    await screen.findByText("Tan Wei Ming");

    act(() => clearEhrContext());

    expect([
      screen.queryByText("Tan Wei Ming"),
      screen.queryByRole("heading", { name: "FHIRcast" }),
    ]).toEqual([null, null]);
  });

  it("offers no control but Sign out", async () => {
    await renderHolding(BASELINE);

    expect(screen.queryAllByRole("button").map((button) => button.textContent)).toEqual([
      "Sign out",
    ]);
  });

  it("shows the Patient card above the links card, titled Pages", async () => {
    await renderHolding(BASELINE);

    expect(screen.getAllByRole("heading").map((heading) => heading.textContent)).toEqual([
      "Home",
      "Patient",
      "Pages",
    ]);
  });

  it("gives the links card no description", async () => {
    await renderHolding(BASELINE);

    expect(screen.queryByText(/Baseline is live|React \+ Vite/)).not.toBeInTheDocument();
  });

  it("renders safely while the session's user details are unavailable", async () => {
    const { requests } = await renderSignedIn("/", STILL_CHECKING, {});

    // No session details, so no Permission: nothing is asked for.
    expect(requests()).toEqual([]);
  });
});
