import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, onTestFinished, vi } from "vitest";

import type { Permission } from "@/auth/api";
import {
  bare,
  deferred,
  holding,
  json,
  type Answers,
  type StartingSession,
} from "@/auth/session-adapter.testHelpers";

import type { GroupRow } from "./accounts-api";
import { GroupPermissionsDialog } from "./group-permissions-dialog";
import { renderInLayout } from "@/shell/signed-in-shell.testHelpers";

// The Group Permissions editor on its own, through what its caller hands it:
// the Group, `onSave`, `onCancel` and `pending`. It reads the vocabulary
// through the gated read, so it renders inside the signed-in layout, whose one
// error line a failed read reports to. What the page then sends, and shows of
// the answer, is `accounts.test.tsx`'s.

const VOCABULARY = "GET /api/admin/permissions";

const AUDITORS: GroupRow = {
  adminGroup: false,
  displayName: "Auditors",
  id: "00000000-0000-4000-8000-0000000000a1",
  memberCount: 2,
  permissions: ["user:read"],
};

const OFFERED: Permission[] = ["audit:read", "group:read", "user:read"];

const SUPERUSER = holding(["group:read"], { superuser: true });

/** The editor for `AUDITORS`, as `session` sees it, the backend answering `answers`. */
async function renderEditor(
  answers: Answers,
  { pending = false, session = SUPERUSER }: { pending?: boolean; session?: StartingSession } = {},
) {
  const onCancel = vi.fn();
  const onSave = vi.fn();
  const rendered = await renderInLayout(
    [
      {
        element: (
          <GroupPermissionsDialog
            group={AUDITORS}
            onCancel={onCancel}
            onSave={onSave}
            pending={pending}
          />
        ),
        path: "/",
      },
    ],
    "/",
    session,
    answers,
  );
  const dialog = screen.getByRole("dialog", { name: "Permissions of Auditors" });
  return { ...rendered, dialog, onCancel, onSave, user: userEvent.setup() };
}

/** Each checkbox the editor offers, by its label, with whether it is ticked. */
const ticks = (dialog: HTMLElement) =>
  within(dialog)
    .getAllByRole("checkbox")
    .map((box) => [box.closest("label")?.textContent, box.getAttribute("aria-checked") === "true"]);

