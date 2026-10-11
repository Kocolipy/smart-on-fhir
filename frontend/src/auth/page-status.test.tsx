import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { usePageStatus, useStatusReporter } from "./page-status";

/** One page status and three of the things that report into it. */
function useHarness() {
  const status = usePageStatus();
  const first = useStatusReporter(status);
  const second = useStatusReporter(status);
  const third = useStatusReporter(status);
  return { first, second, status, third };
}

const renderStatus = () => renderHook(() => useHarness());

describe("page status", () => {
  it("shows nothing before anything reports", () => {
    const { result } = renderStatus();

    expect(result.current.status.error).toBeNull();
  });

  it("shows a reported failure", () => {
    const { result } = renderStatus();

    act(() => result.current.first.report("The listing failed."));

    expect(result.current.status.error).toBe("The listing failed.");
  });

  it("shows the latest of two failures, whoever reported the earlier one", () => {
    const { result } = renderStatus();

    act(() => result.current.second.report("The second failed first."));
    act(() => result.current.first.report("The first failed later."));

    expect(result.current.status.error).toBe("The first failed later.");
  });

  it("shows a source's newer failure in place of its own older one", () => {
    const { result } = renderStatus();

    act(() => result.current.first.report("The first failed."));
    act(() => result.current.second.report("The second failed."));
    act(() => result.current.first.report("The first failed again."));

    expect(result.current.status.error).toBe("The first failed again.");
  });

  it("lets a success withdraw only its own source's failure, so an older one still standing shows", () => {
    const { result } = renderStatus();

    act(() => result.current.first.report("The first failed."));
    act(() => result.current.second.report("The second failed."));
    act(() => result.current.second.report(null));

    expect(result.current.status.error).toBe("The first failed.");
  });

  it("keeps the latest failure when an older source succeeds", () => {
    const { result } = renderStatus();

    act(() => result.current.first.report("The first failed."));
    act(() => result.current.second.report("The second failed."));
    act(() => result.current.first.report(null));

    expect(result.current.status.error).toBe("The second failed.");
  });

  it("shows nothing once every failure has been withdrawn by its own success", () => {
    const { result } = renderStatus();

    act(() => result.current.first.report("The first failed."));
    act(() => result.current.first.report(null));

    expect(result.current.status.error).toBeNull();
  });

  it("withdraws every standing failure, any source's, when one supersedes them all", () => {
    const { result } = renderStatus();

    act(() => result.current.first.report("The first failed."));
    act(() => result.current.second.report("The second failed."));
    act(() => result.current.third.supersedeAll());

    expect(result.current.status.error).toBeNull();
  });

  it("shows a failure reported after everything was superseded", () => {
    const { result } = renderStatus();

    act(() => result.current.first.report("The first failed."));
    act(() => result.current.third.supersedeAll());
    act(() => result.current.second.report("The second failed after."));

    expect(result.current.status.error).toBe("The second failed after.");
  });

  it("gives each reporter a stable report across renders", () => {
    const { rerender, result } = renderStatus();
    const { report, supersedeAll } = result.current.first;

    act(() => result.current.second.report("A render-causing failure."));
    rerender();

    expect(result.current.first.report).toBe(report);
    expect(result.current.first.supersedeAll).toBe(supersedeAll);
  });
});
