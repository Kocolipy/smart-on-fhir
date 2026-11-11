import { useId, useState } from "react";
import { ChevronsUpDownIcon, ListFilterIcon } from "lucide-react";

import { Badge } from "@/components/ui/badge";
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
  parseAuditFilters,
  type AuditFilters,
  type AuditOperation,
  type AuditQuery,
  type AuditRefusedField,
  type ParsedAuditFilters,
} from "./audit-api";
import { AUDIT_OPERATION_LABELS } from "./audit-operation-labels";

const OUTCOME_CHOICES: readonly { label: string; value: AuditFilters["outcome"] }[] = [
  { label: "Any", value: "ANY" },
  { label: "Success", value: "SUCCESS" },
  { label: "Failure", value: "FAILURE" },
];

/**
 * One "Clear selection" in {@link OperationPicker}'s list, shown whatever the
 * search says. `value` is its `cmdk` key, distinct per placement.
 */
function ClearSelection({ onClear, value }: { onClear: () => void; value: string }) {
  return (
    <CommandGroup forceMount>
      <CommandItem forceMount onSelect={onClear} value={value}>
        Clear selection
      </CommandItem>
    </CommandGroup>
  );
}

/**
 * The searchable multi-select over every audit operation. It lists the closed
 * set by label — searched and shown as its label, sent as the operation — and no
 * count per operation: the trail is paged on the server, so a count
 * from the rows on screen would be wrong. The list stays open across changes, so
 * several can be picked in one visit. `aria-checked` carries the selection,
 * because `cmdk` spends `aria-selected` on the row the keyboard is on.
 *
 * While anything is selected, a "Clear selection" sits both first and last in
 * the list, each set apart by a separator and shown whatever the search says.
 * `cmdk` keys an item by its `value`, so the two carry distinct values behind
 * the one label.
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
  const anySelected = selected.length > 0;
  const clear = () => onChange([]);

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          className="w-full justify-between border-dashed"
          size="sm"
          type="button"
          variant="outline"
        >
          Operation: {selected.length === 0 ? "Any" : `${selected.length} selected`}
          <ChevronsUpDownIcon aria-hidden="true" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-80 p-0">
        <Command label="Operations">
          <CommandInput placeholder="Search operations" />
          <CommandList aria-multiselectable="true">
            <CommandEmpty>No operation matches.</CommandEmpty>
            {anySelected ? (
              <>
                <ClearSelection onClear={clear} value="Clear selection (first)" />
                <CommandSeparator alwaysRender />
              </>
            ) : null}
            <CommandGroup>
              {AUDIT_OPERATIONS.map((operation) => {
                const checked = selected.includes(operation);
                const label = AUDIT_OPERATION_LABELS[operation];
                return (
                  <CommandItem
                    aria-checked={checked}
                    key={operation}
                    onSelect={() => toggle(operation)}
                    value={label}
                  >
                    <Checkbox
                      aria-hidden="true"
                      checked={checked}
                      className="pointer-events-none"
                      tabIndex={-1}
                    />
                    {label}
                  </CommandItem>
                );
              })}
            </CommandGroup>
            {anySelected ? (
              <>
                <CommandSeparator alwaysRender />
                <ClearSelection onClear={clear} value="Clear selection (last)" />
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

const ID_FILTERS = [
  { key: "actorId", label: "Actor id" },
  { key: "resourceId", label: "Resource id" },
] as const;

type TypedFilter = "actorId" | "resourceId" | "from" | "to";

/** A filter set Apply refused, and why. */
type Refusal = Extract<ParsedAuditFilters, { ok: false }>;

/**
 * How many of `filters` narrow the listing: the operations, as one, the
 * outcome, and each typed field that is set.
 */
function countSet(filters: AuditFilters): number {
  return [
    filters.operations.length > 0,
    filters.outcome !== "ANY",
    filters.actorId !== "",
    filters.resourceId !== "",
    filters.from !== "",
    filters.to !== "",
  ].filter(Boolean).length;
}

/**
 * The panel's form, mounted each time the panel opens, so it starts from the
 * filters applied and with no refusal, and an edit never applied is forgotten
 * when it closes. No control reaches a request until Apply — which Enter in a
 * typed field also asks for — and Apply first parses the whole set
 * ({@link parseAuditFilters}): a refusal is marked beside the field it is
 * about, an id field or the Occurred range, and nothing is applied. Reset
 * applies no filter.
 */
