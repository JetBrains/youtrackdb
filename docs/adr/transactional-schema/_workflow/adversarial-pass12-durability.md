# YTDB-382 — Adversarial pass 12: durability, scoped (2026-06-12)

Verdict: 4 new findings (0 BLOCKER, 0 MAJOR, 4 MINOR). This pass attacked only
the pass-11 settlement text, durability half: the F109–F113 folds in the
decision-log diff `8e12fb5510..51f6b81d70` (commits `04f52b68ca`,
`4bcaea9472`, `5c16ea157f`, `ba3938247d`, `51f6b81d70`), plus cross-entry
consistency of the relabeled D20 legacy-dump bullet against F90/F94/F100–F103
after the rewrites. No pinned property fails outright: the four MINORs are a
promote-class relabel that is still not generator-context-exact (a
pending-field-name state promotes a malformed dump the "well-formed … holds
exactly" sentence excludes), a delivery attribution that credits the F109
mechanism pins with an outcome only the suppression discipline delivers, an
unbounded per-record buffer whose two failure consequences the cost line
hides, and a liveness control that treats "the logger" as one sink where
LogManager routes per requester-class category.

Method: the settlement diff read hunk by hunk, the full current D20
legacy-dump bullet and the F90/F94/F95/F100–F103/F109–F113 records read for
context, and the pass-9/10/11 durability reports' failed-attack lists read to
avoid re-running dead attacks. mcp-steroid was reachable with the
`transactional-schema` project open at the repo root (`steroid_list_projects`
preflight passed); no finding below required a new cross-codebase
reference-accuracy claim — pass 11's PSI verifications (production callers of
`DatabaseExport#close`, `exportRecord`, both constructors; the
no-operator-CLI fact) are relied on by citation, and every new claim is a
single-file tree read, an artifact disassembly, or a JDK source read. No
grep-caveats attach to anything below. Dependency behavior was verified at
the artifact level: `WriterBasedJsonGenerator.writeEndObject` and
`GeneratorBase.writeRawValue` by disassembling the pinned
`jackson-core:2.21.4` jar from the local repository (root pom pins it at
`pom.xml:112`); the gzip header walk, trailer, and writer shape against the
JDK 21 `src.zip` (`GZIPInputStream.java`, `GZIPOutputStream.java`), the JDK
this project builds and runs on.

## U33: The F110 relabel is still not generator-context-exact — a pending-field-name state promotes a malformed dump, and the swallow arm's "record object open" proxy does not entail object context [MINOR]

The relabeled bounding sentences (D20's legacy-dump parenthetical, the F90
promotion-scope paragraph) state the promote class generator-context-first:
"failures that leave the generator at object context — between sections,
inside any object scope a section opens (`info`, a schema class, an index
entry), or after `exportRecord`'s internal swallow left a record object open
(F109)" promote a "well-formed, valid-gzip dump" / "a cleanly closed dump …
by construction"; "array context (between entries in
`collections`/`records`/`indexes`)" never promotes. F90's carrier says the
equation "holds exactly."

