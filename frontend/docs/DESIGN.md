---
name: component-library
# Every screen in this design system declares an identical borderRadius
# object, so this one scale applies across the shared ui/ primitive set.
rounded:
  DEFAULT: 0.125rem
  lg: 0.25rem
  xl: 0.5rem
  full: 0.75rem
# Colors, typography and spacing are a single unified system. There is no
# per-composite theme toggle: every composite renders against the same
# colors/typography/spacing tokens below.
colors:
  # Resolved from the single config object every screen declares
  # identically (hex-verified, no cross-screen contradiction found).
  primary: "#00327d"
  on-primary: "#ffffff"
  primary-container: "#0047ab"
  on-primary-container: "#a5bdff"
  primary-fixed: "#dae2ff"
  primary-fixed-dim: "#b1c5ff"
  secondary: "#006d43"
  secondary-container: "#75f8b3"
  on-secondary-container: "#007147"
  secondary-fixed: "#78fbb6"
  tertiary: "#31373e"
  tertiary-container: "#474e55"
  tertiary-fixed: "#dde3ec"
  on-tertiary-fixed-variant: "#41474f"
  error: "#ba1a1a"
  error-container: "#ffdad6"
  on-error-container: "#93000a"
  background: "#f9f9ff"
  on-background: "#111c2c"
  surface: "#f9f9ff"
  surface-dim: "#cfdaf1"
  surface-bright: "#f9f9ff"
  surface-variant: "#d8e3fa"
  on-surface: "#111c2c"
  on-surface-variant: "#434653"
  surface-container: "#e7eeff"
  surface-container-low: "#f0f3ff"
  surface-container-lowest: "#ffffff"
  surface-container-high: "#dee8ff"
  surface-container-highest: "#d8e3fa"
  outline: "#737784"
  outline-variant: "#c3c6d5"
  inverse-surface: "#263142"
  # Observed but NOT in the declared token set — a recurring semantic (an
  # allergy/condition badge in a couple of screens uses literal hex instead
  # of this same token). Flagged for human resolution in Manual follow-up —
  # tracked here as observed, not folded into error-container since it
  # reads as a visually distinct amber "condition flag" role, not an error
  # state.
  condition-flag-bg-observed: "#fde68a" # amber-100/200 range, literal hex, undeclared role
  condition-flag-text-observed: "#78350f" # amber-900 range, literal hex, undeclared role
typography:
  body-sm:
    fontFamily: IBM Plex Sans
    fontSize: 12px
    fontWeight: "400"
    lineHeight: 18px
  body-md:
    fontFamily: IBM Plex Sans
    fontSize: 14px
    fontWeight: "400"
    lineHeight: 20px
  body-lg:
    fontFamily: IBM Plex Sans
    fontSize: 16px
    fontWeight: "400"
    lineHeight: 24px
  label-md:
    fontFamily: IBM Plex Sans
    fontSize: 13px
    fontWeight: "600"
    lineHeight: 16px
    letterSpacing: 0.02em
  data-mono:
    fontFamily: IBM Plex Mono
    fontSize: 14px
    fontWeight: "500"
    lineHeight: 20px
  headline-sm:
    fontFamily: IBM Plex Sans
    fontSize: 16px
    fontWeight: "600"
    lineHeight: 24px
  headline-md:
    fontFamily: IBM Plex Sans
    fontSize: 20px
    fontWeight: "600"
    lineHeight: 28px
  headline-lg:
    fontFamily: IBM Plex Sans
    fontSize: 28px
    fontWeight: "700"
    lineHeight: 36px
    letterSpacing: -0.02em
spacing:
  unit: 4px
  stack-gap: 12px
  gutter: 16px
  card-padding: 20px
  margin-page: 24px
borderWidth:
  # Only tracked because batch analysis confirmed TWO real, recurring
  # steps (not a one-off): the App Sidebar's active-nav-item left/right
  # accent border is border-r-2 on most screens but border-r-4 on the
  # "eyebrow header" sidebar chrome variant.
  DEFAULT: 1px
  active-accent: 4px
---

## Brand & Style

A Material-3-derived clinical/administrative record-keeping visual language:
dense data tables, status badges, and patient/service banners rendered in a
cool, procedural palette that reads as "official system of record" rather
than consumer software. The active palette is a navy-and-mint scheme — a deep
blue (`#00327d`) primary against a bright mint secondary (`#006d43`/`#75f8b3`),
evoking a hospital/clinical-chart feel. The shape language (radius scale,
card/table chrome) is shared across every component, which is why all
primitives live in one `ui/` set.

