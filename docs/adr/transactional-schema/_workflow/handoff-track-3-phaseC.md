# Handoff: Phase C — Track 3 Completion (Review mode), pausing for a new session

**Paused:** 2026-06-25
**Phase:** C (Track 3 — Tx-local schema view, transactional enablement, metadata-write mutex)
**Context level at pause:** warning (user-requested pause; context was at 41%)
**Branch:** transactional-schema
**HEAD:** 3182105f95 "Workflow: record Track 3 Phase C review iteration 2 (2/3)"
**Unpushed:** 0 commits

## Durable artifacts on disk
- `plan/track-3.md` — track file. Progress shows iterations 1 and 2 complete; all 4 steps `[x]`; this handoff's PAUSED marker is in its Progress section.
- `plan/track-3/reviews/*.md` — the initial 11-reviewer fan-out files (`*-iter1.md`) plus the step-level files (`*-stepN-iter1.md`), all committed. Gate-check verdicts were returned inline (no files).
- Code on HEAD's ancestry: the two `Review fix:` commits — `d22abddb14` (BC1 blocker + BC3) and `a3549e874c` (BC2, BC6, and the test should-fixes).

## Pending decision
The Track 3 **Completion gate is open in Review mode**. The dimensional review loop has fully converged: 2 of 3 iterations, every in-scope finding VERIFIED, all gate-checks PASS, and the IntelliJ pre-PR inspection gate clean. The user picked **Review mode** at the completion panel and we are mid-loop. The user asked to pause here and continue the review in a new session.

On resume: re-enter the Review-mode Completion loop (the three-option panel), re-offer the one buffered fix below, accept any further observations, then Apply and close the track.

## Accumulated review-mode buffer (1 item) — re-offer on resume
The buffer is normally in-conversation only; it is persisted here because the pause is deliberate.

- **FIX_FINDING — doc-accuracy, no behavior change.** Correct the Javadoc overclaim that the mutex's session-keyed compare-and-clear is already safe against a concurrent foreign releaser. Two locations:
  - `core/.../db/MetadataWriteMutex.java` — class Javadoc (≈ lines 33–46) and the `releaseFor` Javadoc (≈ 98–105).
  - `core/.../db/DatabaseSessionEmbedded.java` — the `releaseMetadataWriteMutexForTx` Javadoc (≈ 2544–2553).
  - Content: state plainly that the release is race-free **in this track** because of the single owning-thread releaser plus the `metadataMutexEngaged` marker gate, and that the concurrent foreign-releaser path Track 7 adds requires converting `holder` to an `AtomicReference<Holder>` with a `compareAndSet`-gated `release()`. Leave the `metadataMutexEngaged` **field** Javadoc (DatabaseSessionEmbedded.java ≈ 247–251) as-is — it correctly claims only visibility.
  - Apply path: a `.java` source edit, so on Apply it goes through a fresh `level=track`, `mode=FIX_REVIEW_FINDINGS` implementer (the orchestrator does not edit source in Phase C), then re-render the panel.

## Q&A audit trail this session (do NOT re-derive)
The user probed six things in Review mode. All are settled; the only resulting actions are the one buffered doc fix plus two filed issues.
1. `MetadataWriteMutex.releaseFor` not using CAS → **safe in Track 3** (single owning-thread releaser; PSI-confirmed caller chain `releaseFor` ← `releaseMetadataWriteMutexForTx` ← {`ensureTxSchemaState` seed-failure catch, `FrontendTransactionImpl.close()`}). The doc overclaims the cross-thread guarantee → the buffered doc fix.
2. `TX_SCHEMA_STATE_KEY` map lookup vs a typed transaction field → recommended **no change** (unmeasurable ns gain, the hot per-record path uses snapshot reads, coupling the tx layer to the schema type; overlaps the deferred PF1 suggestion). User: "Settles it."
3. `metadataMutexEngaged` volatile-not-CAS → volatile gives **visibility** (for the Track-7 foreign reaper), not atomicity; the check-then-clear is safe today via the single-thread releaser. Same deferred-to-Track-7 concern as `releaseFor` → folded into the buffered doc fix.
4. `SchemaShared` single-subclass merge → out of scope; **filed YTDB-1165** (Task / Minor).
5. Drop the non-transactional schema-change path → **filed YTDB-1166** (Feature / Normal).
6. Where `TxSchemaState` is converted on commit → **it is not, in Track 3** — that is Track 4 (commit-time reconciliation, not yet implemented). `getChangedClasses()` has no production consumer yet; the commit paths do not reference `TxSchemaState`. Confirmed, no action.