Three residual imprecisions, two of them outcome-relevant. (1) Object
context has a sub-state the well-formed claim is false for: a pending field
name. `writeEndObject` in jackson-core 2.21.4 checks only
`_writeContext.inObject()` and writes `}` with no dangling-name guard
(artifact-verified: the disassembled method branches on `inObject()`,
reports "Current context not Object but …" otherwise, then emits `}` and
`clearAndGetParent` — no `_verifyValueWrite`, no pending-name check). A
failure between `writeFieldName` and its value write therefore leaves the
generator at object context, `close()`'s `:277` succeeds, and the promotion
runs — but the dump contains `"name":}` and is not well-formed JSON. The
windows are real production seams, not theory: `DatabaseExport:430`–`:431`
(`writeFieldName("stream")` then `index.getDefinition().toJson(...)` — a
corrupt index definition faulting before its first write is exactly the
salvage population), `:437`–`:438` (metadata), and every per-property
`writeFieldName`/`serializeValue` pair inside `recordToJson`
(`JSONSerializerJackson:706`–`:709`) for the swallow class. Import fails at
JSON parse, before section presence — fail-closed, hence MINOR — but the
"holds exactly … well-formed" biconditional and D20's "cleanly closed …
by construction" are false for the sub-state. (2) The swallow arm sorts by
the wrong fact: "after `exportRecord`'s internal swallow left a record
object open" describes the scope stack, but the classifier is the innermost
context. A swallow mid-embedded-array (the serializer opens raw arrays for
link lists, link sets, and embedded collections at
`JSONSerializerJackson:944`/`:951`/`:958`/`:974`/`:981`, and
`serializeLink` throws on a non-persistent link mid-array) strands the
generator at array context while a record object is open. A subsequent
abort then does not promote (`writeEndObject` throws), contradicting
"landing here too"; a run that completes instead promotes at exit 0 with
`brokenRids` and `indexes` nested inside the broken record — caught by
section presence, but by neither the exit-status pin nor the relabeled
sentences' classification. (3) The array-context gloss "(between entries
in `collections`/`records`/`indexes`)" reads as a definition, but the class
is any array scope: nested property arrays, `collection-ids` (`:481`),
`properties` (`:502`), `blob-collections` (`:455`).

Proposed resolution: make the head clause the only classifier and demote
the proxies to illustrations — object context promotes, "including with a
pending field name, in which case the promoted dump is malformed and
parse-rejected rather than well-formed"; condition the swallow arm on the
stranded context ("a swallow that strands at object context lands here; a
swallow mid-embedded-array strands at array context and follows that
class"); mark the array gloss illustrative ("any array scope, e.g. between
entries in …"). Affected: D20, F90, F100, F110.

## U34: "Two mechanism pins deliver those outcomes" overclaims — the primary-exception outcome is delivered by the suppression discipline, not by the F109 mechanisms, and "trivializes" reads as license to drop it [MINOR]

D20's hardening clause now reads: "two outcome pins ride it
(no-file-at-final-name-after-failure …; the scan failure propagates as the
primary exception, not the close-path secondary), two mechanism pins deliver
those outcomes (F109: …)." The F109 record's rationale says "the
primary-exception pin trivializes (nothing needs `writeEndObject` on the
discard path)."

The attribution is right for the first outcome and wrong for the second.
Promote-only-on-success and per-record isolation deliver the no-file pin; no
mechanism in the pair touches exception propagation. The discard path still
performs I/O — closing the generator and the gzip/file stream (the JDK
writer emits the deflate trailer at finish/close), deleting or abandoning
the `.tmp` — and an exception thrown from `finally`-resident cleanup
replaces the in-flight scan failure exactly as the legacy
`finally { close(); }` shape does (`DatabaseExport:157`–`:158`; pass 11's
failed-attack note recorded the same replacement semantics). The live case
is the correlated one: a disk-full fault that aborts the export body is the
same fault that makes the stream close throw. The narrow claim is true —
nothing needs `writeEndObject` on the discard path — but the pin does not
trivialize; the secondary class shrinks from generator-context complaints
to cleanup-I/O failures, and the operator-visible outcome still depends on
the pin's own inline discipline (`addSuppressed` or
log-then-rethrow-original), which F94 (b) still carries. An implementer
reading "deliver those outcomes" and "trivializes" together can drop the
suppression and ship an exporter whose disk-full abort surfaces the stream
complaint instead of the unreadable record — the exact regression the pin
exists to prevent.

Proposed resolution: one clause in D20 and the F109 record — the mechanism
pins deliver the no-file outcome; the primary-exception outcome stays
delivered by its own suppression discipline, unchanged (the pin narrows,
not trivializes: the secondary class becomes cleanup-I/O failures only).
Affected: D20, F109, F94.

## U35: The per-record buffer is unbounded, and the cost line hides both consequences — best-effort mode reclassifies too-big-to-buffer records as broken, and the OOME class cannot honor "discarded whole into brokenRids" [MINOR]

