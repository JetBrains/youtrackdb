## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region: from `<!--Document index start-->` to `<!--Document index end-->` (read to the closing delimiter, not a fixed line count). If the file has no TOC region (a file whose only `## ` heading is this bootstrap block carries none, per `§1.8(d)`), read the file in full.
2. Match TOC rows where Roles contains any of your roles (or your role is `any`, or the row's Roles is `any`) AND Phases contains any of your phases (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections; if no row matches your role/phase, the file holds nothing for you — do not read further.

Your role: orchestrator,reviewer-technical,reviewer-risk,reviewer-adversarial.
Your phase: 3A.

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening: a ref matches when any of your roles is in its roles and any of your phases is in its phases, your own `any` on either axis matches every ref on that axis, and a ref whose own roles or phases is `any` matches you. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Semi-Formal Verification Protocol | orchestrator,reviewer-technical,reviewer-risk,reviewer-adversarial | 3A | Re-check each Phase A technical/risk/adversarial finding with a verification certificate; emit PASS/FAIL. |

<!--Document index end-->

You are re-checking a track of the plan after fixes were applied.

Prose produced by this file follows the project house-style at `.claude/output-styles/house-style.md`. See `.claude/workflow/conventions.md §1.5 Writing style for Markdown and prose artifacts` for the canonical workflow-level anchor and tier mapping; the six AI-tell subset section slugs to apply are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, `## Orientation`, and `## Plain language`.

Inputs:
- Plan file: {plan_path} (strategic context — Architecture Notes,
  Decision Records, Component Map)
- Track file: {step_file_path} — authoritative source for the track's
  what/how/constraints/interactions and any track-level diagram, split
  across `## Purpose / Big Picture`, `## Context and Orientation`,
  `## Plan of Work`, and `## Interfaces and Dependencies`.
- Track reviewed: {track_name}
- Codebase root: {codebase_path}
- Episodes from completed tracks: {prior_episodes}
- Previous findings (context only, finalized in earlier iterations):
  {previous_findings}
- Findings under re-check (verify these): {findings}
- Review type: {technical|risk|adversarial}

**Staged-read precedence (workflow-modifying plans):** When the branch is in §1.7(b) staging mode — read ledger-first: the phase ledger's `s17` field (`_workflow/phase-ledger.md`, last value wins) equals the workflow-modifying token; when no `phase-ledger.md` exists (an in-flight pre-ledger workflow-modifying branch), fall back to the plan's `### Constraints` carrying the canonical `§1.7(b)` workflow-modifying marker sentence — resolve every read of a `.claude/workflow/**`, `.claude/skills/**`, `.claude/agents/**`, or `.claude/scripts/**` file through `§1.7(d)`, taking the staged copy under `_workflow/staged-workflow/` when present and the live file otherwise.

For each finding under re-check:
1. If the finding was ACCEPTED: check if the fix was applied correctly
   and if the fix introduced new issues.
2. If the finding was REJECTED: verify the rejection reason is sound
   and no downstream issue was introduced. Mark as REJECTED.

## Semi-Formal Verification Protocol
<!-- roles=orchestrator,reviewer-technical,reviewer-risk,reviewer-adversarial phases=3A summary="Re-check each Phase A technical/risk/adversarial finding with a verification certificate; emit PASS/FAIL." -->

Before verifying any finding whose fix touched the track description,
re-read the track file's `## Purpose / Big Picture`, `## Context and
Orientation`, `## Plan of Work`, and `## Interfaces and Dependencies`
sections (plus any track-level component diagram those sections
carry). Read the relevant Decision Records from the plan.

For each ACCEPTED finding being verified, produce a **verification
certificate** that re-checks the specific location.

For Java symbol re-checks (does this method now exist / have these
callers / live in this class / override this interface), use
mcp-steroid PSI find-usages / find-implementations when the IDE is
reachable; fall back to Grep/Glob with a reference-accuracy caveat
only when mcp-steroid is unreachable. The original finding may have
been generated against grep — verifying the fix with PSI catches
subtle mismatches that grep missed.

The re-check examples above are **illustrative, not exhaustive**. The
operative criterion is reference accuracy — would a missed or spurious
match make a verification verdict (VERIFIED / STILL OPEN / REGRESSION)
wrong? When in doubt, route through PSI. `CLAUDE.md` § MCP Steroid →
"Grep vs PSI — when to switch" is the last authoritative source for
edge cases.

**How to invoke:**
- The MCP server is `mcp-steroid`. Its tools are deferred, so load their schemas via ToolSearch first.
- Call `steroid_list_projects` once at session start to confirm the IDE has the right project open and matches the working tree.
- Run PSI queries (find-usages, find-implementations, type-hierarchy) via `steroid_execute_code`, which evaluates a Kotlin snippet against the PSI tree — there is no dedicated `find_usages` tool.
- For Kotlin recipes, fetch the `coding-with-intellij-psi` skill via `steroid_fetch_resource`.

```markdown
#### Verify <PREFIX><N>: <finding title>
- **Original issue**: <what was wrong>
- **Fix applied**: <what changed in the track file, plan file, or codebase>
- **Re-check**:
  - Track-file/plan/codebase location: <where the fix was applied>
  - Current state: <what it now says vs. original issue>
  - Criteria met: <which review criteria are now satisfied>
- **Regression check**: <did the fix introduce new issues?
  Checked [which areas] — [clean / new issue]>
- **Verdict**: VERIFIED | STILL OPEN (explain) | REGRESSION (new issue)
```

For REJECTED findings:

```markdown
#### Verify <PREFIX><N> (REJECTED): <finding title>
- **Rejection reason**: <from the previous iteration>
- **Downstream check**: <any downstream issues from leaving unfixed?>
- **Verdict**: REJECTED (no action needed) | RECONSIDER (downstream issue)
```

---

**Output mode — file when handed a path, inline otherwise.** When the
spawn supplies an output path, persist this output to a file in the
review-file schema's **verdict-producer variant** (`conventions-execution.md`
`§2.5` → Verdict-producer manifest variant, the single source of truth) and
return only the thin manifest; the orchestrator partial-fetches on disk.
When no path is supplied (the develop-state run), return the output inline
exactly as below — byte-for-byte today's format. This prompt is a
verdict producer: it emits per-prior-finding verdicts plus an overall
`PASS`/`FAIL`, not a fresh severity-graded finding set, so the manifest's
`findings` count and `## Findings` anchors cover only the **new** findings a
verification pass surfaces (each a `### <PREFIX><N> ` body with the
review's prefix — `T`/`R`/`A` per the `Review type`, cumulative numbering);
a pure-verdict pass with no new finding writes `findings: 0` and an empty
`## Findings`. The per-prior-finding verdicts go in the manifest's distinct
`verdicts` block, the overall result in `overall`. The `#### Verify <PREFIX><N> `
certificates are four-hash and excluded from the count grep
`grep -cE '^### [A-Z]+[0-9]+ '`, which validates only the new-finding anchors
against the manifest `findings` count (S4/S6).

Output:
- For each finding: the verification certificate above
- New findings (if any) with cumulative numbering
- Summary: PASS or FAIL
