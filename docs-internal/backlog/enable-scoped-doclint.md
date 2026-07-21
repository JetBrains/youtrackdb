# Backlog: Enable Scoped doclint (`syntax,reference`)

Proposed follow-up change — no YouTrack issue yet. Origin: review discussion on PR #1240;
empirical probe run 2026-07-21.

## Motivation

The design-decision convention (`docs-internal/dev-workflow/design-decisions.md`) relies
on `{@link}`/`@see` cross-references between documentation sites, but nothing validates
them: doclint is disabled (`<doclint>none</doclint>` in the root pom's
maven-javadoc-plugin config) and JavaDoc is generated only in the `sonatype-oss-release`
profile. The `reference` doclint category would automate exactly this validation.

## Probe findings (2026-07-21)

- `doclint=all` on `core`: 164 errors, 14,680 warnings — would fail the weekly release
  build. The `missing` category alone contributes 14,666 warnings ("no comment" on legacy
  code); it must never be enabled.
- `doclint=syntax,reference` on `core`: 43 errors — tractable. `server`: 0 errors under
  every variant.
- Errors are dominated by legacy OrientDB-era files; `CronExpression.java` (2016,
  pre-HTML5 markup) alone carries about half of the all-category errors. Generated
  sources (SQL parser, ANTLR, annotation processors) contribute zero errors, only
  `missing` warnings.
- There is NO command-line override: the pom-configured `<doclint>` silently wins over
  `-Ddoclint=...` and over `-DadditionalJOption=-Xdoclint:...`. Enabling requires a pom
  edit.
- Cost: pure javadoc on `core` ≈ 7s; `compile javadoc:javadoc` ≈ 30s — a PR-CI gate is
  affordable.

## Proposed plan

1. Triage the 43 `syntax,reference` errors in `core` by file first, then fix them
   (`CronExpression.java` carried about half of the all-category errors in the probe,
   but its share of the `syntax,reference` subset was not measured).
2. Set `<doclint>syntax,reference</doclint>` in the root pom pluginManagement.
3. Wire a PR-CI step running per-module `compile javadoc:javadoc` so violations gate
   PRs, not only the weekly release.
4. Update `docs-internal/dev-workflow/design-decisions.md` § Tooling notes: `{@link}`
   targets become CI-validated; reviewers keep checking semantic staleness only.
5. Optional later phase: enable `html` after cleaning legacy markup. Never enable
   `missing`.

## Probe reproduction recipe (no pom edit needed)

Run `./mvnw -pl <module> compile javadoc:javadoc -Ddebug=true`, copy
`<module>/target/reports/apidocs/{options,packages}` to a temp dir, replace the
`-Xdoclint:none` line with the desired setting, then invoke
`javadoc -J-Xmx2G @options @packages` directly — output matches a real plugin run with
that doclint value.
