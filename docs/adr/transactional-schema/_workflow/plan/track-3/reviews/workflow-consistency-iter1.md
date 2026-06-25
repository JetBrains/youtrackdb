<!--MANIFEST
dimension: workflow-consistency
target: docs/adr/transactional-schema/_workflow/plan/track-3.md
iteration: 1
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, exempt_reason: "(a) no refutation or certificate phase to persist" }
findings:
  - id: WC1
    sev: Minor
    anchor: "#wc1-minor--signatures-line-advertises-a-track-7-releasefor-parameter"
    loc: "docs/adr/transactional-schema/_workflow/plan/track-3.md:566"
    cert: n/a
    basis: judgment
  - id: WC2
    sev: Minor
    anchor: "#wc2-minor--step-1-episode-says-copyfortx-returns-a-txschemastate"
    loc: "docs/adr/transactional-schema/_workflow/plan/track-3.md:296"
    cert: n/a
    basis: judgment
-->

## Findings

### WC1 [Minor] — Signatures line advertises a Track-7 `releaseFor` parameter
- **File:** `docs/adr/transactional-schema/_workflow/plan/track-3.md` (line 566)
- **Axis:** skill/agent cross-reference (signature-vs-implementation drift inside the track file)
- **Cost:** the track's own `## Interfaces and Dependencies` Signatures block lists a method shape this track does not ship and contradicts the track's repeated "ordinal is Track 7" narrative.
- **Issue:** The Signatures line reads `MetadataWriteMutex.engage(session) / releaseFor(session, ordinal)`. The shipped method (Referent: `core/.../db/MetadataWriteMutex.java:107`) is single-arg `releaseFor(DatabaseSessionEmbedded session)` — there is no `ordinal` parameter in this track. The track file itself is explicit that the ordinal is a Track-7 addition (lines 69-70 "Track 7 widens the Holder to `(session, ordinal, thread)`"; line 275 "the `ordinal` and CAS-clear are Track 7"; line 497 "the acquire ordinal ... deferred to Track 7"; lines 510-511). So the Signatures line advertises the future Track-7 shape, not the `(session)` shape this track delivers, contradicting both the shipped code and four other statements in the same file. `engage(session)` and `copyForTx ... SchemaShared` on the same line are correct.
- **Suggestion:** Change the Signatures entry to `releaseFor(session)` to match the shipped single-arg method and the track's own Track-7-defers-ordinal narrative; if the intent was to forward-reference the Track-7 shape, mark it explicitly (e.g. "`releaseFor(session)` this track; `releaseFor(session, ordinal)` after Track 7").

### WC2 [Minor] — Step 1 episode says `copyForTx` returns a `TxSchemaState`
- **File:** `docs/adr/transactional-schema/_workflow/plan/track-3.md` (line 296)
- **Axis:** skill/agent cross-reference (episode narrative vs shipped signature and the track's own Signatures line)
- **Cost:** the Step 1 episode states the wrong return shape for `copyForTx`, disagreeing with both the shipped method and the track's Signatures line.
- **Issue:** The Step 1 episode says `SchemaShared.copyForTx` "returns it inside a `TxSchemaState` that also holds the changed-class set." The shipped method (Referent: `core/.../metadata/schema/SchemaShared.java:177`) returns a bare `SchemaShared` copy, not a `TxSchemaState`. The track's own Signatures line (567) correctly records `SchemaShared.copyForTx() : SchemaShared`, and the Step 2 episode (line 350) correctly attributes the `TxSchemaState` wrap to `resolveForWrite`/`ensureTxSchemaState` ("`resolveForWrite` seeds it via `copyForTx`"). The Step 1 episode is the lone disagreeing statement.
- **Suggestion:** Reword the Step 1 episode so `copyForTx` "returns a fresh private `SchemaShared`," and (if useful) note that `ensureTxSchemaState`/`resolveForWrite` is what wraps it in the `TxSchemaState`, matching the Signatures line and the Step 2 episode.

## Evidence base
