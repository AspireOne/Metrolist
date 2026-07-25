You are resolving git merge conflicts in a long-lived personal fork of an actively developed
upstream Android project. Read `merge-context.md` in the repository root first — it names the
upstream ref being merged and lists the conflicted files.

## Situation

`main` carries a set of intentional deviations from upstream: personal patches, customisations and
behaviour changes that exist because the fork's owner wants them. Upstream has since moved on. Git
has merged what it could and stopped on the files listed in `merge-context.md`.

Your job is to finish that merge.

## The governing rule

For every conflict, **take upstream's improvements and preserve our intent**.

Concretely, for each conflicting hunk work out two things:

1. What was upstream trying to achieve with its change? Bug fix, refactor, new feature, API
   migration — adopt it.
2. What was our side trying to achieve? Adopt upstream's change **in a way that keeps our intent
   working**, rather than choosing one side wholesale.

The common shape of a correct resolution is: upstream's new code, with our modification
re-expressed on top of it. Picking "ours" and discarding upstream's fix is wrong. Picking "theirs"
and silently dropping our customisation is worse — it looks clean, compiles, and quietly reverts
behaviour the owner deliberately added.

Only drop one of our deviations when upstream has genuinely made it obsolete: it implemented the
same thing itself, or it removed the feature our patch modified. If you do that, say so explicitly
in your final message and explain why.

If a conflict is genuinely ambiguous — where a reasonable person would need to ask the owner what
they meant — **stop and fail rather than guess**. Explain what is ambiguous in your final message.
A blocked mirror is cheap; a wrong resolution that ships is not.

## Rules of engagement

- Edit **only** the files listed in `merge-context.md`. Everything else git already merged cleanly
  and is out of bounds. A later step verifies this and will fail the run.
- Do not create new files.
- Do not run `git add`, `git commit`, `git merge --abort`, or any other command that changes git
  state. Only edit file contents; a later step handles the commit.
- Remove every conflict marker (`<<<<<<<`, `=======`, `>>>>>>>`).
- Preserve the surrounding file's existing style, naming and comment density.

## Self-check

If you can, run `./gradlew :app:compileFossReleaseKotlin` and iterate until the resolution
compiles. If the sandbox blocks it (no network for dependency resolution), say so and rely on
careful reading instead — a later workflow step compiles the result regardless.

## Final message

Report, per conflicted file: what upstream changed, what our side changed, how you reconciled them,
and anything you were unsure about. Be specific and brief. This text is what the fork's owner reads
if something later goes wrong, and it is included in the issue raised if this merge is rejected.
