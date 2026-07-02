<!-- MANIFEST
findings: 9   severity: {blocker: 0, should-fix: 8, suggestion: 1}
index:
  - {id: A1, sev: should-fix, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:40, anchor: "### A1 ", cert: C1, basis: "D6 recast leaves the prose AI-tell axis (S4 one-owner invariant) without a stated owner; D7's kept judgment rules lose their authoring-loop enforcer"}
  - {id: A2, sev: should-fix, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:20, anchor: "### A2 ", cert: C3, basis: "D1/D7 mirrored-consumer list omits the ai-tells skill (an always-loaded description hard-coding three removed patterns) and the dsc-ai-tell test suite"}
  - {id: A3, sev: should-fix, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:32, anchor: "### A3 ", cert: C5, basis: "D4 site list omits MANDATORY_OR_FORM_SUBHEADINGS; rename without it makes the same-shape sibling check false-positive on every well-formed design; four more TL;DR sites unlisted"}
  - {id: A4, sev: should-fix, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:24, anchor: "### A4 ", cert: C6, basis: "hybrid narrative half has no named consumer at slice scope; the book signal it preserves is a whole-read property"}
  - {id: A5, sev: should-fix, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:52, anchor: "### A5 ", cert: C7, basis: "transferred book rules pull against kept house-style rules (worked-example-first vs less-text bias and 200-word cap; one-concept-per-section vs sibling consolidation); precedence unstated"}
  - {id: A6, sev: should-fix, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:28, anchor: "### A6 ", cert: C8, basis: "reader-proxy rationale argues the wrong direction; failure-to-follow can manifest as a false-clean pass, and D3 weakens both loop readers at once"}
  - {id: A7, sev: should-fix, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:36, anchor: "### A7 ", cert: C9, basis: "D5 puts track files under the narrative voice with no D8-style anchor guard for the 15-section ExecPlan skeleton implementers read"}
  - {id: A8, sev: should-fix, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:44, anchor: "### A8 ", cert: C10, basis: "D7's per-rule disposition is not closed: trailing hedges and persuasive authority tropes (regex-backed) appear in neither list; adjective triads live only in consumers; negative-parallelism classification asserted, not argued"}
  - {id: A9, sev: suggestion, loc: docs/adr/tech-writer-tone/_workflow/research-log.md:52, anchor: "### A9 ", cert: C12, basis: "D9's forward/backward prose links create a stale-link surface no mechanical check covers after section-move/section-remove mutations"}
evidence_base: {section: "## Evidence base", certs: 14, matches: 4}
cert_index:
  - {id: C1, verdict: WEAK, anchor: "#### C1 "}
  - {id: C2, verdict: HOLDS, anchor: "#### C2 "}
  - {id: C3, verdict: WEAK, anchor: "#### C3 "}
  - {id: C4, verdict: WEAK, anchor: "#### C4 "}
  - {id: C5, verdict: CONSTRUCTIBLE, anchor: "#### C5 "}
  - {id: C6, verdict: WEAK, anchor: "#### C6 "}
  - {id: C7, verdict: WEAK, anchor: "#### C7 "}
  - {id: C8, verdict: FRAGILE, anchor: "#### C8 "}
  - {id: C9, verdict: WEAK, anchor: "#### C9 "}
  - {id: C10, verdict: WEAK, anchor: "#### C10 "}
  - {id: C11, verdict: YES, anchor: "#### C11 "}
  - {id: C12, verdict: CONSTRUCTIBLE, anchor: "#### C12 "}
  - {id: C13, verdict: HOLDS, anchor: "#### C13 "}
  - {id: C14, verdict: HOLDS, anchor: "#### C14 "}
flags: [CONTRACT_OK]
-->

## Findings

