import { useEffect, useRef, useState, type FormEvent } from "react";

import type { Permission } from "@/auth/api";
import { useGatedRead } from "@/auth/use-gated-read";
import { Button } from "@/components/ui/button";

import { DELEGATABLE_PERMISSIONS, type GroupRow } from "./accounts-api";

/**
 * The Superuser's editor of one Group's Permissions (ADR 0015): a modal dialog
 * of one checkbox per delegatable Permission, fed by `GET /api/admin/permissions`
 * and pre-ticked with what the Group carries now. Save hands the page the
 * complete set, in the vocabulary's order; the page sends it, and the backend
 * replaces the Group's with it, the last writer winning.
 *
 * The page owns the write rather than the dialog: the dialog closes once the
 * save settles, and a source that unmounts withdraws what it reported, so a
 * refusal reported from here would leave the page's error line as it closed.
 *
 * Taking a Permission away ends the Group's members' sessions at once; adding
 * one reaches them at their next sign-in. Both are the backend's to do: the
 * dialog only says so.
 */
export function GroupPermissionsDialog({
  group,
  onCancel,
  onSave,
  pending,
}: {
  group: GroupRow;
  onCancel: () => void;
  onSave: (permissions: Permission[]) => void;
  /** Whether the save is in flight: every control disables on it. */
  pending: boolean;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const vocabulary = useGatedRead(DELEGATABLE_PERMISSIONS, {
    failureMessage: "Unable to load the Permissions. Please try again.",
  });
  const [chosen, setChosen] = useState<ReadonlySet<Permission>>(() => new Set(group.permissions));

  // Modal, as the idle warning is: the page behind is inert and focus is trapped.
  // There is no close(): the dialog leaves the DOM when the page closes it.
  useEffect(() => {
    dialog.current?.showModal();
  }, []);

  const offered = vocabulary.view.kind === "ready" ? vocabulary.view.data : null;
  const titleId = `group-permissions-${group.id}`;

  const toggle = (permission: Permission, checked: boolean) =>
    setChosen((current) => {
      const next = new Set(current);
      if (checked) next.add(permission);
      else next.delete(permission);
      return next;
    });

  const save = (event: FormEvent) => {
    event.preventDefault();
    if (offered !== null) onSave(offered.filter((permission) => chosen.has(permission)));
  };

  return (
    <dialog
      aria-labelledby={titleId}
      className="m-auto w-full max-w-md rounded-lg border bg-card p-6 text-card-foreground shadow-lg outline-none backdrop:bg-background/80"
      onCancel={(event) => {
        // Escape is Cancel, except while a save is in flight, which closes the dialog itself.
        event.preventDefault();
        if (!pending) onCancel();
      }}
      ref={dialog}
    >
      <form className="flex flex-col gap-4" onSubmit={save}>
        <h2 className="text-lg font-semibold" id={titleId}>
          Permissions of {group.displayName}
        </h2>
        <p className="text-sm text-muted-foreground">
          The Group grants these to its direct members. A Permission added reaches them at their
          next sign-in; one taken away ends their sessions at once.
        </p>
        {offered === null ? (
          <p className="text-sm text-muted-foreground">
            {vocabulary.view.kind === "loading"
              ? "Loading Permissions…"
              : "The Permissions could not be loaded."}
          </p>
        ) : (
          <fieldset className="flex flex-col gap-2">
            <legend className="sr-only">Permissions</legend>
            {offered.map((permission) => (
              <label className="flex items-center gap-2 text-sm" key={permission}>
                <input
                  checked={chosen.has(permission)}
                  disabled={pending}
                  name={permission}
                  onChange={(event) => toggle(permission, event.target.checked)}
                  type="checkbox"
                />
                {permission}
              </label>
            ))}
          </fieldset>
        )}
        <div className="flex justify-end gap-2">
          <Button disabled={pending} onClick={onCancel} type="button" variant="outline">
            Cancel
          </Button>
          <Button disabled={offered === null || pending} type="submit">
            Save
          </Button>
        </div>
      </form>
    </dialog>
  );
}
