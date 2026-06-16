# Pass-2 author round-2 summary — design.md

## STOP condition hit: design.md was being written by another process

Mid-pass, `Edit` began reporting "modified since read" on `design.md`, and a
`git diff --stat` showed 452 insertions / 306 deletions — far larger than this
wording pass touches. The file's mtime and size kept advancing across observation
windows (e.g. mtime 1781600415 → 1781600468, size 60930 → 60952 over ~25 s). A
concurrent writer is active on the file, which contradicts the prompt's premise
that no other agent is editing it. Per the hard guardrail, I stopped and did not
retry against the racing writer. FIX 7, 8, and 9 were not applied for this reason.

## FIX items applied (confirmed present in the current file content)

- **FIX 1 — R3 F1 (Tx-local index overlay TL;DR).** Roundabout negation "not a
  content copy" rewritten to the positive claim: "The overlay copies only the
  definitions, because an index is a thin handle over a storage-backed engine, so
  the index content stays in the engine and there is nothing to deep-copy."
- **FIX 2 — R3 F2.** Split coordinate predicate separated into two sentences:
  "Copying the handles would duplicate pointers to the same shared engines and
  give no isolation. A new index has no engine to copy at all."
- **FIX 3 — R3 F3.** Two roundabout negations rewritten positively, "cached-out
  slot" glossed in one clause, and the chain linearized: "The overlay reaches the
  snapshot only through a forced rebuild. … the cached set holds the index list as
  it stood at init and stays stale across an overlay change. … goes to the new
  index's cached-out slot (its definition is absent from the stale cached set) and
  is silently untracked. The rebuild discards the stale cached set … it leaves the
  snapshot to rebuild lazily."
- **FIX 4 — R3 F5.** Telegraphic trailing fragment joined ("and it runs as part of
  the commit's single atomic operation"); roundabout negation "with deleted rows
  left out" rewritten to the positive "it indexes each created or updated row whose
  value is in memory and skips each deleted row."
- **FIX 5 — R3 F6.** Roundabout negation "changes no query result" and its dangling
  antecedent replaced with the plain positive claim "Queries return the same rows
  throughout," ordered before the stored-name-lag sentence. No new mechanism
  rationale added (a "because they resolve by re-keyed association" clause was
  drafted, then removed as a new code claim and a fresh negative-parallelism shape).
- **FIX 6 — R5 F6.** Roundabout negation "it can never leave a target database that
  silently holds a half-migrated schema" replaced with the positive claim: "always
  surfaces as a loud verification failure that blocks the target from returning to
  service." (The "silent" contrast carried no fact beyond the positive "loud"
  claim, so it was dropped per the worklist's "keep the positive claim only".)

## FIX items NOT applied (stopped on racing writer)

- **FIX 7 — R5 F7** (`design.md` ~line 1017): "rejected by the import parser rather
  than read in as a valid record" — intended rewrite to active voice + dropped
  contrast: "The import parser rejects a dump that is structurally well-formed JSON
  but malformed in content." Edit refused twice as "modified since read."
- **FIX 8 — R2 F3** (the "validated against it" passive / ambiguous "it"): not
  reached before the race began.
- **FIX 9 — R1 F11** ("transient quiesce" gloss + "Reconciliation" actor): not
  reached before the race began.

## Guardrail confirmation

- No LEAVE-list passage was touched by me.
- No code claim was added; FIX 5's drafted mechanism clause was removed precisely to
  avoid a new claim.
- Line 1 stamp, Mermaid blocks, `### Decisions & invariants` lists, headings, and the
  four-lock order were not touched by me. (Note: line 1's `workflow-sha` stamp and
  parts of `## Overview` / `## Core Concepts` were changed by the concurrent writer,
  not by this author.)
- `## Overview` was not edited by me.