### A1 [should-fix]
**Certificate**: C1 (Challenge: Decision D6)
**Target**: Decision D6 — persona roster
**Challenge**: The recast of `readability-auditor` into a naive target-reader persona leaves the prose AI-tell axis without a stated owner. Today the auditor owns that axis exclusively (`readability-auditor.md:68` — the S4 one-owner-per-surface invariant) and the comprehension reviewer "runs it nowhere" (`comprehension-review.md:39`). A first-person mid-level reader who "will not fill in gaps" reports reading experience; it does not cite `§ Passive voice`, `§ Nominalization`, or `§ Hedge stacking`. Every judgment-only rule D7 keeps (padding, hedging, elegant variation, boldface cap — the subset `dsc-ai-tell` regexes cannot reach, per `readability-auditor.md:70`) therefore loses its per-round enforcer in the authoring loop, which undercuts D7's own keep rationale ("they cut review time").
**Evidence**: `readability-auditor.md:59-70` (axis ownership plus the explicit judgment-vs-regex split); `comprehension-review.md:37-40` (the other reader is barred from the axis); D6's text assigns the persona "first-person slice report + structured findings appendix per D2" without mentioning the axis.
**Proposed fix**: Strengthen D6 to state where the AI-tell judgment axis lands after the recast — for example: the findings appendix retains the house-style `§`-citation obligation (the persona governs register and stance, not the checklist), or the axis moves to a named other owner. One sentence resolves it; without it the derived design can silently drop the enforcement half of the role.

### A2 [should-fix]
**Certificate**: C3 (Challenge: Decision D1)
**Target**: Decision D1 — remove disguise-only rules across the whole style machinery (and the 08:18Z / 08:52Z consumer enumerations it relies on)
**Challenge**: The mirrored-consumer list is missing consumers that hard-code the removed rules. `house-style.md:20` names four readers of the rule set; the log's list (08:18Z) covers only two of them plus `review-workflow-writing-style.md`. Omitted: the `ai-tells` skill, whose **always-loaded** `description:` frontmatter (`.claude/skills/ai-tells/SKILL.md:3`) hard-codes "negative parallelism like 'It's not X, it's Y'", "Title Case headings", and "closing phrases like 'In conclusion'" — three of the six D7 removals — plus a body pointer at line 24. After the change, every session's system reminder would advertise catching patterns the style no longer bans. Also omitted: `.claude/scripts/tests/test_dsc_ai_tell.py` and `tests/fixtures/dsc-ai-tell-fixture.md`, which pin the regexes D1 deletes (`NEGATIVE_PARALLELISM_RE`, `NEGATIVE_PARALLELISM_TRAILING_RE`, `HYPHENATED_PAIR_CLUSTER_RE`, the Title-Case check) — the build breaks if the regexes go and the tests stay.
**Evidence**: grep for "negative parallelism|Title Case" hits `.claude/skills/ai-tells/SKILL.md`, `.claude/scripts/tests/test_dsc_ai_tell.py`, `.claude/scripts/tests/fixtures/dsc-ai-tell-fixture.md`; none of the three appears in the 08:18Z consumer list or the 08:52Z baseline surface list.
**Proposed fix**: Extend the D1 consumer list and the baseline surface list with `.claude/skills/ai-tells/SKILL.md` (description + catalogue lookups) and the `dsc-ai-tell` test suite. State the enumeration was produced by grep over the pattern names so the next reader can re-derive it.

### A3 [should-fix]
**Certificate**: C5 (Violation scenario) + C4 (Challenge: Decision D4)
**Target**: Decision D4 — TL;DR → Summary rename (and the 08:16Z site enumeration)
**Challenge**: The rename-site enumeration misses the one site whose omission produces wrong review verdicts rather than a stale string. `MANDATORY_OR_FORM_SUBHEADINGS` (`design-mechanical-checks.py:63-71`) excludes `"tl;dr"` from the same-shape sibling similarity computation "since otherwise every well-formed section would look identical". If sections carry `### Summary` and that set still lists only `"tl;dr"`, the shared `### Summary` heading counts toward similarity in every section, and the sibling-consolidation check fires on well-formed documents (trace in C5). Four more sites are unlisted: `prompts/create-final-design.md:187`, `skills/review-workflow-pr/SKILL.md:114`, `conventions.md:149`, `agents/comprehension-review.md:34`.
**Evidence**: `design-mechanical-checks.py:63-71` (the exclusion set and its rationale comment); grep for `TL;DR` across `.claude/` returning the four files absent from the 08:16Z list and the 08:52Z baseline.
**Proposed fix**: Add `MANDATORY_OR_FORM_SUBHEADINGS` (both spellings, mirroring the `FOOTER_HEADING_RE` precedent the log already cites) and the four files to the D4 site list.