function AuditFilterForm({
  applied,
  onApply,
}: {
  applied: AuditFilters;
  onApply: (filters: AuditFilters, query: AuditQuery) => void;
}) {
  const baseId = useId();
  const [draft, setDraft] = useState(applied);
  const [refusal, setRefusal] = useState<Refusal | null>(null);
  const refusalId = (field: AuditRefusedField) => `${baseId}-${field}-refusal`;
  const refusalOf = (field: AuditRefusedField) =>
    refusal?.field === field ? refusal.message : null;
  const parseAndApply = (filters: AuditFilters) => {
    const parsed = parseAuditFilters(filters);
    if (parsed.ok) onApply(filters, parsed.query);
    else setRefusal(parsed);
  };
  /** A refusal's message beneath the field it is about, the id its field is described by. */
  const refusalNote = (field: AuditRefusedField) => {
    const message = refusalOf(field);
    return message === null ? null : (
      <p className="text-sm text-destructive" id={refusalId(field)}>
        {message}
      </p>
    );
  };
  /**
   * A typed field's input: named by a `<Label>` of its own, or by `ariaLabel`
   * inside a labelled group. A refusal of `field` marks it invalid and
   * describes it by the message shown beneath.
   */
  const typedField = (
    key: TypedFilter,
    field: AuditRefusedField,
    type: string,
    ariaLabel?: string,
  ) => {
    const refused = refusalOf(field) !== null;
    return (
      <Input
        aria-describedby={refused ? refusalId(field) : undefined}
        aria-invalid={refused}
        aria-label={ariaLabel}
        id={`${baseId}-${key}`}
        onChange={(event) => setDraft({ ...draft, [key]: event.target.value })}
        type={type}
        value={draft[key]}
      />
    );
  };
  return (
    <form
      aria-label="Audit filters"
      className="flex flex-col gap-4"
      onSubmit={(event) => {
        event.preventDefault();
        parseAndApply(draft);
      }}
    >
      <OperationPicker
        onChange={(operations) => setDraft({ ...draft, operations })}
        selected={draft.operations}
      />
      <OutcomeToggle
        onChange={(outcome) => setDraft({ ...draft, outcome })}
        value={draft.outcome}
      />
      {ID_FILTERS.map(({ key, label }) => (
        <div className="flex flex-col gap-1" key={key}>
          <Label htmlFor={`${baseId}-${key}`}>{label}</Label>
          {typedField(key, key, "text")}
          {refusalNote(key)}
        </div>
      ))}
      <div aria-labelledby={`${baseId}-occurred`} className="flex flex-col gap-1" role="group">
        <Label id={`${baseId}-occurred`}>Occurred</Label>
        <div className="flex items-center gap-2">
          {typedField("from", "occurred", "datetime-local", "From")}
          <span aria-hidden="true" className="text-muted-foreground">
            –
          </span>
          {typedField("to", "occurred", "datetime-local", "To")}
        </div>
        {refusalNote("occurred")}
      </div>
      <div className="flex justify-end gap-2">
        <Button
          onClick={() => parseAndApply(EMPTY_AUDIT_FILTERS)}
          size="sm"
          type="button"
          variant="ghost"
        >
          Reset
        </Button>
        <Button size="sm" type="submit">
          Apply
        </Button>
      </div>
    </form>
  );
}

/**
 * The Audit page's filters, behind a Filter button that says how many of the
 * filters applied narrow the listing. Nothing in the panel reaches a request
 * until Apply; an Apply or Reset that applies closes it, and a refused Apply
 * leaves it open on the field to correct.
 */
export function AuditFilterPopover({
  applied,
  onApply,
}: {
  applied: AuditFilters;
  onApply: (filters: AuditFilters, query: AuditQuery) => void;
}) {
  const [open, setOpen] = useState(false);
  const set = countSet(applied);
  return (
    <Popover onOpenChange={setOpen} open={open}>
      <PopoverTrigger asChild>
        <Button
          aria-label={set === 0 ? "Filter" : `Filter, ${set} set`}
          size="sm"
          variant="outline"
        >
          <ListFilterIcon aria-hidden="true" />
          Filter
          {set === 0 ? null : (
            <Badge aria-hidden="true" variant="secondary">
              {set}
            </Badge>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-xl max-w-screen">
        <AuditFilterForm
          applied={applied}
          onApply={(filters, query) => {
            onApply(filters, query);
            setOpen(false);
          }}
        />
      </PopoverContent>
    </Popover>
  );
}
