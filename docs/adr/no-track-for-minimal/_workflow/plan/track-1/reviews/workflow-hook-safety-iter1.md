<!--MANIFEST
dimension: workflow-hook-safety
iteration: 1
target: "Track 1 (track-level) — phase ledger primitive + determine_state ledger rewrite + tests in workflow-startup-precheck.sh (6c2e0b5f68..HEAD)"
evidence_base: { certs: 0 }
cert_index: []
flags: [evidence-trail-exempt]
findings:
  - { id: WH1, sev: Minor, anchor: "wh1-ledger_tail_value-comment-claims-a-quoted-span-consumption-the-code-does-not-implement", loc: "docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh:1658-1677", cert: n/a, basis: judgment }
-->

## Findings

### WH1 [Minor] `ledger_tail_value` comment claims a quoted-span consumption the code does not implement

- File: `docs/adr/no-track-for-minimal/_workflow/staged-workflow/.claude/scripts/workflow-startup-precheck.sh` (line 1658-1677)
- Axis: error handling
- Cost: a future maintainer trusting the comment could reorder the append emit sequence (categories before phase/track) believing the reader tolerates embedded `key=` decoys, silently breaking last-value-wins resolution
- Source: judgment

The reader's anchor comment promises a mechanism the code does not implement:

```sh
# Anchor the key at a token boundary: either at line start (`key=`) or
# after a space (` key=`), so `track=` never matches inside `xtrack=` and
# the `phase=` key never matches a `categories="…phase=…"` substring (a
# quoted value is consumed whole below before the next key is scanned).
```

The parenthetical claim — "a quoted value is consumed whole below before the
next key is scanned" — is false. `ledger_tail_value` does a single `case
" $line" in *" $key="*)` test and one `rest="${line#*" $key="}"` shortest-prefix
strip. It takes the FIRST ` $key=` occurrence on the line; there is no loop that
consumes the quoted `categories` span first, and the scan never re-examines the
rest of the line. The decoy inside `categories="…phase=…"` is never reached only
because the emitter (`append_ledger`, lines 1599-1604) writes the bare `phase`
and `track` tokens BEFORE the quoted `categories`, so the real bare token is
always the first match.

Verified empirically against the staged script:
- `--phase A --categories 'Foo phase=Done bar'` writes
  `phase=A categories="Foo phase=Done bar"` and resolves `phase=A` (the embedded
  `phase=Done` does not win — first-match takes the real token, not because the
  quoted span was consumed).
- `--phase C --track 1 --categories 'decoy track=9 here'` resolves `track=1`
  (the decoy `track=9` inside categories is past the first match).

The outcome the comment promises (a decoy key inside `categories` never wins for
the two keys the script reads, `phase` and `track`) holds today — but it rests on
emit-order plus first-match-per-line, NOT on the quoted-span consumption the
comment describes. The defect is the stale rationale, not current behavior: the
read path is correct for the pinned grammar. The risk is latent coupling — the
safety invariant ("a bare read-key always precedes the quoted `categories` on the
line") is undocumented at the reader, while a plausible-but-wrong invariant
("quoted spans are skipped, so any key order is safe") is documented. Track 2's
runtime consumers also read this ledger; if one ever reads a key emitted AFTER
`categories` (today only the orchestrator-only `s17`/`paused` follow it, and
neither is read by this script), the first-match-per-line behavior would pick a
same-named decoy inside the quoted value. Suggestion: correct the comment to
state the actual mechanism — the reader takes the first ` key=` token on the
line, and decoy `key=` substrings inside `categories` are avoided because the
emitter places every bare read-key before the quoted `categories` field — so the
real invariant (emit-order dependence) is the one a maintainer sees.

## Evidence base