### A4 [should-fix]
**Certificate**: C6 (Challenge: Decision D2)
**Target**: Decision D2 — hybrid output for persona readers
**Challenge**: The narrative half has no named consumer, and at slice scope it cannot carry the signal that justifies it. The dual-clean loop converges on the findings appendix (D2 says so itself); the orchestrator filters findings against settled-state (`readability-auditor.md:99`); D2 rejected the synthesis step that consumed narratives in the book pipeline (`beta-feedback/SYNTHESIS.md` converts three narrative reports into one revision table). So on the auditor path the narrative flows into the orchestrator and stops — cost with no consumer, multiplied by up to ~6 slices per round across up to 3 rounds. The experiential signal the book demonstrated is a whole-read property: `BOOK_BRIEF.md:58` has beta readers "read the full book in order", and the target-reader report's best observations (Part-IV pacing, forward references landing without context, cross-chapter coherence) span chapters. A ~200-line slice read cold produces none of that; a per-slice "stumble" is exactly what a structured finding already records ("I had to re-read X twice" = a hard-to-read finding with location and quote).
**Evidence**: `beta-reader-1-target-reader.md` (per-chapter notes keyed on whole-read continuity); `readability-auditor.md:27,99` (range-sliced fan-out; findings-keyed settled-state); D2's own convergence clause.
**Proposed fix**: Split the contract per role: hybrid (narrative + findings) for the whole-doc comprehension/time-constrained reviewer, where a reading narrative is meaningful; findings-with-persona-voice for the range-sliced auditor. Or name the narrative's consumer and its context-budget cost in D2 explicitly.

### A5 [should-fix]
**Certificate**: C7 (Challenge: Decision D9)
**Target**: Decision D9 — book voice-rule transfer boundary
**Challenge**: Two transferred rules pull directly against kept house-style rules and the log names no precedence. (1) "Concrete before abstract — open every section with a worked example" (`BOOK_BRIEF.md:20`) against the kept "When in doubt, bias toward **less text**" (`house-style.md:52`) and the 200-word soft section cap plus padding criterion (`house-style.md:283-285`): a worked example opening every section routinely exceeds the cap, and the writing-style reviewer (`review-workflow-writing-style.md:83-86`) will flag what the persona reader demanded. (2) "One concept per section" (`BOOK_BRIEF.md:21`) splits sections; `§ Same-shape sibling consolidation` (`house-style.md:395`) merges them back. Two enforcement agents holding contradictory targets thrash the authoring loop: the author adds a worked example for the target reader, the style reviewer flags length, round count rises.
**Evidence**: file:line pairs above; both rule sets would be simultaneously live after the change, and no D-record ranks them.
**Proposed fix**: Add a precedence clause to D9 (e.g. the narrative rules govern mechanism-overview prose and win over the length heuristics there, with a new exempt category or an adjusted cap; consolidation yields to one-concept-per-section only when the siblings teach distinct concepts). Any consistent ranking clears the gate; silence does not.

### A6 [should-fix]
**Certificate**: C8 (Assumption test: Decision D3)
**Target**: Decision D3 — Sonnet for readability-auditor and comprehension-review
**Challenge**: The proxy rationale argues the benign direction ("if Sonnet can follow it, a mid-level developer can") and skips the load-bearing one: when the document is bad, does Sonnet *report* it? An auditor's value is detection — the judgment cases the regex cannot reach (`readability-auditor.md:70`). A reader who fails to follow a passage flags it only if it recognizes the failure; a weaker model can gloss over confusion and return clean. The dual-clean loop converges on auditor-clean, so a false-clean shortens the loop with a worse document, and D3 removes the strong-model backstop from both loop readers at once (auditor and outer comprehension gate). Remaining prose backstops are the regex checker (mechanical subset only) and the Phase-C writing-style reviewer (fires only on workflow-markdown diffs at 3B/3C).
**Evidence**: `readability-auditor.md:41-53,70` (the detection bar and judgment-not-count framing); `comprehension-review.md:23` (outer-gate role); the prior user feedback the log itself cites in the 08:20Z open question.
**Verdict on the assumption**: FRAGILE — plausible for the comprehension gate (its output is answers-plus-citations, so failure is visible as a wrong or uncited answer) but unargued for the auditor's detection duty.
**Proposed fix**: Strengthen D3's Why with the false-clean risk and the chosen mitigation — e.g. Sonnet is defensible for both roles *because* the persona output makes failure visible (a target reader must narrate what it understood, so glossing shows), or keep one Opus backstop (the outer gate) while the auditor moves. Name the reasoning; the current one-directional proxy claim does not cover the failure mode.

