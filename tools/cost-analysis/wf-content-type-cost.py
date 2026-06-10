#!/usr/bin/env python3
"""Orchestrator cost by CONTENT TYPE with write/read/output split, per phase.

Each turn's REAL cache-write, cache-read, and output cost is attributed to a
content type so the modeled total equals the true bill. The "model reasoning"
type folds together the model's generated content: visible reply text, tool-call
JSON inputs, AND retained extended thinking (estimated as cumulative prior-turn
output tokens — validated: the prefix gap tracks cumulative output within a few %).
Its OUTPUT column is the generation cost; its write/read columns are the cost of
re-reading that retained content on later turns.

Buckets: FLOOR (fixed system prefix), model_gen (thinking+text+tool calls),
wf_proc / wf_art (workflow docs), subagent (sub-agent outputs returned),
task (Task prompts), tool_out (Bash/grep), code (source reads),
RESIDUAL (untranscribed remainder — mostly the ~1.5x tool-output undercount).

Sizing char/4 x 1.12 (cl100k-anchored). Opus 4.8: read 0.50, write-5m 6.25,
output 25 /MTok. Override transcript dir with WF_PROJECT_DIR; pass session-id
stems as args (default: open-speedup 12-session set).
"""
import json, os, collections, sys

WRITE = 6.25 / 1e6; READ = 0.50 / 1e6; OUT = 25.0 / 1e6; INr = 5.0 / 1e6
F = 1.12
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
    """Return {bucket: {'w':$, 'r':$, 'o':$}} + input$ for one orchestrator transcript."""
    with open(path, encoding="utf-8") as fh:
        lines = [json.loads(l) for l in fh if l.strip()]
    read_id = {}; task_ids = set()
    resident = collections.defaultdict(float); pending = collections.defaultdict(float); seen = set()
    W = collections.defaultdict(float); R = collections.defaultdict(float); O = collections.defaultdict(float); I = 0.0
    floor = None; cum_out = 0.0
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
                        pending["model_gen"] += len(json.dumps(inp)) / 4
                elif bt in ("text", "thinking"):
                    pending["model_gen"] += len(b.get(bt, "")) / 4
            mid = m.get("id"); rid = o.get("requestId")
            if usage and mid and rid and (mid, rid) not in seen:
                seen.add((mid, rid))
                cr = usage.get("cache_read_input_tokens") or 0
                cc = usage.get("cache_creation") or {}
                w = (cc.get("ephemeral_5m_input_tokens") or usage.get("cache_creation_input_tokens") or 0) + (cc.get("ephemeral_1h_input_tokens") or 0)
                intok = usage.get("input_tokens") or 0; outtok = usage.get("output_tokens") or 0
                real = cr + w + intok
                for bk, v in list(pending.items()): resident[bk] += v
                pending.clear()
                comp = {bk: v * F for bk, v in resident.items()}
                comp["model_gen"] = comp.get("model_gen", 0) + cum_out   # retained thinking
                if floor is None: floor = max(1.0, real - sum(comp.values()))
                comp["FLOOR"] = floor
                comp["RESIDUAL"] = max(0.0, real - floor - sum(v for k, v in comp.items() if k != "FLOOR"))
                ssum = sum(comp.values()); scale = real / ssum if ssum > real else 1.0
                for bk, v in comp.items():
                    if real:
                        R[bk] += cr * (v * scale / real) * READ
                        W[bk] += w * (v * scale / real) * WRITE
                O["model_gen"] += outtok * OUT
                I += intok * INr
                cum_out += outtok
        elif t == "user":
            c = o.get("message", {}).get("content")
            if isinstance(c, list):
                for b in c:
                    if isinstance(b, dict) and b.get("type") == "tool_result":
                        tuid = b.get("tool_use_id"); L = clen(b.get("content")) / 4
                        if tuid in task_ids: pending["subagent"] += L
                        elif tuid in read_id: pending[wfb(read_id[tuid])] += L
                        else: pending["tool_out"] += L
    return W, R, O, I

PHASE = {
    "d6fb4ed8": "exec-tracks B+C", "2294a479": "exec-tracks B+C", "5a35307e": "exec-tracks B+C", "c65d3661": "exec-tracks B+C",
    "46764d14": "exec-tracks impl", "86b0deec": "exec-tracks impl", "48f12216": "exec-tracks small", "eb3a9289": "exec-tracks small",
    "7349adfa": "create-plan P0/1", "60c8ca29": "create-plan P0/1", "779b4af1": "migrate-workflow", "5640d186": "migrate-workflow"}
LAB = {"FLOOR": "Fixed floor (sys+tools+CLAUDE+MEM+skills)", "model_gen": "Model reasoning+reply (thinking+text+tools)",
       "wf_proc": "Workflow process docs (.claude/**)", "wf_art": "Workflow artifact docs (docs/adr/**)",
       "subagent": "Sub-agent outputs (returned)", "task": "Task prompts (orch->sub-agent)",
       "tool_out": "Tool output (Bash/grep)", "code": "Source-code reads", "RESIDUAL": "Untranscribed residual (~1.5x tool undercount)"}
