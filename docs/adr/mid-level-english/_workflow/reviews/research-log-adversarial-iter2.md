<!-- MANIFEST
iteration: 2
findings: 2
severity: should-fix
prior_verdicts:
- id: A1
  verdict: VERIFIED
  basis: "11-prompt count corrected and re-verified (ls=11); DR7 + new discovery classify CLAUDE.md:104 as illustrative-and-lagging; total re-derived to ~50, exact list pushed to per-track Interfaces."
- id: A2
  verdict: VERIFIED
  basis: "DR3 gained the 'Tier-B restatement is required' bullet — common-word/acronym/idiom apply at comment scale, short-sentence/clause-nesting does not, mirroring conventions.md:574-581 (anchor confirmed)."
- id: A3
  verdict: VERIFIED
  basis: "DR4 now cites the criterion-2 consumer-class test at conventions.md:1229-1237 (anchor confirmed: execution-procedure files stay staged, the part-touched test is the gate)."
- id: A4
  verdict: VERIFIED
  basis: "DR5 gained the 'Reconciliation with ## Banned vocabulary' bullet stating precedence (Banned vocab owns the closed AI-tell list; Plain language owns general-English choice outside it, never re-bans a tier word)."
evidence_base: 4 certificates (DR7 decision challenge, hook/test omission assumption test, DR3 anchor-slip check, prior-finding re-verification scan); reference checks via grep+Read (mcp-steroid unreachable); facts confirmed against repo
cert_index: C-A5, A-A6, C-DR3anchor, RV-scan
flags: workflow-machinery-lens; reference-accuracy-caveat (grep-only, no PSI); DR7 challenged as fresh decision
index:
- id: A5
  sev: should-fix
  anchor: "### A5 "
  loc: research-log.md Surprises blast-radius (line 42) + DR2 + DR6
  cert: A-A6
  basis: assumption
- id: A6
  sev: suggestion
  anchor: "### A6 "
  loc: research-log.md DR3 (Tier-B restatement bullet, second anchor)
  cert: C-DR3anchor
  basis: decision
-->

## Findings

### A5 [should-fix]
**Certificate**: A-A6 (assumption test — the blast-radius set is now complete after the A1 correction)
**Target**: The revised Surprises blast-radius entry (research-log.md:42), DR2 ("no test fixtures"), and DR6 ("no hook-logic edit").
**Challenge**: The A1 correction fixed the prompt count but the enumeration still omits a whole class of subset-citation site — the runtime hook and its guard test — and the omission is the same shape the A1 finding was about (a NUMERIC "five" plus a verbatim slug list). `.claude/hooks/house-style-write-reminder.sh:262` builds `tier_b_body` from the literal string "the **five** sections in `.claude/output-styles/house-style.md`: § Orientation, § Banned vocabulary, § Banned sentence patterns, § Banned analysis patterns, § Em-dash discipline" and then restates the Orientation Tier-B carve inline ("§ Orientation bans out-of-file assumptions in code comments, not in-file terseness"). Under DR3, this hook string is a flip target twice over: the "five" becomes "six", and DR3's newly-required Tier-B plain-language carve must be added next to the Orientation one, or the runtime reminder a developer sees on every Java/Kotlin write ships stale. The log's enumeration (line 42) lists only agents, prompts, core workflow docs, and skills — no hook, no script, no settings. Separately, `.claude/scripts/tests/test_house_style_hook.py:693-728` (`test_16_section_name_guard`, `TIER_B_HEADINGS`) pins the subset slug list the hook cites; its docstring says "Update the hook script and this test together." DR2's flat "no test fixtures" is therefore imprecise: this is not a new fixture but an existing guard that must gain the sixth slug to keep guarding the full subset, and DR6's "no hook-logic edit" is true for logic yet hides a required hook-prose edit. None fail-closed — the guard checks presence, not exhaustiveness, so it stays green while silently under-covering — which is exactly why the omission needs to be named in the plan rather than discovered at implementation.
**Evidence**: `house-style-write-reminder.sh:262` (`tier_b_body` string: "the five sections … § Orientation … § Em-dash discipline" + the Orientation Tier-B carve); `test_house_style_hook.py:693-699` (`TIER_B_HEADINGS` = the five slugs) and `:702-728` (`test_16_section_name_guard`, docstring "Update the hook script and this test together"); research-log.md:42 enumeration names no hook/script/test. Reference-accuracy caveat: all are textual reads of named files (a shell string, a Python list, a test assertion), verified by grep+Read; no symbol resolution, so PSI would not change the verdict.
**Proposed fix**: Add the hook and its guard test to the blast-radius enumeration (Surprises line 42 and the per-track Interfaces). State in DR3 that the hook's `tier_b_body` gains the sixth slug, the "five→six" numeric bump, and the parallel plain-language Tier-B carve. Reword DR2 from "no test fixtures" to "no *new* mechanical check or fixture; the existing `test_16` slug guard and the hook string are updated in lockstep with house-style.md." Reword DR6's "no hook-logic edit" to "no hook *logic* edit; the hook's reminder *string* is a prose flip target."

