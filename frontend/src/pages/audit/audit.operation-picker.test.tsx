import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { type Answers, type StartingSession } from "@/auth/session-adapter.testHelpers";

import { AUDIT_EVENTS_PATH, AUDIT_OPERATIONS } from "./audit-api";
import {
  AUDITOR,
  EVENT_1,
  everyRead,
  answerPage,
  readPaths,
  findTable,
  openFilters,
  pickerTrigger,
  openPicker,
  pick,
  lastRead,
  unfiltered,
  apply,
  ACTOR_A,
  outcome,
} from "./audit.testHelpers";
import { renderSignedIn } from "@/shell/signed-in-shell.testHelpers";

/** The page as `session` sees it, inside the signed-in layout that renders its one error line. */
function renderAudit(answers: Answers, session: StartingSession = AUDITOR) {
  return renderSignedIn("/audit", session, answers);
}

describe("Audit", () => {
  describe("the Operation picker", () => {
    const shownOptions = () => screen.getAllByRole("option").map((option) => option.textContent);

    it("lists every operation by its label alone, with no count beside it", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await openPicker(user);

      expect(shownOptions()).toEqual([
        "Login succeeded",
        "Login failed",
        "Logged out",
        "Lockout set",
        "Lockout lifted",
        "Connector created",
        "Connector deleted",
        "Connector token issued",
        "Connector token rotated",
        "Connector token revoked",
        "User created",
        "Users listed",
        "User replaced",
        "User deleted",
        "User sessions revoked",
        "Group created",
        "Group replaced",
        "Group deleted",
        "Groups listed",
        "Users and Groups searched",
        "Recovery resource seeded",
        "Dormancy lockout",
        "Dormancy membership revocation",
        "Password change required",
        "Password changed",
        "Access denied",
        "Permission granted",
        "Permission revoked",
        "Group Permissions changed",
        "FHIRcast token released",
      ]);
    });

    it("sends each operation's own value for its label", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await openPicker(user);
      for (const option of screen.getAllByRole("option")) await user.click(option);
      await user.keyboard("{Escape}");
      await apply(user);

      await vi.waitFor(() =>
        expect(lastRead(requests)).toBe(
          `${AUDIT_EVENTS_PATH}?` +
            AUDIT_OPERATIONS.map((operation) => `operation=${operation}`).join("&") +
            "&startIndex=1&count=20",
        ),
      );
    });

    it("narrows the list as the search is typed", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await openPicker(user);
      await user.type(screen.getByRole("combobox"), "lock");

      expect(shownOptions().sort()).toEqual(["Dormancy lockout", "Lockout lifted", "Lockout set"]);
    });

    it("says so when the search matches no operation", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await openPicker(user);
      await user.type(screen.getByRole("combobox"), "zzz");

      expect(screen.getByText("No operation matches.")).toBeInTheDocument();
    });

    it("names its filter and how many are selected", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      expect(pickerTrigger()).toHaveAccessibleName("Operation: Any");
      await pick(user, "Logged out", "Lockout set");

      expect(pickerTrigger()).toHaveAccessibleName("Operation: 2 selected");
    });

    it("gives a checkbox to each operation, checked once it is selected", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await pick(user, "Logged out");

      const checkedOf = (name: string) => {
        const option = screen.getByRole("option", { name });
        return [
          option.getAttribute("aria-checked"),
          option.querySelector('[data-slot="checkbox"]')?.getAttribute("data-state"),
        ];
      };
      expect([checkedOf("Logged out"), checkedOf("Lockout set")]).toEqual([
        ["true", "checked"],
        ["false", "unchecked"],
      ]);
    });

    // The row is the control: its checkbox only shows the state, so it is no stop of its own.
    it("keeps each checkbox out of the Tab order", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await openPicker(user);

      const stops = screen
        .getAllByRole("option")
        .map((option) => option.querySelector('[data-slot="checkbox"]')?.getAttribute("tabindex"));
      expect(new Set(stops)).toEqual(new Set(["-1"]));
    });

    it("sends only what is selected when Apply is pressed", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await pick(user, "Logged out", "Lockout set", "Logged out");
      await user.keyboard("{Escape}");
      await apply(user);

      await vi.waitFor(() =>
        expect(readPaths(requests)).toEqual([
          unfiltered,
          `${AUDIT_EVENTS_PATH}?operation=LOCKOUT_SET&startIndex=1&count=20`,
        ]),
      );
    });

    const clearings = () => screen.queryAllByRole("option", { name: "Clear selection" });

    it("offers clearing first and last in the list, each set apart, once something is selected", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await pick(user, "Logged out");
      const shown = shownOptions();

      expect({
        first: shown[0],
        last: shown[shown.length - 1],
        clearings: clearings().length,
        separators: within(screen.getByRole("listbox")).getAllByRole("separator").length,
      }).toEqual({
        first: "Clear selection",
        last: "Clear selection",
        clearings: 2,
        separators: 2,
      });
    });

    it.each([
      ["first", 0],
      ["last", 1],
    ])(
      "clears every selection in one step from the %s clearing, and Apply reads every operation",
      async (_where, index) => {
        const { requests } = await renderAudit(
          everyRead(answerPage([EVENT_1], { totalResults: 1 })),
        );
        const user = userEvent.setup({ delay: null });

        await findTable();
        await pick(user, "Logged out", "Lockout set");
        await user.keyboard("{Escape}");
        await apply(user);
        await vi.waitFor(() => expect(requests()).toHaveLength(2));
        await pick(user);
        await user.click(clearings()[index]!);
        expect(pickerTrigger()).toHaveAccessibleName("Operation: Any");
        await user.keyboard("{Escape}");
        await apply(user);

        await vi.waitFor(() => expect(requests()).toHaveLength(3));
        expect(lastRead(requests)).toBe(unfiltered);
      },
    );

    it("offers no clearing with nothing selected", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await openPicker(user);

      expect(clearings()).toEqual([]);
    });

    it("offers both clearings still while a search hides every operation", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await pick(user, "Logged out");
      await user.type(screen.getByRole("combobox"), "zzz");

      expect(shownOptions()).toEqual(["Clear selection", "Clear selection"]);
    });

    it("leaves the typed field on screen as it stands through a change", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await user.type(screen.getByLabelText("Actor id"), ACTOR_A);
      await pick(user, "Logged out");

      expect(screen.getByLabelText("Actor id")).toHaveValue(ACTOR_A);
    });

    it("keeps every control as set through a refused Apply, and sends them all once it is put right", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await user.type(screen.getByLabelText("Actor id"), "not-a-uuid");
      await pick(user, "Logged out");
      await user.keyboard("{Escape}");
      await user.click(outcome("Failure"));
      await apply(user);
      await screen.findByText("Actor id must be a valid UUID.");
      await user.clear(screen.getByLabelText("Actor id"));
      await user.type(screen.getByLabelText("Actor id"), ACTOR_A);
      await apply(user);

      await vi.waitFor(() =>
        expect(readPaths(requests)).toEqual([
          unfiltered,
          `${AUDIT_EVENTS_PATH}?operation=LOGOUT&outcome=FAILURE&actorId=${ACTOR_A}&startIndex=1&count=20`,
        ]),
      );
    });

    it("works from the keyboard alone: open, search, pick, close", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      pickerTrigger().focus();
      await user.keyboard("{Enter}");
      await vi.waitFor(() => expect(screen.getByRole("combobox")).toHaveFocus());
      await user.keyboard("lockout l{Enter}");
      expect(screen.getByRole("option", { name: "Lockout lifted" })).toHaveAttribute(
        "aria-checked",
        "true",
      );
      await user.keyboard("{Escape}");

      await vi.waitFor(() => expect(pickerTrigger()).toHaveFocus());
      expect(screen.queryByRole("combobox")).toBeNull();
    });

    it("names its search and its list for a screen reader", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      expect(pickerTrigger()).toHaveAttribute("aria-haspopup", "dialog");
      await openPicker(user);

      expect(screen.getByRole("combobox")).toHaveAccessibleName("Operations");
      expect(screen.getByRole("listbox")).toHaveAttribute("aria-multiselectable", "true");
    });
  });
});