### A7 [should-fix]
**Certificate**: C9 (Challenge: Decision D5)
**Target**: Decision D5 — voice boundary (design docs and track files vs issue/PR surfaces)
**Challenge**: D5 places track files under the technical-writer voice, but the D8 guard that protects machine-facing skeleton from narrative drift names only design.md anchors (D-records, `### Summary` blocks, footers, diagrams). A track file is a 15-section ExecPlan template (`conventions-execution.md §2.1`) whose consumers are implementer spawns reading under context budget; an author agent told "the technical-writer voice governs track files" can legitimately narrativize step descriptions, invariants, and episode fields, inflating every implementer read with no D8-equivalent anchor list to stop it.
**Evidence**: `conventions-execution.md:28-54` (the 15-section track shape and its structured-field blocks); D8's guard text enumerates design-doc anchors only; D5 asserts the track-file scope in one clause with no guard.
**Proposed fix**: Extend the D8 guard (or add a clause to D5): in track files the voice governs the prose sections (`## Purpose / Big Picture` and peers) while the structured ExecPlan fields stay registry-terse, mirroring the design-doc anchor carve.

### A8 [should-fix]
**Certificate**: C10 (Challenge: Decision D7)
**Target**: Decision D7 — per-rule disposition
**Challenge**: A per-rule disposition that leaves rules undispositioned defeats its purpose. Two regex-backed rules appear in neither list: **trailing hedges** (`house-style.md:104`, one of the six `§ Banned sentence patterns` items) and **persuasive authority tropes** (`house-style.md:229`, backed by `AUTHORITY_TROPE_RE` at `design-mechanical-checks.py:178`). An implementer editing `dsc-ai-tell` must know keep-or-remove for both and the log does not say. **Adjective triads** exist only in consumers (`review-workflow-writing-style.md:112`, the `ai-tells` description) with no house-style section at all — the removal criterion cannot even be applied to them from the source file D1 designates. Separately, the negative-parallelism classification is asserted rather than argued: house-style's own rationale ("adds no information; it performs depth", `house-style.md:100`) is the same padding/review-cost rationale D7 uses to *keep* throat-clearing, so the log needs the distinguishing argument (the pattern reframes real content at near-zero length cost, and the trailing-negation regex needed a curated intensifier discriminator to avoid eight false positives on this branch's own design — `design-mechanical-checks.py:108-130` — i.e. the ban costs false-positive management the padding rules do not).
**Evidence**: file:line citations above; D7's remove and keep lists quoted at research-log.md:45.
**Proposed fix**: State the default ("any rule not named keeps its current disposition") or complete the enumeration for the two banned-pattern sections plus the consumer-only rules; add the negative-parallelism distinction (framing vs additive filler) so the classification is argued, not asserted.

### A9 [suggestion]
**Certificate**: C12 (Violation scenario: Decision D9 forward/backward links)
**Target**: Decision D9 — connect-forward-and-backward as light prose links
**Challenge**: Section-scoped prose links (opener names what the section builds on, closer names what depends on it) create a link-consistency surface no mechanical check covers. `design-mechanical-checks.py` resolves `Mechanics:` links and `**Full design**` refs, and it has no intra-document narrative-link check; `edit-design`'s mutation kinds include `section-move`, `section-remove`, and `section-rename`. After a move or remove, an untouched sibling's opener still names the old neighbor, and only a human read catches it — the same failure class the CLAUDE.md rebase-conflict rule warns about for prose.
**Evidence**: mutation-kind list at `create-plan/SKILL.md:25`; check inventory in `design-mechanical-checks.py` (no narrative-link rule).
**Proposed fix**: Note in D9 that the adapted link rule needs either a mutation-discipline reminder (re-read neighbor openers/closers on section-move/remove) or a naming convention that makes links greppable (cite the section heading verbatim). Existing decision holds; this is hardening.

## Evidence base

**Decision challenges**

