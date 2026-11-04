---
type: Agent Guide
title: Issue tracker
description: Creating, reading, triaging, labelling, commenting on and closing GitHub issues and pull requests with the gh CLI.
tags: [agents, github, issues]
---

# Issue tracker: GitHub

Issues and specs live as GitHub issues in this one repository, driven with the
`gh` CLI from inside the clone (it infers the repository).

- **Read** an issue with `gh issue view <number> --comments`, a PR with
  `gh pr view <number> --comments` plus `gh pr diff <number>`. A bare `#42` may
  be either — issues and PRs share one number space — so try `gh pr view` and
  fall back to `gh issue view`.
- **List** with bodies, labels and comments in one call:
  `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`,
  adding `--label` as needed.
- **Write** multi-line bodies through a heredoc.

## Labels

`gh label list` is the authority. Beside GitHub's defaults and `accessibility`,
the one workflow label is **`ready-for-agent`**: a verified, dependency-ordered
ticket an agent may implement without further triage.

## Skill vocabulary

"Publish to the issue tracker" means create a GitHub issue; "fetch the relevant
ticket" means `gh issue view <number> --comments`.
