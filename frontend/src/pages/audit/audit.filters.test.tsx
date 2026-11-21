import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import { type Answers, type StartingSession } from "@/auth/session-adapter.testHelpers";

import { AUDIT_EVENTS_PATH } from "./audit-api";
import {
  AUDITOR,
  EVENT_1,
  EVENT_2,
  at,
  everyRead,
  answerPage,
  readPaths,
  table,
  findTable,
  type User,
  filterTrigger,
  openFilters,
  pickerTrigger,
  pick,
  lastRead,
  unfiltered,
  applyButton,
  apply,
  ACTOR_A,
  outcome,
} from "./audit.testHelpers";
import { renderSignedIn } from "@/shell/signed-in-shell.testHelpers";

/** The page as `session` sees it, inside the signed-in layout that renders its one error line. */
function renderAudit(answers: Answers, session: StartingSession = AUDITOR) {
  return renderSignedIn("/audit", session, answers);
}

/**
 * Enters a year-275760 value into a date filter as a browser delivers it. A
 * browser's `datetime-local` accepts years up to 275760, but jsdom sanitizes
 * any year past 9999 to `""`, so the control is read as plain text for this one
 * change — the value reaching the page is the one a real picker would send.
 */
function enterYear275760(label: "From" | "To") {
  const input = screen.getByLabelText(label);
  input.setAttribute("type", "text");
  fireEvent.change(input, { target: { value: "275760-12-31T23:59" } });
}

/** The open filter panel, or null while it is closed. */
const filterPanel = () => screen.queryByRole("form", { name: "Audit filters" });

/** Presses Reset, opening the filters first if they are closed. */
async function reset(user: User) {
  await openFilters(user);
  await user.click(screen.getByRole("button", { name: "Reset" }));
}

const RESOURCE_A = "00000000-0000-4000-8000-00000000000c";