#### C1 Challenge: Decision D6 — persona roster
- **Chosen approach**: Recast `readability-auditor` as the target-reader persona (first-person slice report + findings appendix) and `comprehension-review` as the time-constrained reviewer; mechanical checks stay personless.
- **Best rejected alternative**: New persona agents alongside the old roles (rejected as duplicating the review surface) — the variant worth arguing is keep-the-editor-duty: recast the register while explicitly retaining the auditor's house-style enforcement checklist.
- **Counterargument trace**:
  1. Today the auditor owns the prose AI-tell axis exclusively: `readability-auditor.md:68` "You own the prose AI-tell axis on every surface where prose is judged. The comprehension reviewer runs it nowhere — that is the one-owner-per-surface invariant (S4)."
  2. The axis is judgment beyond regex reach: `readability-auditor.md:70` "you take the cases the regex cannot reach — an unglossed entity, a folded causal chain, an inflated-abstraction label…".
  3. D6's persona is "a mid-level dev assigned to implement/review, 'will not fill in gaps'" — a naive reader stance. Nothing in D6 says the findings appendix keeps the `§`-citation obligation (`readability-auditor.md:103` requires each finding to name the house-style `§` it breaks).
  4. If the persona replaces the editor stance, D7's kept judgment rules (passive voice, nominalization, hedge stacking, elegant variation, boldface cap) have no authoring-loop owner: the regex checker covers only its mechanical subset, and `review-workflow-writing-style.md` runs at phases 3B/3C on diffs, not per authoring round.
- **Codebase evidence**: `readability-auditor.md:59-70,103`; `comprehension-review.md:37-40`; `review-workflow-writing-style.md:15-16` (roles/phases).
- **Survival test**: WEAK — the roster shape survives, but D6 must say where the enforcement axis lands.

#### C3 Challenge: Decision D1 — remove disguise-only rules across the whole style machinery
- **Chosen approach**: Remove at the source (`house-style.md`) and update three named mirrors (regex checker, `review-workflow-writing-style.md`, `house-conversation.md`).
- **Best rejected alternative**: Chat-register-only removal — correctly rejected; the challenge is to the consumer enumeration, not the direction.
- **Counterargument trace**:
  1. `house-style.md:20` names four readers of the rule set: the `ai-tells` skill, the cold-read prompt in `prompts/design-review.md`, `dsc-ai-tell`, and `house-conversation.md`.
  2. The log's consumer list (08:18Z) and baseline (08:52Z) include two of these plus `review-workflow-writing-style.md`; the `ai-tells` skill appears nowhere in the log.
  3. `.claude/skills/ai-tells/SKILL.md:3` hard-codes in its always-loaded `description:` three D7 removals: negative parallelism, Title Case headings, closing phrases; line 24 repeats closing phrases in the catalogue-lookup table.
  4. `.claude/scripts/tests/test_dsc_ai_tell.py` and `tests/fixtures/dsc-ai-tell-fixture.md` pin the regexes D1 deletes; removing `NEGATIVE_PARALLELISM_RE` / the Title-Case check without them fails the script's test run.
- **Codebase evidence**: grep hits listed at A2; `design-mechanical-checks.py:99-195` (the regex constants slated for removal).
- **Survival test**: WEAK — decision direction survives; the enumeration must be completed or the removal ships half-mirrored.

#### C4 Challenge: Decision D4 — TL;DR → Summary rename shape and sites
- **Chosen approach**: `### Summary` sub-heading, details under their own sub-headings, both spellings accepted (D11 precedent).
- **Best rejected alternative**: `**Summary.**` bold-prefix only — correctly rejected on the separation criterion; the challenge is site completeness (see C5 for the constructed violation).
- **Counterargument trace**:
  1. The 08:16Z site list names `design-document-rules.md`, the `section_has_tldr` regexes, `SHAPE_EXEMPT_SECTION_NAMES`, `prompts/design-review.md`, ~5 `edit-design` sites, `planning.md:774`, `house-style.md § Navigability`.
  2. grep for `TL;DR` also returns `prompts/create-final-design.md:187` (the Phase-4 per-section shape restatement), `skills/review-workflow-pr/SKILL.md:114` (reviewer shape checklist), `conventions.md:149` (plan-file structure description), `agents/comprehension-review.md:34` (the structural-findings list the gate runs).
  3. None of the four is in the log; two of them (`comprehension-review.md`, `create-final-design.md`) are behavior-bearing at review time — a gate checking for "TL;DR present" against a doc that writes `### Summary` reports a false structural finding.
- **Codebase evidence**: grep output; `prompts/design-review.md` TOC row "Structural findings (always check) … TL;DR".
- **Survival test**: WEAK — rename survives; site list needs the four files plus C5's set.

