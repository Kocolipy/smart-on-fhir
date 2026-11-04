---
type: Agent Guide
title: Domain docs
description: How to read the glossary, the domain rules and the ADRs before changing domain behaviour, how to add a term, and how a decision that changes an ADR is edited into it.
tags: [agents, domain, adr, glossary]
---

# Domain docs

One glossary, one rules folder and one ADR folder, shared by both apps.

## Before exploring, read

- **`/CONTEXT.md`** — the glossary: what each term means, and the synonyms it
  rules out.
- **`/docs/domain/`** — the behaviour behind those terms, one file per area,
  named under each glossary section and listed in its `index.md`. Read every
  file whose terms your change touches.
- **`/docs/adr/`** — the decisions, numbered. Read every ADR touching the area
  you work in. Each states its decision as it stands now; git holds its history.

## Use the glossary's vocabulary

Name a domain concept — in an issue, a proposal, a hypothesis, a test name — by
its `CONTEXT.md` term. A concept the glossary lacks is either invented language
(reconsider) or a real gap: add the term in the same change — one or two
sentences, plus `_Avoid_` for the synonyms it displaces — and its rules to its
area's file in `docs/domain/`. A new area is a new file there with OKF
frontmatter (`type: Domain Rules`, `title`, `description`), an `index.md` entry
and a `Rules:` link under its glossary section.

## Flag ADR conflicts

Surface a contradiction with an ADR explicitly:

> _Contradicts ADR 0007 (Lockout lifted only by Unlock), but worth reopening
> because…_

## Changing an ADR

An ADR always reads as the decision in force, so a change to a decision
**restates its body in place**, in the same change, with no revision date —
never as an "Amended" section, a Status changelog, or a later ADR's "revisited"
section. A rejected predecessor survives only as the reason it was rejected,
under "Alternatives considered"; issue numbers and implementation history stay
in git and the tracker.

A new decision gets a new ADR: OKF frontmatter (`type: Decision Record`,
`title`, `description`) and a `/docs/adr/index.md` entry. A decision that
replaces or belongs to another ADR is **absorbed** into it, the old file removed
and every citation repointed — docs and code comments alike — with its index
entry. Cite an ADR by number, and by section name where it is long.
