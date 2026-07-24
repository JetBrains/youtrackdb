# YTDB-382 — Adversarial pass 11: durability, scoped (2026-06-12)

Verdict: 5 new findings (0 BLOCKER, 1 MAJOR, 4 MINOR). This pass attacked only
the pass-10 settlement text, durability half: the F100/F101/F102/F103 folds in
the decision-log diff `da824ff9d5..HEAD` (commits `6ba329645b`, `5b0932d31c`,
`520954f8d1`, `0f630c2942`), plus cross-entry consistency across
D20/F90/F94/F95/F100–F103 after the rewrites. The MAJOR is the same genus as
F100 itself, one layer down: the corrected pin "a fail-fast abort leaves
exit ≠ 0 and no file at the final name" asserts an invariant the specified
mechanism set does not deliver — the fold dropped pass 10's own qualifier
("or after `exportRecord`'s internal swallow left a record object open"), and
one unserializable record before the abort is enough to strand the generator
at object context, where `close()` succeeds and the failure path promotes a
dump to the final name. The four MINORs are a promote-class label that is
wrong in both directions, a gzip header constant that is the JDK writer's
shape rather than the format's, a best-effort ack gate that no shipped
importer can enforce, and an export-log review whose failure trigger checks
capture presence while the failure mode it answers is capture wiring.

Method: the settlement diff read hunk by hunk, the full current D20 entry and
the F90/F94/F95/F100–F103 records read for context, pass 10's report read
including its bounding paragraphs and survived-attack list. mcp-steroid was
reachable with the `transactional-schema` project open at the repo root; the
reference-accuracy claims below (the production caller sets of
`DatabaseExport#close`, both `DatabaseExport` constructors, and
`exportRecord`) were verified with PSI `ReferencesSearch` and are marked
PSI-verified. Control-flow and line-anchor claims were verified by reading the
live tree (`DatabaseExport`, `DatabaseImport`, `JSONSerializerJackson`,
`FileUtils`). Dependency behavior was verified at the artifact level: the
generator-context checks (`writeEndObject`, `writeEndArray`,
`_verifyValueWrite` status 5 → `_reportCantWriteValueExpectName`) by
disassembling the pinned `jackson-core:2.21.4` jar from the local repository
(root pom pins it at `pom.xml:112`), and the gzip header/trailer/counter
behavior against the JDK 21 `src.zip`
(`java.base/java/util/zip/GZIPOutputStream.java`, `GZIPInputStream.java`,
`Inflater.java`), the JDK this project builds and runs on.

## U28: "A fail-fast abort leaves exit ≠ 0 and no file at the final name" is not an invariant of the specified mechanisms — one swallowed broken record before the abort strands the generator at object context, and the failure path promotes [MAJOR]

The F100 resolution and D20's hardening clause pin: "a fail-fast abort leaves
exit ≠ 0 and no file at the final name (the `.tmp` orphan the only residue)",
and elevate it to a requirement: "the no-file-at-final-name-after-failure
property is a requirement the new exporter keeps whatever its close-path
implementation." The grounding sentence attributes the property to "the
array-context rethrow."

The array-context grounding is conditional, and the fold dropped the
condition its own source named. Pass 10's U24 bounded the promote class as
"failures that land at object context — between sections, or after
`exportRecord`'s internal swallow left a record object open"; the F100
resolution and the F90/D20 corrections carried only the "between sections"
arm. The dropped arm defeats the pin. Tree-read: `recordToJson` writes each
record incrementally through the shared generator
(`JSONSerializerJackson:701` `writeStartObject`, per-property
`writeFieldName`/`serializeValue`, `:721` `writeEndObject`; embedded entities
recurse at `:936`), and `exportRecord`'s catch (`DatabaseExport:597`) adds
the rid to `brokenRids`, logs, and returns without any generator-context
repair. F94 (b) keeps exactly this path: "The `brokenRids` path is untouched."
So a fetched-but-unserializable record — the class `brokenRids` exists for,
and per F94's own premise the exact population the migration serves — leaves
the generator stranded inside the half-written record object.

