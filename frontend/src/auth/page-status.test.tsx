import { act, render, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { useStatusReporter, type StatusReporter } from "./page-status";
import { InPageStatus, shownLine } from "./page-status.testHelpers";

/** Three of the things that report into one page status. */
function useHarness() {
  const first = useStatusReporter();
  const second = useStatusReporter();
  const third = useStatusReporter();
  return { first, second, third };
}

/** The harness inside a page status whose reset key the test moves with `moveTo`. */
function renderStatus() {
  let resetKey = "/first";
  const hook = renderHook(() => useHarness(), {
    wrapper: ({ children }: { children: ReactNode }) => (
      <InPageStatus resetKey={resetKey}>{children}</InPageStatus>
    ),
  });
  return {
    ...hook,
    moveTo(next: string) {
      resetKey = next;
      hook.rerender();
    },
  };
}

/** A source that hands its reporter out, so a test can report through it after it unmounts. */
function Source({ onReporter }: { onReporter: (reporter: StatusReporter) => void }) {
  onReporter(useStatusReporter());
  return null;
}

/** Runs `render`, which must throw, with React's own report of the error silenced. */
function renderingError(attempt: () => void): unknown {
  const silence = vi.spyOn(console, "error").mockImplementation(() => undefined);
  try {
    attempt();
  } catch (error) {
    return error;
  } finally {
    silence.mockRestore();
  }
  return null;
}

describe("page status", () => {
  it("shows nothing before anything reports", () => {
    renderStatus();

    expect(shownLine()).toBeNull();
  });

  it("shows a reported failure", () => {
    const { result } = renderStatus();

    act(() => result.current.first.report("The listing failed."));

    expect(shownLine()).toBe("The listing failed.");
  });

  it("shows the latest of two failures, whoever reported the earlier one", () => {
    const { result } = renderStatus();

    act(() => result.current.second.report("The second failed first."));
    act(() => result.current.first.report("The first failed later."));

    expect(shownLine()).toBe("The first failed later.");
  });

  it("shows a source's newer failure in place of its own older one", () => {
    const { result } = renderStatus();

    act(() => result.current.first.report("The first failed."));
    act(() => result.current.second.report("The second failed."));
    act(() => result.current.first.report("The first failed again."));

    expect(shownLine()).toBe("The first failed again.");
  });

  it("lets a success withdraw only its own source's failure, so an older one still standing shows", () => {
    const { result } = renderStatus();

    act(() => result.current.first.report("The first failed."));
    act(() => result.current.second.report("The second failed."));
    act(() => result.current.second.report(null));

    expect(shownLine()).toBe("The first failed.");
  });

  it("keeps the latest failure when an older source succeeds", () => {
    const { result } = renderStatus();

    act(() => result.current.first.report("The first failed."));
    act(() => result.current.second.report("The second failed."));
    act(() => result.current.first.report(null));

    expect(shownLine()).toBe("The second failed.");
  });

  it("shows nothing once every failure has been withdrawn by its own success", () => {
    const { result } = renderStatus();

    act(() => result.current.first.report("The first failed."));
    act(() => result.current.first.report(null));

    expect(shownLine()).toBeNull();
  });

  it("withdraws every standing failure, any source's, when one supersedes them all", () => {
    const { result } = renderStatus();

    act(() => result.current.first.report("The first failed."));
    act(() => result.current.second.report("The second failed."));
    act(() => result.current.third.supersedeAll());

    expect(shownLine()).toBeNull();
  });

  it("shows a failure reported after everything was superseded", () => {
    const { result } = renderStatus();

    act(() => result.current.first.report("The first failed."));
    act(() => result.current.third.supersedeAll());
    act(() => result.current.second.report("The second failed after."));

    expect(shownLine()).toBe("The second failed after.");
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

/**
 * One line per page, held by the structure rather than by convention: the
 * status exists only inside its scope, the scope cannot be doubled, and a
 * failure does not outlive its page or its source.
 */
describe("page status scope", () => {
  it("withdraws every standing failure when its reset key changes", () => {
    const { moveTo, result } = renderStatus();
    act(() => result.current.first.report("The first page's listing failed."));

    moveTo("/second");

    expect(shownLine()).toBeNull();
  });

  it("shows a failure reported after its reset key changed", () => {
    const { moveTo, result } = renderStatus();
    moveTo("/second");

    act(() => result.current.first.report("The second page's listing failed."));

    expect(shownLine()).toBe("The second page's listing failed.");
  });

  it("refuses to be rendered inside another, so a page cannot draw a second line", () => {
    const thrown = renderingError(() =>
      render(
        <InPageStatus>
          <InPageStatus>page</InPageStatus>
        </InPageStatus>,
      ),
    );

    expect(thrown).toEqual(
      new Error("A page status already encloses this one: a page has one error line."),
    );
  });

  it("has nothing to report to outside a scope", () => {
    const thrown = renderingError(() => renderHook(() => useStatusReporter()));

    expect(thrown).toEqual(
      new Error("A page status is reported to only inside the signed-in layout's scope."),
    );
  });

  it("withdraws a source's failure once the source unmounts", () => {
    let reporter: StatusReporter | undefined;
    const keep = (given: StatusReporter) => {
      reporter = given;
    };
    const { rerender } = render(
      <InPageStatus>
        <Source onReporter={keep} />
      </InPageStatus>,
    );
    act(() => reporter?.report("The departed source failed."));

    rerender(<InPageStatus>{null}</InPageStatus>);

    expect(shownLine()).toBeNull();
  });

  it("ignores a failure a source reports after it unmounted", () => {
    let reporter: StatusReporter | undefined;
    const keep = (given: StatusReporter) => {
      reporter = given;
    };
    const { rerender } = render(
      <InPageStatus>
        <Source onReporter={keep} />
      </InPageStatus>,
    );
    rerender(<InPageStatus>{null}</InPageStatus>);

    act(() => reporter?.report("An answer that landed too late."));

    expect(shownLine()).toBeNull();
  });

  it("ignores a supersede a source asks for after it unmounted", () => {
    let staying: StatusReporter | undefined;
    let departed: StatusReporter | undefined;
    const keepStaying = (given: StatusReporter) => {
      staying = given;
    };
    const keepDeparted = (given: StatusReporter) => {
      departed = given;
    };
    const { rerender } = render(
      <InPageStatus>
        <Source onReporter={keepStaying} />
        <Source onReporter={keepDeparted} />
      </InPageStatus>,
    );
    rerender(
      <InPageStatus>
        <Source onReporter={keepStaying} />
      </InPageStatus>,
    );
    act(() => staying?.report("The staying source failed."));

    act(() => departed?.supersedeAll());

    expect(shownLine()).toBe("The staying source failed.");
  });
});
