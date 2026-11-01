import { useId } from "react";
import { ChevronsUpDownIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from "@/components/ui/command";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";

import {
  AUDIT_OPERATIONS,
  EMPTY_AUDIT_FILTERS,
  type AuditFilters,
  type AuditOperation,
} from "./audit-api";

const OUTCOME_CHOICES: readonly { label: string; value: AuditFilters["outcome"] }[] = [
  { label: "Any", value: "ANY" },
  { label: "Success", value: "SUCCESS" },
  { label: "Failure", value: "FAILURE" },
];

/**
 * The searchable multi-select over every audit operation. It lists the closed
 * set and no count per operation: the trail is paged on the server, so a count
 * from the rows on screen would be wrong. The list stays open across changes, so
 * several can be picked in one visit. `aria-checked` carries the selection,
 * because `cmdk` spends `aria-selected` on the row the keyboard is on.
 */
function OperationPicker({
  onChange,
  selected,
}: {
  onChange: (next: readonly AuditOperation[]) => void;
  selected: readonly AuditOperation[];
}) {
  const toggle = (operation: AuditOperation) =>
    onChange(
      selected.includes(operation)
        ? selected.filter((other) => other !== operation)
        : [...selected, operation],
    );

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button className="border-dashed" size="sm" type="button" variant="outline">
          Operation: {selected.length === 0 ? "Any" : `${selected.length} selected`}
          <ChevronsUpDownIcon aria-hidden="true" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-80 p-0">
        <Command label="Operations">
          <CommandInput placeholder="Search operations" />
          <CommandList aria-multiselectable="true">
            <CommandEmpty>No operation matches.</CommandEmpty>
            <CommandGroup>
              {AUDIT_OPERATIONS.map((operation) => {
                const checked = selected.includes(operation);
                return (
                  <CommandItem
                    aria-checked={checked}
                    key={operation}
                    onSelect={() => toggle(operation)}
                    value={operation}
                  >
                    <Checkbox
                      aria-hidden="true"
                      checked={checked}
                      className="pointer-events-none"
                      tabIndex={-1}
                    />
                    {operation}
                  </CommandItem>
                );
              })}
            </CommandGroup>
            {selected.length > 0 ? (
              <>
                <CommandSeparator alwaysRender />
                <CommandGroup forceMount>
                  <CommandItem forceMount onSelect={() => onChange([])} value="Clear selection">
                    Clear selection
                  </CommandItem>
                </CommandGroup>
              </>
            ) : null}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  );
}

function OutcomeToggle({
  onChange,
  value,
}: {
  onChange: (next: AuditFilters["outcome"]) => void;
  value: AuditFilters["outcome"];
}) {
  const labelId = useId();
  return (
    <div className="flex flex-col gap-1">
      <Label id={labelId}>Outcome</Label>
      <ToggleGroup
        aria-labelledby={labelId}
        onValueChange={(next) => {
          // Pressing the position already chosen reports "": no position, and no change.
          if (next !== "") onChange(next as AuditFilters["outcome"]);
        }}
        size="sm"
        type="single"
        value={value}
        variant="outline"
      >
        {OUTCOME_CHOICES.map((choice) => (
          <ToggleGroupItem key={choice.value} value={choice.value}>
            {choice.label}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
    </div>
  );
}

const TYPED_FILTERS = [
  { key: "actorId", label: "Actor id", type: "text" },
  { key: "resourceId", label: "Resource id", type: "text" },
  { key: "from", label: "From", type: "datetime-local" },
  { key: "to", label: "To", type: "datetime-local" },
] as const;

/**
 * The filters above the Audit page's table. The Operation and Outcome controls
 * apply on change, through `onControlChange`; the typed fields wait for Apply,
 * which Enter in one of them also asks for, through `onApply`. Each hands over the
 * whole draft for checking, so a change in either control with an invalid typed
 * field reads nothing, and a valid one it leaves unsent is still there for Apply.
 */
export function AuditFilterToolbar({
  draft,
  onApply,
  onControlChange,
  onDraft,
}: {
  draft: AuditFilters;
  onApply: (filters: AuditFilters) => void;
  onControlChange: (filters: AuditFilters) => void;
  onDraft: (filters: AuditFilters) => void;
}) {
  const baseId = useId();
  return (
    <form
      aria-label="Audit filters"
      className="flex flex-wrap items-end gap-3"
      onSubmit={(event) => {
        event.preventDefault();
        onApply(draft);
      }}
    >
      <OperationPicker
        onChange={(operations) => onControlChange({ ...draft, operations })}
        selected={draft.operations}
      />
      <OutcomeToggle
        onChange={(outcome) => onControlChange({ ...draft, outcome })}
        value={draft.outcome}
      />
      {TYPED_FILTERS.map(({ key, label, type }) => (
        <div className="flex flex-col gap-1" key={key}>
          <Label htmlFor={`${baseId}-${key}`}>{label}</Label>
          <Input
            id={`${baseId}-${key}`}
            onChange={(event) => onDraft({ ...draft, [key]: event.target.value })}
            type={type}
            value={draft[key]}
          />
        </div>
      ))}
      <Button size="sm" type="submit">
        Apply
      </Button>
      <Button onClick={() => onApply(EMPTY_AUDIT_FILTERS)} size="sm" type="button" variant="ghost">
        Reset
      </Button>
    </form>
  );
}
