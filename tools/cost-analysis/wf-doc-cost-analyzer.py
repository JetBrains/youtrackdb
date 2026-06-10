#!/usr/bin/env python3
"""Quantify the $ cost of workflow documents living in the cached prefix.

Per transcript (orchestrator AND each sub-agent), walk turns chronologically and
attribute cost to workflow-doc bytes resident in the prefix:
  - initial cache-write  (Σ new_wf × write_5m)            — load it once
  - warm re-reads        (Σ resident × retained_frac × read) — the re-read tail
  - cold re-writes        (Σ resident × rewrite_frac × write) — TTL-expiry penalty

Continuous cold model (no binary threshold): at each turn the re-written fraction
of the prefix is rewrite_frac = max(0, write - prefix_growth) / prev_prefix, which
is ~0 on a clean warm turn, ~1 on a full TTL expiry, and in-between on a partial
expiry (floor retained, old content above it re-cached). Resident docs pay the
write rate on the rewritten fraction and the read rate on the retained fraction.

Buckets: process (.claude/{workflow,agents,skills,docs,output-styles}),
artifact (/docs/adr/**), and (secondary) Task-embedded prompt text.

Sizing: len(content)/4. tiktoken cl100k measures real-token/char4 = 0.98 across
41 workflow docs; Claude's tokenizer runs ~10% higher, so DOC_FACTOR = 1.08
(band 0.98-1.20) converts char/4 -> real tokens. Both raw and calibrated reported.
"""
import json, sys, glob, os, importlib.util, pathlib, collections
from datetime import datetime

# session-stats.py lives at <repo-root>/.claude/scripts/; this file is at
# <repo-root>/tools/cost-analysis/. Resolve relative to __file__ so the script
# is portable across worktrees; fall back to the develop checkout if absent.
_ss = pathlib.Path(__file__).resolve().parents[2] / ".claude" / "scripts" / "session-stats.py"
if not _ss.exists():
    _ss = pathlib.Path("/home/andrii0lomakin/Projects/ytdb/develop/.claude/scripts/session-stats.py")
spec = importlib.util.spec_from_file_location("ss", str(_ss))
ss = importlib.util.module_from_spec(spec); spec.loader.exec_module(ss)

WRITE = 6.25 / 1_000_000   # Opus 4.8 cache write_5m $/token
READ  = 0.50 / 1_000_000   # Opus 4.8 cache read   $/token
DOC_FACTOR = 1.08          # char/4 -> real tokens (tiktoken-anchored); band 0.98-1.20
DOC_FACTOR_LO, DOC_FACTOR_HI = 0.98, 1.20

PROCESS_SUBSTR = ("/.claude/workflow/", "/.claude/agents/", "/.claude/skills/",
                  "/.claude/docs/", "/.claude/output-styles/")
ARTIFACT_SUBSTR = ("/docs/adr/",)

def classify_path(fp):
    if any(s in fp for s in PROCESS_SUBSTR):
        return "process"
    if any(s in fp for s in ARTIFACT_SUBSTR):
        return "artifact"
    return None

def content_len(cont):
    if isinstance(cont, str):
        return len(cont)
    if isinstance(cont, list):
        return sum(len(x.get("text", "")) for x in cont if isinstance(x, dict))
    return 0

def doc_key(fp):
    for anchor in ("/.claude/", "/docs/"):
        i = fp.find(anchor)
        if i >= 0:
            return fp[i + 1:]
    return os.path.basename(fp)

def parse_ts(ts):
    if not ts:
        return None
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00"))
    except Exception:
        return None

