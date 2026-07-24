<!-- MANIFEST
findings: 2   severity: {blocker: 0, should-fix: 0, suggestion: 2}
index:
  - {id: WS1, sev: suggestion, loc: "track-4.md:148", anchor: "### WS1 ", cert: C1, basis: "Episodes Step 3 Critical context: three Phase-C focal points crammed into one sentence as inline (a)/(b)/(c); house-style Mechanism-traces rule says break an inline enumeration onto separate lines. Episode structured-field block (length-cap exempt) and Phase-4-swept, so low priority"}
  - {id: WS2, sev: suggestion, loc: "track-4.md:135", anchor: "### WS2 ", cert: C2, basis: "Episodes Step 3 'What changed from the plan': two additions crammed into one sentence as inline (1)/(2); same Mechanism-traces inline-enumeration rule; also a mild 'not only values' scope tail. Phase-4-swept, low priority"}
evidence_base: {section: "## Evidence base", certs: 2, matches: 2}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
flags: [CONTRACT_OK]
-->

## Findings

### WS1 [suggestion] Inline (a)/(b)/(c) focal-point enumeration crammed into one sentence

- **File**: `docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-4.md` (line 148), Episodes → Step 3 → Critical context.
- **Axis**: bullet-vs-prose (inline enumeration).
- **Cost**: three parallel focal points packed into a single sentence as `(a) … ; (b) … ; (c) …`, each carrying its own dash-elaboration. The reader has to disassemble a run-on to separate the three items.
- **Issue**: violates `house-style.md § Mechanism traces and inline citations` — "An inline `(1)… (2)… (3)… ` enumeration crammed into one sentence is the same run-on; break it onto separate lines." The `### Title Case headings forbidden` / § Structural rules length-cap exemption for ExecPlan `## Episodes` structured-field blocks covers section length only; the inline-enumeration rule is a structural readability rule that is not relaxed by the registry-terse log register (breaking onto lines does not lengthen the prose).
- **Suggestion**: keep the `**Phase C focal points:**` label and reflow the three items as three list lines, e.g.
  ```
  **Phase C focal points:**
  - the non-String `Text` gate fires only when a class context is available — a fully-generic `g.V().has(nonStringField, Text)` still translates;
  - the `has(key)` presence recogniser relies on the `values→properties` optimiser rewrite — if that rewrite changes, the returnType acceptance must follow;
  - the polymorphic subclass sweep over-declines conservatively (any included subclass declaring the property non-String declines the whole `has()`).
  ```
- **Scope note**: this Episode block is a length-cap-exempt ExecPlan structured-field block and the whole `_workflow/` tree is swept at the Phase 4 cleanup commit, so this is a low-priority (suggestion) style item, not a merge blocker.

### WS2 [suggestion] Inline (1)/(2) enumeration crammed into one sentence

- **File**: `docs/adr/gremlin-to-match-translator-design/_workflow/plan/track-4.md` (line 135), Episodes → Step 3 → "What changed from the plan".
- **Axis**: bullet-vs-prose (inline enumeration).
- **Cost**: two correctness-driven additions packed into one sentence as `(1) … ; (2) …`, the second item running long with its own parenthetical. Same run-on disassembly cost as WS1, at smaller scale.
- **Issue**: same `house-style.md § Mechanism traces and inline citations` inline-enumeration rule. Secondary: the item-(2) tail "…the `values→properties` optimiser rewrite), not only `values`" is a mild negative-parallelism scope clause per `§ Banned sentence patterns`.
- **Suggestion**: reflow onto two list lines, e.g.
  ```
  Two correctness-driven additions the plan did not spell out:
  - an `isVertexClass` guard on the `~label` re-type, so a typo'd or edge-class label declines rather than re-typing to a class whose `SELECT FROM L` would error where native returns empty;
  - the presence recogniser accepts both the `values` and the `properties` return type of `PropertiesStep` (the `values→properties` optimiser rewrite).
  ```
  The rewritten item-(2) also drops the "not only `values`" negative-parallelism tail by stating the positive ("accepts both … `values` and … `properties`").
- **Scope note**: same Phase-4-swept, length-cap-exempt Episode block as WS1 — low priority.

## Evidence base

#### C1 Inline (a)/(b)/(c) enumeration — CONFIRMED

track-4.md:148 packs three focal points into one sentence as `(a) … (b) … (c) …`; `house-style.md § Mechanism traces and inline citations` explicitly names the inline `(1)/(2)/(3)` enumeration a run-on to break onto separate lines, and the § Structural rules length-cap exemption for `## Episodes` structured-field blocks covers length only, not inline enumeration — confirmed violation.

#### C2 Inline (1)/(2) enumeration — CONFIRMED

track-4.md:135 packs two additions into one sentence as `(1) … (2) …` under the same `§ Mechanism traces and inline citations` inline-enumeration rule; the trailing "not only `values`" is additionally a `§ Banned sentence patterns` negative-parallelism scope clause — confirmed violation.
