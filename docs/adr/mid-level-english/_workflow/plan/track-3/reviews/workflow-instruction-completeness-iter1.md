<!-- MANIFEST
findings: 3   severity: {Critical: 0, Recommended: 2, Minor: 1}
index:
  - {id: WI1, sev: Recommended, loc: .claude/agents/review-workflow-writing-style.md:205, anchor: "### WI1 ", cert: C1, basis: "new ### Plain language criterion produces findings but the Output-format Axis taxonomy has no plain-language value; reviewer improvises a label or mislabels as banned vocabulary"}
  - {id: WI2, sev: Recommended, loc: .claude/agents/review-workflow-writing-style.md:173-175, anchor: "### WI2 ", cert: C2, basis: "file-output Evidence-base cert description enumerates only section-length and banned-vocab cert shapes; a plain-language finding has no cert-material template"}
  - {id: WI3, sev: Minor, loc: .claude/agents/review-workflow-writing-style.md:121-126, anchor: "### WI3 ", cert: C3, basis: "## Process has no step driving the plain-language lens; pre-existing pattern (heading style, repo-anchored voice also unstepped), so the new lens follows the gap rather than introducing one"}
evidence_base: {section: "## Evidence base", certs: 5, matches: 3}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: SURVIVED, anchor: "#### C4 "}
  - {id: C5, verdict: SURVIVED, anchor: "#### C5 "}
flags: [CONTRACT_OK]
-->

## Findings

### WI1 [Recommended]
- **Axis:** phase output → next-phase input
- **Cost:** orphan output — a plain-language finding has no axis label in the format that consumes it, so the reviewer improvises one or files it under the wrong lens
- **Issue:** Step 2 adds the `### Plain language` criterion (`:75`-`:78`), which produces findings. The Output-format render template at `:205` enumerates the `Axis:` value set: `banned vocabulary | em-dash overuse | BLUF lead | section length | heading style | repo-anchored voice | knowledge-cutoff disclaimer | bullet-vs-prose | conciseness | adjective triads`. That set covers every other `### ` subsection in `## Review criteria` but omits `plain language`. A reviewer that finds a plain-language issue has no axis label for it. The likely improvisation is `banned vocabulary`, which is exactly the lens the new scope guard (`:78`) tells it not to collide with, so the omission can quietly re-route a plain-language hit into the lens it was meant to stay out of.
- **Suggestion:** Add `| plain language` to the `Axis:` enumeration at `:205`, between `banned vocabulary` and `em-dash overuse` so the order tracks the `## Review criteria` section order.

### WI2 [Recommended]
- **Axis:** phase output → next-phase input
- **Cost:** orphan output on the file-output path — a plain-language finding written to the review file has no cert-material template, so the reviewer improvises its `## Evidence base` rendering
- **Issue:** On the file-plus-manifest path, the `## Evidence base` cert description (`:173`-`:175`) names the cert material as "each finding's style check from the `## Process` steps: the section-length three-step decision (size threshold → exempt-category → padding-pattern) or the banned-vocabulary sweep result that confirms the violation." That enumerates two cert shapes and stops. A plain-language finding is neither a section-length decision nor a banned-vocab sweep result — it is the judgment call the `### Plain language` criterion describes (`:76` "this is a judgment call … report each as a finding"). The reviewer writing the file has no template for the plain-language cert and must invent one. This is the file-path analogue of WI1: WI1 leaves the inline-output finding without an axis, WI2 leaves the file-output finding without a cert shape.
- **Suggestion:** Extend the cert-material sentence at `:173`-`:175` to name the plain-language cert shape, e.g. append "…, or, for a plain-language finding, the one-line judgment that the flagged unit reads harder than its plain rewrite (no count, no score, matching the criterion at `### Plain language`)."

### WI3 [Minor]
- **Axis:** error and recovery path
- **Cost:** the `## Process` walk-through never names the plain-language lens, so a reviewer that follows the numbered steps literally can finish without ever applying it
- **Issue:** The `## Process` steps (`:121`-`:126`) are (1) read house-style, (2) grep banned vocabulary, (3) scan em-dash + length, (4) spot-check BLUF. No step drives the reviewer to read each unit for plain-language clarity, even though `### Plain language` is now a first-class criterion. This is graded Minor because it is a pre-existing pattern, not a gap this change introduces: the same Process already omits a step for heading style, repo-anchored voice, knowledge-cutoff, bullet-vs-prose, conciseness, and adjective triads. The new lens follows the established (incomplete) shape of the Process rather than breaking a complete one. The `### Plain language` criterion is reachable from the "Key rules to enforce" pointer (`:31`) and from `## Review criteria` itself, so the lens is not stranded; it is only absent from the optional procedural walk-through.
- **Suggestion:** Optional. If the Process is meant to enumerate every criterion, add a step for plain-language clarity (a per-unit read for the four signals at `:77`); if it is meant to be a representative subset, leave it and accept that `## Review criteria` is the authoritative checklist.

## Evidence base

#### C1 WI1 — CONFIRMED
Axis taxonomy at `:205` lists 10 values; cross-checked against the `### ` subsections under `## Review criteria` (`:70` banned vocabulary, `:80` em-dash, `:84` BLUF, `:88` section length, `:94` heading style, `:98` repo-anchored voice, `:102` knowledge-cutoff, `:106` bullet-vs-prose, `:110` conciseness, `:118` adjective triads). All 10 map to an axis value; only the newly added `### Plain language` (`:75`) has no axis value. The taxonomy was complete pre-change, so the omission is introduced by this track.

#### C2 WI2 — CONFIRMED
File-output cert description at `:173`-`:175` enumerates exactly two cert shapes (section-length three-step decision; banned-vocab sweep result). The `### Plain language` criterion (`:76`) is explicitly a no-score judgment call, matching neither enumerated shape; no third shape is named. Confirmed orphan on the file-output path.

#### C3 WI3 — CONFIRMED (pre-existing pattern, hence Minor)
`## Process` (`:121`-`:126`) has 4 steps mapping to banned vocabulary (step 2), em-dash + section length (step 3), BLUF (step 4). The remaining `### ` criteria (heading style, repo-anchored voice, knowledge-cutoff, bullet-vs-prose, conciseness, adjective triads) have no Process step either, so the absent plain-language step follows the established pattern. The criterion is reachable via the `:31` pointer and `## Review criteria`, so it is not unreachable — only absent from the walk-through. Graded Minor on the introduced-vs-pre-existing distinction.

#### C4 (focal point: scope-guard overlap with banned-vocabulary sweep) — SURVIVED
Scope guard at `:78` explicitly resolves the double-report risk: "It also never re-bans a `## Banned vocabulary` tier word; that closed list belongs to the banned-vocabulary sweep above, and a tier word is reported there, not here." Ownership is assigned. No finding.

#### C5 (focal point: "See § Review criteria → Plain language below" pointer resolution) — SURVIVED
Pointer at `:31` resolves to the `### Plain language` subsection at `:75` under `## Review criteria` (`:68`). Section exists, ordering ("below") is correct. The Track 1 dependency also holds: the `## Plain language` section exists in house-style.md (`:78`) with the boundary clause (`:90`) the lens mirrors. No finding.
