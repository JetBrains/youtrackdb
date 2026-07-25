# Crash-Safety / Durability + Fail-Closed Robustness Review — Track 8 Step 6, iteration 1

- **Commit under review:** `612340c91d` ("Validate dump info fields and add migration runbook")
  on branch `transactional-schema`, diffed against parent `fb9867e751`; HEAD at review time
  `f2cebaa9c3` (track-file/design-drafts update only — `DatabaseImport.java` and the test file
  are byte-identical between `612340c91d` and HEAD, so all line citations below are HEAD line
  numbers).
- **Perspective:** crash-safety / durability + fail-closed robustness. Finding IDs CS75+.
- **Binding spec:** `plan/track-8.md` Step 6 bullet + Step 6 episode (the ten red-first pins,
  the SR3 record, the FM-M10 as-built note); `plan/track-8-design-drafts.md` Q-M2 ruling
  (drafts:627-641), SR1/SR2/SR3 (drafts:659-696), R1 dispatch (drafts:373-376), §A2
  CS38/WI11 (drafts:825-840), M2.b-5 (drafts:431-435), FM-M10/M11/M12 (drafts:449-451),
  WI12a/b (gate-1 table).
- **In-scope files:** `core/.../core/db/tool/DatabaseImport.java` (primary — `importInfo`
  rework :779-893, `runPreFlightChecks` matrix :442-521, `>= 16` redirect :447-453),
  `core/.../core/db/tool/DatabaseImportInfoMatrixTest.java` (new); supporting reads:
  `JSONReader.java` (reader semantics), `DatabaseImportHardeningTest.java`, parent-commit
  `importInfo` (fb9867e751:697-733), `DatabaseExport.exportInfo` (:504-534, honest field
  order).
- **Prior-review residuals NOT re-filed:** CS64 (justified-deferred `importRecord` swallow),
  CS67 (section-order-blind), CS68 (post-root in-member junk), CS70-CS74 (resource/session/
  global-state hygiene + tag-blind brokenRids + detectFraming reset edge) — all recorded in
  `crash-safety-step5-iter1.md` and dispositioned by the Step-5 gate.
- **Mode:** read-only; no Maven; no production/test file modified; only this report written.

## 0. Review obligations (criteria + premises)