describe("GroupPermissionsDialog", () => {
  it("opens as a modal dialog titled for the Group, the layout behind it hidden", async () => {
    await renderEditor({ [VOCABULARY]: json(OFFERED) });

    expect(screen.queryByRole("button", { name: "Sign out" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { hidden: true, name: "Sign out" })).toBeInTheDocument();
  });

  it("says the Permissions are loading, with Save disabled, until the vocabulary is read", async () => {
    const { dialog } = await renderEditor({ [VOCABULARY]: deferred().answer });

    expect(within(dialog).getByText(/^Loading Permissions…$/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("offers each Permission of the vocabulary, in its order, ticked with what the Group carries", async () => {
    const { dialog } = await renderEditor({ [VOCABULARY]: json(OFFERED) });

    await within(dialog).findByRole("checkbox", { name: "user:read" });
    expect(ticks(dialog)).toEqual([
      ["audit:read", false],
      ["group:read", false],
      ["user:read", true],
    ]);
  });

  it("hands Save the chosen set in the vocabulary's order, whatever order it was ticked in", async () => {
    const { dialog, onSave, user } = await renderEditor({ [VOCABULARY]: json(OFFERED) });

    await user.click(await within(dialog).findByRole("checkbox", { name: "group:read" }));
    await user.click(within(dialog).getByRole("checkbox", { name: "audit:read" }));
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(onSave.mock.calls).toEqual([[["audit:read", "group:read", "user:read"]]]);
  });

  it("hands Save an empty set once every Permission is unticked", async () => {
    const { dialog, onSave, user } = await renderEditor({ [VOCABULARY]: json(OFFERED) });

    await user.click(await within(dialog).findByRole("checkbox", { name: "user:read" }));
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(onSave.mock.calls).toEqual([[[]]]);
  });

  it("hands Save nothing, and fails nowhere, when the form is submitted before the vocabulary is read", async () => {
    const { dialog, onSave } = await renderEditor({ [VOCABULARY]: deferred().answer });
    // React reports an error thrown by an event handler on the window rather
    // than to the caller, so the submit's outcome is read there.
    const reported: unknown[] = [];
    const report = (event: ErrorEvent) => {
      event.preventDefault();
      reported.push(event.error);
    };
    window.addEventListener("error", report);
    onTestFinished(() => window.removeEventListener("error", report));

    fireEvent.submit(within(dialog).getByRole("button", { name: "Save" }).closest("form")!);

    expect({ reported, saves: onSave.mock.calls }).toEqual({ reported: [], saves: [] });
  });

  it("says the Permissions could not be loaded, on the page's error line too, when the read fails", async () => {
    const { dialog } = await renderEditor({ [VOCABULARY]: bare(503) });

    expect(
      await within(dialog).findByText(/^The Permissions could not be loaded\.$/),
    ).toBeInTheDocument();
    // The layout's error line sits behind the modal, so Radix hides it from the
    // accessibility tree: still asserted, just not through it.
    expect((await screen.findByRole("alert", { hidden: true })).textContent).toBe(
      "Unable to load the Permissions. Please try again.",
    );
    expect(within(dialog).getByRole("button", { name: "Save" })).toBeDisabled();
  });

  it("asks for no vocabulary, and offers none, for a session not holding group:read", async () => {
    const { dialog, requests } = await renderEditor(
      { [VOCABULARY]: json(OFFERED) },
      { session: holding([], { superuser: true }) },
    );

    expect(within(dialog).getByText(/^The Permissions could not be loaded\.$/)).toBeInTheDocument();
    expect(requests()).toEqual([]);
  });

  it("hands Cancel on to the caller, and Save nothing", async () => {
    const { dialog, onCancel, onSave, user } = await renderEditor({ [VOCABULARY]: json(OFFERED) });

    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    expect({ cancels: onCancel.mock.calls.length, saves: onSave.mock.calls.length }).toEqual({
      cancels: 1,
      saves: 0,
    });
  });

  it("takes Escape as Cancel, and Save nothing", async () => {
    const { onCancel, onSave, user } = await renderEditor({ [VOCABULARY]: json(OFFERED) });

    await user.keyboard("{Escape}");

    expect({ cancels: onCancel.mock.calls.length, saves: onSave.mock.calls.length }).toEqual({
      cancels: 1,
      saves: 0,
    });
  });

  it("ignores Escape while the save is in flight", async () => {
    const { onCancel, user } = await renderEditor(
      { [VOCABULARY]: json(OFFERED) },
      { pending: true },
    );

    await user.keyboard("{Escape}");

    expect(onCancel).not.toHaveBeenCalled();
  });

  it("disables every control, and hides the close button, while the save is in flight", async () => {
    const { dialog } = await renderEditor({ [VOCABULARY]: json(OFFERED) }, { pending: true });

    await within(dialog).findByRole("checkbox", { name: "user:read" });
    expect(
      [...within(dialog).getAllByRole("checkbox"), ...within(dialog).getAllByRole("button")].map(
        (control) => (control as HTMLInputElement | HTMLButtonElement).disabled,
      ),
    ).toEqual([true, true, true, true, true]);
  });

  it("leaves every control enabled once the vocabulary is read and no save is in flight", async () => {
    const { dialog } = await renderEditor({ [VOCABULARY]: json(OFFERED) });

    await within(dialog).findByRole("checkbox", { name: "user:read" });
    expect(
      [...within(dialog).getAllByRole("checkbox"), ...within(dialog).getAllByRole("button")].map(
        (control) => (control as HTMLInputElement | HTMLButtonElement).disabled,
      ),
    ).toEqual([false, false, false, false, false, false]);
  });
});
