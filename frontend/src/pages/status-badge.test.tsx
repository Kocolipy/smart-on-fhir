import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { StatusBadge, UserStatusBadge } from "./status-badge";

/** The one badge rendered: its text and the Badge variant it was drawn with. */
const shown = () => {
  const badge = screen.getByText(/./, { selector: '[data-slot="badge"]' });
  return [badge.textContent, badge.getAttribute("data-variant")];
};

describe("StatusBadge", () => {
  it.each([
    ["SUCCESS", "Success", "success"],
    ["FAILURE", "Failure", "danger"],
    ["ACTIVE", "Active", "success"],
    ["DEACTIVATED", "Deactivated", "warning"],
  ] as const)("shows %s as %s, in the %s variant", (status, label, variant) => {
    render(<StatusBadge status={status} />);

    expect(shown()).toEqual([label, variant]);
  });
});

describe("UserStatusBadge", () => {
  it.each([
    [true, "Active", "success"],
    [false, "Deactivated", "warning"],
  ] as const)("shows active: %s as %s, in the %s variant", (active, label, variant) => {
    render(<UserStatusBadge active={active} />);

    expect(shown()).toEqual([label, variant]);
  });
});