### A6 [suggestion]
**Certificate**: C-DR3anchor (decision challenge — the DR3 Tier-B bullet's second anchor)
**Target**: DR3, the new "Tier-B restatement is required" bullet (research-log.md:18).
**Challenge**: The A2 fix is sound but introduced a citation slip in the same bullet. DR3 names the anchor correctly once ("a restatement at `conventions.md:574-581`") and then incorrectly a second time ("mirroring the Orientation floor restatement at `house-style.md:574-581`"). `house-style.md` is 471 lines, so `house-style.md:574-581` does not exist; the restatement lives only in `conventions.md:574-581`. Same line numbers, wrong file — a copy-paste of the line range onto the wrong path. It is a suggestion, not a should-fix, because the correct anchor appears earlier in the same bullet so the intended target is unambiguous, but a later reader (or a track Interfaces section deriving the edit-site list from this bullet) could chase the dead `house-style.md:574-581` anchor.
**Evidence**: `wc -l house-style.md` = 471 (`house-style.md:574-581` out of range); the Tier-B Orientation restatement text ("literal 'too terse to follow without opening the code' test does not transfer", "bans out-of-file assumptions, not in-file terseness") resolves only to `conventions.md:574-581` via grep. Reference-accuracy caveat: line-count and anchor existence are textual facts, verified by Read; no PSI dependence.
**Proposed fix**: Correct the second citation in DR3's bullet from `house-style.md:574-581` to `conventions.md:574-581`.

## Evidence base

#### RV-scan: Re-verification of the four prior findings
- **A1 — VERIFIED.** "12 prompts" → "11" in both Surprises (line 42) and the DR-level reasoning; `ls .claude/workflow/prompts/*.md` = 11 confirms. DR7 added to de-enumerate `CLAUDE.md:104`; a new Surprises discovery (line 43) classifies `:104` as illustrative-and-lagging (four items, Orientation absent — `grep -c orientation CLAUDE.md` = 0, `#1142` has no `CLAUDE.md` row, both reconfirmed). Total re-derived to ~50 with the exact set pushed to per-track Interfaces (a `lite`-tier requirement). The miscount and the `CLAUDE.md` mis-classification are both resolved.
- **A2 — VERIFIED.** DR3 gained the "Tier-B restatement is required, not skipped" bullet: common-word / acronym / idiom moves apply at code-comment scale, short-sentence / clause-nesting does not, and the §1.5 Tier-B prose gains a parallel plain-language paragraph mirroring the Orientation restatement at `conventions.md:574-581` (anchor confirmed present and on-point). The under-argued "no carve-out needed" claim is now backed by the per-surface restatement. (One citation-path slip in the same bullet — see A6 — does not reopen A2.)
- **A3 — VERIFIED.** DR4's "Why" now cites `conventions.md:1229-1237` and states the criterion-2 consumer-class test ("what part of the file an edit touches, not the file's identity"). The anchor confirms: lines 1229-1233 are criterion 2 with the execution-procedure exclusion, 1235-1237 the "what consumes the edited file, not what the planner meant" rule. The opt-out coverage of the two execution-procedure files is now grounded in the line-cited boundary.
- **A4 — VERIFIED.** DR5 gained the "Reconciliation with `## Banned vocabulary`" bullet stating the precedence: Banned vocabulary owns the closed AI-tell list (`leverage→use`, `utilize` not on it); Plain language owns general-English word choice outside that list and never re-bans a tier word; the two have different scopes (AI-anomalous frequency vs reader proficiency). The undocumented section-to-section relationship is now explicit.

#### C-A5 (folded into A-A6)
See A-A6 — the blast-radius completeness challenge is an assumption test.

#### A-A6: Assumption test — the corrected blast-radius set is now complete
- **Claim**: After the A1 correction, the enumeration (research-log.md:42) covers every subset-citation site that the five→six flip touches.
- **Stress scenario**: Sweep for the subset slug list and the numeric "five" outside the four directories the log names (agents, prompts, core workflow docs, skills) — specifically hooks, scripts, settings, and tests.
- **Code evidence**: `grep -rln 'Banned analysis patterns' .claude/hooks .claude/scripts` returns `house-style-write-reminder.sh`, `design-mechanical-checks.py`, `test_house_style_hook.py`. The hook (`:262`) carries both a NUMERIC "five" and the verbatim slug list plus the Orientation Tier-B carve; the test (`:693-728`) pins the slug list with an explicit "update the hook and this test together" docstring. The log mentions `design-mechanical-checks.py` (Surprises line 37) but not the hook or the hook test, and its blast-radius line 42 names none of them.
- **Verdict**: BREAKS. The corrected enumeration is still incomplete: the runtime hook's reminder string and its guard test are subset-citation flip targets the plan must name. The omission is the same NUMERIC-"five"-plus-slug-list shape the A1 correction was about, on a surface (an every-Java-write runtime reminder) where a stale string is user-visible.

#### C-DR3anchor: Decision challenge — DR3 Tier-B bullet second anchor
- **Chosen approach**: DR3 mirrors the new section's Tier-B carve on "the Orientation floor restatement at `house-style.md:574-581`."
- **Best rejected alternative**: Cite the restatement at its real location, `conventions.md:574-581`, the anchor the same bullet already names correctly once.
- **Counterargument trace**:
  1. The bullet cites `conventions.md:574-581` correctly for the §1.5 Tier-B prose, then re-cites the line range against `house-style.md`.
  2. `house-style.md` is 471 lines; `house-style.md:574-581` is out of range and holds nothing.
  3. A reader or a derived Interfaces list that follows the second anchor lands on a dead reference.
- **Codebase evidence**: `wc -l house-style.md` = 471; the restatement text resolves only to `conventions.md:574-581`.
- **Survival test**: WEAK (suggestion). The decision is right; the citation path is wrong and self-contradicts the earlier correct anchor in the same bullet.

#### C-A5b: Decision challenge — DR7 de-enumerate CLAUDE.md:104 (new decision)
- **Chosen approach**: Replace the four-item parenthetical at `CLAUDE.md:104` with a pointer to the canonical subset list, rather than enumerating six names there (DR7).
- **Best rejected alternative**: Grow it to six (keep the inline list, add the missing Orientation and the new section).
- **Counterargument trace**:
  1. DR7 removes the inline names so `CLAUDE.md` drops out of every future flip's blast radius and the current Orientation lag is fixed in one move.
  2. The "grow to six" alternative keeps an at-a-glance reader benefit: `CLAUDE.md` is the always-loaded surface, and a reader skimming it sees the subset contents without opening `house-conversation.md`. De-enumeration trades that inline visibility for a pointer hop.
  3. The trade is correct here. The parenthetical is not load-bearing — it is one illustrative clause inside a "two layers, two files" sentence whose real job is to say *that* the chat register applies the subset, not *which* sections it contains; the authoritative enumeration is two files away by design (`house-conversation.md:21-27`, `conventions.md §1.5`). The single-declarative-source discipline the rules already state (`house-style.md:20` calls house-style "the single declarative source") argues against a fourth hand-maintained copy on the most-edited file in the repo. The lag DR7 cites (four items, post-four→five) is direct evidence the inline copy does not get maintained.
- **Codebase evidence**: `CLAUDE.md:104` (the parenthetical sits inside the "Two layers, two files" explanatory sentence, not a normative list); `grep -c orientation CLAUDE.md` = 0 (the copy already rotted once); `house-style.md:20` self-describes as the single source.
- **Survival test**: YES. DR7 is the right call. De-enumeration loses only inline skim-visibility of the section names on one surface, which a pointer to the canonical list restores at one hop, and removes a proven-to-rot maintenance copy. No finding.
