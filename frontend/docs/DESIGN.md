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
    fontFamily: Inter
    fontSize: 12px
    fontWeight: "400"
    lineHeight: 18px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: "400"
    lineHeight: 20px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: "400"
    lineHeight: 24px
  label-md:
    fontFamily: Inter
    fontSize: 13px
    fontWeight: "600"
    lineHeight: 16px
    letterSpacing: 0.02em
  data-mono:
    fontFamily: monospace
    fontSize: 14px
    fontWeight: "500"
    lineHeight: 20px
  headline-sm:
    fontFamily: Public Sans
    fontSize: 16px
    fontWeight: "600"
    lineHeight: 24px
  headline-md:
    fontFamily: Public Sans
    fontSize: 20px
    fontWeight: "600"
    lineHeight: 28px
  headline-lg:
    fontFamily: Public Sans
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

## Typography

The library uses one named type scale rather than generic Tailwind
`text-sm`/`text-lg`: `body-sm/md/lg`, `label-md`, `data-mono`, and
`headline-sm/md/lg`. Headlines use "Public Sans" while body, label, and mono
levels stay on Inter.

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
`Expand`/`Collapse` row actions). Disabled state is `disabled` + reduced
opacity (0.3-0.6 range observed).

### Input

Search bars (header, icon-prefixed, pill or boxed depending on screen) and
plain labelled text fields in forms. A readonly variant is used for
display-only form fields.

### Select

Native `<select>` filter dropdowns (a type filter; a record switcher; a sort
dropdown).

### Badge

The single most-used primitive — status pills everywhere
(Approved/Pending/Pass/Fail/Active/Expired/Fit/Unfit/Waiver, etc.).
Correctly-tokened badges (`secondary-container`/`error-container`) and
raw-Tailwind-color badges (`bg-red-100`, `bg-amber-100`) both recur for the
same semantic states — this is real drift to be aware of, not a token gap to
fix blindly. Two shape variants recur: `rounded` box and `rounded-full` pill,
some with a leading colored dot.

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

Header row on a tinted surface-container, `divide-y` body rows, often with a
zebra-striped variant and an inline expandable detail row.

### Dialog / AlertDialog

Confirmation and detail-view modals — full-screen scrim with
`backdrop-blur-sm`, header/body/footer composition, close button.

### Tabs

An underline-active style screen/category switch. Segmented-pill filter
controls that look similar are a judgment call for whether they're true Tabs
usage versus a styled button group — check intent before mapping to this
primitive.

### Separator

Vertical header dividers and horizontal card/table-section dividers.

### Sidebar

The persistent left nav rail. Some variants add a real collapsible/toggle
interaction and a nested two-level sub-nav; others are a structurally
simpler flat list with two distinct chrome variants (plain header vs.
two-line "eyebrow" header).

### Pagination

Multiple visual shapes recur (labeled Previous/Next with a "Showing X to Y
of Z" caption; icon-only chevrons with "Page X of Y"; a hybrid of both). A
plain icon-button row with no semantic nav wrapper is a
"pagination-lookalike," not real pagination — keep those as plain Buttons,
not this primitive.

### Collapsible / Accordion

Expandable table rows and history/outcome blocks use native
`<details>/<summary>` or manually-scripted expand/collapse extensively.
Mapped to `Collapsible` where open state isn't mutually exclusive,
`Accordion` reserved for cases with real single/multiple-open semantics.

### Alert

Inline validation/status notes (icon + bold label + sentence) — e.g. a
"Final Report (Validated)" note, or a "Disagreement/Justification" warning
callout on an outcome form.
