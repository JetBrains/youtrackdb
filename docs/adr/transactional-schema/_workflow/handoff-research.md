# Handoff: Phase 0 — pass 11 registered; F104–F113 await settlement

**Paused:** 2026-06-12
**Phase:** 0 (research / adversarial loop, `/create-plan`)
**Context level at pause:** warning (40%) — clean stop after the pass-11 registration commit
**Branch:** transactional-schema
**HEAD:** 57f835bb14 "Run adversarial pass 11 (scoped), register F104-F113"
**Unpushed:** 0 commits (all pushed)

## What I was investigating

This session settled all of pass 9 (F93/F94/F95) and pass 10 (F96–F103), then
ran scoped pass 11 (two lenses over the pass-10 settlement diff
`da824ff9d5..0f630c2942`) and registered F104–F113 with proposed resolutions
in §2a. Verdict: 0 BLOCKER, 3 MAJOR, 7 MINOR — concentrated on the
brand-new F96 re-swap text and the F100/F103 pin rewrites.

## Already ruled out

- **The F96 release race** — attacked and survived: the session-keyed CAS
  delivers exactly-one-release for the owner-finally-vs-pool-teardown race;
  the F98 dissolution holds; the ordinal's ABA story holds.
- **The F97 three-pin composition** — mask and leak both excluded when all
  three pins hold (the gap is placement wording, F108, not composition).
- **F100's primary-exception pin, the pre-deleted-final-name claim, F101's
  nine-branch inventory, F102's wording coherence, F103's probe story and
  reject arm** — all survived the durability lens; see the report's
  failed-attack list.
- Settlement decisions this session (do not reopen): F96 = wedge rejected,
  `Semaphore(1)` + session-keyed atomic compare-and-clear guard; F100 =
  outcomes pinned (no file at final name, root cause primary); F103 =
  reject-non-gzip on the migration path; F94 = fail-fast exporter is this
  branch's deliverable (YTDB-1115 filed, correcting comment posted).

## Most promising lead

The settlement queue, proposed resolutions registered in §2a (full analysis
in `adversarial-pass11-{concurrency,durability}.md`):

- **F104 [MAJOR]** — engage/teardown handshake unspecified: pool teardown
  racing a mid-flight acquire leaks the permit with no releaser (the wedge
  returns); also pin engagement-record survival across `clear()`.
- **F105 [MAJOR]** — holder record needs the acquiring-thread field for the
  engage guard (never compared by the release CAS).
- **F109 [MAJOR]** — no-file pin not an invariant: a swallowed broken record
  strands the generator at object context and the abort promotes; fix =
  promote-only-on-success (completion flag before rename).
- **F106/F107/F108** [MINOR] — heal-exclusion properties unstated; residual
  thread-owned-lock language in four live anchors (incl. D7's Guard (F38)
  bullet); gate placement pinned to the freezer's wake loop.
- **F110/F111/F112/F113** [MINOR] — promote-class label; gzip header-length
  parse; best-effort ack gate reach; error-capture liveness control.

## Open questions

- **Pass 12 vs loop-dry** after F104–F113 settle: F104/F105/F109 will produce
  fresh mechanism text (the handshake, the thread field, the
  promote-only-on-success rewrite) — the same fresh-text argument that
  motivated passes 10 and 11. User decides.
- **Performance pass PRE-COMMITTED** once the loop is dry (fresh session).
- Then **Phase 1** via `/create-plan` Step 4a (`edit-design`). Open Phase-1
  items: D12/F57 boundary; pin candidates (freezer read-lock enclosure,
  export-into-empty-directory).

## Raw notes / partial findings

- Commits this session (all pushed): `c726cdbb8f` resume + handoff delete,
  `12a0ae9132`+`3aee54b05d` F94, `5c5ab117ef` F93, `c496195c8b` F95,
  `da824ff9d5` pass-10 register, `b836dbe570` F97, `51c196fe52` F96 (+F98),
  `6ba329645b` F100, `be6857a4cf` F99, `5b0932d31c` F101, `520954f8d1` F102,
  `0f630c2942` F103, `57f835bb14` pass-11 register.
- YTDB-1115 filed (exporter fail-fast) with a correcting comment scoping it
  to this branch's new exporter; YTDB-1113 remains closed Invalid.
- IDE: `transactional-schema` and `ytdb-fork-3.8-dev` open; preflight clean
  this session.

## Resume notes

- Do NOT re-explore: F1–F103 settled ground, the F92 Gremlin threading
  ground, or the pass-11 reports' failed-attack lists.
- **Next action on resume:** settle F104–F113 one by one with the user
  (orientation register in chat, ASCII diagrams in chat, Mermaid in files;
  MAJORs F104/F105/F109 first), one commit per settlement, folding each
  accepted resolution into D7/D20 and the F-records. Then the user decides
  pass 12 vs loop-dry → the pre-committed performance pass.
- mcp-steroid: re-run `steroid_list_projects` preflight on resume before any
  symbol audit.