From the stranded state, the abort promotes. A subsequent fail-fast rethrow
at the iterator catch (the F94 (b) default), or the normal end-of-records
`writeEndArray` (`:250`, which now throws "Current context not Array but
Object", artifact-verified against jackson-core 2.21.4), unwinds to the
`finally` (`:157`–`:158`); `close()`'s `writeEndObject` (`:277`) now finds an
object context, succeeds, `jsonGenerator.close()` auto-closes the remaining
scopes, and `atomicMoveWithFallback` (`:291`) promotes. The in-flight scan
failure keeps propagating, so the run ends exit ≠ 0 with a structurally
mangled dump at the final name — exactly the combination the fresh pin says
"does not exist." Which endpoint a run reaches depends on stranding depth:
expecting-field-name stranding cascades every later record into `brokenRids`
(each `writeStartObject` trips `_verifyValueWrite` status 5,
artifact-verified) and aborts at `:250` with promotion; stranding inside a
nested array lets later records nest silently inside the broken record's
property array and the export can even complete exit 0 with promotion. No
endpoint matches the pin. PSI-verified that no other production close path
exists that could change this: `DatabaseExport#close` is called in production
only from the `:158` `finally` and the jmh-ldbc helper, and `exportRecord`
only from the `:207` loop.

Every endpoint stays fail-closed at import — the promoted dump is missing
top-level sections or the manifest, so section presence (legacy) or F75
(manifest era) hard-fails, and the exit-status pin catches the loud variants
— which is why this is MAJOR and not BLOCKER. The defect is the same one F100
charged the previous text with: a pinned acceptance property the specified
mechanisms cannot deliver. An implementer tests the clean case (no broken
records, iterator fault), sees no file at the final name, and ships; the
composed case (one broken record, then a fault) promotes anyway, and the
"whatever its close-path implementation" wording forbids reading the pin as
scoped to the clean case.

Proposed resolution: state the mechanism instead of the accident. The new
exporter promotes only on success — an explicit completion flag set after the
last section is written, checked before the rename; every failure path leaves
(or deletes) the `.tmp` and never renames. That makes the no-file pin a real
invariant independent of generator context, satisfies "whatever its
close-path implementation" by construction, and trivializes the
primary-exception pin (with no `writeEndObject` needed on the discard path,
the scan failure is the only exception in flight). If `brokenRids`
log-and-continue is kept for best-effort mode, additionally pin per-record
write isolation (render each record to a buffer, write atomically) or
generator-context repair after a swallowed record — without it, one broken
record degrades every later record in the salvage dump, defeating the mode's
own purpose. Restore the dropped qualifier in F100's grounding and the F90/D20
bounding sentences. Affected: F100, F94, F90, D20.

## U29: "Between-section (object-context)" mislabels the promote class in both directions — between-entries faults in array sections never promote, mid-section faults in object scopes do [MINOR]

The F100 fold's bounding language equates the two terms: D20 reads
"a between-section export failure (object context; bounded per F100 — the
array-context mid-records class never promotes ...)" and the F90 correction
reads "holds only for failures landing at object context (between sections)."

The tree contradicts the equation both ways. `collections` is an array of
objects (`DatabaseExport:327`–`:328`, entries `:343`–`:351`), as are
`records`, `brokenRids`, and `indexes` (`:409` ff.) — a fault between entries
of any of these sections sits at array context, where `close()` throws and
nothing promotes, even though such a fault is "between" items and outside any
record scan. Conversely, a fault inside the `info` object (`:361` ff.), inside
a schema class or property object (`:477`–`:573`), or inside an index object
(`:409`–`:441`) sits at object context mid-section, where the failure path
promotes a dump whose open scopes were auto-closed. The promote class is a
generator-context fact, not a section-boundary fact; pass 10's wording kept
that straight and the fold's shorthand lost it.

Concrete failure: an acceptance-test writer for the section-presence closure
induces a "between-section" failure by faulting between two collection
entries, observes no promoted dump, and concludes the promote class is empty;
or audits the F90 sentence against a mid-schema fault and finds a promoted
dump where the label says "between sections" should not apply. Proposed
resolution: reword both sentences to "failures that leave the generator at
object context (between sections, or inside any object scope a section
opens)" and keep "array context" as the never-promotes label. Affected: D20,
F90, F100.

## U30: The F103 measurement's "fixed 10-byte header" is the JDK writer's shape, not RFC 1952's — a dump re-gzipped by standard CLI tools falsely fails the arithmetic, and the reject-non-gzip arm does not bound it [MINOR]

D20's pin names the measurement: "a `GZIPInputStream` subclass comparing
`Inflater.getBytesRead()` plus the fixed 10-byte header and 8-byte trailer
against the physical file size."

