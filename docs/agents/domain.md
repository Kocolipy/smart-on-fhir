# Domain Docs

How to consume this repo's domain documentation before exploring or changing
domain behavior, terminology, or architecture.

## Before exploring, read these

- **`/CONTEXT.md`** — the glossary: what each domain term means in a sentence or
  two, and the synonyms it rules out.
- **`/docs/domain/`** — the behavior behind those terms and the reasoning
  behind it, under the same names, one file per area. Each glossary section
  names its file, and `/docs/domain/index.md` lists them all. Read every file
  whose terms your change touches.
- **`/docs/adr/`** — the architectural decisions, numbered. Read every ADR that
  touches the area you are about to work in. An ADR is a record of its date: a
  later addendum or ADR can refine it, and a class it names may since have moved.

This is a single-context repo: one `CONTEXT.md`, one `docs/adr/`, shared by both
apps.

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal,
a hypothesis, a test name), use the term as defined in `CONTEXT.md`, not a
synonym the glossary avoids.

If the concept you need is not in the glossary yet, either you are inventing
language the project does not use (reconsider) or there is a real gap: add the
term to `CONTEXT.md` — one or two sentences of what it is, plus `_Avoid_` for the
synonyms it displaces — and any rule it carries to its area's file in
`docs/domain/`, in the same change that introduces it. A new area is a new file
there: OKF frontmatter (`type: Domain Rules`, `title`, `description`), an entry in
`index.md`, and a `Rules:` link under its glossary section.

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than
silently overriding it:

> _Contradicts ADR-0007 (permanent lockout until Admin Unlock), but worth
> reopening because…_

A decision that changes a recorded one gets a new ADR, or an addendum on the
existing one, in the same change.