The isolation pin reads "each record renders to a buffer and is written
whole or discarded whole into `brokenRids`, in both modes," with the cost
recorded as "one record-sized buffer and a copy per record, on an offline
migration tool."

"Record-sized" is unbounded. Embedded entities recurse
(`JSONSerializerJackson:936`), embedded collections nest, and `writeBinary`
base64-inflates blob content 4:3; nothing bounds one record's rendering.
The legacy path streams every record through the shared generator in
O(compression-buffer) memory (`DatabaseExport:91`–`:98`, 16 KB); isolation
makes the exporter O(rendered record) plus a copy. Two consequences follow
that the pin does not state. (1) A legitimately large record — streamable
by the legacy exporter, larger than heap or the 2 GiB Java array bound when
buffered — now fails its render: in best-effort mode it is "discarded whole
into `brokenRids`," reclassifying big as broken (reported, so detection
holds, but the salvage dump silently sheds a healthy record); in fail-fast
mode the export aborts on every attempt, so the next format migration's
exporter — the one this hardening exists to protect — cannot export a
database the storage handles fine. (2) For an `OutOfMemoryError`
specifically, "discarded whole" is not deliverable at all: it is an
`Error` the per-record catch shape does not catch, and post-OOME
continuation of a salvage run is unreliable; the run dies with the `.tmp`
orphan. Every endpoint stays fail-closed via promote-only-on-success —
hence MINOR — but the pin promises a per-record disposition the mechanism
cannot provide for this class, and the cost sentence implies a boundedness
the format does not have.