## Verbatim re-present text (Track 3 completion summary — paste back on resume)
Track 3 (4 risk:high steps, 0 failed), commit range `8bbe3d2d18..HEAD`. Phase C review outcome: 11-reviewer fan-out → 1 blocker (BC1) plus 9 should-fix, all fixed and gate-verified across 2 iterations; IntelliJ inspection gate clean.
- **BC1 (blocker):** the tx-local seed re-entered the mutex engage. During `copyForTx`/`fromStream`, the inheritance rebuild propagates polymorphic index membership through the shared de-guarded index manager, re-entering `ensureTxSchemaState` while the marker is unset and the committed schema write lock is held — self-deadlock under `-da`, engage-order assert trip under `-ea`. Fires when the committed schema has an indexed superclass with a subclass. Fixed with a transient `seedingTxSchemaState` guard that no-ops seed-time ripples; regression test reproduces the shape.
- **BC3:** engage-order invariant promoted from assert-only to an always-on runtime throw. **BC2:** mutex release moved into `finally`. **BC6:** deferred index-handle reads short-circuit to the empty-result contract.
- Tests hardened: in-tx createIndex-duplicate / dropIndex-unknown divergence, same-tx superclass linking, drop-then-reference loud failure, deferred-handle and message assertions, blocking proven through the production seam, seed-failure-releases-permit regression.
- Cross-track contracts: Track 4 relies on `getChangedClasses()` carrying only genuinely-changed classes (BC1 fix guarantees it) and on the seed-failure release as the liveness complement to Track 7's heal; Track 5 must honor the deferred-handle read contract; CS1 (in-tx `createClass` allocates a durable collection in its own WAL atomic op, recovery-visible on rollback) is Track-4-owned per D2/D10.
- Deferred suggestions (not fixed): dead-code candidates `reresolvePropertyImpl` / `isEngagedBy` (likely Track-4/7 scaffolding), plus BC4, BC5, CQ1–CQ5, TB3, TB4, TC5, TC6, TX3, TX4, TS1–TS3, PF1, PF2. Workflow findings WS1–WS4 / WC1–WC2 on the track file are noted-not-fixed (append-only episodes / frozen Phase-1 sections / ephemeral, swept at Phase 4). No plan corrections.

## Resume notes
- **Do NOT redo:** the iteration count (2/3 in Progress); gate-checks already PASSed (all VERIFIED, no STILL OPEN / REGRESSION); the IntelliJ inspection gate (clean — only intentional `ObjectEquality` identity comparisons); the step-1 review-file rename (committed `0cbbda58c2`). Do NOT re-spawn reviewers or gate-check sub-agents — their output is on disk / already consumed.
- **On State C re-entry:** the precheck routes to Track Completion; `track-code-review.md` § Track Completion step 3 re-reads `git diff 8bbe3d2d18..HEAD` and re-compiles the episode before re-presenting. Re-enter Review mode, re-offer the buffered doc fix, accept further observations.
- **On Approve (with the buffered fix):** spawn a `level=track`, `mode=FIX_REVIEW_FINDINGS` implementer to apply the doc fix, re-render the panel, and on final Approve write the track episode + collapse the Track 3 description + mark `[x]` + commit `Mark Track 3 complete`.
- **Base commit for Phase C:** `8bbe3d2d18011f1ca6b1702a35e3c252ceba20b1`.
- **Tooling:** mcp-steroid (IDE) went DOWN mid-session; the last few audits (SchemaShared subclasses, the non-tx branches, the commit-path TxSchemaState check) used grep with reference-accuracy caveats. Re-probe `steroid_list_projects` at resume; re-verify any load-bearing symbol claim with PSI when it is back.
- **Clock note:** the Progress iteration entries are stamped 2026-06-17 from a transient sandbox clock skew; the real date is 2026-06-25. Cosmetic, in an ephemeral file — do not rewrite history for it.
- **Reflection candidates for the resuming session's phase-complete reflection** (one new finding filed this session; recurrences already filed — see below). The new finding: Case-3 of the workflow-machinery override routes `review-workflow-writing-style` / `-consistency` / `-context-budget` onto a Java track's own ephemeral `_workflow/` bookkeeping (the track file + dimensional-review manifests) during Phase C, producing should-fix prose findings on append-only episodes and frozen Phase-1 track sections that Phase C is contractually barred from editing — so they have no clean fix path (compounds YTDB-1126). Recurrences observed (already filed, do not re-file): YTDB-1112 (review-file step↔track naming collision — step-1 files renamed this session) and YTDB-1126 (track-file findings have no implementer fixer).