The constant is correct only for streams written by `GZIPOutputStream`,
which emits exactly 10 header bytes with FLG=0 (artifact-verified, JDK 21
`GZIPOutputStream.java:190`–`:203`). RFC 1952 headers are variable-length:
FEXTRA, FNAME, FCOMMENT, and FHCRC extend them, and the JDK's own reader
parses them as such (`GZIPInputStream.readHeader`, `:192`–`:240`, returns the
actual length). `gzip` and `pigz` store FNAME by default when compressing a
file, so the natural completion of the inspect round-trip D20 itself
describes — gunzip a dump to look inside, then re-gzip it — produces a valid
single-member dump whose header exceeds 10 bytes. The arithmetic then reports
the header surplus as trailing bytes and fails a sound dump with a misleading
diagnostic. The migration path's reject-non-gzip arm does not bound this: a
re-gzipped dump is gzip and passes the magic check. The primary migration
dump is unaffected (written by the old binaries' `GZIPOutputStream`, header
exactly 10), and the window is false-failure only — a longer header or a
second member always makes the expected size smaller than the actual, never
equal, so the check over-rejects but never under-rejects (verified
arithmetically; minimum member overhead is 18 bytes).

The rest of the measurement survived attack. `getBytesRead()` is the right
counter on JDK 21: it returns `long` (`Inflater.java:657`–`:662`), so a
>2 GiB compressed dump does not truncate as `getTotalIn()`'s `int` would; it
counts only inflater-consumed deflate bytes, excluding the trailer residue
the JDK probe eats; and it is not reset on the single-member path —
`inf.reset()` runs only after a concatenated member's header parses
(`GZIPInputStream.java:266`), and the reset zeroes the counter
(`Inflater.java:701`), which makes the arithmetic fail loudly on any
multi-member file and accidentally enforces F95's single-member pin.

Proposed resolution: replace "the fixed 10-byte header" with the parsed
header length (the subclass reads FLG and walks the optional fields, the same
arithmetic `readHeader` already performs), or scope the constant to
JDK-written dumps and extend D20's re-point-at-original instruction from
gunzipped dumps to re-compressed ones. The framing-parse arm needs no change.
Affected: D20, F103, F95.

## U31: The best-effort ack gate binds no shipped importer — the same no-upper-bound and unknown-field-skip facts the F101 fold records as bump-enabling make a truncated v15 salvage dump import cleanly on every pre-branch binary [MINOR]

F94 (b) pins: "A best-effort-marked dump requires the ack flag at import even
in the manifest era," with the marker "recorded in the dump's `info` section
as a scalar field (F101: the importer's unknown-field skip is verified for
scalars only, `DatabaseImport:418`–`:420`)."