def analyze_transcript(path):
    with open(path) as fh:
        lines = [json.loads(l) for l in fh if l.strip()]

    read_id_to_path = {}
    pending = {"process": 0.0, "artifact": 0.0, "task": 0.0}
    pending_files = collections.defaultdict(float)
    resident = {"process": 0.0, "artifact": 0.0, "task": 0.0}
    resident_files = collections.defaultdict(float)
    cost = {b: {"initial": 0.0, "read": 0.0, "cold": 0.0} for b in ("process", "artifact", "task")}
    per_file_cost = collections.defaultdict(float)
    per_file_initial = collections.defaultdict(float)
    per_file_loadtok = collections.defaultdict(float)

    seen_turns = set()
    prev_prefix = None
    peak_prefix = 0
    n_turns = 0
    n_cold_binary = 0       # legacy read<5000 & write>50000 count (for comparability)
    n_rewrite_events = 0    # rewrite_frac > 0.25 (partial or full expiry)
    sum_rewrite_frac = 0.0
    compaction = False
    last_ts = None
    cold_after_gap = 0

    def flush_turn(usage, ts):
        nonlocal prev_prefix, peak_prefix, n_turns, n_cold_binary, n_rewrite_events
        nonlocal sum_rewrite_frac, compaction, last_ts, cold_after_gap
        cache_read = usage.get("cache_read_input_tokens") or 0
        cc = usage.get("cache_creation") or {}
        w5 = cc.get("ephemeral_5m_input_tokens") or 0
        w1 = cc.get("ephemeral_1h_input_tokens") or 0
        if not (w5 or w1):
            w5 = usage.get("cache_creation_input_tokens") or 0
        in_t = usage.get("input_tokens") or 0
        total_write = w5 + w1
        real_prefix = cache_read + total_write + in_t

        # rewrite fraction of the prefix (continuous cold model)
        if prev_prefix is None or prev_prefix <= 0:
            rewrite_frac = 0.0  # first turn: everything is initial, nothing resident yet
        else:
            growth = max(0.0, real_prefix - prev_prefix)
            rewrite_old = max(0.0, total_write - growth)
            rewrite_frac = min(1.0, rewrite_old / prev_prefix)
        retained_frac = 1.0 - rewrite_frac

        # diagnostics
        if cache_read < 5000 and total_write > 50000:
            n_cold_binary += 1
        if rewrite_frac > 0.25:
            n_rewrite_events += 1
        sum_rewrite_frac += rewrite_frac
        # compaction: prefix drops >15% below running peak (true eviction, not TTL)
        if peak_prefix > 80000 and real_prefix < 0.85 * peak_prefix:
            compaction = True
        peak_prefix = max(peak_prefix, real_prefix)
        gap = None
        a, b = parse_ts(last_ts), parse_ts(ts)
        if a and b:
            gap = (b - a).total_seconds()
        if rewrite_frac > 0.5 and gap and gap > 300:
            cold_after_gap += 1
        n_turns += 1

        # attribute cost: new docs -> initial write; resident -> split read/rewrite
        for bk in ("process", "artifact", "task"):
            rb = resident[bk]
            nw = pending[bk]
            cost[bk]["initial"] += nw * WRITE
            cost[bk]["read"] += rb * retained_frac * READ
            cost[bk]["cold"] += rb * rewrite_frac * WRITE
        for f, tok in list(resident_files.items()):
            per_file_cost[f] += tok * (retained_frac * READ + rewrite_frac * WRITE)
        for f, tok in list(pending_files.items()):
            per_file_initial[f] += tok * WRITE
            per_file_loadtok[f] += tok
        # promote pending -> resident
        for bk in ("process", "artifact", "task"):
            resident[bk] += pending[bk]; pending[bk] = 0.0
        for f, tok in list(pending_files.items()):
            resident_files[f] += tok
        pending_files.clear()
        prev_prefix = real_prefix
        last_ts = ts or last_ts

    for o in lines:
        t = o.get("type")
        if t == "assistant":
            msg = o.get("message") or {}
            content = msg.get("content") or []
            usage = msg.get("usage") or {}
            for b in content:
                if not isinstance(b, dict):
                    continue
                bt = b.get("type")
                if bt == "tool_use":
                    name = b.get("name")
                    inp = b.get("input") or {}
                    if name == "Read":
                        read_id_to_path[b.get("id")] = inp.get("file_path", "")
                    if name in ("Task", "Agent"):
                        pending["task"] += (len(inp.get("prompt", "")) + len(inp.get("description", ""))) / 4
            mid = msg.get("id"); rid = o.get("requestId")
            key = (mid, rid)
            if usage and mid and rid and key not in seen_turns:
                seen_turns.add(key)
                flush_turn(usage, o.get("timestamp"))
        elif t == "user":
            msg = o.get("message") or {}
            content = msg.get("content")
            if isinstance(content, list):
                for b in content:
                    if isinstance(b, dict) and b.get("type") == "tool_result":
                        tuid = b.get("tool_use_id")
                        fp = read_id_to_path.get(tuid)
                        if fp:
                            bucket = classify_path(fp)
                            if bucket:
                                tok = content_len(b.get("content")) / 4
                                pending[bucket] += tok
                                pending_files[doc_key(fp)] += tok

    per_file = {}
    for f in set(list(per_file_cost) + list(per_file_initial)):
        per_file[f] = {"reread_cold": per_file_cost[f], "initial": per_file_initial[f],
                       "total": per_file_cost[f] + per_file_initial[f],
                       "loadtok": per_file_loadtok[f]}
    return {
        "path": os.path.basename(path),
        "n_turns": n_turns, "n_cold_binary": n_cold_binary,
        "n_rewrite_events": n_rewrite_events,
        "avg_rewrite_frac": (sum_rewrite_frac / n_turns) if n_turns else 0,
        "cold_after_gap": cold_after_gap,
        "cost": cost, "resident_final": dict(resident),
        "compaction": compaction, "per_file": per_file,
    }

