<!--
manifest:
  role: reviewer-risk
  phase: 3A
  track: "Track 1: Conventions opt-out, Orientation rule, and the atomic subset sync"
  iteration: 1
  kind: verdict-producer
  overall: PASS
  findings: 0
  verdicts:
    - id: R1
      sev: should-fix
      verdict: VERIFIED
      loc: "track-1.md Validation/Acceptance bullet 1 + Plan-of-Work steps 3/4 + Interfaces conventions bullet; conventions.md:570; readability-feedback/SKILL.md:54"
      note: "Acceptance bullet 1 now keeps the count on the unchanged 4-string governance grep (returns 54, verified) and adds a separate anchored `## Orientation`-presence check; steps 3/4 and the Interfaces bullet pin the anchored `## Orientation` form, never bare. Both live governance greps (conventions.md:570, readability-feedback:54) stay 4-string. Bare-word collision hazard real (33 files carry `## Context and Orientation`)."
    - id: R2
      sev: should-fix
      verdict: VERIFIED
      loc: "implementation-plan.md:21 Constraints marker + D6; track-1.md D6 + Plan-of-Work step 1 + acceptance bullet 3 + Interfaces conventions bullet"
      note: "Canonical opt-out prefix pinned `This plan uses the §1.7 prose-rule self-application opt-out:` in D6 (both files), step 1, acceptance bullet, Interfaces; the marker sentence is carried verbatim at implementation-plan.md:21. Distinct from the workflow-modifying prefix `This plan is workflow-modifying:` (conventions.md:899) — zero collision, so it does NOT trigger the staging gate. Three criteria-switch blocks confirmed at technical-review.md:113 / risk-review.md:110 / adversarial-review.md:282, each with a separate Staged-read precedence block, so the OR-extension can target the criteria block alone (D6 feasibility holds)."
    - id: R3
      sev: suggestion
      verdict: VERIFIED
      loc: "track-1.md Plan-of-Work step 2"
      note: "Step 2 now carries the note: key the :379 rewrite off its sentence text or apply it before inserting `## Orientation`, because the insertion shifts every line below it. house-style.md anchors verified live: Document-shape :377, scoping sentence :379, Why-before-what :403, Explanatory register :427, Self-check :433."
    - id: R4
      sev: suggestion
      verdict: VERIFIED
      loc: "track-1.md D5 Risks/Caveats + implementation-plan.md:26 stamp-advance constraint"
      note: "D5 now defines the trigger as the last commit touching the drift-gate pathspec (`.claude/workflow`, `.claude/skills`, `.claude/agents`), naming it the §1.6(b) SHA-computation pathspec, and states out-of-pathspec edits (house-style.md, hook, test) do not advance the stamp base. Anchor §1.6(b) resolves: conventions.md `### (b) SHA computation at artifact-creation time` defines `git log ... -- .claude/workflow .claude/skills .claude/agents`, matching WORKFLOW_PATHSPECS at startup-precheck.sh:273. The implementation-plan.md:26 constraint bullet carries the same precise pathspec definition."
-->

# Risk gate verification — Track 1, iteration 1

## Verification certificates

#### Verify R1: the two governance greps gain `Orientation` (D1) and acceptance still asserts "returns 54"
- **Original issue**: D1 said the grep "gains `Orientation`" while acceptance asserted the same grep "returns 54" — two different greps conflated; and the bare word `Orientation` collides with `## Context and Orientation` headings, destroying the closed-set-pointer enumerator.
- **Fix applied**: Acceptance bullet 1 (`track-1.md:108`) keeps the count on the unchanged 4-string governance grep and adds a *separate* anchored `## Orientation`-presence check across every closed-set enumeration. Plan-of-Work step 3 (`:85`) leaves the `§1.5` rename-enumeration grep (`:570`) on the original four strings and mandates the anchored `## Orientation` form if added to any grep, "never bare." Step 4 (`:91`) repeats it for both rename-enumeration greps. The Interfaces conventions bullet (`:131`) and readability bullet (`:135`) pin "anchored `## Orientation` only, never bare, R1."
- **Re-check**:
  - Location: `track-1.md` acceptance bullet 1 + steps 3/4 + Interfaces bullets.
  - Current state: count check on the unchanged 4-string grep (live `conventions.md:570` and `readability-feedback/SKILL.md:54` both still hold the four-string grep, verified); presence check is anchored. The `grep -rln 'Banned vocabulary\|…'` returns **54** on the live tree.
  - Criteria met: the count-mutation tension is dissolved (count grep unchanged); the bare-word collision is foreclosed (anchored form mandated). Collision hazard is real — 33 files carry `## Context and Orientation`.
- **Regression check**: Checked the two live governance greps (still four-string), the acceptance bullet's `## Orientation`-presence wording (anchored, lists the right site classes), and the catalogue-shape carve-out (`ai-tells` verified by a different shape). Clean.
- **Verdict**: VERIFIED

