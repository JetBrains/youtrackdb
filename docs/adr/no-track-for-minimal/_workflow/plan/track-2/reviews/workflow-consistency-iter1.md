<!-- MANIFEST
producer: review-workflow-consistency
dimension: workflow-consistency
review_target: track
track: 2
iteration: 1
commit_range: 01b13bfd642b48c498c85007cfd7014c370fd2d4..HEAD
evidence_base:
  certs: 0
cert_index: []
flags:
  evidence_trail_exempt: true
  evidence_trail_exempt_reason: "(a) no refutation or certificate phase to persist"
index:
  - id: WC1
    sev: blocker
    anchor: "### WC1 [blocker] prompts/structural-review.md standalone staged-read block left un-re-pointed"
    loc: "docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/prompts/structural-review.md:128"
    cert: n/a
    basis: judgment
  - id: WC2
    sev: should-fix
    anchor: "### WC2 [should-fix] conventions.md §1.7(l) describes the criteria-switch trigger as ### Constraints-only, contradicting its re-pointed consumers"
    loc: "docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/conventions.md:1437-1438"
    cert: n/a
    basis: judgment
-->

## Findings

### WC1 [blocker] prompts/structural-review.md standalone staged-read block left un-re-pointed

- **File**: `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/prompts/structural-review.md` (line 128)
- **Axis**: skill/agent cross-reference (standalone §1.7(b) staged-read block sync across the review-prompt family)
- **Cost**: a `minimal` workflow-modifying branch's Phase-2 structural reviewer reads the live (develop) workflow file instead of the staged copy — a phantom-mismatch, the exact failure mode this block exists to prevent; the missing `.claude/scripts/**` prefix means a staged script edit is never resolved to its staged copy.

**Issue.** Six of the seven Phase-2/3A review prompts carry a standalone "Staged-read precedence (workflow-modifying plans)" block, and Steps 1-2 re-pointed all six to the ledger-first form with the four-prefix path set:

```
When the branch is in §1.7(b) staging mode — read ledger-first: the phase ledger's `s17` field … when no `phase-ledger.md` exists … fall back to the plan's `### Constraints` … resolve every read of a `.claude/workflow/**`, `.claude/skills/**`, `.claude/agents/**`, or `.claude/scripts/**` file …
```

`prompts/structural-review.md:128` is the seventh and was **not** re-pointed. It still carries the develop-era contract:

```
When the plan's `### Constraints` carries the canonical `§1.7(b)` workflow-modifying marker sentence, resolve every read of a `.claude/workflow/**`, `.claude/skills/**`, or `.claude/agents/**` file through `§1.7(d)` …
```

This was verified two ways: (1) `diff` of the live vs. staged block is byte-identical (Step 3 touched this file for the bloat-check / presence-check edits but left this block untouched); (2) the delta file lists the structural-review changes but shows zero edits to the "Staged-read precedence" string.

The referent is the staging-mode contract itself. Track 2's D4 reader inventory (track-2.md Decision Log D4, and Plan-of-Work step 2) names "the two gate-recheck prompts that carry the same standalone §1.7(b) staged-read block" and `track-code-review` as carriers that must re-point, and Step 1's episode records that within-file coherence already forced re-pointing the co-resident standalone blocks in `consistency-review.md` and `step-implementation.md`. `prompts/structural-review.md` is an in-scope file (Interfaces and Dependencies list, line 626 of the diff context) edited in Step 3, so this block is squarely in scope and the miss makes the file self-inconsistent with its six siblings.

The trigger half is the live-read hazard: under §1.7(k)/(b) the canonical marker home is now the ledger `s17` field (conventions.md §1.7(b)/(k), "This track defines the home; Track 2 re-points the readers"). A `minimal` workflow-modifying branch resolves `s17` from the ledger and carries no plan `### Constraints` marker, so this block's `### Constraints`-only trigger never fires, the staged-read precedence goes inert, and the structural reviewer reads develop's version of any workflow file the plan cites. The path half is the D14 gap: the three-prefix set omits `.claude/scripts/**`, so even when the trigger does fire (pre-ledger `### Constraints` branch), a staged script reference resolves to the live script.

**Suggestion.** Re-point line 128's standalone block to the same ledger-first, four-prefix wording the six sibling prompts now carry (the exact string at e.g. `prompts/risk-review.md:108`). This is the within-file coherence fix Step 1 applied to `consistency-review.md` and `step-implementation.md`, applied to the one prompt Step 3 missed.

### WC2 [should-fix] conventions.md §1.7(l) describes the criteria-switch trigger as ### Constraints-only, contradicting its re-pointed consumers

- **File**: `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/workflow/conventions.md` (lines 1437-1438)
- **Axis**: cross-file rule restatement (normative §1.7(l) spec vs. its three Phase-3A consumer prompts)
- **Cost**: the normative spec and the consumers it governs disagree on the read source; a workflow-modifying branch with a ledger `s17` token but no `### Constraints` marker (the now-standard shape) fires the consumer criteria-switch blocks while the spec says they should not.

**Issue.** §1.7(l) (the "Opt-out criteria-switch extension") still states:

```
The three Phase-3A criteria-switch blocks (the "Workflow-machinery criteria" block in `technical-review.md`, `risk-review.md`, and `adversarial-review.md`) fire when the plan's `### Constraints` carries **either** the (b) workflow-modifying marker prefix **or** the (k) opt-out marker prefix.
```

This describes only the `### Constraints` path. But Step 1 re-pointed those three consumers' "Workflow-machinery criteria" block to read ledger-first (verified at `risk-review.md:110`, identical in `technical-review.md` and `adversarial-review.md`):

```
When the branch is in §1.7(b) staging mode **or** the `§1.7(k)` … opt-out mode — read ledger-first: the phase ledger's `s17` field … equals either the workflow-modifying token or the opt-out token; when no `phase-ledger.md` exists, fall back to the plan's `### Constraints` …
```

So the normative spec (§1.7(l)) names `### Constraints` as the sole trigger source while its three consumers read the ledger `s17` field first and treat `### Constraints` as the fallback. The referent is the §1.7(l) spec block itself: it now describes only the consumers' fallback path, contradicting their primary path.

This is not merely a deferred Track-1 concern. Track 1's own §1.7(k) (conventions.md:1340-1344) defines the ledger `s17` field as the canonical home for both mutually-exclusive values (`s17 = workflow-modifying` / `s17 = opt-out`) and states "**This track defines the home; Track 2 re-points the readers** (the staging consumers and **the (l) criteria-switch blocks**) from the plan `### Constraints` scan onto the ledger `s17` value." Track 1 explicitly handed the (l) criteria-switch read-source re-point to Track 2; Step 1 re-pointed the three consumer prompts but left §1.7(l)'s own description of those blocks on the old source. The track episodes (Step 1 / Step 3 "What was discovered") flag this as a Phase-C open seam under the `conventions.md` §1.7(c)-only carve-out, which is why it lands here rather than as a Step edit.

**Suggestion.** Bring §1.7(l) lines 1437-1440 in line with the consumers: state that the three criteria-switch blocks fire when the ledger `s17` field equals either the workflow-modifying or the opt-out token (read ledger-first), with the plan's `### Constraints` "either marker" prefix as the pre-ledger fallback — mirroring the wording Step 1 wrote into the three prompts and the §1.7(c) read-side amendment. If the team prefers to keep `conventions.md` strictly within the §1.7(c) carve-out for this track, record the (l) wording fix as a Phase-C plan correction so the spec and its consumers are reconciled before the staged tree is promoted.

## Evidence base
