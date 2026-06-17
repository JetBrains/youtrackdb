<!--MANIFEST
dimension: workflow-prompt-design
target: "edit-design/SKILL.md (staged), delta vs live"
commit_range: dcfb9c0442~1..dcfb9c0442
evidence_base: { certs: 0 }
cert_index: []
flags: { evidence_trail_exempt: true, exempt_reason: "(a) no refutation or certificate phase to persist" }
index:
  - { id: WP1, sev: Recommended, anchor: "wp1-comprehension-review-allow-list", loc: "edit-design/SKILL.md:501", cert: n/a, basis: judgment }
  - { id: WP2, sev: Minor, anchor: "wp2-warm-up-wait-mechanism", loc: "edit-design/SKILL.md:515-529,937", cert: n/a, basis: judgment }
-->

## Findings

### WP1 [Recommended] comprehension-review allow-list omits Grep the role's own reading rules require

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md` (line 501)
- Axis: tooling routing
- Cost: the comprehension gate is spawned with a stated allow-list it cannot do its job under, so a `**Full design**`-ref or house-style section read fails mid-review on the very kinds (`phase1-creation`, `phase4-creation`, `design-sync`) that run the whole-doc structural checks.

The shared spawn-contract table the delta adds lists the comprehension gate's allow-list as `Read` only:

```
| cold comprehension gate | `comprehension-review` | `Read` | **never** |
```

But the role's own procedure needs `Grep`. The de-warmed `prompts/design-review.md` § Reading rules (the prompt this gate runs) says *"Plan / track-file reads (`target=design`): grep-only for `**Full design**` link resolution"* and *"`house-style.md` reads: read only the cited `§ <heading>` section using grep + targeted Read"*. The `comprehension-review` agent definition restates the same: *"For `**Full design**` link resolution, grep the plan / track files only for the link target"* and *"Read `house-style.md` only for the cited `§ <heading>` … using grep plus a targeted `Read(offset, limit)`"* — yet its frontmatter is `tools: Read`. So the structural checks the comprehension gate is the sole owner of (the `**Full design**` resolution at `design-review.md` § Structural findings, and the navigability / audience-fit house-style citations) have no tool to run under. The authoritative allow-list lives in the agent definition, but this SKILL table is the wiring contract the orchestrator reads, and it propagates the same wrong surface.

Suggestion: change the table row (and the `comprehension-review.md` frontmatter at its fix-site) to `Read, Grep`. The sibling cold roles (`readability-auditor`, `absorption-check`) already carry `Read, Grep` for the identical "grep a cited section" pattern; the comprehension gate is the one role left at `Read`-only despite running the same kind of read. If the intent is genuinely to forbid Grep (e.g. force whole-file `Read` of house-style), then the agent's Reading rules and `design-review.md` § Reading rules must drop the grep instruction instead — but two of three documents currently say grep, so the allow-list is the outlier.

### WP2 [Minor] fan-out warm-up names a wait but no wait mechanism, and the one named tool cannot block in this environment

- File: `docs/adr/understandable-design/_workflow/staged-workflow/.claude/skills/edit-design/SKILL.md` (line 515-529, 937)
- Axis: deterministic decision rules
- Cost: an orchestrator reading "wait a short fixed delay … then spawn the rest" has no instruction for *how* to wait, and the one tool the delta assigns the task to (`Bash`, line 937: *"sequence the fan-out cache warm-up"*) cannot hold a foreground delay in this harness, so the step is under-specified at the point an implementer would wire it.

The warm-up paragraph says *"spawn one auditor, wait a short fixed delay (the warm-up delay, default about a minute) … then spawn the rest concurrently"* and *"Do not block until the first agent finishes … wait only the fixed warm-up delay."* The mechanism for the wait is unstated, and the `Tools used` section points the wait at `Bash`. A foreground `sleep` is the obvious reading, but it is not viable here, and "wait without blocking on the agent" is precisely the hard part the prose glosses.

This is partly mitigated: the design frames the warm-up as a tunable cost lever, not a correctness dependency, and the prose explicitly states the loop must produce correct dual-clean output with the warm-up disabled — so an implementer who cannot realize the wait still has a correct fallback. The track also defers the exact warm-up plumbing to implementation (gate A7, D13 Risks/Caveats). Given that escape hatch this is a polish note, not a blocker: a one-clause pointer to *how* the delay is taken (or an explicit "the wait mechanism is an implementation choice; if none is available, disable the warm-up and pay N cold prefixes") would close the gap a reader hits at line 515.

## Evidence base