describe("Audit", () => {
  describe("filters", () => {
    it.each(["Actor id", "Resource id"])(
      "refuses a malformed %s beside its field in the panel, and reads nothing",
      async (label) => {
        const { requests } = await renderAudit(
          everyRead(answerPage([EVENT_1], { totalResults: 1 })),
        );
        const user = userEvent.setup({ delay: null });

        await findTable();
        await openFilters(user);
        await user.type(screen.getByLabelText(label), "not-a-uuid");
        await apply(user);

        const field = screen.getByLabelText(label);
        expect(field).toHaveAccessibleDescription(`${label} must be a valid UUID.`);
        expect({
          invalid: field.getAttribute("aria-invalid"),
          sent: requests().length,
        }).toEqual({ invalid: "true", sent: 1 });
      },
    );

    it("builds the request from a resource id and a date range", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await user.type(screen.getByLabelText("Resource id"), "00000000-0000-4000-8000-000000000002");
      fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-01-01T00:00" } });
      fireEvent.change(screen.getByLabelText("To"), { target: { value: "2026-01-02T00:00" } });
      await apply(user);

      // Each bound is read as Singapore time, eight hours ahead of UTC.
      expect(readPaths(requests).slice(-1)[0]).toBe(
        `${AUDIT_EVENTS_PATH}?resourceId=00000000-0000-4000-8000-000000000002&from=2025-12-31T16%3A00%3A00.000Z&to=2026-01-01T16%3A00%3A00.000Z&startIndex=1&count=20`,
      );
    });

    it("bounds the listing at the events' shown times when they are typed into From and To", async () => {
      const { requests } = await renderAudit(
        everyRead(answerPage([EVENT_1, EVENT_2], { totalResults: 2 })),
      );
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      const [first, second] = within(table())
        .getAllByRole("rowheader")
        .map((header) => header.textContent.replace(" ", "T"));
      fireEvent.change(screen.getByLabelText("From"), { target: { value: first } });
      fireEvent.change(screen.getByLabelText("To"), { target: { value: second } });
      await apply(user);

      // From is the first event's minute, so it is included; To is the second's.
      expect(readPaths(requests).slice(-1)[0]).toBe(
        `${AUDIT_EVENTS_PATH}?from=2026-01-02T19%3A04%3A00.000Z&to=2026-01-03T10%3A11%3A00.000Z&startIndex=1&count=20`,
      );
    });

    it("refuses a year-275760 date beneath the Occurred range, and keeps the listing on screen", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      enterYear275760("To");
      await apply(user);

      const range = within(within(filterPanel()!).getByRole("group", { name: "Occurred" }));
      for (const end of ["From", "To"]) {
        const input = range.getByLabelText(end);
        expect(input).toHaveAttribute("aria-invalid", "true");
        expect(input).toHaveAccessibleDescription("To date must be a valid date.");
      }
      expect(within(table()).getByText("Login failed")).toBeInTheDocument();
    });

    it("sends no request for a refused date", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      enterYear275760("From");
      await apply(user);

      await screen.findByText("From date must be a valid date.");
      expect(requests()).toHaveLength(1);
    });

    it("reads again when the same filters are applied twice", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await user.click(outcome("Failure"));
      await apply(user);
      await apply(user);

      await vi.waitFor(() =>
        expect(readPaths(requests)).toEqual([
          unfiltered,
          `${AUDIT_EVENTS_PATH}?outcome=FAILURE&startIndex=1&count=20`,
          `${AUDIT_EVENTS_PATH}?outcome=FAILURE&startIndex=1&count=20`,
        ]),
      );
    });

    it("clears a refusal once the filters are applied valid", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      const actorId = screen.getByLabelText("Actor id");
      await user.type(actorId, "not-a-uuid");
      await apply(user);
      await screen.findByText("Actor id must be a valid UUID.");
      await user.clear(actorId);
      await apply(user);
      await openFilters(user);

      expect({
        invalid: screen.getByLabelText("Actor id").getAttribute("aria-invalid"),
        message: screen.queryByText("Actor id must be a valid UUID."),
      }).toEqual({ invalid: "false", message: null });
    });

    it("reads only the first page on an Apply from a later page", async () => {
      const { requests } = await renderAudit({
        [at(1)]: answerPage([EVENT_1], { totalResults: 51 }),
        [at(2)]: answerPage([EVENT_2], { startIndex: 2, totalResults: 51 }),
      });
      const user = userEvent.setup({ delay: null });

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));
      await screen.findByText("Showing 2 to 2 of 51 events");
      await apply(user);
      await screen.findByText("Showing 1 to 1 of 51 events");

      expect(readPaths(requests)).toEqual([
        `${AUDIT_EVENTS_PATH}?startIndex=1&count=20`,
        `${AUDIT_EVENTS_PATH}?startIndex=2&count=20`,
        `${AUDIT_EVENTS_PATH}?startIndex=1&count=20`,
      ]);
    });

    it("sends nothing until Apply, whatever is changed in the panel", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await pick(user, "Logged out");
      await user.keyboard("{Escape}");
      await user.click(outcome("Failure"));
      await user.type(screen.getByLabelText("Actor id"), ACTOR_A);
      expect(readPaths(requests)).toEqual([unfiltered]);
      await apply(user);

      await vi.waitFor(() =>
        expect(readPaths(requests)).toEqual([
          unfiltered,
          `${AUDIT_EVENTS_PATH}?operation=LOGOUT&outcome=FAILURE&actorId=${ACTOR_A}&startIndex=1&count=20`,
        ]),
      );
    });

    it("applies on Enter in a typed field", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });
      const id = "00000000-0000-4000-8000-000000000002";

      await findTable();
      await openFilters(user);
      await user.type(screen.getByLabelText("Resource id"), `${id}{Enter}`);

      await vi.waitFor(() =>
        expect(lastRead(requests)).toBe(
          `${AUDIT_EVENTS_PATH}?resourceId=${id}&startIndex=1&count=20`,
        ),
      );
    });
  });

  describe("the Outcome toggle", () => {
    it("offers Any, Success and Failure under its label, Any chosen first", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      const toggle = within(screen.getByRole("radiogroup", { name: "Outcome" }));
      expect(
        toggle
          .getAllByRole("radio")
          .map((radio) => [radio.textContent, radio.getAttribute("aria-checked")]),
      ).toEqual([
        ["Any", "true"],
        ["Success", "false"],
        ["Failure", "false"],
      ]);
    });

    it.each([
      ["Success", "SUCCESS"],
      ["Failure", "FAILURE"],
    ] as const)("sends %s on Apply", async (name, value) => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await user.click(outcome(name));
      await apply(user);

      await vi.waitFor(() =>
        expect(readPaths(requests)).toEqual([
          unfiltered,
          `${AUDIT_EVENTS_PATH}?outcome=${value}&startIndex=1&count=20`,
        ]),
      );
    });

    it("reads every outcome again when Any is applied", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await user.click(outcome("Failure"));
      await apply(user);
      await openFilters(user);
      await user.click(outcome("Any"));
      await apply(user);

      await vi.waitFor(() => expect(requests()).toHaveLength(3));
      expect(lastRead(requests)).toBe(unfiltered);
    });

    it("stays where it is when the position chosen is pressed", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await user.click(outcome("Any"));

      expect(outcome("Any")).toHaveAttribute("aria-checked", "true");
    });

    it("moves with the arrow keys and chooses with Space", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      outcome("Any").focus();
      await user.keyboard("{ArrowRight}");
      await vi.waitFor(() => expect(outcome("Success")).toHaveFocus());
      await user.keyboard(" ");

      expect(outcome("Success")).toHaveAttribute("aria-checked", "true");
    });
  });

  describe("Reset", () => {
    it("clears every control and reads the unfiltered first page", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await user.type(screen.getByLabelText("Actor id"), "00000000-0000-4000-8000-000000000001");
      await user.type(screen.getByLabelText("Resource id"), "00000000-0000-4000-8000-000000000002");
      fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-01-01T00:00" } });
      fireEvent.change(screen.getByLabelText("To"), { target: { value: "2026-01-02T00:00" } });
      await user.click(outcome("Failure"));
      await pick(user, "Logged out");
      await user.keyboard("{Escape}");
      await apply(user);
      await vi.waitFor(() => expect(requests()).toHaveLength(2));
      await reset(user);

      await vi.waitFor(() => expect(requests()).toHaveLength(3));
      expect(lastRead(requests)).toBe(unfiltered);
      await openFilters(user);
      expect({
        typed: ["Actor id", "Resource id", "From", "To"].map(
          (label) => screen.getByLabelText<HTMLInputElement>(label).value,
        ),
        operations: pickerTrigger().textContent,
        any: outcome("Any").getAttribute("aria-checked"),
      }).toEqual({ typed: ["", "", "", ""], operations: "Operation: Any", any: "true" });
    });

    it("clears a refusal, whatever was typed", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await user.type(screen.getByLabelText("Actor id"), "not-a-uuid");
      await apply(user);
      await screen.findByText("Actor id must be a valid UUID.");
      await reset(user);
      await openFilters(user);

      expect(screen.queryByText("Actor id must be a valid UUID.")).not.toBeInTheDocument();
    });

    it("returns from a later page to the first", async () => {
      await renderAudit({
        [at(1)]: answerPage([EVENT_1], { totalResults: 51 }),
        [at(2)]: answerPage([EVENT_2], { startIndex: 2, totalResults: 51 }),
      });
      const user = userEvent.setup({ delay: null });

      await findTable();
      await user.click(screen.getByRole("button", { name: "Next" }));
      await screen.findByText("Showing 2 to 2 of 51 events");
      await reset(user);

      expect(await screen.findByText("Showing 1 to 1 of 51 events")).toBeInTheDocument();
    });
  });

  describe("the filter panel", () => {
    const panel = filterPanel;

    it("is closed until Filter is pressed, beside Refresh in the card header", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      expect(panel()).toBeNull();
      expect(filterTrigger().closest('[data-slot="card-action"]')).toBe(
        screen.getByRole("button", { name: "Refresh" }).closest('[data-slot="card-action"]'),
      );
      await user.click(filterTrigger());

      expect(panel()).toBeInTheDocument();
    });

    it("is one named form, every typed field labelled", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      const form = within(screen.getByRole("form", { name: "Audit filters" }));
      expect(
        ["Actor id", "Resource id", "From", "To"].map((label) => {
          const field = form.getByLabelText(label);
          return [field.tagName, field.getAttribute("type")];
        }),
      ).toEqual([
        ["INPUT", "text"],
        ["INPUT", "text"],
        ["INPUT", "datetime-local"],
        ["INPUT", "datetime-local"],
      ]);
      expect(
        [...form.getByRole("group", { name: "Occurred" }).querySelectorAll("input")].map((input) =>
          input.getAttribute("aria-label"),
        ),
      ).toEqual(["From", "To"]);
    });

    it("opens with no field marked invalid and no message beneath any", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      const form = within(filterPanel()!);

      expect({
        fields: ["Actor id", "Resource id", "From", "To"].map((label) => {
          const field = form.getByLabelText(label);
          return [field.getAttribute("aria-invalid"), field.getAttribute("aria-describedby")];
        }),
        notes: form.queryAllByRole("paragraph"),
      }).toEqual({ fields: Array(4).fill(["false", null]), notes: [] });
    });

    it("reaches every control by Tab, in order", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      const form = within(screen.getByRole("form", { name: "Audit filters" }));
      pickerTrigger().focus();
      const expected = [
        outcome("Any"),
        form.getByLabelText("Actor id"),
        form.getByLabelText("Resource id"),
        form.getByLabelText("From"),
        form.getByLabelText("To"),
        form.getByRole("button", { name: "Reset" }),
        applyButton(),
      ];
      const reached: (Element | null)[] = [];
      while (reached.length < expected.length) {
        await user.tab();
        reached.push(document.activeElement);
      }

      expect(reached).toEqual(expected);
    });

    it("closes on an Apply that reads", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await user.type(screen.getByLabelText("Actor id"), ACTOR_A);
      await user.click(applyButton());

      expect(panel()).toBeNull();
    });

    it("stays open on a refused Apply", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await openFilters(user);
      await user.type(screen.getByLabelText("Actor id"), "not-a-uuid");
      await user.click(applyButton());
      await screen.findByText("Actor id must be a valid UUID.");

      expect(screen.getByLabelText<HTMLInputElement>("Actor id").value).toBe("not-a-uuid");
    });

    it("forgets a change never applied once it closes", async () => {
      const { requests } = await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await pick(user, "Logged out");
      await user.keyboard("{Escape}");
      await user.click(outcome("Failure"));
      await user.type(screen.getByLabelText("Actor id"), ACTOR_A);
      await user.keyboard("{Escape}");
      await openFilters(user);

      expect({
        actorId: screen.getByLabelText<HTMLInputElement>("Actor id").value,
        any: outcome("Any").getAttribute("aria-checked"),
        operations: pickerTrigger().textContent,
        sent: readPaths(requests),
      }).toEqual({ actorId: "", any: "true", operations: "Operation: Any", sent: [unfiltered] });
    });

    it("closes on Reset", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });

      await findTable();
      await reset(user);

      expect(panel()).toBeNull();
    });

    it("names and shows on its button how many filters the listing was last read with", async () => {
      await renderAudit(everyRead(answerPage([EVENT_1], { totalResults: 1 })));
      const user = userEvent.setup({ delay: null });
      const shown = () => [filterTrigger().getAttribute("aria-label"), filterTrigger().textContent];

      await findTable();
      const before = shown();
      await pick(user, "Logged out", "Lockout set");
      await user.keyboard("{Escape}");
      await user.click(outcome("Failure"));
      await user.type(screen.getByLabelText("Actor id"), ACTOR_A);
      await user.type(screen.getByLabelText("Resource id"), RESOURCE_A);
      fireEvent.change(screen.getByLabelText("From"), { target: { value: "2026-01-01T00:00" } });
      fireEvent.change(screen.getByLabelText("To"), { target: { value: "2026-01-02T00:00" } });
      const unapplied = shown();
      await apply(user);
      const applied = shown();
      await reset(user);

      expect([before, unapplied, applied, shown()]).toEqual([
        ["Filter", "Filter"],
        ["Filter", "Filter"],
        ["Filter, 6 set", "Filter6"],
        ["Filter", "Filter"],
      ]);
    });
  });
});
