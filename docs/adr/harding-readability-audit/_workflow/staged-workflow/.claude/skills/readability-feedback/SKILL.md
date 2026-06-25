---
name: readability-feedback
description: "Audit a finished design document for hard-to-read or hard-to-understand paragraphs, then harden the house-style rules so future design docs avoid them. Fans out audit sub-agents, classifies each obscure passage as caught-by-an-existing-rule or a gap, and proposes plus (on approval) applies rule changes across the style docs. Use when the user wants to feed a design doc back into the style guide, find obscure paragraphs and fix the rules, harden the writing rules from a real DD, or close the readability feedback loop. Accepts a design-doc path, an adr dir name, or defaults to the current branch's design. Reports the doc's own violations; does not rewrite the doc."
argument-hint: "[design-doc-path | adr-dir | (default: current branch's design)]"
user-invocable: true
---

Audit a design document for obscure prose and harden the house-style rules so the next design doc avoids the same trouble. The output is rule changes, not a rewrite of the audited doc.

This is the codified form of the manual loop: read a real DD, find the paragraphs a reviewer has to re-read, check whether a rule already forbids each one, and for the ones no rule catches, add or sharpen a rule in the style docs.

## When to use this, and when not

Use it when the user points at a finished or near-finished design document and wants the *rules* improved from it: "harden the style from this design", "find the hard-to-read paragraphs and fix the rules", "feed this DD back into the style guide".

Distinct from three neighbors: `ai-tells` rewrites a draft; `review-docs` validates a doc's grammar and query correctness; `self-improvement-reflection` files YouTrack friction issues. This one edits the prose rules in `house-style.md` and its sync set, driven by a real DD.

It does not rewrite the audited design document. Design docs are frozen after Phase 1 or live on another branch, so the skill reports their obscure paragraphs for the author to fix separately and changes only the rule docs.

## Inputs

`$ARGUMENTS` resolves in this order:

1. A file path to `design.md`, `design-final.md`, or `design-mechanics.md` — audit that file (and its companion if both exist).
2. An adr dir name — audit `docs/adr/<dir>/_workflow/design.md` plus `design-mechanics.md`, or `docs/adr/<dir>/design-final.md` if the working copy is gone.
3. Empty — discover the current branch's design under `docs/adr/`. If more than one candidate exists, ask which.

The authoritative ruleset is always the live `.claude/output-styles/house-style.md` in the repo, never a copy.

## Procedure