Proposed resolution: one sentence on the isolation pin — bound the buffer
with a stated overflow consequence (spill to a temp file beyond a
threshold, or pin "a record whose rendering exceeds the bound aborts the
fail-fast export / is reported as oversized, distinct from corrupted, in
best-effort"), and state the O(rendered-record) memory requirement as the
accepted cost. Affected: D20, F109, F94.

## U36: The F113 liveness control treats "the logger" as one sink, but LogManager routes per requester-class category — a sentinel through any other category false-passes the control [MINOR]

D20's review pin, F94 (a), and the F102 note carry the control: "provoke
one known line through the logger before the export and confirm it appears
in the capture (or verify the logger destination where the tool supports
introspection)."

"The logger" is not one sink. `SLF4JLogManager.log` resolves an SLF4J
logger from the requester's class name and caches it per class
(`SLF4JLogManager:49`–`:64`, `LoggerFactory.getLogger(requesterName)`),
gating on `log.isEnabledForLevel(level)`. Every error line in the export
path uses requester `this`, so all of them travel exactly one category —
`…db.tool.DatabaseExport`
(`DatabaseExport:152`/`:213`/`:225`/`:281`/`:293`/`:606`). The backends in
scope route and filter per category: the server reads
`youtrackdb-server-log.properties` into JUL
(`LogManager.installCustomFormatter`), and JUL, logback, and log4j2 all
support per-category levels and per-category appenders. A pre-export
sentinel provoked through the embedding tool's own class therefore lands in
the capture while the `DatabaseExport` category is filtered or routed to a
different appender; the control passes, the export-time error lines at
`:213`/`:225`/`:606` vanish, and the review reads the empty capture as
clean — the exact misrouted-channel failure mode F113 was written against,
one level down. The level direction needs no pin (a sentinel dropped by a
threshold fails the control and the review — the safe direction), and the
destination-verification arm inherits the same gap ("the logger
destination" is per-category too). The fix is cheap because the requester
API already supports it: `LogManager.error` accepts a `Class<?>` requester
and resolves the same category as instance requesters
(`SLF4JLogManager:49`–`:53`).

Proposed resolution: one clause on the control — the sentinel is provoked
at error level through the export tool's own logger category (e.g.,
`LogManager.error(DatabaseExport.class, …)`, the category every export
error site uses), and the destination-verification arm checks that
category's effective destination, not the root's. Affected: D20, F94,
F102, F113.

## Failed attacks

- **Completion-flag crash windows.** The flag is process-local and the
  rename is the last act of the same process: a crash anywhere between
  last-section write, flag set, and rename leaves only the `.tmp`; no
  durable flag is needed and no window changes the no-file outcome.
- **"Leaves (or deletes) the `.tmp`" ambiguity.** Both arms preserve
  no-file-at-final-name; a kept orphan is replaced on the next run by the
  constructor's pre-delete of both names (`DatabaseExport:85`/`:88`).
- **Completion flag vs manifest-last ordering.** Both F75 variants compose:
  a separate-file manifest follows the dump promote under the F82 fsync
  order; a manifest-as-final-section precedes the flag; "the last section"
  is well-defined in each.
- **"Success" with non-empty `brokenRids` in fail-fast mode.** Coherent with
  the design's reported-vs-silent-loss line: per-record losses are logged
  and in-dump-reported in both modes (F94 (b)); the flag certifies all
  sections written, not zero loss.
- **Buffered-record write framing.** Implementable with valid framing:
  `writeRawValue` runs `_verifyValueWrite` before `writeRaw`
  (artifact-verified, GeneratorBase in jackson-core 2.21.4), so separators
  and context update; a naive `writeRaw` misuse drops every inter-record
  comma and fails the first round-trip test loudly.
- **The unconditional-isolation rationale.** Verified: fail-fast without
  isolation does admit the exit-0 mangled promotion (the swallow keeps
  log-and-continue in both modes; nested-array stranding completes over a
  broken records section), so "promote-only-on-success alone still admits
  the exit-0 variant" is correct as written.
- **Array-context-never-promotes.** Holds: `close()` runs the explicit
  `writeEndObject` (`:277`) before `jsonGenerator.close()` (`:278`); the
  disassembled method throws on `!inObject()`, `JsonGenerationException` is
  an `IOException`, the `:280` catch rethrows as `DatabaseExportException`,
  and `:291` is unreached.
- **F111 walk composition with the inflater arithmetic.** Header bytes
  bypass the inflater (`readHeader` reads from the raw stream, JDK 21
  `GZIPInputStream:192`–`:235`); the FNAME/FCOMMENT loops count their
  terminators, FEXTRA adds XLEN+2, FHCRC adds 2; expected = parsed header +
  `getBytesRead()` + 8 is exact for a single member.
- **F95 single-member enforcement under the parsed header.** Survives the
  F111 change: the subclass parses the first member's header, so for n ≥ 2
  members the expected size falls short of the actual by at least
  d₁+h₂+8 ≥ 18 bytes — a concatenated file always fails loudly, never
  accidentally matches.
- **F112 gate reach and actionability.** The gate is pinned on this
  branch's importer ("requires the ack flag at import"), the marker is
  operator-discoverable without a v15 tool (a scalar in the dump's
  plain-JSON `info` section under gunzip), and the residue — an
  unknown-provenance dump restored on a pre-branch binary — is exactly the
  recorded detection-only exposure.
- **F112/F101 coherence.** The reach sentence cites the same two facts (no
  upper bound; scalar skip `DatabaseImport:418`–`:420`) F101 records, and
  F101 is explicitly left standing; no contradiction.
- **D20's F100 grounding parenthetical vs the F109 mechanism pins.**
  Layered, not contradictory: the parenthetical cites the corrected legacy
  analysis, the mechanism pins govern the new exporter, and the F109 record
  states the relationship ("a mechanism instead of an accident"). U34
  attacks the delivery attribution, not this layering.
- **F113 level-direction false-pass.** None exists: a sentinel dropped by a
  level threshold makes the control fail and the review fail — the safe
  direction; only the category vector false-passes (filed as U36).
- **Cross-entry relabel consistency.** D20, F90, and F100's relabel line
  state the same class with the same arms; the shared imprecision is U33,
  not a divergence between carriers.
- **F104 extension notes, durability angle.** The mid-flight permit-leak
  wedge and its handshake are availability/concurrency material with no
  durable state implicated; left to the concurrency lens.
