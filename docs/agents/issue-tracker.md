# Issue tracker: GitHub

Issues and specs for this repository live in GitHub Issues at `Nitish-bot/duit`. Use the `gh` CLI for all operations and infer the repository from the configured `origin` remote.

## Conventions

- Create an issue with `gh issue create`.
- Read an issue and its discussion with `gh issue view <number> --comments`.
- List issues with `gh issue list`, including labels and comments when a workflow needs them.
- Comment with `gh issue comment`, change labels with `gh issue edit`, and close with `gh issue close`.
- When a skill says “publish to the issue tracker,” create a GitHub issue.
- When a skill says “fetch the relevant ticket,” read the GitHub issue and its comments.

## Pull requests as a triage surface

**PRs as a request surface: no.** Pull requests are implementation artifacts, not incoming feature requests for triage.
