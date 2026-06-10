#!/usr/bin/env python3
"""Orchestrator cost distribution across all 12 open-speedup sessions, per phase.

Distributes each turn's REAL cache-read / cache-write / output cost across resident
prefix buckets by their share of the real prefix, so each session's modeled total
equals its true bill. Buckets:
  FLOOR     cold-start fixed prefix (system prompt + tool schemas + CLAUDE.md + MEMORY + skills)
  INJECTED  harness/session context re-sent per turn but not itemized in the transcript
  wf_proc   workflow process docs (.claude/**)
  wf_art    workflow artifact docs (docs/adr/**)
  subagent  sub-agent outputs returned to the orchestrator
  task      Task prompts (orchestrator -> sub-agent)
  orch_gen  orchestrator's own reasoning + text (resident)
  tool_out  Bash/grep/etc tool output
  code      source-code reads
  OUTPUT    generation (only non-resident bucket)
"""
import json, os, importlib.util, pathlib, collections, sys
# session-stats.py lives at <repo-root>/.claude/scripts/; this file is at
# <repo-root>/tools/cost-analysis/. Resolve relative to __file__ so the script
# is portable across worktrees; fall back to the develop checkout if absent.
_here = pathlib.Path(__file__).resolve()
_ss = _here.parents[2] / ".claude" / "scripts" / "session-stats.py"
if not _ss.exists():
    _ss = pathlib.Path("/home/andrii0lomakin/Projects/ytdb/develop/.claude/scripts/session-stats.py")
spec = importlib.util.spec_from_file_location("ss", str(_ss))
ss = importlib.util.module_from_spec(spec); spec.loader.exec_module(ss)

WRITE = 6.25 / 1e6; READ = 0.50 / 1e6; OUT = 25.0 / 1e6; IN = 5.0 / 1e6
F = 1.12  # char/4 -> real Claude tokens (cl100k measured 1.03 on this content; +~9% for Claude)
PROC = ("/.claude/workflow/", "/.claude/agents/", "/.claude/skills/", "/.claude/docs/", "/.claude/output-styles/")
ART = ("/docs/adr/",)

def wfb(fp):
    if any(s in fp for s in PROC): return "wf_proc"
    if any(s in fp for s in ART): return "wf_art"
    return "code"

def clen(c):
    if isinstance(c, str): return len(c)
    if isinstance(c, list): return sum(len(x.get("text", "")) for x in c if isinstance(x, dict))
    return 0

def decompose(path):
    lines = [json.loads(l) for l in open(path) if l.strip()]
    read_id = {}; task_ids = set()
    resident = collections.defaultdict(float); pending = collections.defaultdict(float)
    seen = set(); rcost = collections.defaultdict(float); wcost = collections.defaultdict(float)
    out_cost = 0.0; in_cost = 0.0; floor = None
    for o in lines:
        t = o.get("type")
        if t == "assistant":
            m = o.get("message") or {}; content = m.get("content") or []; usage = m.get("usage") or {}
            for b in content:
                if not isinstance(b, dict): continue
                bt = b.get("type")
                if bt == "tool_use":
                    nm = b.get("name"); inp = b.get("input") or {}
                    if nm == "Read": read_id[b.get("id")] = inp.get("file_path", "")
                    if nm in ("Task", "Agent"):
                        task_ids.add(b.get("id"))
                        pending["task"] += (len(inp.get("prompt", "")) + len(inp.get("description", ""))) / 4
                    else:
                        pending["orch_gen"] += len(json.dumps(inp)) / 4
                elif bt in ("text", "thinking"):
                    pending["orch_gen"] += len(b.get(bt, "")) / 4
            mid = m.get("id"); rid = o.get("requestId")
            if usage and mid and rid and (mid, rid) not in seen:
                seen.add((mid, rid))
                cr = usage.get("cache_read_input_tokens") or 0
                cc = usage.get("cache_creation") or {}
                w = (cc.get("ephemeral_5m_input_tokens") or usage.get("cache_creation_input_tokens") or 0) + (cc.get("ephemeral_1h_input_tokens") or 0)
                intok = usage.get("input_tokens") or 0; outtok = usage.get("output_tokens") or 0
                real = cr + w + intok
                for bk, tok in list(pending.items()): resident[bk] += tok
                pending.clear()
                vis = {bk: tok * F for bk, tok in resident.items()}
                if floor is None: floor = max(1.0, real - sum(vis.values()))
                resid = max(0.0, real - floor - sum(vis.values()))
                comp = {"FLOOR": floor, "INJECTED": resid}; comp.update(vis)
                denom = sum(comp.values()) or 1
                for bk, tok in comp.items():
                    sh = tok / denom; rcost[bk] += cr * sh * READ; wcost[bk] += w * sh * WRITE
                out_cost += outtok * OUT; in_cost += intok * IN
        elif t == "user":
            c = o.get("message", {}).get("content")
            if isinstance(c, list):
                for b in c:
                    if isinstance(b, dict) and b.get("type") == "tool_result":
                        tuid = b.get("tool_use_id"); L = clen(b.get("content")) / 4
                        if tuid in task_ids: pending["subagent"] += L
                        elif tuid in read_id: pending[wfb(read_id[tuid])] += L
                        else: pending["tool_out"] += L
    total = {bk: rcost.get(bk, 0) + wcost.get(bk, 0) for bk in set(list(rcost) + list(wcost))}
    total["OUTPUT"] = out_cost; total["INPUT"] = in_cost
    return total