The enforcement lives only in importers that ship with or after this branch.
The F101 inventory the same fold records cuts the other way for every
importer already shipped: no version branch uses `EXPORTER_VERSION` as an
upper bound, so a pre-branch binary accepts a v15 dump without complaint, and
`importInfo`'s else-arm skip (tree-read, `DatabaseImport:415`–`:421`) is
precisely the mechanism that discards the best-effort marker unread. A
deliberately lossy salvage dump — the one artifact the ack gate exists for —
therefore imports on any older deployment with no flag, no warning, and a
manifest that agrees with its truncated content (F75 verification passes by
F94's own "salvage manifest agrees with its truncated dump" observation). The
exposure is the downgrade/cross-version restore path, not the D20 migration
(old-to-new by construction), and retrofitting shipped binaries is the same
move option (a) rejected — which is why this is MINOR and detection-only.

Proposed resolution: record the reach honestly where the gate is pinned: the
marker is enforceable only by v15-aware importers; on older binaries it is
advisory dead weight. Add one procedure line: a best-effort-marked dump is
not a valid cross-version restore artifact — import it only with binaries
that enforce the marker. Affected: F94, F101, D20.

## U32: The two-captures review fails on a missing capture, but the failure mode it answers produces a present-but-empty one — the error capture has no positive control, and the one-redirected-stream arm inherits the gap [MINOR]

D20's review pin (per F102) states the two captures — listener output for the
count lines, the error log for every error line, or one redirected stream —
"and the review fails when either capture is missing."

Missing is the wrong trigger. F102's mechanism was an operator reviewing an
artifact that structurally lacks the error channel; the artifact in that
scenario exists. A clean export writes zero error lines (tree-read: every
error line in the export path is `LogManager` output at
`DatabaseExport:213`/`:225`/`:606`; the listener carries only progress and
count lines), so an empty error capture is also what a successful run
produces, and a capture wired to the wrong sink — stdout captured while
`LogManager` writes to a logfile — is indistinguishable from a clean run. The
listener capture carries intrinsic positive controls (the start/end messages
and the per-collection `OK (records=...)` lines must appear), the error
capture carries none, and the one-redirected-stream arm does not close the
gap: the OK lines prove the stream captured the listener, not that the
logger's destination feeds the same stream. The "review fails when either
capture is missing" gate therefore passes exactly the misrouted case the pin
was written against. MINOR because the review is already pinned as a
heuristic and the ack flag stays mandatory regardless of its outcome.

Proposed resolution: add a liveness control for the error capture: the
procedure verifies the embedding tool's logger destination is the captured
artifact (configuration check), or provokes one known line through the same
logger before the export and confirms it appears in the capture; absent
either, the review treats an empty error capture as unverified rather than
clean. Affected: D20, F94, F102.

## Attacks run that produced no new finding

- **F100's primary-exception pin (implementability).** "The abort propagates
  the scan failure as the primary exception (`addSuppressed` or
  log-then-rethrow-original)" is implementable, but not with the bare legacy
  call-site shape: an exception thrown from `finally { close(); }`
  (`DatabaseExport:157`–`:158`) always replaces the in-flight wrapped scan
  failure, so the new exporter must catch the body exception and suppress the
  close-path secondary (try-with-resources gives this ordering for free). The
  pin binds the outcome, not the call-site shape, so no finding. Under U28's
  promote-only-on-success resolution the pin becomes trivial: a discard-path
  close that skips `writeEndObject` leaves the scan failure as the only
  exception in flight. One wrinkle noted, not filed: F100's proposal sentence
  ("attaches the scan failure to the propagated exception") reads with the
  opposite attachment direction from its resolution (scan failure as
  primary); the resolution and both D20/F94 carriers agree with each other
  and with the stated operator-visible consequence, and the resolution
  governs.
- **F100's pre-deleted-final-name claim.** Holds:
  `FileUtils.prepareForFileCreationOrReplacement` deletes any prior file at
  the final name in the constructor (`DatabaseExport:85`, `FileUtils:285`),
  so on the clean abort path nothing sits at the final name.
- **F101's nine-branch inventory.** Re-checked against the tree: all nine
  comparison anchors match (`:298`/`:313` `>= 12`, `:415` `< 14`, `:574`
  `>= 14`, `:736` `< 11`, `:847` `<= 4`, `:866`/`:1001` `<= 13`, `:875`
  `< 9`), and the skip path at `:418`–`:420` is the else-arm scalar read the
  record describes. U31 attacks the gate's reach, not this inventory.
- **F102/F94/D20 two-captures coherence.** The two-captures wording is
  consistent across D20's review pin, F94 (a), and the F102 record, including
  the one-redirected-stream alternative and the no-operator-CLI note. U32
  attacks the trigger condition, not the wording's consistency.
- **F103's stream-exhaustion prohibition and probe story.** Accurate against
  JDK 21 source: `readTrailer` probes for a concatenated member through a
  `SequenceInputStream` over the inflater's remaining buffer plus the stream
  and swallows the probe's `IOException` (`GZIPInputStream.java:243`–`:270`),
  so residue smaller than the final buffer fill is consumed into the dead
  buffer and exhaustion checks pass it. The arithmetic the pin names is
  position-independent (it compares against the physical file size), which is
  exactly why it escapes the probe.
- **F103's reject-non-gzip arm vs the general path.** The arm statements are
  coherent across D20, F95's measurement note, and the F103 record: reject on
  the migration path, fallback-with-recorded-consequence elsewhere. The
  plain-JSON fallback site reads as described (`DatabaseImport:138`–`:143`,
  mark/reset under the try). U30 attacks the header constant, not the arm
  choice.
- **Cross-entry contradiction sweep.** D20/F90/F94/F95/F100–F103 read
  end-to-end after the rewrites: the residual legacy procedure pin ("a dump
  file at the final name proves nothing about export success") coexists
  correctly with the new-exporter pins (it describes the legacy exporter the
  migration actually runs), the bounded F90 sentence and its D20 carrier
  match each other, and the redrawn F94 mermaid matches its prose. No
  contradicting sentence pair found beyond the two filed as U28/U29.