#### C6 Challenge: Decision D2 — hybrid narrative + findings output
- **Chosen approach**: Persona readers return a first-person narrative reading report plus a structured findings appendix.
- **Best rejected alternative**: "Manifest-only with persona identity" — rejected as losing the experiential signal; strongest at slice scope, where the signal does not arise.
- **Counterargument trace**:
  1. The signal D2 cites comes from whole-book reads: `BOOK_BRIEF.md:58` "Beta readers (3 personas, parallel) — read the full book in order"; the target-reader report's high-value items are cross-chapter (pacing shift at Part IV, forward references landing before their referent, terminology drift between Ch 2 and Ch 14).
  2. In the book pipeline the narrative has a consumer: `beta-feedback/SYNTHESIS.md` converts three reports into a prioritized revision table. D2 explicitly rejects that synthesis step.
  3. In the workflow loop, convergence and settled-state key on discrete findings (`readability-auditor.md:99`; D2's own Why). No consumer of the narrative half is named.
  4. The auditor is range-sliced (~200-line windows, up to ~6 spawns per round, cap 3 rounds): the narrative halves multiply into orchestrator context with no reader, while a per-slice stumble is expressible as a finding with quote and location — the shape the loop already consumes.
- **Codebase evidence**: `readability-auditor.md:27,87-99`; `beta-feedback/SYNTHESIS.md:7-16`; `beta-reader-1-target-reader.md` per-chapter notes.
- **Survival test**: WEAK — hybrid survives for the whole-doc reader; the uniform application to the sliced auditor needs a consumer or a per-role split.

#### C7 Challenge: Decision D9 — book voice-rule transfer boundary
- **Chosen approach**: Transfer five rules verbatim, two adapted; already-present citations rule noted.
- **Best rejected alternative**: Transfer with an explicit precedence table against the kept house-style rules — not listed in the log.
- **Counterargument trace**:
  1. `BOOK_BRIEF.md:20` rule 2: "Open every section with a worked example or a short snippet." Applied to a design doc, a per-section worked example collides with `house-style.md:52` ("bias toward less text… A 200-word Summary that's correct beats an 800-word one") and the soft cap at `house-style.md:283`.
  2. The style reviewer enforces the cap-plus-padding criterion (`review-workflow-writing-style.md:83-86`); the persona reader (per D6) demands the worked example. The author is squeezed between two live enforcement agents with no ranking.
  3. `BOOK_BRIEF.md:21` rule 3 ("one concept per section — if a section introduces two concepts, split it") multiplies same-shape siblings; `house-style.md:395` consolidates three-plus same-shape siblings into one TL;DR + comparison table. A doc rewritten to satisfy one triggers the other.
  4. "Earn every name" is near-duplicate of the kept `§ Orientation` gloss-at-first-use move (`house-style.md:61`), so that pair reconciles; the two conflicts above do not reconcile by themselves.
- **Codebase evidence**: file:line pairs above.
- **Survival test**: WEAK — the transfer set is sound; the log needs a precedence clause for the two named collisions.

#### C9 Challenge: Decision D5 — voice governs design docs and track files
- **Chosen approach**: Technical-writer voice on design docs and track files; terse BLUF register on issue/PR/commit surfaces.
- **Best rejected alternative**: Voice on design docs only, track files keep the current register (not listed in the log).
- **Counterargument trace**:
  1. Track files are the 15-section ExecPlan template (`conventions-execution.md §2.1`, TOC row: "The 15-section track file shape") consumed by implementer spawns per step.
  2. D8's anchor guard ("the technical-writer voice governs only the mechanism prose between these anchors") enumerates design-doc anchors: D-records, `### Summary` blocks, footers, diagrams. No equivalent enumeration exists for track-file sections.
  3. An author agent instructed that the voice governs track files can narrativize step descriptions and invariants; every implementer read then pays the length. The ExecPlan structured-field exemption in `house-style.md:284` shields those blocks from the *length* rule, but nothing directs the author to keep them terse under the new voice.
- **Codebase evidence**: `conventions-execution.md:28-54`; D8 guard text at research-log.md:49; `house-style.md:284`.
- **Survival test**: WEAK — boundary decision survives; track files need the D8-style carve stated.

#### C10 Challenge: Decision D7 — per-rule disposition completeness
- **Chosen approach**: Closed remove list (6 rules) + keep list, with two alternatives rejected.
- **Best rejected alternative**: Same split plus an explicit default clause and full coverage of the two banned-pattern sections.
- **Counterargument trace**:
  1. `§ Banned sentence patterns` has six items; D7 dispositions five (remove: negative parallelism, roundabout negation, closing phrases; keep: throat-clearing, prompt-restating). **Trailing hedges** (`house-style.md:104`) appears in neither list.
  2. **Persuasive authority tropes** (`house-style.md:229`) is regex-backed (`AUTHORITY_TROPE_RE`, `design-mechanical-checks.py:178`) and undispositioned; an implementer editing `dsc-ai-tell` cannot tell whether the regex stays.
  3. **Adjective triads** exists only in consumers (`review-workflow-writing-style.md:112`; the `ai-tells` skill description) — D1's remove-at-the-source procedure has no source section to act on for it.
  4. The negative-parallelism classification ("pure disguise, little comprehension value") contradicts house-style's stated rationale for the ban ("The pattern adds no information; it performs depth", `house-style.md:100`) — the same review-cost rationale D7 uses to keep throat-clearing. The reconciling argument exists (the pattern reframes real content at near-zero added length, and the trailing-negation regex needed a curated `just|merely|simply` discriminator to avoid eight documented false positives — `design-mechanical-checks.py:115-126`) but the log does not make it.
- **Codebase evidence**: citations inline above.
- **Survival test**: WEAK — the split survives; close the enumeration and argue the boundary case.

#### C11 Challenge: Decision D8 — frozen design.md stays the track-derivation seed, dual-register
- **Chosen approach**: Design stays the seed; narrative voice confined to mechanism prose between machine-facing anchors; `### Summary` blocks stay plain-claim and self-contained.
- **Best rejected alternative**: Narrative voice only in Phase-4 `design-final.md`, working `design.md` stays fully terse (not listed) — the reviewed/user-accepted surface at Phase 1 is `design.md` itself, so the review-cost pain the user named lands there; deferring the voice to Phase 4 would leave the expensive-to-review artifact unchanged.
- **Counterargument trace**:
  1. Scenario: a Step-4b derivation spawn extracts track content from a narrative design. Narrative spreads a mechanism across a problem→forces→mechanism→consequences arc.
  2. D8's guard keeps D-records in canonical four-bullet form and `### Summary` blocks self-contained; the derivation spawn's partial-read path (summaries + records + footers) stays extraction-stable.
  3. The rejected log-as-seed alternative fails independently: the log is append-only with superseded entries and holds no integrated mechanism (research-log.md:49-50), so re-derivation from code would race the frozen reviewed design.
- **Codebase evidence**: the D-record canonical form and footer rules in `design-document-rules.md`; the guard text at research-log.md:49.
- **Survival test**: YES — the rationale holds, including against the unlisted Phase-4-only alternative.

**Violation scenarios**

#### C5 Violation scenario: same-shape sibling check stays quiet on well-formed designs after the D4 rename
- **Invariant claim**: The mechanical checks report no false findings on a document that follows the mandated per-section shape.
- **Violation construction**:
  1. Start state: post-D4 design.md where every `##` mechanism section opens `### Summary` and ends `### Edge cases` + `### Decisions & invariants`, per the new shape.
  2. Action sequence: run `design-mechanical-checks.py` whole-doc. The same-shape sibling computation excludes only the sub-headings in `MANDATORY_OR_FORM_SUBHEADINGS` (`design-mechanical-checks.py:63-71`), which lists `"tl;dr"` and not `"summary"`.
  3. Intermediate state: every sibling section now shares the non-excluded `### Summary` heading plus its excluded mandatory peers.
  4. Violation point: the sibling-similarity comparison counts the shared `### Summary` in every section's internal heading sequence, pushing three-plus siblings over the same-shape trigger (`design-mechanical-checks.py:1387` region emits the consolidation finding).
  5. Observable consequence: a consolidation finding ("TL;DR + Comparison table + Per-instance short bodies") fires on every well-formed post-rename design, exactly the false-identical case the exclusion-set comment says it exists to prevent.
- **Feasibility**: CONSTRUCTIBLE — follows from the exclusion set's own rationale comment; no unusual document needed.

#### C12 Violation scenario: D9 forward/backward links go stale under section-move
- **Invariant claim**: A frozen or evolving design stays internally consistent under `edit-design` mutations.
- **Violation construction**:
  1. Start state: design.md with D9-adapted links — section §B's opener says "this builds on the commit-window seam (§A)", §C's closer says "§D consumes this snapshot".
  2. Action sequence: an `edit-design` `section-move` relocates §A after §B (mutation kinds listed at `create-plan/SKILL.md:25`); the mutation edits §A only.
  3. Intermediate state: §B's opener now references forward to a section the reader has not met — the exact opaque-forward-reference failure YTDB-1163 names for leads.
  4. Violation point: no mechanical check covers narrative links (`design-mechanical-checks.py` resolves `Mechanics:` and `**Full design**` links only); the auditor re-audits changed slices, and §B may sit in a settled, unchanged slice.
  5. Observable consequence: a cold reader follows a "builds on" pointer backward into a section that is now ahead of them; the defect survives review rounds because no agent's scope contains it.
- **Feasibility**: CONSTRUCTIBLE — requires one section-move on a linked pair, a routine mutation.

**Assumption tests**

#### C2 Assumption test: the veteran's depth-allergy folds into the comprehension verdict without losing signal
- **Claim**: D6 — a third skeptical-veteran spawn is unnecessary; its signal folds into the comprehension reviewer's mental-model verdict.
- **Stress scenario**: The book veteran's distinct catches were content gaps needing new substance (`beta-feedback/SYNTHESIS.md:20-24`: histogram interpolation arithmetic, a missing stream section, an unanswered cache question) — depth judged against the system, not against the document's internal coherence. A document-only reviewer (no log, no code — `comprehension-review.md:45`) cannot see what the document omits entirely.
- **Code evidence**: The workflow covers that axis elsewhere: the absorption check does two-way log-coverage per round (`absorption-check.md`), the full-tier Phase-A technical/risk/adversarial reviews are code-grounded, and `prompts/design-review.md` treats insufficient material as a finding. The residual (depth-vs-code inside covered topics) belongs to the code-grounded gates, which exist at every tier that carries a design.
- **Verdict**: HOLDS — the fold loses nothing the roster does not catch elsewhere; D6's cost argument stands.

#### C8 Assumption test: "if Sonnet can follow the document, a mid-level developer can"
- **Claim**: D3 — Sonnet works as the mid-level-reader proxy for both reader roles.
- **Stress scenario**: A design with an over-dense mechanism section (folded causal chain, unglossed entity). The auditor's job is to *notice and report* the failure. A model that partially follows the passage without registering its own confusion returns a clean slice; the dual-clean loop then converges one round early with the defect in place. Both loop readers move to Sonnet simultaneously, so no strong-model prose gate remains between the author and the frozen design except the regex checker (mechanical subset) and later diff-scoped reviews.
- **Code evidence**: `readability-auditor.md:41-53` sets a detection bar ("reject two dismissals at their root…") that presumes the reviewer recognizes reducible confusion; `readability-auditor.md:70` assigns it exactly the cases regex cannot catch. The proxy claim as written covers reading, not detection. The counter-consideration: D2/D6's persona output makes comprehension failure more visible (a target reader narrating what it understood exposes glossing), which partially mitigates — but the log does not connect D2 to D3 this way.
- **Verdict**: FRAGILE — holds if the persona-output visibility argument is made explicit or one Opus backstop is kept; unargued as written.

#### C13 Assumption test: the 08:12Z roster and model-pin inventory is accurate
- **Claim**: `design-author`, `readability-auditor`, `comprehension-review` = opus; `absorption-check`, `fidelity-check` = sonnet already.
- **Stress scenario**: A wrong pin inventory would misprice D3's delta.
- **Code evidence**: frontmatter `model:` lines — `readability-auditor.md:5` opus, `comprehension-review.md:5` opus, `design-author.md:5` opus, `absorption-check.md:5` sonnet, `fidelity-check.md:5` sonnet. All match.
- **Verdict**: HOLDS.

#### C14 Assumption test: the 08:05Z YTDB-1163 summary matches the live issue
- **Claim**: Two proposed rules (self-contained plain-claim lead; body stands with the lead deleted, anaphor-opener test) and four acceptance sites.
- **Stress scenario**: A drifted summary would misdirect the D7/D8 BLUF-hardening work.
- **Code evidence**: Fetched YTDB-1163 (Bug, Major, dev-workflow, unresolved): proposed changes 1 and 2 and the four acceptance bullets (`house-style.md` § BLUF lead / § Orientation, `review-workflow-writing-style.md` BLUF criteria, self-check #9, exemplar pair beside `house-style.md:70-74`) match the log's entry verbatim in substance.
- **Verdict**: HOLDS.