1. **Resolve the target.** Confirm the file(s) exist. Capture section boundaries with `grep -nE '^#{1,3} ' <file>` and the line count.
2. **Partition.** Split the doc into ~200-line ranges on `##` / `# Part` boundaries; give each companion file (`design-mechanics.md`) its own range. Cap at ~6 sub-agents. This is the canonical partition **value** (window size, boundary set, cap) that the in-loop `readability-auditor` fan-out in `edit-design/SKILL.md` § Step 4 reuses — keep the two in sync so the standalone tool and the in-loop path cannot drift. Only the partition value is shared: this tool fans out `general-purpose` sub-agents with the self-contained inline prompt below (step 3), so it does **not** adopt the in-loop path's whole-doc floor, its agent-side whole-doc guard, or its `slice_count` / `total_lines` params (those sub-agents take no params file).
3. **Fan out the audit.** Launch one `general-purpose` sub-agent per range, in parallel, with the dispatch prompt in `## Audit sub-agent prompt` below (fill `{TARGET_PATH}`, `{START}`, `{END}`). Each agent reads `house-style.md` in full, audits only its range, and classifies every obscure passage as `CAUGHT by § <section>` or `GAP`.
4. **Synthesize.** Merge the findings. Split them into CAUGHT (the doc's own violations) and GAP (no rule catches). Group the GAPs by underlying tell, and fold an aggravated-but-caught residual into the nearest group.
5. **Draft one rule change per GAP group.** Name the rule, the target section (general tells go under Banned sentence patterns / Banned analysis patterns / Structural rules; design-doc-shape tells go under Document-shape rules), the scope, a Before/After grounded in the real finding, and the sync set it triggers (see `## Rule sync map`).
6. **Gate.** Present two things and stop for approval: (a) the obscure-paragraph report with `file:line` for both CAUGHT and GAP, and (b) the proposed rule changes grouped by tell. Apply only what the user approves. Editing the rules is never automatic, because every rule applies to every future design doc.
7. **Apply through the sync discipline.** For each approved rule, walk `## Rule sync map` and edit every file the rule's scope touches in one pass.
8. **Validate and report.** Run the checks in `## Validation`, report what changed, and offer to commit. Re-runs converge: because step 3 reads the current rules, a paragraph that was a GAP last time is CAUGHT next time.

## Rule sync map

When a rule is added or renamed, update every file its scope touches:

- `.claude/output-styles/house-style.md` — the rule prose **and** the matching `## Self-check` item (1-10). Always.
- `.claude/output-styles/house-conversation.md` — the matching AI-tell enumeration line, only if the rule is general (it then applies to chat too). Skip for design-only rules.
- `.claude/workflow/prompts/design-review.md` — a `### Human-reader cold-read additions` bullet, the `§ Tone and depth` count, and the TOC-row summary, only if the rule is a design-doc-shape rule the cold-read reviewer must verify. A rule on prose density or terseness (the kind `## Orientation` and the over-dense AI-tells cover) instead joins the `### Prose AI-tell additions` block, which scans both `target=design` and `target=tracks` and carries its own `§ Tone and depth` evidence clause.
- `.claude/workflow/design-document-rules.md` — the `## Mechanical checks` table and/or the `design-sync` Human-reader enumeration, only if the rule is design-doc-scoped.
- `.claude/scripts/design-mechanical-checks.py` — a regex constant wired into `check_dsc_ai_tell`, only if the pattern is cleanly regex-detectable. Most readability tells are judgment-only; say so and skip the script.

On a rename, run the governance grep from `conventions.md §1.5` to find every pointer and update them in the same commit:

```bash
grep -rnE '## Orientation|## Plain language|§ Orientation|§ Plain language|Banned sentence patterns|Banned analysis patterns' .claude/ CLAUDE.md
```

`Orientation` and `Plain language` are common words, so the scan matches them only in their `##` or `§` heading-pointer form to stay precise; the other two names are distinctive enough to match bare.

## Validation

- `python3 .claude/scripts/workflow-reindex.py --check` — run after editing any `.claude/workflow/**` file; confirms the TOC regions still resolve.
- Code-fence balance — after adding a Before/After block, confirm each fenced block has a matching closer. A raw fence count can mislead, because an inline code span may itself contain backtick delimiters.
- After a rename, grep that no stale rule name survives anywhere under `.claude/`.

## Audit sub-agent prompt

Dispatch this to each range agent, substituting the three placeholders:

```text
Audit a YouTrackDB design document for hard-to-read prose and classify each finding against the house-style ruleset.

STEP 1 — Read the authoritative ruleset IN FULL: `.claude/output-styles/house-style.md`. Note especially § Orientation, § Plain language, § Banned sentence patterns, § Banned analysis patterns (and its subsections), § Punctuation and typography, § Structural rules (and its subsections), and § Document-shape rules.

STEP 2 — Read `{TARGET_PATH}`, lines {START}-{END} only.

STEP 3 — Find every obscure passage in that range: run-on multi-clause sentences; dense identifier soup with no connective tissue; nominalizations and placeholder words (nouns or pro-verbs); file:line, signature, or code-literal asides wedged into a sentence; inline (1)(2)(3) enumerations; broken or telegraphic grammar (a signature or runtime expression in the subject slot, a missing copula, a dropped relative pronoun, a split predicate); disconnected one-line assertions with no motivation. Audit prose and bullets only — skip Mermaid bodies, D/S codes, References footers, and headings.

STEP 4 — Classify each finding as `CAUGHT by § <exact section>` or `GAP`. A too-terse passage — prose that cannot be followed without opening the code, or a one-line assertion dropped with no motivation — is `CAUGHT by § Orientation`, not a GAP. A passage that is hard to read for uncommon words, long sentences, or idioms is `CAUGHT by § Plain language`, not a GAP. Mark GAP only after checking every relevant section; for a GAP, name in one sentence the dimension of unreadability no current rule addresses.

Do NOT propose rewrites. Return EXACTLY this Markdown, no preamble:

## Summary
- Range audited: {TARGET_PATH}:{START}-{END}
- Total findings: <n>
- Caught: <n>   GAP: <n>

## Findings
### F1
- Location: <file>:<line(s)>
- Phrase: "<verbatim quote>"
- Why obscure: <one sentence>
- Verdict: CAUGHT by § <section> | GAP
- If GAP — uncovered dimension: <one sentence>
```
