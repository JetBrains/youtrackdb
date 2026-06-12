# YTDB-382 — Adversarial pass 10: durability (2026-06-12)

Verdict: 4 new findings (0 BLOCKER, 1 MAJOR, 3 MINOR). This pass attacked only
the pass-9 settlement text, the decision-log diff `a031b4f73a..c496195c8b`:
the F94 fold (D20's rewritten legacy-dump verification bullet, the F94
resolution paragraphs, the F81/F90 coverage corrections) and the F95 fold
(D20's framing pin on the F82 bullet, the F91 framing note), plus cross-entry
consistency across D20/F63/F75/F81/F90/F94/F95. The MAJOR is a premise
contradiction in fresh text: the fail-fast abort the F94 fold specifies never
produces the promoted truncated dump the fold says the section-presence check
hard-fails, because the legacy `close()` throws on the abort path before the
promotion line the fold cites. The three MINORs are a false "sole version
branch" audit record, an export-log review pinned as one artifact whose two
signal classes land in two different sinks, and a fully-consumed gzip check
that the wired JDK decoder stack defeats for any trailing residue smaller
than the final buffer fill.

Method: the settlement diff read hunk by hunk, the full current D20 entry and
the F63/F75/F81/F82/F90/F91/F94/F95 records read for context, pass 9's report
read including its failed-attack list. mcp-steroid was reachable with the
`transactional-schema` project open at the repo root; the two
reference-accuracy claims below (the `DatabaseExport` production-caller set,
the `DatabaseImport.exporterVersion` branch inventory) were verified with PSI
`ReferencesSearch` and are marked PSI-verified. Line-anchor and control-flow
claims were verified by reading the live tree (`DatabaseExport`,
`DatabaseImport`, `FileUtils`, `PaginatedCollectionStateV2`). Two claims sit
in dependencies rather than the tree and were verified at the artifact level:
Jackson's end-object context check by disassembling the pinned
`jackson-core:2.21.4` jar from the local repository (the version the root pom
pins at `pom.xml:112`), and the gzip trailer/probe behavior against the
JDK 21 `src.zip` (`java.base/java/util/zip/GZIPInputStream.java`), the JDK
this project builds and runs on.

## U24: The F94 fail-fast abort never promotes a dump — `close()`'s `writeEndObject` throws at records-array context before the rename, so the "promoted dump missing post-`records` sections, hard-failed by section presence" composition the fold pins does not exist [MAJOR]

The F94 resolution (b) claims: "A fail-fast abort composes with the F90
machinery: exit ≠ 0, and the `finally` still promotes a cleanly closed dump
missing every post-`records` section, which the section-presence check
hard-fails." D20's rewritten bullet repeats it ("a fail-fast abort leaves
exit ≠ 0 plus a dump missing every post-`records` section"), and the fold's
Mermaid diagram draws the abort flowing into "section-presence check:
HARD-FAIL".

The tree contradicts the promotion half. The fail-fast rethrow lands at the
`:221` catch, inside the `records` array: `exportRecords` opens the array at
`DatabaseExport:171`–`:172`, every completed `exportRecord` call returns the
generator context to the array, and the closing `writeEndArray` (`:250`) is
skipped by the rethrow. The exception unwinds to `exportDatabase`'s `finally`
(`:157`–`:158`), which calls `close()`. `close()` runs `writeEndObject()`
first (`:277`), and Jackson's writer-based generator rejects that call when
the current context is not an object: `WriterBasedJsonGenerator.writeEndObject`
checks `_writeContext.inObject()` and raises `_reportError("Current context
not Object but Array")`, verified by disassembling the pinned
`jackson-core:2.21.4` jar. `JsonGenerationException` is an `IOException`, so
the catch at `:280` rethrows it as a `DatabaseExportException` at `:284`, and
control never reaches the `atomicMoveWithFallback` promotion at `:291`. The
constructor deleted any prior file at the final name
(`FileUtils.prepareForFileCreationOrReplacement`, `FileUtils:285`), so the
abort leaves nothing at the final name and the partial data orphaned at
`<name>.gz.tmp`. Two secondary effects: the exception thrown from the
`finally` replaces the in-flight scan failure, so the propagated error is the
generator-context complaint rather than the root cause (which survives only
in the `:152` log line), and the section-presence check never receives a file
to hard-fail.

This also bounds the settled F90 sentence the fold extends. "A mid-records
export failure produces a cleanly closed dump at the final name by
construction" (D20's bullet) is true for failures that land at object
context — between sections, or after `exportRecord`'s internal swallow left a
record object open — because there `:277` closes the innermost object and
`jsonGenerator.close()` (`:278`) auto-closes the rest. For the array-context
class, which is exactly the scan-failure class the F94 hardening rethrows, it
is false. F90's closure itself stands: between-section failures do promote
cleanly closed dumps missing sections, so the section-presence check stays
necessary.

Concrete failure: an implementer building YTDB-1115 to this text writes the
acceptance test the fold implies — abort the export mid-`records`, assert a
promoted dump at the final name, assert the import hard-fails on section
presence — and finds it unsatisfiable. The natural "fix" that makes the
spec's pinned outcome real is to soften `close()` so the failure path
promotes (swap the strict `writeEndObject` for Jackson's auto-close), which
ships a promoted truncated dump at the final name where today's behavior
leaves none, trading the tree's fail-closed outcome for one that depends on
the import-side check the spec was layering on top. The fold should instead
record the true composition: a fail-fast abort leaves exit ≠ 0 and no file at
the final name (the `.tmp` orphan is the only residue), which is strictly
stronger than the section-presence story — and the masked root cause in the
propagated exception is worth a one-line pin if the new exporter keeps the
legacy `close()` shape.

## U25: "The importer's sole version branch is `< 14`" is false — nine `exporterVersion` comparison branches exist; the conclusion survives but the recorded audit does not [MINOR]

The F94 resolution's spot-check paragraph claims: "the importer's sole
version branch (`< 14` selects the backwards-compat serializer,
`DatabaseImport:415`–`:417`, so 15 is accepted unchanged)."

PSI-verified (`ReferencesSearch` on `DatabaseImport.exporterVersion`): the
field has nine comparison branches, not one — `:298` and `:313`
(`>= 12`), `:415` (`< 14`), `:574` (`>= 14`, selects the
`collection-ids`/`cluster-ids` key name), `:736` (`< 11`), `:847` (`<= 4`),
`:866` and `:1001` (`<= 13`), `:875` (`< 9`) — plus the assignment at `:414`
and a debug log at `:1230`. The conclusion holds: every branch evaluates the
same way for 15 as for 14, no branch compares against `EXPORTER_VERSION` as
an upper bound, and `importInfo` skips unknown fields (`:418`–`:420`), so a
v15 dump with a scalar best-effort marker imports unchanged.

Concrete failure: the audit record, not this bump. The next person bumping
the version (15 → 16, the "next format migration" the fold itself plans for)
reads "sole version branch", checks `:415`, and misses `:574`'s `>= 14` and
the two `<= 13` sites — the exact shape that breaks when a future format
change interacts with a version test written as an inequality. Correct the
record to the full inventory, and pin one detail the skip-path evidence does
not cover: the best-effort marker must be a scalar `info` field, because the
`:419` skip was only shown to pass over scalar values.

## U26: The export-log review is pinned as one artifact, but the count lines and the error lines go to two different sinks — the reviewed log can structurally lack every error line [MINOR]

D20's rewritten bullet pins: "For legacy dumps the procedure adds an
export-log review (F94): per-collection `records=current/total` lines and
error lines — a heuristic, because the denominator is the storage's
approximate counter and a first-fetch failure logs nothing."

The tree splits the two named signals across channels. The count lines are
listener output: `listener.onMessage` prints the collection header
(`DatabaseExport:191`–`:196`) and the `OK (records=current/total)` line
(`:243`–`:245`). Every error line is logger output:
`LogManager.instance().error` at `:213` (the IO rethrow path), `:225` (the
swallowed scan failure F94 is about), and `:606` (the broken-record path).
Where each channel lands is decided by the embedding tool, and the tree has
no operator-facing export tool to decide it: PSI-verified, the only
production caller of a `DatabaseExport` constructor is the LDBC benchmark
helper (`jmh-ldbc/.../LdbcDatabaseTool.java:57`); every other reference is a
test. The decision log's own F94 entry cites these line numbers as the old
binaries' behavior, so the two-sink split carries to the migration's exporter
on the log's own premise.

Concrete failure: the operator captures the export tool's console output —
the natural reading of "export log", and the channel the `records=` lines
arrive on — and reviews it. A swallowed mid-collection failure shows
`OK (records=9990/10000)` against an approximate denominator, ambiguous by
the entry's own caveat; the disambiguating error line went to the logger
channel and is absent from the reviewed artifact, so the review passes a
lossy export. The heuristic's two named signals must be stated as two
captures (the tool's stdout and its error log, or a single redirected
stream), with the review failing when either is missing — otherwise the
fresh pin re-creates in the procedure the same single-channel blindness F94
found in the exit status.

## U27: The fully-consumed gzip check is defeated by the wired decoder stack — the JDK probe eats trailing bytes into a dead buffer, so the natural implementations never fire for residue under the final 16 KB fill, and the plain-JSON fallback makes "unconditional" conditional on the input being gzip at all [MINOR]

D20's framing pin (F95): "the importer verifies the compressed file is fully
consumed at decompression end-of-stream and fails loudly on trailing bytes
(an unconditional check, not a disabled-by-default `assert`)". The F95
resolution calls the pin "mechanical, not prose-only."

Two grounds. First, measurement: JDK 21's `GZIPInputStream.readTrailer`
(verified in `src.zip`) always probes for a concatenated next member — it
wraps the inflater's leftover buffer plus the underlying stream in a
`SequenceInputStream`, calls `readHeader`, and swallows any `IOException`
from it. The probe consumes an indeterminate number of trailing bytes before
failing (two for a bad magic; up to 64 KB if garbage parses as an FEXTRA
length), and the importer's stack — `BufferedInputStream` under
`GZIPInputStream(in, 16384)` (`DatabaseImport:134`–`:143`, the `:139`
two-arg form the fold cites) — prefetches in 16 KB fills. At decompression
end-of-stream, any trailing residue smaller than the final fill sits
consumed inside the dead decoder buffer and the underlying stream reads
exhausted. The natural implementations of the pin (check the buffered stream
returns -1 after gzip EOF, or count raw bytes against file size below the
decoder) therefore pass on up to roughly 16 KB of trailing bytes: the check
ships, runs unconditionally, and never fires in precisely the window it was
pinned for. A sound check exists but is not derivable from the pin's text:
compare the inflater's `getBytesRead()` plus the fixed 10-byte JDK header
and 8-byte trailer against the physical file size (a `GZIPInputStream`
subclass, since `inf` is protected), or parse the gzip framing directly. The
pin should name the measurement; otherwise a reviewer cannot distinguish a
working check from a vacuous one, and the regression test for "trailing
bytes fail loudly" passes or fails depending on whether the test's garbage
happens to exceed the final fill.

Second, coverage: the importer's constructor catch (`:140`–`:143`) silently
reroutes any input that fails the gzip header to plain-JSON parsing. Pass 9
dry-listed the corrupted-header case as fail-closed (binary garbage fails
the JSON parse loudly); the fresh "unconditional" wording adds a case that
ground did not cover. A valid uncompressed dump — an operator gunzips the
file to inspect it, then feeds the `.json` — imports with the entire gzip
layer absent, no CRC, no fully-consumed check, and no notice, while the
contract text says whole-stream validation is in force. Either the
file-based import rejects non-gzip input, or the pin states the fallback and
its validation consequence.

Severity stays MINOR on the fold's own layering: the manifest and
section-presence checks remain the stated independent authority, so no
silent-loss schedule escapes every layer. The defect is a mechanical pin
that under-delivers exactly where it was aimed.

## Attacks run that produced no new finding

- **"A salvage manifest agrees with its truncated dump."** Coherent with
  F75's design: the manifest counts are "known only at export end", i.e.,
  counts of what was actually exported, so a best-effort export's manifest
  matches its own lossy content and F63's import-side verification passes.
  The ack-flag requirement for best-effort-marked v15 dumps is correctly
  grounded.
- **Ack-flag conditionality across entries.** D20's "(it is mandatory on the
  primary migration path)" and "refuses a legacy dump unless", F81's and
  F90's correction notes ("stays mandatory for legacy dumps"), and F94 (a)
  ("mandatory on every legacy dump") form a consistent set: all legacy dumps,
  plus best-effort v15 dumps; no sentence pair contradicts.
- **v15 acceptance through the importer.** All nine version branches treat 15
  exactly as 14, no upper-bound rejection exists, and unknown `info` fields
  are skipped (`:418`–`:420`). The acceptance conclusion survives; only the
  "sole branch" audit record fails (U25).
- **Single-gzip-member writer claim.** Verified: one `GZIPOutputStream` over
  one `FileOutputStream` (`DatabaseExport:90`–`:98`), no `finish()` or
  restart sites in the file; the JDK writer emits one fixed 10-byte header
  and one trailer at close.
- **Clean-EOF-on-malformed-next-header claim.** Accurate against JDK 21
  source: `readTrailer` catches the probe's `IOException` and returns
  end-of-stream; the behavior is constructor-independent (the two-arg form
  only sizes the buffer).
- **Section list and order.** `info`, `collections`, `schema`, `records`
  (plus `brokenRids`), `indexes` last (`DatabaseExport:136`–`:140`, `:393`)
  — D20's option (c) enumeration matches the tree.
- **Count-line and denominator claims.** `OK (records=current/total)` at
  `:243`–`:245`; the denominator is
  `getApproximateCollectionCount` → `PaginatedCollectionStateV2.
  getApproximateRecordsCount` (`:104`); a first-fetch failure logs nothing
  (`:221`–`:222` null guard). All hold as the fold states.
- **`brokenRids` population.** The set is a local of `exportRecords` passed
  only into `exportRecord`, populated only at `:601` — the fold's
  single-site claim holds by construction within the file.
- **Fail-fast exit status at the API boundary.** Holds: whether the original
  scan failure or `close()`'s replacement propagates (U24), a
  `DatabaseExportException` reaches the caller, so a CLI wrapper exits
  non-zero either way.