PHASE = {
    "d6fb4ed8": "exec-tracks B+C", "2294a479": "exec-tracks B+C",
    "5a35307e": "exec-tracks B+C", "c65d3661": "exec-tracks B+C",
    "46764d14": "exec-tracks impl", "86b0deec": "exec-tracks impl",
    "48f12216": "exec-tracks small", "eb3a9289": "exec-tracks small",
    "7349adfa": "create-plan P0/1", "60c8ca29": "create-plan P0/1",
    "779b4af1": "migrate-workflow", "5640d186": "migrate-workflow",
}
ORDER = ["FLOOR", "INJECTED", "OUTPUT", "wf_proc", "wf_art", "subagent", "task", "orch_gen", "tool_out", "code", "INPUT"]
LABEL = {"FLOOR": "floor", "INJECTED": "injected", "OUTPUT": "output", "wf_proc": "wf-proc",
         "wf_art": "wf-art", "subagent": "subagt-out", "task": "task-prompt", "orch_gen": "orch-gen",
         "tool_out": "tool-out", "code": "code-read", "INPUT": "input"}

# Default study set (open-speedup). Override by passing session-id stems as args;
# any 8-char prefix not in PHASE rolls up under "other".
_STUDY = "/home/coder/.claude/projects/-home-andrii0lomakin-Projects-ytdb-open-speedup"
_DEFAULT_SIDS = ["d6fb4ed8-e29c-4ab6-a046-aa0a72736ca7", "2294a479-6125-4811-8a1d-91bda3fad3e8",
        "5a35307e-9757-40b0-b180-8a86e2086db0", "c65d3661-d17f-4ccd-a344-00ae878b8cc1",
        "46764d14-5dbb-4c34-8aac-ad5c5eea993a", "86b0deec-8420-49db-93e9-3f5aebf3e7f4",
        "48f12216-f339-45e7-bea9-fb545eaaf48c", "eb3a9289-651e-4143-a128-407d4671f762",
        "7349adfa-5d47-4717-a5fc-c8d0797b9353", "60c8ca29-4869-4ffc-aeb8-22b4e007ca90",
        "779b4af1-1222-4829-9311-c09c9d343d91", "5640d186-3a7b-4814-9229-41b0e03d97d5"]
SIDS = [a for a in sys.argv[1:] if not a.startswith("--")] or _DEFAULT_SIDS


def resolve_base(sids):
    """Transcript dir: WF_PROJECT_DIR wins; else the study dir when it holds the
    requested sessions; else the current project's own dir (cwd, Claude-encoded)."""
    env = os.environ.get("WF_PROJECT_DIR")
    if env: return env
    if all(os.path.exists(os.path.join(_STUDY, s + ".jsonl")) for s in sids):
        return _STUDY
    return os.path.expanduser("~/.claude/projects/" + os.getcwd().replace("/", "-").replace(".", "-"))


BASE = resolve_base(SIDS)

by_phase = collections.defaultdict(lambda: collections.defaultdict(float))
phase_total = collections.defaultdict(float)
all_tot = collections.defaultdict(float); grand = 0.0
per_sess = []
for sid in SIDS:
    tot = decompose(os.path.join(BASE, sid + ".jsonl"))
    s = sum(tot.values()); ph = PHASE.get(sid[:8], "other")
    per_sess.append((sid[:8], ph, s, tot))
    for bk, v in tot.items():
        by_phase[ph][bk] += v; all_tot[bk] += v
    phase_total[ph] += s; grand += s

def pct_row(label, tot, denom):
    cells = "".join(f"{100*tot.get(bk,0)/denom:5.0f}" for bk in ORDER)
    return f"  {label:20}{cells}   ${denom:6.2f}"

hdr = "".join(f"{LABEL[bk]:>5}" for bk in ORDER)
print("PER-PHASE ORCHESTRATOR COST DISTRIBUTION (% of orchestrator $; orch-only, excludes sub-agent transcripts)")
print(f"  {'phase':20}{hdr}   {'orch$':>7}")
print("  " + "-" * (20 + 5 * len(ORDER) + 10))
_PREFERRED = ["exec-tracks B+C", "exec-tracks impl", "exec-tracks small", "create-plan P0/1", "migrate-workflow"]
_phases = [p for p in _PREFERRED if p in by_phase] + [p for p in by_phase if p not in _PREFERRED]
for ph in _phases:
    print(pct_row(ph, by_phase[ph], phase_total[ph]))
print("  " + "-" * (20 + 5 * len(ORDER) + 10))
print(pct_row("ALL (12 orch)", all_tot, grand))
sysohead = 100 * (all_tot["FLOOR"] + all_tot["INJECTED"]) / grand
docs = 100 * (all_tot["wf_proc"] + all_tot["wf_art"]) / grand
deleg = 100 * (all_tot["subagent"] + all_tot["task"]) / grand
print(f"\n  rollup: system/harness overhead (floor+injected) {sysohead:.0f}%  |  workflow docs {docs:.0f}%  "
      f"|  output {100*all_tot['OUTPUT']/grand:.0f}%  |  delegation (subagt-out+task) {deleg:.0f}%")
print("\nPER-SESSION orchestrator $:")
for sid, ph, s, tot in per_sess:
    print(f"  {sid}  {ph:18} ${s:6.2f}   floor+inj {100*(tot['FLOOR']+tot['INJECTED'])/s:3.0f}%  "
          f"docs {100*(tot['wf_proc']+tot['wf_art'])/s:3.0f}%  out {100*tot['OUTPUT']/s:3.0f}%")