**Criteria (the charter's four, restated as checkable obligations):**

- **O1 (charter #1 — SR1 boundary):** every NEW throw site introduced by this diff (the
  dangling/malformed field-name guard, the WI12a exporter-version parse-fail-closed arm, the
  `>= 16` redirect, the three schema-version arms, the type-violation judgment) is classified
  pre-flight (fires before `runDeferredImportPreamble()`'s first statement) vs post-mutation;
  every ruled matrix rejection is genuinely pre-mutation. The claimed parent desync defect
  (dangling info field name → rejection AFTER the preamble) is confirmed at the parent and
  confirmed closed at this commit for the dangling-NAME shapes.
- **O2 (charter #2 — matrix fail-closed):** every adversarial-honest `info` shape traced along
  the changed paths lands in exactly one verdict — rejected-loudly / tolerated-by-design /
  silent-accept (defect) — with no malformed shape slipping to a lenient path and no shape
  able to desync the reader past the pre-flight boundary.
- **O3 (charter #3 — `>= 16` ordering):** no input shape — including re-declaration attempts
  against Step 5's first-declaration latch — lets a dump declaring `>= 16` reach any
  `>= 15`-keyed strictness arm (pre-flight or section loop) or the lenient path.
- **O4 (charter #4 — `<= 14` no-regression):** the ONLY version-ungated new rejection is the
  malformed-field-name guard; every other new check is version-gated; reader CONSUMPTION on
  declared-legacy dumps is byte-identical to the parent arm-by-arm.

**Premises (traced code semantics all verdicts below rely on):**

- P1: `JSONReader.readNext(NEXT_IN_OBJECT)` scans for `,` or `}` at CURLY-brace depth 0
  outside strings. It tracks `{`/`}` nesting (JSONReader.java:242-248) and string state
  (:238-240, :258-261) but does NOT track `[`/`]` — square brackets are structurally
  invisible to the value scanner.
- P2: after a nested object value's closing `}` decrements `openBrackets` back to 0, the
  SAME character is immediately re-checked against the separator set (:250-256) and `}` IS in
  `NEXT_IN_OBJECT` — so the nested object's own closing brace terminates the read and becomes
  `lastChar()`. The caller cannot distinguish it from the enclosing object's close.
- P3: `readNext` returns SILENTLY with stale `value`/`lastCharacter` when the underlying
  reader is not `ready()` (:207, :213), and on EOF mid-scan it breaks out (:268-271),
  suffers the unconditional separator strip (:299-301) — losing the token's LAST data
  character — and returns the remainder as `value`. Truncation loudness on the v15 path comes
  from the gzip decoder, not the JSON layer (Step-5 P1, re-verified).
- P4: `readString(FIELD_ASSIGNMENT)` is string-aware: a `:` INSIDE a quoted field name does
  not terminate the scan; the terminating `:` is the first one outside quotes at depth 0.
  Quote-stripping is `substring(1, lastIndexOf('"'))` (:118-126).
- P5: `importInfo`'s field loop is `while (jsonReader.lastChar() != '}')`
  (DatabaseImport.java:792) with NO `hasNext()`/EOF guard; the section loop in
  `importDatabase` DOES carry one (:311).
- P6: the honest v15 exporter writes info fields in the fixed order `name`,
  `exporter-version`, `engine-version`, [`engine-build`], `storage-config-version`,
  `schema-version`, `schemaRecordId`, `indexMgrRecordId`, [`best-effort`]
  (DatabaseExport.java:504-534) — all scalar values, no object/array values, no structural
  characters in names.
- P7: `runDeferredImportPreamble` (:530-547) remains the FIRST target-mutating code on the
  import path, called from exactly one site (:335), strictly after `importInfo` (:329)
  succeeded with a parseable version (:333) and `runPreFlightChecks` (:334) returned
  (Step-5 review §3, re-verified against this diff — the diff adds no new call site and no
  code before :335 that writes to the target).
- P8: `exporterVersion` re-declaration with a DIFFERING value is rejected at parse by the
  Step-5-fix latch (:818-822, present at the parent); a same-value re-declaration is
  tolerated at parse and caught by WI10c under v15.

## 1. Criterion 1 — SR1 boundary for ALL new rejection points

**Decision criterion:** a new throw site is SR1-compliant if (a) it is reachable on the
first-info-section flow only BEFORE `runDeferredImportPreamble()` executes its first
statement, and (b) any post-mutation reachability (second info section after a legitimate
unlock) is structural-tampering territory, loud, and condemn-target per SR1.

**The single mutation gate re-verified (P7).** The diff touches only `importInfo` internals
and `runPreFlightChecks` internals; the `case "info"` arm's ordering
(`importInfo(); if (exporterVersion != -1) { runPreFlightChecks(); runDeferredImportPreamble(); }`,
:328-336) is unchanged from Step 5. Every throw INSIDE `importInfo` or INSIDE
`runPreFlightChecks` on the first info section therefore precedes the preamble structurally.

**New throw sites — exhaustive enumeration:**

| # | Throw site | Location | First-info flow | Second-info flow (post-unlock) |
|---|---|---|---|---|
| T1 | dangling/malformed field-name guard | :792-796 | **pre-mutation** (thrown mid-`importInfo`, before return to the case arm) | post-mutation, loud, condemned (tampered late info — SR1-sanctioned) |
| T2 | WI12a unparseable exporter-version | :799-811 | **pre-mutation**; thrown BEFORE the latch assignment, so `exporterVersion` stays `-1` and the preamble stays locked even if the exception were swallowed upstream (it is not) | post-mutation, loud, condemned |
| T3 | `>= 16` reject-with-redirect | :447-453 | **pre-mutation** — first statement of `runPreFlightChecks`, which precedes `runDeferredImportPreamble` at the only call site | unreachable post-unlock with a differing version (the P8 latch rejects the re-declaration first); same-version 16 re-run impossible (16 throws on first run) |
| T4 | schema-version malformed | :460-465 | **pre-mutation** | reachable only via duplicate info (loud either way) |
| T5 | schema-version missing | :466-470 | **pre-mutation** | idem |
| T6 | schema-version out-of-range | :471-482 | **pre-mutation** | idem |
| T7 | known-field type-violation judgment | :483-488 | **pre-mutation** (violations are collected during `importInfo`, judged at :483 BEFORE the preamble call) | post-mutation loud if a second info section adds a violation — duplicate-info shape, condemned anyway |

Nothing else in the diff throws. The unknown-field logging (:490-493) and the two capture
lists (:185, :188) are side-effect-free with respect to the target. **O1 verdict on the
enumerated sites: holds** — with one boundary carve-out found on the VALUE side of the info
parse, worked in §2 (CS75): a shape exists where NO matrix rejection is warranted, the
pre-flight passes on a desync-truncated capture, and the inevitable rejection fires
post-mutation. That shape does not falsify any T-row above (each enumerated rejection, when
IT fires, fires pre-mutation) but it does falsify the step's broader "no malformed info shape
desyncs past the boundary" claim.

### 1.1 The claimed desync fix — parent defect confirmed, fix confirmed for the NAME shapes

**Parent defect trace (fb9867e751).** Parent `importInfo` (fb9867e751:697-733) reads the
field name with the string-aware until-the-colon scan (P4) and has NO name validation. Take
the test's exact shape — an honest v15 dump with `,"dangling-crash"` spliced after the
`indexMgrRecordId` value (the mid-write crash shape: name written, value missing). At the
parent:

1. All honest fields parse; `exporterVersion = 15` is latched.
2. `readString(FIELD_ASSIGNMENT)` for the dangling name scans PAST the info object's closing
   `}` (not a `:`), past the `,`, through the next section's quoted tag, stopping at the
   colon after `"collections"`. Quote-strip yields
   `dangling-crash"},"collections` — a garbage "field name" containing `"`/`}`/`,`.
3. Parent: not `exporter-version`/`best-effort` → else-arm `readNext(NEXT_IN_OBJECT)` —
   consumes into the collections section (square brackets invisible, P1), and the loop keeps
   consuming until some `}` at depth 0 becomes `lastChar` → the field loop EXITS deep inside
   the collections section; the trailing `COMMA_SEPARATOR` read consumes further.
4. `importInfo` returns; `exporterVersion == 15` → pre-flight passes → **the preamble
   MUTATES the target** → the section loop reads desynced garbage as a tag → unsupported-tag
   or parse error, POST-mutation.

This matches the step's red-first record for `danglingInfoFieldIsRejected` (RED at the parent
with `AssertionError: the pre-flight rejection must leave the target unmutated` — the
empirical witness for step 4).

**Fix confirmed for every dangling-NAME shape.** At this commit the guard (:792-796) fires at
step 2, INSIDE `importInfo`, before the case arm can reach the preamble — pre-mutation. Shape
coverage argument: a dangling name mid-object always makes the until-colon scan swallow at
least the enclosing `}` plus the following section's quoted tag (the next `:` in the stream
sits after a section or field name), so the parsed "name" always contains at least one of
`"` `}` `,` — the guard's character class — or, at end-of-stream, the scan EOF-breaks and the
quote-strip arithmetic throws loudly (P3/P4; pre-mutation via the outer catch). For the v15
exporter's ACTUAL crash shape (gzip, truncated mid-write) the decoder rejects even earlier
(truncated member, Step-5 S7/S8 — unchanged). A dangling name in a SECOND info section fires
the guard post-mutation — loud, condemned, tampering territory. **Criterion 1 verdict on the
desync fix: confirmed for the name side; the value side has the CS75 escape (§2).**

## 2. Criterion 2 — fail-closed gaps in the matrix (adversarial shape enumeration)

**Decision criteria.** (D1) A shape whose declared version is `>= 15` and whose info content
violates the ruled matrix must reject loudly PRE-mutation. (D2) A shape the ruling tolerates
(unknown fields, Q-M2(4)) must import with the reader position INTACT — tolerance achieved by
desync-luck is not tolerance. (D3) A shape with no usable version must ride SR2, never the
lenient path.

Every shape below traced end-to-end through `importInfo` (:779-866) +
`runPreFlightChecks` (:442-521) + the section loop, with the JSONReader premises P1-P5.

| # | Info shape | Traced outcome | Verdict |
|---|---|---|---|
| I1 | `"exporter-version": "15"` (quoted string) | raw token keeps quotes → `Integer.parseInt` fails → WI12a throw :805-811 naming the raw value, pre-mutation, preamble locked | rejected-loudly ✓ |
| I2 | `"exporter-version": 15.0` / `1e2` / out-of-int-range / `null` | parseInt fails → WI12a, pre-mutation | rejected-loudly ✓ |
| I3 | `"exporter-version":` empty (`""`, bare `,`) | raw empty/`""` → parseInt fails → WI12a | rejected-loudly ✓ |
| I4 | misspelled `"exporter-versio": 15` | unknown field, value skipped cleanly (scalar); no version declared → first non-info tag → SR2 :319-325 (or EOF arm :371-375), pre-mutation — rides SR2 exactly as the charter asks | rejected-loudly ✓ (by SR2 design) |
| I5 | `"schema-version"` missing on a v15 dump | pre-flight :466-470, pre-mutation, names the supported range | rejected-loudly ✓ (test-pinned) |
| I6 | `"schema-version": 6` (== MIN == CURRENT) | in range 6..6 → accepted | tolerated-by-design ✓ |
| I7 | `"schema-version": 5` (MIN-1) / `7` (CURRENT+1) | range throw :471-482 naming declared vs supported, direction-specific redirect | rejected-loudly ✓ (both test-pinned) |
| I8 | `"schema-version": "6"` / `6.5` / `null` (v15) | `Long.parseLong` fails → captured raw → malformed throw :460-465, pre-mutation | rejected-loudly ✓ |
| I9 | `"schema-version"` repeated in one info object with differing values | last-write-wins (no latch on this field — only `exporter-version` is latched) — but schema-version carries NO dispatch power (validated, never keyed on), so a tamperer gains nothing over writing the passing value directly | tolerated (null; no new power) |
| I10 | `"name": 42` (known optional, wrong type, v15) | violation collected :872-877 → judged :483-488, pre-mutation | rejected-loudly ✓ (test-pinned) |
| I11 | same as I10 but declared v14 | violation collected, NEVER judged (`>= 15` gate :454) → lenient path unchanged | tolerated-by-design ✓ (R1/FM-M12; test-pinned via alien schema-version) |
| I12 | unknown field, scalar value (incl. strings containing `{`/`,` INSIDE quotes, empty value, single-element array `[42]`, array of strings with in-string commas) | scanner string-awareness/P1 keep the read clean → tolerated + logged at pre-flight | tolerated-by-design ✓ (test-pinned for the scalar case) |
| I13 | unknown field, ARRAY value with top-level commas (`"weird": [1,2]`) | value read stops at the FIRST in-array comma (P1: `[` untracked) → next "field name" scan swallows `2],"<next>"` → contains `]`/`,`/`"` → the T1 guard fires, pre-mutation. Message says "dangling or malformed field" — misleading for a legal-JSON shape, but loud and pre-mutation | rejected-loudly ✓ (message nit folded into CS75's remedy) |
| I14 | unknown field, OBJECT value (`"future": {"a": 1}`), placed mid-object AFTER `exporter-version` and `schema-version` with at least one honest field following | **P2 desync**: the nested `}` terminates the value read AND becomes `lastChar` → the field loop exits BELIEVING the info object closed; the trailing `COMMA_SEPARATOR` read consumes only the next `,`; `importInfo` returns with a truncated-but-valid capture → **pre-flight PASSES → preamble MUTATES** → the remaining info fields are consumed as section tags → `Invalid format. Found unsupported tag 'schemaRecordId'` — **POST-mutation** rejection of a shape the Q-M2(4) ruling tolerates | **defect → CS75 (should-fix)** |
| I15 | same as I14 but the object-valued unknown field is LAST in the info object | premature loop exit at the nested `}`; the `COMMA_SEPARATOR` read swallows the REAL info `}` plus the following comma → the reader lands exactly on the next section tag → import proceeds and SUCCEEDS, field logged | silent-accept-by-accident — outcome matches the ruling's tolerance, but only via double-luck (folded into CS75) |
| I16 | same as I14 but the object field precedes `exporter-version` | premature exit with `exporterVersion == -1` → preamble locked → next tag → SR2, pre-mutation (wrong message for a tolerated shape, but fail-closed) | rejected-loudly ✓ (message nit, folded into CS75) |
| I17 | v15, `"best-effort": "true"` (quoted) | violation :888-892 → pre-flight reject, pre-mutation | rejected-loudly ✓ (improvement over the parent's silent quote-strip accept-as-marker) |
| I18 | declared-v14, `"best-effort": "true"` (quoted) | NEW: raw keeps quotes → `parseBoolean("\"true\"")` = **false** → marker silently dropped → ack gate passes → lenient import proceeds. PARENT: `readBoolean` quote-stripped → `true` → the marker-keyed gate (SR3) REJECTED. Fail-OPEN divergence on the legacy path | **defect (micro) → CS76 (suggestion)** |
| I19 | `"exporter-version": -1` literal | indistinguishable from undeclared → preamble stays locked → SR2 | rejected-loudly ✓ (Step-5 S3 quirk, unchanged) |
| I20 | `"exporter-version": +15` | `parseInt` accepts a leading `+` → treated as 15 | tolerated (null; harmless parse leniency) |
| I21 | truncated PLAIN stream mid-info (legacy file dump or stream ctor; e.g. `{"info":{"exporter-version":14` EOF) | P3: EOF-break strips the last digit (`14` → `1`, a WRONG version silently latched), then `in.ready()` is false forever → `importInfo`'s guardless loop (P5) spins on stale reads; the new default arm additionally grows `unknownInfoFields` each spin → hang, eventually OOM. Gzip arm: decoder-loud ✓; this is plain-arm only | **defect (pre-existing, in-seam) → CS78 (suggestion)** |
| I22 | legal-JSON quoted field name containing structural chars (`{"weird:name": 1}`, `{"a,b": 1}`) — any declared version | P4: the parent scanned these CLEANLY (in-string `:`/`,` invisible to the until-colon scan) and tolerated them as unknown fields; the NEW ungated guard rejects them (quote-strip yields `weird:name` → contains `:`) | rejected-loudly — but contradicts the recorded "acceptance is unchanged / such a shape can never parse cleanly" justification → **CS77 (suggestion)** |
| I23 | empty info object `"info":{},` | field-name scan swallows `},"<next-tag>"` → guard fires pre-mutation (parent: desync chaos) | rejected-loudly ✓ (strict improvement) |

**CS75 concrete counterexample (I14, honest field order P6):** take an honest v15 dump and
splice `"future":{"a":1},` between `"schema-version":6` and `"schemaRecordId":"…"`; re-gzip
(valid single member). Trace: `name`/`exporter-version`(15)/`engine-version`/
`storage-config-version`/`schema-version`(6) parse; `future` → default arm
`readNext(NEXT_IN_OBJECT)` → P2 → value=`{"a":1`-truncated, `lastChar=='}'` → field loop
exits; `COMMA_SEPARATOR` read consumes the `,`; pre-flight: version 15 ✓, schema 6 ✓, no
violations ✓, gzip ✓ → PASSES → `runDeferredImportPreamble()` DROPS the target's default
classes and indexes → section loop reads `schemaRecordId` as a TAG → SR2 passes (version
declared) → `default ->` unsupported-tag throw :366-367 — **post-mutation**, on a dump whose
info section is legal JSON and whose only irregularity is a ruled-tolerated unknown field.
The target is condemned (SR1) for an input the matrix was ruled to tolerate, and the failure
class is the SAME reader-desync-past-the-boundary defect the step's headline fix closed on
the name side. Alternative hypothesis checked: could the WI10c duplicate/presence checks or
the manifest tally catch it earlier/pre-mutation? No — the throw fires before any further
section parses, and pre-flight had already passed; nothing pre-mutation ever sees the
remnant fields.

## 3. Criterion 3 — `>= 16` ordering under inconsistency

**Decision criterion:** for every input shape carrying a `>= 16` declaration anywhere, the
declaration must terminate in the redirect (or a strictly-earlier loud rejection) before any
`>= 15`-keyed arm — pre-flight (:454-520), section-loop (`clusters` :343-348, `manifest`
:359-364), or post-loop strictness (:380-382) — evaluates with `exporterVersion >= 15`, and
before the lenient (`<= 14`) path can be entered.

**Exhaustive path enumeration (the `>= 15`-keyed consumers of `exporterVersion` are exactly:
:343, :359, :380, :454, :502; the lenient-path key is the absence of all of them):**

1. **16 declared in the first info section:** `importInfo` latches 16 (no `< 14` serializer
   swap) → `exporterVersion != -1` → `runPreFlightChecks` → the redirect (:447-453) is the
   FIRST statement — it fires before the schema-version arms, before Q-M3 (:502), before the
   ack gate (:515), and before `runDeferredImportPreamble` (:335). The section-loop and
   post-loop `>= 15` arms are unreachable (the throw unwinds importDatabase). Pre-mutation.
   Test-pinned (`v16DumpIsRejectedWithRedirectNamingBothVersions`, with target-unmutated
   assert). ✓
2. **15 first, 16 later (duplicate info section or repeated field):** the P8 latch
   (:818-822) rejects the re-declaration the moment it parses — the strictness gate and all
   `>= 15` arms only ever see 15, the honest first declaration. Loud; post-mutation if the
   second section trails the preamble — tampering territory, SR1-condemned. No downgrade or
   upgrade of arming possible. ✓
3. **16 first, 15 later:** unreachable — arm 1's redirect fires at the first info section's
   pre-flight, before any second section parses. ✓
4. **14 first, 16 later:** the first info legitimately unlocks the LENIENT preamble (R1);
   the second declaration hits the latch (16 != 14) → loud rejection. The v16 declaration
   never arms anything; the dump was processed as a declared-14 dump up to the rejection —
   exactly the latch's contract. ✓
5. **`>= 16` via numeric disguise:** out-of-int-range (`4294967311`), float, exponent —
   `Integer.parseInt` rejects them all → WI12a throw, pre-mutation; no wraparound path to a
   small value exists. ✓
6. **16 declared, plain-JSON framing:** the redirect (:447) fires BEFORE Q-M3 (:502) — the
   operator gets the redirect (the more actionable message), not the framing complaint.
   Ordering intent (CQ24 resolution) honored. ✓
7. **16 declared, `manifest`/`clusters` tag BEFORE info:** SR2 (:319-325) fires at the first
   non-info tag with version `-1` — pre-mutation, preamble locked. ✓
8. **16 declared after an object-valued unknown field earlier in the SAME info section:** if
   the weird field precedes `exporter-version`, the I16 desync exits `importInfo` with
   version `-1` → SR2 fires instead of the redirect — still pre-mutation and loud, but the
   redirect's both-versions message is lost (a forward-compat message-quality nuance for a
   richer v16 info shape; folded into CS75's remedy note). If it follows the version, the
   redirect fires normally at pre-flight (the desync-truncated capture does not matter — the
   version is already latched). ✓ (no strictness-arm exposure either way)

**Verdict: null finding.** No path exists from a `>= 16` declaration to any `>= 15` arm or to
the lenient path; the redirect's ahead-of-everything placement plus the parse-time latch close
the matrix in both re-declaration directions. The only blemish is arm 8's message degradation
(not a safety gap).

## 4. Criterion 4 — no regression on `<= 14`

**Decision criteria.** (D1) The only version-UNGATED new rejection is the field-name guard.
(D2) Every other new check is `>= 15`/`>= 16`-gated or fires only where the parent also threw.
(D3) Reader CONSUMPTION per info field arm is byte-identical to the parent for declared-legacy
dumps (same scan primitives, same separators).

**Gating audit of every new arm:** the `>= 16` redirect — value-gated by definition; the
schema-version trio, type-violation judgment, unknown-field logging — all inside
`if (exporterVersion >= 15)` (:454); Q-M3 and the ack gate — unchanged from the parent (the
ack gate's marker-keyed width is now the RECORDED SR3 ruling, discharging Step-5's CS66 —
inline comment :509-513 cites it; no longer an unrecorded deviation). The WI12a arm (:799-811)
is version-ungated but fires only where the parent threw a bare `NumberFormatException` from
`readInteger` at the SAME reader position — acceptance identical, message improved (naming
the raw value). D1/D2 hold: the guard (:792-796) is the sole ungated NEW rejection, exactly
as the step's as-built note claims.

**Consumption byte-identity, arm-by-arm vs fb9867e751:697-733:**

| Field | Parent primitive | New primitive | Consumption | Parse/semantics |
|---|---|---|---|---|
| `exporter-version` | `readInteger(NEXT_IN_OBJECT)` = `readNext` + `parseInt(value.trim())` | `readNext(NEXT_IN_OBJECT)` + `trim` + `parseInt` | identical | identical (latch unchanged, `< 14` serializer swap unchanged in position) |
| `schema-version` | else-arm `readNext(NEXT_IN_OBJECT)` | `readNext(NEXT_IN_OBJECT)` + capture | identical | capture judged v15-only ✓ |
| `name`/`engine-version`/`engine-build`/`schemaRecordId`/`indexMgrRecordId`/`storage-config-version` | else-arm `readNext(NEXT_IN_OBJECT)` | `readInfoFieldRawValue()` (same `readNext`) + violation collect | identical | violations judged v15-only ✓ |
| `best-effort` | `readBoolean` = `readString(NEXT_IN_OBJECT, …, skip=DEFAULT_JUMP)` + `parseBoolean` | `readNext(NEXT_IN_OBJECT)` + `trim` + `parseBoolean` | identical (iSkipChars affects buffering, not scan termination) | **DIVERGES for quoted/whitespace-embedded tokens** — parent quote-stripped (`"true"` → marker SET), new preserves quotes (`"true"` → parseBoolean false → marker DROPPED). Legacy fail-open micro-regression → **CS76** |
| unknown fields | else-arm `readNext(NEXT_IN_OBJECT)` | same + list append | identical | logging v15-only ✓ (legacy stays silent, byte-for-byte) |

**The ungated guard's legacy acceptance delta (D1's fine print):** the as-built note
justifies ungating with "no honest dump of any version produces a structural-character field
name" (true — P6 and the historical info field set are all plain identifiers) and "the shape
can never parse into a clean import — the reader desyncs" (FALSE for one sub-family: quoted
names whose structural characters sit INSIDE the quotes, I22 — the parent parsed those
cleanly and tolerated them). Acceptance IS therefore changed for hand-edited/exotic legacy
dumps carrying such names — fail-closed direction, zero honest-dump impact, but the recorded
rationale overclaims → **CS77** (record-correction + optional guard narrowing).

**Legacy reader-behavior on truncation:** the guardless `importInfo` loop's stale-read hang
(I21) is byte-identical at the parent (traced both sides) — no regression, but recorded as
**CS78** because this step rewrote the exact loop and a hang is the worst fail-closed outcome
an operator can get for a crash-truncated legacy dump (the plan's own Validation bullet
demands truncated dumps "fail the import loudly").

**Verdict: holds** modulo CS76 (fail-open micro-divergence, hand-edited legacy shapes only)
and CS77 (recorded-rationale overclaim); consumption is byte-identical everywhere else, and
the declared-v14 lenient cell is test-pinned green before AND after
(`v14DumpWithAlienSchemaVersionStaysLenient`).

## 5. Findings (detailed)

### CS75 — should-fix — an unknown info field with a JSON-OBJECT value desyncs `importInfo` past the object boundary: mid-object placement passes pre-flight, mutates the target, and rejects POST-mutation — the same defect class as the step's headline dangling-name fix, escaping through the value side
`DatabaseImport.java:854-859` (default arm's `readNext(NEXT_IN_OBJECT)` value skip),
`:792` (loop keyed on `lastChar() != '}'`), JSONReader.java:242-256 (P2: a nested object's
closing `}` re-checks as the separator once depth returns to 0 — indistinguishable from the
enclosing close). Counterexample (I14, §2): splice `"future":{"a":1},` between
`schema-version` and `schemaRecordId` in an honest v15 dump, re-gzip → the field loop exits
at the nested `}`, pre-flight passes on the truncated capture, `runDeferredImportPreamble`
drops the target's default classes/indexes, and the remnant `schemaRecordId` field is read
as a section tag → `Invalid format. Found unsupported tag 'schemaRecordId'` — post-mutation,
target condemned, for a legal-JSON dump whose only irregularity is a Q-M2(4)-TOLERATED
unknown field. Adjacent outcomes of the same root cause: trailing placement silently accepts
via double-luck (I15 — the ruled tolerance holds by accident, not by construction); an array
value with top-level commas is caught by the T1 guard but with a misleading
"dangling or malformed field" message (I13); an object field preceding `exporter-version` in
a v16 dump degrades the ruled redirect into an SR2 message (I16/§3 arm 8). No honest v15
exporter writes non-scalar info values (P6), so the practical trigger is hand-editing or a
future exporter's richer info shape — but the step's contract sentence ("a dangling/malformed
info field is rejected at parse, pre-mutation … the shape can never … desync") is exactly
what this shape falsifies, and the charter's criterion 2 asks this question verbatim. Remedy
(small, in-seam): make the info value skip structure-aware — track square/curly depth for the
value read (a `readNext` variant, or reject `[`/`{`-led values for unknown/known info fields
at parse, which is pre-mutation and fail-closed and keeps scalar tolerance intact); that
single change collapses I13-I16 into clean pre-mutation outcomes and restores the ruled
tolerance for I14/I15 (or converts them into a DELIBERATE, recorded scalar-only rule).

### CS76 — suggestion — the best-effort raw-token rework silently DROPS a quoted marker on the declared-legacy path: `"best-effort": "true"` was gate-rejected at the parent, now imports leniently
`DatabaseImport.java:839-845` (raw token keeps quotes → `Boolean.parseBoolean("\"true\"")`
= false; the collected type violation is judged v15-only :483/:454), vs parent
fb9867e751 (`readBoolean` → `readString` quote-strip → `true` → the SR3 marker-keyed gate
:515-520 rejected absent the ack flag). Counterexample: declared-v14 dump hand-carrying
`"best-effort": "true"` — parent: refused without `-acceptBestEffortDump=true`; this commit:
marker silently false, lenient import proceeds. Fail-OPEN direction on the one gate whose
SR3 rationale is "rejecting a dump that CLAIMS possible incompleteness … is intended
fail-closed behavior"; the v15 direction improved (I17 → type-violation reject). Hand-edited
inputs only (no legacy exporter writes the field). Remedy: quote-strip the raw token before
`parseBoolean` (restores parent parity on legacy) while keeping the v15 type violation as-is.

### CS77 — suggestion — the ungated field-name guard rejects legal-JSON quoted names the parent parsed CLEANLY, and the recorded justification ("such a shape can never parse into a clean import") overclaims
`DatabaseImport.java:792-796`; JSONReader.java P4 (string-aware until-colon scan).
Counterexample (I22): `{"info":{"weird:name": 1, "exporter-version": 14, …}}` — the parent
scanned the quoted name intact (in-string `:` invisible), quote-stripped to `weird:name`, and
tolerated it as an unknown field on every version; the guard now rejects it (contains `:`) on
every version, including declared-legacy. Fail-closed direction, zero honest-dump impact —
but the as-built note's acceptance-unchanged claim is false in the letter for this
sub-family, and the guard fires on shapes that DON'T desync. Disposition options: (a) record
the widening honestly (SR3-style: hand-edited-only, intended fail-closed) and correct the
note/comment; and/or (b) narrow the guard's character class to the desync witnesses — every
traced desync remnant contains at least one of `"` `{` `}` `[` `]` (a desynced name always
crosses a quote boundary), so dropping bare `:`/`,` from the class readmits the clean-parse
names while keeping every §1.1/I13/I23 shape caught.

### CS78 — suggestion (pre-existing, in-seam) — a truncated PLAIN-JSON info section hangs the importer (stale-read spin, now with unbounded list growth) instead of rejecting loudly, after silently latching a truncation-corrupted version token
`DatabaseImport.java:792` (`while (lastChar() != '}')`, no EOF/`hasNext` guard — unlike the
section loop :313), `:854-859` (each stale spin appends the stale token to
`unknownInfoFields`), JSONReader.java:207/:213 (P3 silent stale return at EOF),
:268-271 + :299-301 (EOF-break plus unconditional separator strip corrupts the token —
`{"info":{"exporter-version":14` EOF parses and latches version **1**). Counterexample:
a legacy plain dump (or stream-ctor input) truncated mid-info — the exact legacy mid-write
crash shape — spins forever in `importInfo` (each iteration: stale field name passes the
guard, default arm's `readNext` returns immediately on `!ready()`, `lastChar` never changes),
growing `unknownInfoFields` toward OOM. Byte-identical hang at the parent (traced both
sides; the list growth is new but changes only the hang's terminal form), gzip v15 arm is
decoder-loud — so this is a legacy/stream-arm robustness gap, not a Step-6 regression; filed
because the step rewrote this exact loop and the track's Validation letter says truncated
dumps "fail the import loudly". `importManifest` (:553-570) shares the guardless-loop shape
(reachable only behind the gzip arm in practice). Remedy: bound the loop with
`jsonReader.hasNext()` and throw a loud truncation rejection on EOF-mid-object — pre-mutation
on the first info section, and behavior-visible only on inputs that today hang.

## 6. Null verdicts (checked, no finding)

| Obligation | Verdict | Where traced |
|---|---|---|
| Criterion 1 — every NEW throw site classified; matrix rejections pre-mutation | **holds** — T1-T7 all pre-mutation on the first-info flow; single mutation gate unchanged (P7); WI12a provably pre-latch | §1 |
| Criterion 1 — parent dangling-name desync defect confirmed + fix confirmed | **holds** — parent trace steps 1-4 + red-first record; guard covers every dangling-NAME shape (structural-char witness argument); v15 gzip crash shape decoder-loud earlier | §1.1 |
| Criterion 3 — `>= 16` can reach no `>= 15` arm nor the lenient path | **holds** — consumer enumeration (:343,:359,:380,:454,:502) + arms 1-8 incl. latch interplay, numeric disguises, tag-before-info | §3 |
| Criterion 4 — all non-guard checks version-gated; legacy consumption byte-identical | **holds** modulo CS76/CS77 (both hand-edited-only shapes) — arm-by-arm primitive table | §4 |
| Q-M2(2) range as one-constant bump | **holds** — `MIN_IMPORTABLE_SCHEMA_VERSION` (6, :174) vs `SchemaShared.CURRENT_VERSION_NUMBER` (6); bumping CURRENT widens to 6..7 with no other edit | :458-482 |
| WI12a same-outcome-as-undeclared letter | **holds** — throw before latch, preamble stays locked, message names the raw value | :799-811 |
| SR3 recording obligation (Step-5 CS66 carry) | **discharged** — inline ruling citation at the gate :509-513; drafts §SR3 recorded | §4 |
| Misspelled critical field rides SR2, never lenient | **holds** — I4: unknown-tolerated, version never declared, SR2 fires pre-mutation | §2 |
| Type-violation collection cannot fire on the legacy path | **holds** — single judgment site :483 inside the `>= 15` block; lists are read nowhere else | §2 I11 |
| Pre-flight re-run on duplicate info sections | harmless — judgment idempotent, `preambleExecuted` latch (:531) blocks re-mutation, WI10c condemns under v15 | §1 T-table |
| Test-side SR1 scope (target-unmutated asserts) | **holds** — every rejection test plants `PreFlightMarker` and asserts it survived + no dump class arrived | DatabaseImportInfoMatrixTest.java:104-110 |

## 7. Hypothesis log

| # | Hypothesis | Evidence sought | Outcome |
|---|---|---|---|
| H1 | Some matrix rejection fires only after the preamble | throw-site enumeration vs the single call site :335 | refuted for T1-T7; **confirmed one boundary escape via the VALUE side → CS75** |
| H2 | The parent dangling-name defect is as claimed (post-mutation) | parent importInfo trace + red-first record | confirmed (defect at parent; fixed pre-mutation at HEAD for name shapes) |
| H3 | A `>= 16` dump can reach a `>= 15` arm via re-declaration | latch semantics ×4 orderings + numeric disguises | refuted (§3 arms 1-5) |
| H4 | The `>= 16` redirect can be preempted by a v15 arm (CQ24 regression) | statement order in runPreFlightChecks | refuted — redirect is the first statement :447 |
| H5 | Unknown fields with structured values can desync the reader | P1/P2 scanner semantics, placement matrix | **confirmed → CS75** (object values; arrays caught by guard with message nit) |
| H6 | The new guard changes legacy acceptance beyond the recorded note | quoted-structural-name shapes vs parent scan | **confirmed → CS77** (clean-parse sub-family; rationale overclaims) |
| H7 | The raw-token rework diverges from parent parsing on legacy | primitive-by-primitive comparison | **confirmed → CS76** (quoted boolean, fail-open); all other arms identical |
| H8 | Truncated info can silently mis-latch a version or hang | P3 stale/EOF-strip semantics, both commits | **confirmed → CS78** (pre-existing hang + corrupted-token latch; plain arm only) |
| H9 | schema-version needs the CS63-style latch | dispatch-power analysis of the field | refuted — validated-only field, last-write-wins grants no power (I9) |
| H10 | Type violations or unknown-field state leak across the version gate | consumer search for the two lists | refuted — single v15-gated judgment/log site |
| H11 | The `unknownInfoFields` log could mask a rejection (ordering) | statement order :483 vs :490 | refuted — violations judged before logging; logging is throw-free |

## 8. Verdict

The step's core promise — the ruled Q-M2/SR2 matrix judged at a genuinely pre-mutation seam —
is implemented faithfully for every shape the matrix ENUMERATES: all seven new throw sites
fire pre-flight on the honest flow, the `>= 16` redirect is provably ahead of every v15 arm
in all re-declaration orderings, the schema-version range is the promised one-constant bump,
the SR3 ruling is recorded where it fires, and the declared-legacy path's consumption is
byte-identical outside two hand-edited-only corners. The parent's dangling-field desync
defect is real (traced + red-first witnessed) and closed on the field-NAME side. The review
found **no blocker** and **one should-fix**: CS75 — the identical desync class re-enters
through an unknown field's OBJECT value, where a Q-M2(4)-tolerated legal-JSON shape passes
pre-flight on a truncated capture, mutates the target, and dies post-mutation (with a
silent-accept-by-luck variant and two message-degradation satellites). Three suggestions:
CS76 (quoted best-effort marker now silently dropped on legacy — the one fail-OPEN direction
in the diff), CS77 (the ungated guard's recorded justification overclaims; a clean-parse
legacy sub-family is newly rejected), CS78 (pre-existing truncated-plain-info hang, in-seam,
worst-possible operator outcome for the legacy crash shape). Nothing found contradicts SR1's
condemn-target doctrine, the WI11 block boundary, or the R1 leniency ruling beyond the items
above.

## Compact findings block

| ID | severity | location (file:line) | one-line summary | counterexample gist |
|---|---|---|---|---|
| CS75 | should-fix | DatabaseImport.java:854-859,792 (+ JSONReader.java:242-256) | unknown info field with a JSON-object value desyncs the info loop (nested `}` doubles as the separator) — mid-object placement passes pre-flight, MUTATES, then rejects post-mutation; trailing placement silently accepts by luck; the step's headline desync fix escaped through the value side | splice `"future":{"a":1},` between `schema-version` and `schemaRecordId` in an honest v15 dump → preamble drops target classes, then `Invalid format. Found unsupported tag 'schemaRecordId'` |
| CS76 | suggestion | DatabaseImport.java:839-845,483,454 | best-effort raw token keeps quotes → `parseBoolean` false → quoted marker silently dropped on declared-legacy (violation judged v15-only); parent's `readBoolean` quote-strip honored it and the SR3 gate rejected | v14 dump + `"best-effort": "true"` → parent refused without ack flag; now imports leniently (fail-open) |
| CS77 | suggestion | DatabaseImport.java:792-796 | ungated field-name guard rejects legal-JSON quoted names with in-quote structural chars that the parent parsed cleanly and tolerated on all versions — the recorded "can never parse cleanly / acceptance unchanged" justification is false for this sub-family | `{"weird:name": 1}` in a declared-v14 info → parent tolerated as unknown field; now "dangling or malformed field" rejection |
| CS78 | suggestion (pre-existing) | DatabaseImport.java:792,854-859 (+ JSONReader.java:207,213,268-271,299-301) | truncated plain-JSON info section → stale-read infinite spin in the guardless `importInfo` loop (new: unbounded `unknownInfoFields` growth → OOM), after the EOF-strip silently latches a truncation-corrupted version token (`14`→`1`); gzip arm decoder-loud, plain/stream arm hangs | plain legacy dump ending `…"exporter-version":14` (EOF) → version 1 latched, importer hangs instead of rejecting loudly |

**Null-verdict notes per charter criterion:** #1 (SR1 boundary) — holds for all seven new
throw sites T1-T7 (each pre-mutation on the first-info flow; WI12a pre-latch; parent defect
confirmed and name-side fix confirmed), with CS75 as the value-side boundary escape (§1, §2);
#2 (matrix fail-closed) — 23 shapes enumerated: 15 rejected-loudly, 4 tolerated-by-design,
1 tolerated-null (I9 no-dispatch-power), defects CS75/CS76/CS78 as tabled (§2); #3 (`>= 16`
ordering) — **clean null verdict**, no path to any `>= 15` arm or the lenient path across all
8 adversarial orderings, sole blemish a message degradation folded into CS75 (§3); #4
(`<= 14` regression) — holds with byte-identical consumption arm-by-arm; the only ungated new
rejection is the guard as claimed; deviations limited to hand-edited-only shapes CS76/CS77
(§4).