def analyze_session(orch_path):
    files = [orch_path]
    stem = os.path.splitext(orch_path)[0]
    sub_dir = os.path.join(stem, "subagents")
    subs = sorted(glob.glob(os.path.join(sub_dir, "agent-*.jsonl"))) if os.path.isdir(sub_dir) else []
    files += subs
    agg = {b: {"initial": 0.0, "read": 0.0, "cold": 0.0} for b in ("process", "artifact", "task")}
    orch_agg = {b: {"initial": 0.0, "read": 0.0, "cold": 0.0} for b in ("process", "artifact", "task")}
    per_file_tot = collections.defaultdict(float)
    per_file_load = collections.defaultdict(float)
    any_compaction = False
    diags = []
    for fp in files:
        r = analyze_transcript(fp)
        for b in agg:
            for k in agg[b]:
                agg[b][k] += r["cost"][b][k]
                if fp == orch_path:
                    orch_agg[b][k] += r["cost"][b][k]
        for f, d in r["per_file"].items():
            per_file_tot[f] += d["total"]; per_file_load[f] += d["loadtok"]
        any_compaction = any_compaction or r["compaction"]
        diags.append(r)
    sess = ss.session_totals(orch_path)
    orch = ss.aggregate_file(__import__("pathlib").Path(orch_path))
    orch_cost = ss._sum_records(orch)["cost"]
    return {"agg": agg, "orch_agg": orch_agg, "per_file_tot": dict(per_file_tot),
            "per_file_load": dict(per_file_load), "compaction": any_compaction,
            "sess_cost": sess["cost"], "orch_cost": orch_cost,
            "n_files": len(files), "diags": diags}

def docs_raw(agg):
    return sum(agg[b][k] for b in ("process", "artifact") for k in agg[b])

PHASE = {
    "7349adfa": "create-plan (P0/1)", "60c8ca29": "create-plan (P0/1)",
    "d6fb4ed8": "exec-tracks B+C", "2294a479": "exec-tracks B+C",
    "5a35307e": "exec-tracks B+C", "c65d3661": "exec-tracks B+C",
    "46764d14": "exec-tracks impl", "86b0deec": "exec-tracks impl",
    "48f12216": "exec-tracks", "eb3a9289": "exec-tracks",
    "779b4af1": "migrate-workflow", "5640d186": "migrate-workflow",
}

