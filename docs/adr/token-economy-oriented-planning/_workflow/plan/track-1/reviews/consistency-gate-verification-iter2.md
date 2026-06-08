<!-- MANIFEST
verdicts:
  - {id: CR1, verdict: VERIFIED, cert: "#### Verify CR1 "}
overall: PASS
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
flags: [CONTRACT_OK]
-->

## Verification certificates

#### Verify CR1: Byte-identical paraphrase set understated as the technical/risk/adversarial trio
- **Original issue**: The plan, design, and track file described the byte-identical sizing-rule paraphrase set as exactly three review prompts ("the three sizing-rule paraphrases (the technical, risk, and adversarial review prompts)"). The authoritative SYNC comment in `prompts/structural-review.md` names a larger set: the conventions §1.1 glossary + §1.2 planning-rule section, the create-plan Step 4 rule, and the Track terminology paraphrase in all five review prompts (technical, risk, adversarial, consistency, plus structural-review.md's own bullet). The trio framing understated the S2 invariant.
- **Fix applied (full-SYNC-set rendering, user-chosen)**: The orchestrator restated the out-of-scope set in `implementation-plan.md` (§Constraints lines 33-39 + Invariant S2 lines 166-174) and `track-1.md` (§Plan of Work S2 invariant lines 135-142, §Validation and Acceptance lines 167-182, §Interfaces and Dependencies out-of-scope set lines 206-215) to name the full SYNC set, and added the note that `structural-review.md` is edited only in its TRACK SIZING check region while its paraphrase bullet stays byte-identical.
- **Re-check**:
  - Search/trace performed: Read `prompts/structural-review.md:56-68` (SYNC comment) and `:74-80` (its own paraphrase bullet); `grep -nE "maximizes \(packs|two-sided bound|merge candidate|split candidate|~20-25"` over `consistency-review.md` and `create-plan/SKILL.md`; `grep -nE "## 1\.1 Glossary|Planning rule"` over `conventions.md`; `grep` for the byte-identical-set wording across `implementation-plan.md` and `plan/track-1.md`; Read of each edited region. Workflow-prose branch — markdown heading / exact-phrase matches are definitive, no PSI, no reference-accuracy caveat.
  - Code location: SYNC comment `prompts/structural-review.md:56-68`; own paraphrase bullet `:74-80` (distinct from the TRACK SIZING check region near `:193`); `consistency-review.md:73-75` (paraphrase present); `create-plan/SKILL.md:324-329` (Step 4 sizing rule present); `conventions.md:64` (§1.1 Glossary) and `:228` (§1.2 Planning rule, both present). Edited plan sites `implementation-plan.md:34-39, 166-174`; edited track sites `track-1.md:135-142, 167-182, 206-215`.
  - Current state: All three load-bearing documents now name the full SYNC set verbatim — "the §1.1 glossary and §1.2 plan summary, the create-plan Step 4 rule, and the Track terminology paraphrase in all five review prompts (technical, risk, adversarial, consistency, and structural-review.md's own bullet)." This is an exact match for the SYNC comment's enumeration (conventions first two numbered subsections + create-plan Step 4 + the Track terminology bullet in four named files + structural-review.md's own = five review prompts, two conventions sites, one create-plan site). Each named position is factually present (verified above), so the addition of `consistency-review.md` and `structural-review.md`'s own bullet is accurate, not a new over-statement. The diff-verification acceptance line (`track-1.md:177-182`) now guards the true must-not-change set. The plan/track correctly distinguish the structural-review.md TRACK SIZING *check* region (edited) from its paraphrase *bullet* (byte-identical), keeping S2 true for the edited file. design.md still carries the trio wording at §Advisory enforcement — expected frozen-design lag, reconciled at Phase 4 per the frozen-design rule; not a STILL OPEN basis.
- **Regression check**: Checked S1 (subordination), S3 (producer/consumer co-ship), the D1-D5 Decision Records, the Mermaid track diagram, and the scope indicator (`~3 files`) in both documents — all unchanged and still internally consistent. No phantom reference introduced: conventions §1.1/§1.2, create-plan Step 4, and all five paraphrase bullets exist. The fix is confined to the S2 enumeration wording; no other plan/track claim contradicted. Clean.
- **Verdict**: VERIFIED

## Findings

(none)