ORDER = ["FLOOR", "model_gen", "wf_proc", "wf_art", "subagent", "task", "tool_out", "code", "RESIDUAL"]

_STUDY = "/home/coder/.claude/projects/-home-andrii0lomakin-Projects-ytdb-open-speedup"
_DEFAULT = ["d6fb4ed8-e29c-4ab6-a046-aa0a72736ca7", "2294a479-6125-4811-8a1d-91bda3fad3e8", "5a35307e-9757-40b0-b180-8a86e2086db0",
    "c65d3661-d17f-4ccd-a344-00ae878b8cc1", "46764d14-5dbb-4c34-8aac-ad5c5eea993a", "86b0deec-8420-49db-93e9-3f5aebf3e7f4",
    "48f12216-f339-45e7-bea9-fb545eaaf48c", "eb3a9289-651e-4143-a128-407d4671f762", "7349adfa-5d47-4717-a5fc-c8d0797b9353",
    "60c8ca29-4869-4ffc-aeb8-22b4e007ca90", "779b4af1-1222-4829-9311-c09c9d343d91", "5640d186-3a7b-4814-9229-41b0e03d97d5"]
SIDS = [a for a in sys.argv[1:] if not a.startswith("--")] or _DEFAULT


def resolve_base(sids):
    """Transcript dir: WF_PROJECT_DIR wins; else the study dir when it holds the
    requested sessions; else the current project's own dir (cwd, Claude-encoded)."""
    env = os.environ.get("WF_PROJECT_DIR")
    if env: return env
    if all(os.path.exists(os.path.join(_STUDY, s + ".jsonl")) for s in sids):
        return _STUDY
    return os.path.expanduser("~/.claude/projects/" + os.getcwd().replace("/", "-").replace(".", "-"))


BASE = resolve_base(SIDS)

# per-phase accumulation: phase -> bucket -> {w,r,o}; plus input
ph_W = collections.defaultdict(lambda: collections.defaultdict(float))
ph_R = collections.defaultdict(lambda: collections.defaultdict(float))
ph_O = collections.defaultdict(lambda: collections.defaultdict(float))
ph_I = collections.defaultdict(float)
for sid in SIDS:
    W, R, O, I = decompose(os.path.join(BASE, sid + ".jsonl"))
    ph = PHASE.get(sid[:8], "other")
    for bk in set(list(W) + list(R) + list(O)):
        ph_W[ph][bk] += W.get(bk, 0); ph_R[ph][bk] += R.get(bk, 0); ph_O[ph][bk] += O.get(bk, 0)
    ph_I[ph] += I

def print_table(title, W, R, O, I):
    grand = sum(W.values()) + sum(R.values()) + sum(O.values()) + I
    if grand <= 0: return
    print(f"\n=== {title} — total ${grand:.2f} ===")
    print(f"  {'content type':44}{'write$':>8}{'read$':>8}{'out$':>7}{'total$':>8}{'%':>5}")
    print("  " + "-" * 80)
    for bk in ORDER:
        tt = W.get(bk, 0) + R.get(bk, 0) + O.get(bk, 0)
        if tt == 0: continue
        print(f"  {LAB[bk]:44}{W.get(bk,0):8.2f}{R.get(bk,0):8.2f}{O.get(bk,0):7.2f}{tt:8.2f}{100*tt/grand:4.0f}%")
    print(f"  {'Uncached input':44}{'':8}{'':8}{'':7}{I:8.2f}{100*I/grand:4.0f}%")
    tw, tr, to = sum(W.values()), sum(R.values()), sum(O.values())
    print(f"  {'TOTAL':44}{tw:8.2f}{tr:8.2f}{to:7.2f}{grand:8.2f} 100%")

_PREF = ["exec-tracks B+C", "exec-tracks impl", "exec-tracks small", "create-plan P0/1", "migrate-workflow"]
phases = [p for p in _PREF if p in ph_W] + [p for p in ph_W if p not in _PREF]
for ph in phases:
    print_table(ph, ph_W[ph], ph_R[ph], ph_O[ph], ph_I[ph])
# grand aggregate
aW = collections.defaultdict(float); aR = collections.defaultdict(float); aO = collections.defaultdict(float); aI = 0.0
for ph in ph_W:
    for bk in ph_W[ph]: aW[bk] += ph_W[ph][bk]
    for bk in ph_R[ph]: aR[bk] += ph_R[ph][bk]
    for bk in ph_O[ph]: aO[bk] += ph_O[ph][bk]
    aI += ph_I[ph]
print_table("ALL (12)", aW, aR, aO, aI)