if __name__ == "__main__":
    mode = "--summary" if "--summary" in sys.argv else "--detail"
    # Default to the open-speedup 12-session study set when no sids are passed,
    # matching the sibling analyzers (avoids a divide-by-zero on a bare run).
    _STUDY = "/home/coder/.claude/projects/-home-andrii0lomakin-Projects-ytdb-open-speedup"
    _DEFAULT = ["d6fb4ed8-e29c-4ab6-a046-aa0a72736ca7", "2294a479-6125-4811-8a1d-91bda3fad3e8",
        "5a35307e-9757-40b0-b180-8a86e2086db0", "c65d3661-d17f-4ccd-a344-00ae878b8cc1",
        "46764d14-5dbb-4c34-8aac-ad5c5eea993a", "86b0deec-8420-49db-93e9-3f5aebf3e7f4",
        "48f12216-f339-45e7-bea9-fb545eaaf48c", "eb3a9289-651e-4143-a128-407d4671f762",
        "7349adfa-5d47-4717-a5fc-c8d0797b9353", "60c8ca29-4869-4ffc-aeb8-22b4e007ca90",
        "779b4af1-1222-4829-9311-c09c9d343d91", "5640d186-3a7b-4814-9229-41b0e03d97d5"]
    targets = [a for a in sys.argv[1:] if not a.startswith("--")] or _DEFAULT

    def resolve_base(sids):
        """Transcript dir: WF_PROJECT_DIR wins; else the study dir when it holds the
        requested sessions; else the current project's own dir (cwd, Claude-encoded)."""
        env = os.environ.get("WF_PROJECT_DIR")
        if env: return env
        if all(os.path.exists(os.path.join(_STUDY, s + ".jsonl")) for s in sids):
            return _STUDY
        return os.path.expanduser("~/.claude/projects/" + os.getcwd().replace("/", "-").replace(".", "-"))

    BASE = resolve_base([s for s in targets if not s.endswith(".jsonl")])
    rows = []
    for sid in targets:
        path = os.path.join(BASE, sid + ".jsonl") if not sid.endswith(".jsonl") else sid
        res = analyze_session(path)
        agg = res["agg"]; draw = docs_raw(agg)
        dcal = draw * DOC_FACTOR
        sc = res["sess_cost"]
        task_raw = sum(agg["task"].values())
        proc = sum(agg["process"].values()); art = sum(agg["artifact"].values())
        # split components across proc+art
        comp = {k: agg["process"][k] + agg["artifact"][k] for k in ("initial", "read", "cold")}
        rows.append((sid[:8], PHASE.get(sid[:8], "?"), sc, proc, art, comp, dcal,
                     100 * dcal / sc, task_raw * DOC_FACTOR, res))
        if mode == "--detail":
            print("=" * 74)
            print(f"SESSION {sid[:8]} [{PHASE.get(sid[:8],'?')}]  files={res['n_files']}  "
                  f"session=${sc:.2f}  orch=${res['orch_cost']:.2f}")
            print(f"  compaction={res['compaction']}")
            for b in ("process", "artifact"):
                tt = sum(agg[b].values())
                print(f"  {b:8s} raw=${tt:6.3f} (init ${agg[b]['initial']:.3f} / read ${agg[b]['read']:.3f} / cold ${agg[b]['cold']:.3f})")
            print(f"  DOCS raw=${draw:.3f}  calib(x{DOC_FACTOR})=${dcal:.3f}  "
                  f"= {100*draw/sc:.1f}%raw / {100*dcal/sc:.1f}%calib  "
                  f"band[{100*draw*DOC_FACTOR_LO/sc:.1f}-{100*draw*DOC_FACTOR_HI/sc:.1f}%]")
            print(f"  components: init ${comp['initial']*DOC_FACTOR:.3f} / "
                  f"reread ${comp['read']*DOC_FACTOR:.3f} / cold ${comp['cold']*DOC_FACTOR:.3f} (calib)")
            print(f"  task-embedded calib=${task_raw*DOC_FACTOR:.3f} ({100*task_raw*DOC_FACTOR/sc:.1f}%)")
            top = sorted(res["per_file_tot"].items(), key=lambda kv: -kv[1])[:8]
            for f, c in top:
                print(f"     ${c*DOC_FACTOR:6.3f}  load={res['per_file_load'][f]/1000:5.1f}Ktok  {f}")
    if mode == "--summary":
        print(f"{'session':9} {'phase':18} {'$sess':>7} {'proc$':>6} {'art$':>6} "
              f"{'init':>5} {'reread':>6} {'cold':>5} {'docs$':>6} {'doc%':>5} {'task$':>6} cmp")
        for (sid, ph, sc, proc, art, comp, dcal, pct, taskcal, res) in rows:
            print(f"{sid:9} {ph:18} {sc:7.2f} {proc*DOC_FACTOR:6.2f} {art*DOC_FACTOR:6.2f} "
                  f"{comp['initial']*DOC_FACTOR:5.2f} {comp['read']*DOC_FACTOR:6.2f} {comp['cold']*DOC_FACTOR:5.2f} "
                  f"{dcal:6.2f} {pct:4.1f}% {taskcal:6.2f} {'!' if res['compaction'] else ''}")
        # phase rollup
        print("\n--- phase rollup (calibrated $, doc% = docs/session) ---")
        byp = collections.defaultdict(lambda: [0.0, 0.0, 0.0])  # [docs$, sess$, task$]
        for (sid, ph, sc, proc, art, comp, dcal, pct, taskcal, res) in rows:
            byp[ph][0] += dcal; byp[ph][1] += sc; byp[ph][2] += taskcal
        for ph, (d, s, tk) in sorted(byp.items(), key=lambda kv: -kv[1][1]):
            print(f"  {ph:18} docs=${d:6.2f}  sess=${s:7.2f}  doc%={100*d/s:4.1f}%  task=${tk:.2f}")
        td = sum(v[0] for v in byp.values()); tsv = sum(v[1] for v in byp.values()); tt = sum(v[2] for v in byp.values())
        print(f"  {'ALL':18} docs=${td:6.2f}  sess=${tsv:7.2f}  doc%={100*td/tsv:4.1f}%  task=${tt:.2f}")
