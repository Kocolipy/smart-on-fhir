import { Badge } from "@/components/ui/badge";

/**
 * Every status a page shows as a badge: an audit event's outcome, spelled as
 * the API's `AuditOutcome` (a shared page part imports no page, so an outcome
 * added there fails typecheck where the Audit page hands it over), and whether
 * a User is active or a Deactivated User (`CONTEXT.md`).
 */
type Status = "SUCCESS" | "FAILURE" | "ACTIVE" | "DEACTIVATED";

/**
 * Each status's text and its Badge variant. A worked operation, and a User
 * that may sign in, read as success; a refused one as danger; a Deactivated
 * User as warning — a state to notice, not a fault.
 */
const STATUS_BADGES: Record<Status, { label: string; variant: "success" | "warning" | "danger" }> =
  {
    SUCCESS: { label: "Success", variant: "success" },
    FAILURE: { label: "Failure", variant: "danger" },
    ACTIVE: { label: "Active", variant: "success" },
    DEACTIVATED: { label: "Deactivated", variant: "warning" },
  };

/** A status as its badge: the status's text in its variant's soft tint. */
export function StatusBadge({ status }: { status: Status }) {
  const { label, variant } = STATUS_BADGES[status];
  return <Badge variant={variant}>{label}</Badge>;
}

/** A User's status badge, read from its SCIM `active`. */
export function UserStatusBadge({ active }: { active: boolean }) {
  return <StatusBadge status={active ? "ACTIVE" : "DEACTIVATED"} />;
}
