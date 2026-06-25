# design-author params — tracks, Step-4b round 2 (re-ground flagged passages only)

## Inputs
- target: tracks
- output_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/plan/
- plan_dir: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/plan/
- research_log_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/research-log.md
- design_path: /home/andrii0lomakin/Projects/ytdb/harding-readability-audit/docs/adr/harding-readability-audit/_workflow/design.md
- round: 2

## flagged_passages (re-draft only these; do not re-ground the whole file)

Edit `plan/track-1.md` in place (line 1 stamp untouched). Eight readability
findings plus one absorption gap:

- **F1 (track-1.md:39, 53) — idiom.** "belt-and-suspenders" / "intended
  belt-and-suspenders": replace with the literal meaning (the orchestrator
  self-check and the agent guard each enforce the floor independently — a
  deliberate redundant double-check, not a bug).
- **F2 (track-1.md:114) — over-dense sentence.** The never-clean-tail sentence
  packs a definition, a long parenthetical, the bounding claim, the budget cap,
  and the S5 exit into one sentence. Split into three: define the never-clean
  tail; state it never settles and re-audits each round; state the two bounds
  (budget cap, then S5 exit) separately.
- **F3 (track-1.md:118) — synonym-cycle restatement.** The `because` clause
  repeats the parenthetical gloss verbatim. Drop the redundant clause; keep one
  gloss of the S3 freeze-order gate.
- **F4 (track-1.md:131) — over-dense multi-step sentence.** The one-round
  sequence (author spawn → auditor fan-out → absorption check → collect →
  dual-clean decision) is a run-on with three inline glosses. Present it as a
  short numbered list, or trim it to a lead-in that points at the sequence
  diagram just below.
- **F5 (track-1.md:53) — comma-chained enumeration.** The "not a collapse"
  three-way agreement (partition emits one / self-check expects one / guard does
  not fire) is comma-chained. Split into three short sentences or a 3-item list,
  one per actor.
- **F6 (track-1.md:35, 202, 250) — unglossed load-bearing term "wiring
  error".** Gloss at first use: name what the orchestrator does on mismatch
  (stops and reports the bad slice count rather than proceeding with the wrong
  fan-out).
- **F7 (track-1.md:181) — unglossed "gate-A7 warm-up".** Do not cite the
  ephemeral ID "gate-A7"; describe the mechanism self-containedly: the cache
  warm-up is the fixed delay between the first auditor spawn and the rest so the
  later spawns hit a warm prompt-cache prefix; it only sequences the N>1 spawns
  and never reduces N to 1, so "disable the warm-up" means "pay N cold
  prefixes," never "run one whole-doc spawn." (Seed: design.md §"Deterministic
  design-path slice partition" — "Warm-up is severed from slicing".)
- **F8 (track-1.md:7) — idiom in the BLUF.** Replace "re-litigating settled
  prose" with the literal verb ("re-reviewing prose it already settled" or
  "re-flagging already-settled prose").
- **Absorption F1 — D7 missing from `## Decision Log`.** Add a concise D7
  record after D6 (before D8, to keep numeric order — or at the end; your
  call): "D7: Tier is `full`; §1.7 routing is full staging, not the §1.7(k)
  prose-rule opt-out." Four-bullet form seeded from research-log D7 / design.md
  §"Meta: tier and §1.7 routing": Alternatives considered = the §1.7(k)
  prose-rule opt-out (edit live, gain self-application), rejected because the
  edited orchestration loops are executable procedure (criterion 2) and
  live-editing would destabilize the branch's own later phases; Rationale = the
  staging trade-off keeps live workflow at develop-state (I6); Risks/Caveats =
  the branch cannot dogfood its own fixes during its own authoring (accepted);
  Implemented in = this track (a routing constraint over every step — all edits
  staged; see `## Invariants & Constraints` I6 and `## Plan of Work`).
  `**Full design**`: design.md §"Meta: tier and §1.7 routing".

After editing, the seven `#### D<n>` headers become eight (D1–D8); confirm the
`## Decision Log` HTML-comment guidance still reads coherently.
