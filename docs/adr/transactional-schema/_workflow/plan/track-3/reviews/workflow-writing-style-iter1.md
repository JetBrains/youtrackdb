<!--
MANIFEST
dimension: workflow-writing-style
step: track-3
iteration: 1
commit_range: 8bbe3d2d18011f1ca6b1702a35e3c252ceba20b1..HEAD
verdict: pass-with-suggestions
blocker_count: 0
should_fix_count: 4
suggestion_count: 0
finding_count: 4
high_water_mark: 4
evidence_base: "## Evidence base"
cert_index: "#### C1"
flags: []
index:
  - id: WS1
    sev: should-fix
    anchor: "### WS1"
    loc: "docs/adr/transactional-schema/_workflow/plan/track-3.md:158-159"
    cert: "#### C1"
    basis: read
  - id: WS2
    sev: should-fix
    anchor: "### WS2"
    loc: "docs/adr/transactional-schema/_workflow/plan/track-3.md:192-193"
    cert: "#### C2"
    basis: read
  - id: WS3
    sev: should-fix
    anchor: "### WS3"
    loc: "docs/adr/transactional-schema/_workflow/plan/track-3.md:234-236"
    cert: "#### C3"
    basis: read
  - id: WS4
    sev: should-fix
    anchor: "### WS4"
    loc: "docs/adr/transactional-schema/_workflow/plan/track-3.md:464-466"
    cert: "#### C4"
    basis: read
-->

## Findings

### WS1 [should-fix] Paired em dashes (`X — Y — Z` cadence) in the Self-commit-sites bullet

- **File**: `docs/adr/transactional-schema/_workflow/plan/track-3.md` (line 158-159)
- **Axis**: em-dash overuse
- **Cost**: two em dashes in one prose paragraph (one sentence), the paired-dash interrupting-clause cadence the house-style forbids
- **Issue**: Violates `house-style.md § Punctuation and typography → Em-dash discipline` ("At most one em dash per paragraph", "Never use the `X — Y — Z` triple-clause cadence"). The sentence reads: "Today every DDL operation runs with no user transaction open — the throw-guards above force it to — so this `executeInTxInternal` opens a top-level transaction...". The aside is set off by a paired em dash, forcing the reader to hold the main clause across the interruption.
- **Suggestion**: Demote the interrupting clause to a parenthetical or a separate sentence. Replace the two dashes with: "Today every DDL operation runs with no user transaction open (the throw-guards above force it to), so this `executeInTxInternal` opens a top-level transaction...".

### WS2 [should-fix] Paired em dashes in the Plan of Work `copyForTx` sentence

- **File**: `docs/adr/transactional-schema/_workflow/plan/track-3.md` (line 192-193)
- **Axis**: em-dash overuse
- **Cost**: two em dashes in one sentence within the Plan of Work opening paragraph
- **Issue**: Violates `house-style.md § Em-dash discipline`. The sentence reads: "...so `copyForTx` is `new SchemaShared(); copy.fromStream(session, committed.toStream(session))` — a serialize-then-re-parse round trip, not a field clone — and it holds the committed `SchemaShared.lock` write lock...". The gloss is wrapped in a paired em dash, the `X — Y — Z` cadence.
- **Suggestion**: Make the gloss a parenthetical and keep the contrast as a positive statement: "...so `copyForTx` is `new SchemaShared(); copy.fromStream(session, committed.toStream(session))` (a serialize-then-re-parse round trip, not a field clone), and it holds the committed `SchemaShared.lock` write lock...".

### WS3 [should-fix] Paired em dashes in the Ordering-constraints sentence

- **File**: `docs/adr/transactional-schema/_workflow/plan/track-3.md` (line 234-236)
- **Axis**: em-dash overuse
- **Cost**: two em dashes in one sentence in the "Ordering constraints" prose paragraph
- **Issue**: Violates `house-style.md § Em-dash discipline`. The sentence reads: "...it must be provably first on **every** write path — including the de-guarded membership sites that themselves take the index-manager and index exclusive locks, and direct `SchemaEmbedded` callers — not just the canonical `SchemaProxy.createClass` path.". The included-cases clause is set off by a paired em dash, the `X — Y — Z` cadence.
- **Suggestion**: Split into two sentences or use a colon for the inclusion list: "...it must be provably first on **every** write path, including the de-guarded membership sites that themselves take the index-manager and index exclusive locks, and direct `SchemaEmbedded` callers. That covers more than the canonical `SchemaProxy.createClass` path.".