## Colors

The token set draws from one unified palette. `primary`/`primary-container`
carry brand chrome (headers, active nav, primary buttons);
`secondary`/`secondary-container` carry positive/active status badges
(Active, Current, Fit, Validated); `error`/`error-container` mark allergy and
rejection states. `tertiary`/`tertiary-container` exist in the token set but
are used sparingly, for a narrow set of secondary status accents (an "Action
Required" badge, a pending/negative outcome tone) — reach for them only for
that accent role, not as a general-purpose color. `surface-container-*` tiers
are used for nested card/table elevation (lowest = pure white card body,
low/high = subtly tinted nested panels). `outline`/`outline-variant` are
reserved for hairline borders — never load-bearing text color anywhere in the
library.

Status badges draw on three **soft status** tokens, each a light fill under dark
text of the same hue, defined in `src/index.css`'s `:root` / `.dark` pair and
mapped in its `@theme inline` block:

| Token pair                                 | Built from                                                    | Contrast |
| ------------------------------------------ | ------------------------------------------------------------- | -------- |
| `success-soft` / `success-soft-foreground` | `secondary-container` / `on-secondary-container` (mint)       | 4.6:1    |
| `warning-soft` / `warning-soft-foreground` | `condition-flag-bg-observed` / `condition-flag-text-observed` | 7.3:1    |
| `danger-soft` / `danger-soft-foreground`   | `error-container` / `on-error-container`                      | 7.2:1    |

The amber pair adopts the observed condition-flag values as the declared
warning role.

## Typography

The library uses one named type scale rather than generic Tailwind
`text-sm`/`text-lg`: `body-sm/md/lg`, `label-md`, `data-mono`, and
`headline-sm/md/lg`. Every sans level, headlines included, is IBM Plex Sans, so
a headline differs from body text by its size and weight alone; `data-mono` is
IBM Plex Mono, so an NRIC or an id renders in the same face on every OS.

Each level is a Tailwind utility, `text-<level>` (`text-body-md`,
`text-label-md`, `text-headline-lg`), defined as a `--text-<level>` token in
`src/index.css`'s `@theme inline` block. One class sets the level's size, line
height, weight and letter spacing from the front matter above; a `leading-*`,
`font-*` or `tracking-*` beside it overrides just that property. The family is
not part of a text utility, so pair a level with its face: the headline levels
with `font-headline` (`font-headline text-headline-lg`), `data-mono` with
`font-mono`, and the body and label levels with nothing, since IBM Plex Sans is
the default. `cn` knows the levels are font sizes, so a caller's text colour
(`text-muted-foreground`) never drops one. The signed-in page title is
`headline-lg` and a card title (`CardTitle`) is `headline-sm`.

The faces are self-hosted from `public/fonts/` (latin subset), never a font
CDN, because the backend's CSP admits fonts from the app's own origin only
(`frontend/docs/BACKEND_CONTRACT.md`). The vendored weights are IBM Plex Sans
400, 500, 600 and 700 and IBM Plex Mono 400 and 500 — every weight the scale and
the components use (Plex Sans 500 is `font-medium`, on buttons and labels; Plex
Mono 500 is `data-mono`). They are licensed under the SIL Open Font License, in
`public/fonts/IBM-Plex-LICENSE.txt`. A weight
with no vendored face renders as the nearest one that is, so a new weight needs
its file in `public/fonts/` and an `@font-face` beside the others.

## Layout & Spacing

One spacing scale is in effect: `unit` (4px), `stack-gap` (12px), `gutter`
(16px), `card-padding` (20px), and `margin-page` (24px) — a simple
4px-rooted, content/card-oriented ladder.

## Elevation & Depth

The design leans on flat, bordered-card chrome rather than shadow for depth
(`border` + `rounded-*` + a surface-container tint does the separation work
instead of `shadow-md`). The two confirmed real shadow uses: `shadow-sm` on
the active state of a segmented ToggleGroup/Tabs control, and a
`backdrop-blur-sm` dialog scrim on modal overlays (`bg-slate-900/60
backdrop-blur-sm`). No shadow is applied as a resting-card default — don't
add one.

## Shapes

A very tight `DEFAULT` (0.125rem/2px) for the tightest chrome, stepping up to
`lg` (0.25rem/4px) for cards/inputs, `xl` (0.5rem/8px) for dialogs/larger
panels, and `full` (0.75rem/12px) — notably NOT a true pill (9999px) despite
the name. This reads as deliberately understated/squared-off rather than
soft — appropriate for a clinical/administrative record system. Borders are
hairline (`border`, 1px) almost everywhere; the App Sidebar is the one place
a heavier `border-r-4` accent recurs (vs. the baseline `border-r-2`), on a
specific sidebar chrome variant.

## Components

### Button

Solid primary (`bg-primary text-white`), outline (`border-primary
text-primary`), icon-only ghost (notification/settings/chevron/close icons
in headers), and a text-link-as-button style (`View Details`,
`Expand`/`Collapse` row actions), every size on `rounded-lg`. Disabled state
is `disabled` + reduced opacity (0.3-0.6 range observed).

### Input

Search bars (header, icon-prefixed, pill or boxed depending on screen) and
plain labelled text fields in forms. A readonly variant is used for
display-only form fields.

### Select

Native `<select>` filter dropdowns (a type filter; a record switcher; a sort
dropdown). A filter over a long closed set, as the Audit page's Operation filter
is, is instead a searchable multi-select: a `Popover` holding a `Command` list with
a checkbox per value, its trigger naming the filter and how many are selected.

### Badge

The single most-used primitive — status pills everywhere
(Approved/Pending/Pass/Fail/Active/Expired/Fit/Unfit/Waiver, etc.). A status
takes one of three soft variants, each drawn from its token pair under Colors
and never from a raw Tailwind colour:

- `success` — a positive state: an audit event's Success, an active User.
- `warning` — a state to notice that is no fault: a Deactivated User, which
  reads "Deactivated", never "inactive".
- `danger` — a refusal or failure: an audit event's Failure.

`src/pages/status-badge.tsx` holds the one mapping from each status the pages
show to its label and variant. Two shape variants recur: `rounded` box and
`rounded-full` pill, some with a leading colored dot.

### Avatar

Real `<img>` user-photo avatars in app headers, though inconsistently
present — a text-only name/role chip is also used instead of a real avatar
in some places. Always needs `AvatarFallback` per shadcn convention — assume
no native broken-image handling.

### Card

The base panel chrome: white/surface-container-lowest body, hairline border,
`rounded-lg`/`rounded-xl`, `p-6`/`card-padding` internal padding. Used for
every info panel, patient-banner wrapper, and nested sub-section.

### Table

Header row on a tinted surface-container (`bg-accent`, which `TableHeader`
applies and a hover leaves alone) whose column headings are `label-md`
(13px, semibold), a sortable heading's button included; header and body
cells share `px-4` so a heading sits over its column's values, and a body
cell adds `py-2.5`. `divide-y` body rows, often with a
zebra-striped variant and an inline expandable detail row. Identifiers —
UUIDs, correlation ids, routes — are set in monospace (`font-mono`, the
`data-mono` role), and timestamps in tabular figures (`tabular-nums`) so a
column of them aligns.

### Dialog / AlertDialog

Confirmation and detail-view modals — full-screen scrim with
`backdrop-blur-sm`, header/body/footer composition, close button.

### Tabs

An underline-active style screen/category switch. Segmented-pill filter
controls that look similar are a judgment call for whether they're true Tabs
usage versus a styled button group — check intent before mapping to this
primitive. A filter choosing one of a few values, as the Audit page's Outcome
filter is, is a `ToggleGroup` of `type="single"`.

### Separator

Vertical header dividers and horizontal card/table-section dividers.

### Sidebar

The persistent left nav rail. Some variants add a real collapsible/toggle
interaction and a nested two-level sub-nav; others are a structurally
simpler flat list with two distinct chrome variants (plain header vs.
two-line "eyebrow" header). The navigation sidebar's labels are set in
capitals (`uppercase`, so the accessible names keep their case). Its items
stand with no gap between them, the padding filling it — `px-4 py-2.5` on a
top-level item, `px-4 py-2` on a group's link — so each is hovered and clicked
edge to edge; a sidebar folded to its icons keeps its square `size-8` items.
Its Sign out, at the foot, is `destructive` red, hovered on a `destructive/10`
tint. Accounts and Audit logs sit in a collapsible ADMIN group: a
`Collapsible` whose button carries the group's icon and a chevron that turns
when open, with the links in a `SidebarMenuSub` beneath it, indented `ml-5` and
padded `px-3`. The group starts open; its links grow from no height and fade in
as it opens (`animate-group-open`, 180ms) and shrink and fade out as it closes
(`animate-group-close`, 150ms), at once with reduced motion. On a sidebar
folded to its icons the button opens the sidebar out to the group.

### Pagination

Every paged table shares one shape: a muted "Showing X to Y of Z <rows>"
caption on the left, and on the right a row of compact square buttons
(`size-8`, `gap-1.5`, an ellipsis the same size) — `«`, one
per page number (the first, the last, and the shown page's neighbours once
past seven, with an ellipsis for each gap), `»`. The page shown is filled
with `primary`; the rest are outlined in muted text. A listing whose rows
all fit on the first page shows no pagination at all. A
plain icon-button row with no semantic nav wrapper is a
"pagination-lookalike," not real pagination — keep those as plain Buttons,
not this primitive.

### Collapsible / Accordion

Expandable table rows and history/outcome blocks use native
`<details>/<summary>` or manually-scripted expand/collapse extensively.
Mapped to `Collapsible` where open state isn't mutually exclusive,
`Accordion` reserved for cases with real single/multiple-open semantics.

An expandable table row keeps its scannable columns in the row and moves the
rest into a detail row beneath it. The row's last cell holds a ghost icon
`Button` — a chevron that turns when open — named for the row
(`Details of Login failed at 2026-01-03 03:04`), with `aria-expanded` stating
its state and `aria-controls` naming the detail. The detail row is one cell
spanning every column, rendered while open and through its closing animation,
holding a `region` of the
same name with the fields as a `<dl>`. Each row opens on its own, and a native
button gives Enter and Space for free. The detail grows from no height and
fades in as it opens (`animate-detail-open`, 180ms) and shrinks and fades out
as it closes (`animate-detail-close`, 150ms); with reduced motion it appears
and leaves at once. The Audit page's rows are this pattern,
and also toggle on a click anywhere on the row or its detail — except a click
that ends a text selection — with the button stopping propagation so it toggles
once;
the Accounts page's Group member lists open the same way from the member
count.

### Alert

Inline validation/status notes (icon + bold label + sentence) — e.g. a
"Final Report (Validated)" note, or a "Disagreement/Justification" warning
callout on an outcome form.

### Loading & empty states

Every listing renders these through the one listing body
(`src/pages/listing-body.tsx`), so they look the same on every page:

- **Loading** — while a listing's first read is in flight, three full-width
  `Skeleton` rows (`h-9`, `gap-2`) stand where its rows will be. The skeletons
  are `aria-hidden`; the listing's loading copy ("Loading users…") sits beside
  them in a `status` region, visually hidden, so a screen reader still hears
  it.
- **A later read of a paged listing** — a move, a sort, a filter — holds the
  page last read in place, dimmed to `opacity-60` after 150ms, `aria-busy` and
  out of the pointer's reach, with the pager disabled, so the listing never
  drops back to its skeletons; its loading copy is still said in a visually
  hidden `status` region. A page with no rows is not held, and a failed read
  drops the held page. A page short of rows stands as tall as the listing's
  last full page did, so the pager stays where it was.
- **Empty** — a successful read with no rows shows a centered, muted
  `lucide-react` `Inbox` icon (`size-8`, decorative) above the listing's empty
  copy in `text-sm text-muted-foreground`, with `py-8` around both. The copy is
  the listing's own: "No connectors exist.", or a search's "No users match …",
  or, for a later page that emptied, the pager's "This page is now empty."
- **Failed or not permitted** — the body is blank. A failure is never drawn as
  empty or as a load still under way; the page's one error line says what went
  wrong. Whether a failed refresh keeps the last rows is each listing's own
  `staleRows` choice; every paged listing (`src/pages/paged-listing.tsx`) hides
  them.
- **Read again** — a listing whose read failed offers an outline `sm` "Try
  again" button, right-aligned beneath the body (beside the pager on a paged
  listing), which reads the same page, or the Connectors listing, again. The audit trail's "Refresh" is the same outline
  `sm` button, offered whatever the read came to, in the card header's top
  right (`CardAction`) in place of "Try again".

The Patient card and the Group Permissions dialog are not listings and keep
their plain loading lines.
