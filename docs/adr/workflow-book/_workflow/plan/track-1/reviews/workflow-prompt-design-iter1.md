<!-- MANIFEST
findings: 3
severity: { blocker: 0, should-fix: 1, suggestion: 2 }
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, exempt_reason: "(a) no refutation or certificate phase to persist" }
index:
  - { id: WP1, sev: should-fix, loc: "workflow-book-builder/PIPELINE.md:54,67", anchor: "#wp1-should-fix--revision-pass-ownership-is-undefined", cert: n/a, basis: judgment }
  - { id: WP2, sev: suggestion, loc: "workflow-book-builder/PIPELINE.md:58", anchor: "#wp2-suggestion--substantially-rewrote-gate-is-softer-than-the-band-taxonomy-that-actually-decides-it", cert: n/a, basis: judgment }
  - { id: WP3, sev: suggestion, loc: "workflow-book-builder/prompts/author.md:32,48", anchor: "#wp3-suggestion--authors-cross-chapter-flag-has-no-named-output-channel", cert: n/a, basis: judgment }
-->

## Findings

### WP1 [should-fix] — revision-pass ownership is undefined

- **Location:** `workflow-book-builder/PIPELINE.md:54` (Step 4) and `workflow-book-builder/PIPELINE.md:67` (Step 6); the corresponding role prompts at `workflow-book-builder/prompts/technical-reviewer.md:30` and `workflow-book-builder/prompts/beta-reader.md` ("Deliverable").
- **Axis:** clean-context invocation / deterministic decision rules.
- **Cost:** non-reproducible behavior at the revision step — one run edits chapters directly and skips the author's voice discipline, another re-spawns an author; the book's voice consistency depends on which the orchestrator improvises.
- **Issue:** Step 4 says "Apply blockers and important fixes in a short revision pass" and Step 6 says "Apply the top issues they raise in a revision pass." Both reviewer prompts explicitly forbid self-editing ("You write findings; the producer applies them in a revision pass"; "the producer applies fixes in a revision pass"), so the producer (the START-block session) owns the apply. But neither the START block nor any role prompt states *how* the producer applies a fix: edit the chapter file inline, or re-spawn an author per `author.md` over the affected sections. The two paths diverge materially — a direct inline edit bypasses the author prompt's voice-and-pacing discipline (`BOOK_BRIEF.md` rules, the connect-forward/backward paragraphs, "earn every name"), so a blocker fix applied inline can silently violate the voice rules the technical reviewer is forbidden from touching. A fresh orchestrator with no prior turn to lean on will pick arbitrarily.
- **Proposed fix:** In the START block, add one rule under "Rules that apply to the whole run" stating the apply mechanism: e.g., "Apply a technical-review or beta-read fix by re-spawning an author over the affected chapter (per `prompts/author.md`) when the fix changes prose a reader sees; apply a pure citation/anchor correction inline. Either way, the chapter re-clears the voice rules before the run ends." This makes the revision pass reproducible and keeps voice-bearing edits inside the author discipline.

### WP2 [suggestion] — "substantially rewrote" gate is softer than the band taxonomy that actually decides it

- **Location:** `workflow-book-builder/PIPELINE.md:58` (Step 5) and `workflow-book-builder/prompts/copy-editor.md:3` (preamble: "over chapters an author substantially rewrote").
- **Axis:** deterministic decision rules.
- **Cost:** an orchestrator may re-derive "substantially" by taste and copy-edit (or skip) a chapter inconsistently across runs, where the band taxonomy already gives a crisp answer.
- **Issue:** Step 5 gates the copy edit on "chapters an author substantially rewrote" and excludes "a surgical citation sweep." Step 3 already partitions every touched chapter into exactly two author outcomes: a full author (the `rewrite` and `new-or-restructure` bands) or a citation sweep (the `sweep` band). "Substantially rewrote" maps one-to-one onto "got a full author, not a sweep" — but the prompt states the gate in the soft language of degree rather than naming the band, so a reader of the prompt alone has to reconstruct the binding instead of reading it off.
- **Proposed fix:** State the gate in band terms: "Spawn a copy editor over every `rewrite` and `new-or-restructure` chapter (the chapters Step 3 gave a full author). `sweep` chapters get no copy edit." This reuses the taxonomy already defined in Step 2 and removes the degree judgment.

### WP3 [suggestion] — the author's cross-chapter flag has no named output channel

- **Location:** `workflow-book-builder/prompts/author.md:48` ("Do not change another chapter… note it for the producer") against the Deliverable at `workflow-book-builder/prompts/author.md:32`.
- **Axis:** output contract.
- **Cost:** the producer consuming an author's session output is told to look for a list of verified source files and undefined-term references, but not for the "an earlier chapter is wrong" flag, so a cross-chapter defect the author noticed can be dropped on the floor.
- **Issue:** The author's "What not to do" raises two flags to the producer: a needed-but-undefined concept ("flag it for the producer rather than defining it out of order") and a wrong/incomplete earlier chapter ("note it for the producer"). The Deliverable names an explicit end-of-session output channel for only the first ("listing the source files you verified and any concept you had to reference that no chapter yet owns"). The cross-chapter-defect flag has no place in the stated deliverable, so whether the producer ever sees it depends on the author volunteering it outside the contract. The copy-editor prompt, by contrast, folds all three of its flags into one Deliverable line; the author prompt should match.
- **Proposed fix:** Extend the author Deliverable to name the second channel too: "End your session by listing the source files you verified, any concept you had to reference that no chapter yet owns, and any earlier chapter your work revealed to be wrong or incomplete." Now both flags the body raises have a contracted home the producer can read.

## Evidence base
