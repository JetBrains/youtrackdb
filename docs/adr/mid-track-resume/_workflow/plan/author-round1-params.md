# design-author params — phase1-creation round 1

- target: design
- output_path: /home/andrii0lomakin/Projects/ytdb/mid-track-resume/docs/adr/mid-track-resume/_workflow/design.md
- research_log_path: /home/andrii0lomakin/Projects/ytdb/mid-track-resume/docs/adr/mid-track-resume/_workflow/research-log.md
- round: 1

## Notes (subject framing — ground the decisions in the research log, not here)

The design covers re-routing the **State-C resume sub-state** in
`.claude/scripts/workflow-startup-precheck.sh` from the track file's
`## Concrete Steps` roster parse to a new **track-scoped `substate` key on the
phase ledger** (research-log decisions D1/D2/D3), with a wrap-fixed
`roster_scan` kept as the fallback. This is **bash + markdown workflow
machinery**, NOT Java — so the "Class Design" section maps to the script's
function structure and the ledger grammar, and "Workflow" maps to the
resume-state-machine flow and the per-track `substate` lifecycle (the committed
append-boundary cadence in D1's table). Diagrams: a state diagram of the
sub-state lifecycle and a flow diagram of the dual-path resolution
(ledger-primary → wrap-fixed `roster_scan` fallback) earn their place; render
them as Mermaid `stateDiagram`/`flowchart`, not `classDiagram`.

Ground in the live code: `workflow-startup-precheck.sh`
(`determine_c_substate`, `determine_state_from_ledger`, `roster_scan`,
`append_ledger`, `ledger_tail_value`, the ledger grammar in the script header),
and the append sites in `.claude/workflow/{track-review,track-code-review,
step-implementation,step-implementation-recovery,workflow}.md`. This is a
workflow-modifying / §1.7-staged change. No Java symbols — use grep/Read, not
PSI-for-Java.