### WS4 [should-fix] Paired em dashes in the Step 4 `What was done` paragraph

- **File**: `docs/adr/transactional-schema/_workflow/plan/track-3.md` (line 464-466)
- **Axis**: em-dash overuse
- **Cost**: two em dashes in one sentence of the Step 4 episode prose body (the `### Step 4 — commit ...` heading's own dash is exempt template format and not counted)
- **Issue**: Violates `house-style.md § Em-dash discipline`. The sentence reads: "Engage runs inside `DatabaseSessionEmbedded.ensureTxSchemaState` — the single seam every de-guarded write path (proxy `resolveForWrite` plus the three `IndexManagerEmbedded` de-guards) funnels through — strictly before the `copyForTx` seed...". The seam gloss is set off by a paired em dash, the `X — Y — Z` cadence.
- **Suggestion**: Move the gloss to a parenthetical and keep the placement clause inline: "Engage runs inside `DatabaseSessionEmbedded.ensureTxSchemaState` (the single seam every de-guarded write path funnels through: proxy `resolveForWrite` plus the three `IndexManagerEmbedded` de-guards), strictly before the `copyForTx` seed and above any shared metadata lock.".

## Evidence base

#### C1 — em-dash count, Self-commit-sites bullet (L155-172)
- Banned-vocabulary sweep over the file: 0 Tier 1-4 hits. Banned-sentence-pattern sweep: 0 negative-parallelism / throat-clearing / closer hits (L100 "blocks rather than racing" is a positive contrast, not "it's not X, it's Y"). BLUF and plain-language passes clean on this bullet.
- Em-dash count for the blank-line-bounded bullet at L155-172 = 2, both in the one sentence at L158-159 around the interrupting clause "the throw-guards above force it to". Triple-clause `X — Y — Z` cadence per `§ Em-dash discipline`. Confirmed finding.

#### C2 — em-dash count, Plan of Work opening paragraph (L188-195)
- Em-dash count for the paragraph = 2, both in the one sentence at L192-193 around the gloss "a serialize-then-re-parse round trip, not a field clone". Over the one-per-paragraph cap and matches the forbidden `X — Y — Z` cadence. Confirmed finding.

#### C3 — em-dash count, Ordering-constraints paragraph (L233-240)
- Em-dash count for the paragraph = 2, both in the one sentence at L234-236 around the inclusion clause. Over the cap, `X — Y — Z` cadence. Confirmed finding. (The sibling "Track-3 commit contract" paragraph at L223-231 carries one em dash and passes.)

#### C4 — em-dash count, Step 4 `What was done` prose (L462-483)
- The episode anchor `### Step 4 — commit 1e99e6dc73 ...` (L461) carries one em dash, exempt as the ExecPlan structured-field heading template (`house-style.md § Structural rules` "Section length cap exception" category 1; the same template exemption covers its anchor punctuation). The `**What was done:**` prose paragraph that follows carries 2 em dashes, both in the one sentence at L464-466 around the seam gloss. Over the cap, `X — Y — Z` cadence. Confirmed finding.

#### Survived checks (one-line roster)
- Banned vocabulary (Tier 1-4): no hits across the file. Survived.
- Banned sentence patterns (negative parallelism, roundabout negation, throat-clearing, closers, prompt-restating, knowledge-cutoff): no hits. The several "not only X" / "not just X" / "NOT" usages (L65, L115, L171, L179, L206, L454, L457, L512, L525) are emphasis or scope qualifiers in registry-terse log/plan prose, not the "it's not X, it's Y" anti-pattern. Survived.
- BLUF lead per section: Purpose (L4), Context and Orientation (L146), Plan of Work (L188) each open with the conclusion / baseline, not background. Survived.
- Plain language / orientation: the Surprises and Decision Log sections are registry-terse log register (shared-vocabulary block per `house-style.md § Orientation`); their dense semicolon-joined bullets (e.g. L68-74) are acceptable under that carve-out, not a finding. Survived.
- Section length: no non-exempt prose unit over the soft cap carries a padding pattern (`house-style.md § Padding-based finding criterion`); the episode `**What was done:**` blocks are template-bound ExecPlan structured fields. Survived.