#### Verify R2: a distinct opt-out marker re-points the three criteria-switch blocks (D6)
- **Original issue**: D6's "distinct opt-out marker" had no pinned canonical spelling/prefix, unlike the workflow-modifying marker at `conventions.md:899`. Without a byte-identical prefix shared by the three blocks and the `### Constraints` note, the criteria switch silently dead-ends for future opt-out branches.
- **Fix applied**: The canonical prefix `This plan uses the §1.7 prose-rule self-application opt-out:` is pinned in D6 (`track-1.md:57`, `implementation-plan.md:116` references it via the parallel-to-workflow-modifying framing), Plan-of-Work step 1 (`:83`), acceptance bullet 3 (`:110`), and the Interfaces conventions bullet (`:131`). The marker sentence is carried verbatim in `implementation-plan.md`'s `### Constraints` at `:21`.
- **Re-check**:
  - Location: `implementation-plan.md:21` (the verbatim marker), D6 in both files, step 1, acceptance bullet, Interfaces.
  - Current state: the prefix is fixed, matched case-sensitively on the prefix alone, and the in-plan `### Constraints` carries it verbatim. The three criteria-switch blocks exist at the claimed live lines (`technical-review.md:113`, `risk-review.md:110`, `adversarial-review.md:282`), each immediately preceded by a separate `Staged-read precedence` block — confirming the OR-extension can target the criteria block and leave the staged-read block on the workflow-modifying marker.
  - Criteria met: prompt-design soundness (one byte-identical prefix, no drift), rule coherence (staged-read block deliberately not extended, stated in D6 and `## Context and Orientation`).
- **Regression check — staging-gate collision (spawn-mandated)**: The pinned opt-out prefix `This plan uses the §1.7 prose-rule self-application opt-out:` does NOT begin with the workflow-modifying prefix `This plan is workflow-modifying:` — a 0-match check confirmed the opt-out sentence carries no workflow-modifying-prefix substring. The marker therefore cannot accidentally trigger the staging gate. Also checked the load-bearing note's prose lead at `implementation-plan.md:29` ("This plan **takes** the `§1.7`…"): it uses "takes," not "uses," so it is not a second matchable copy of the pinned "uses" prefix. Clean.
- **Verdict**: VERIFIED

#### Verify R3: step 2 inserts `## Orientation` and rewrites `house-style.md:379` in the same step
- **Original issue**: inserting `## Orientation` shifts every line below it, invalidating the bare `:379` reference within the same step.
- **Fix applied**: Plan-of-Work step 2 (`track-1.md:84`) now reads: "Key the `:379` rewrite off its sentence text, or apply it before inserting `## Orientation` — inserting the new section shifts every line number below it, so a bare `:379` goes stale mid-step (R3)."
- **Re-check**:
  - Location: `track-1.md` Plan-of-Work step 2.
  - Current state: the self-invalidation is called out and two robust resolutions offered (text-keyed edit, or sequence the insertion last). Live `house-style.md` confirms the scoping sentence is at :379 ("not enforced on issue bodies, PR descriptions, or status prose, which use the BLUF rule alone"), Document-shape heading at :377, and the structural anchors (`### Why-before-what` :403, `### Explanatory register` :427, `## Self-check` :433) all resolve.
  - Criteria met: instruction completeness (the ordering hazard now has an explicit avoidance).
- **Regression check**: Checked that the three reconciliation edits (line 379, finding category, Self-check) remain enumerated in step 2 and D3 — all present. Clean.
- **Verdict**: VERIFIED

#### Verify R4: the drift pathspec is `workflow|skills|agents` only; D5's "last workflow-editing commit" was ambiguous
- **Original issue**: the largest edits (`house-style.md`) are out-of-pathspec, so "last workflow-editing commit" was ambiguous about which commit re-arms the stamp.
- **Fix applied**: D5 (`track-1.md:51`) defines the trigger as "the **last commit touching the drift-gate pathspec** (`.claude/workflow`, `.claude/skills`, or `.claude/agents` — the `§1.6(b)` SHA-computation pathspec; out-of-pathspec edits to `house-style.md`, the hook, or the test do **not** advance the stamp base … R4)." The `implementation-plan.md:26` constraint bullet carries the same precise definition.
- **Re-check**:
  - Location: `track-1.md` D5 Risks/Caveats; `implementation-plan.md:26`.
  - Current state: the trigger is now the last in-pathspec commit, with out-of-pathspec exclusions named.
  - Criteria met: rule coherence — the `§1.6(b)` anchor resolves: `conventions.md` `### (b) SHA computation at artifact-creation time` defines `git log … -- .claude/workflow .claude/skills .claude/agents`, matching `WORKFLOW_PATHSPECS=".claude/workflow/ .claude/skills/ .claude/agents/"` at `workflow-startup-precheck.sh:273`.
- **Regression check**: Verified the new `§1.6(b)` anchor is not a phantom reference — the sub-label exists in the live `conventions.md` §1.6 region and its body defines exactly that pathspec. No dangling anchor introduced. Clean.
- **Verdict**: VERIFIED

## Findings

(No new findings. This is a pure-verdict pass.)

## Summary

PASS — R1/R2/R3/R4 all VERIFIED; the pinned opt-out prefix is confirmed distinct from the workflow-modifying prefix (no staging-gate collision); no regressions; 0 new findings.
