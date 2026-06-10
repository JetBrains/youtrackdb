#!/usr/bin/env python3
"""Lever -> cost-coefficient map for the workflow economic model (open Q#6).

Builds on the resolved functional form: per turn the billed read is
cr_k * READ where cr_k is the resident prefix, and resident content of type b
at turn k is res_b,k. Across a session the read bill for bucket b is
  read_b$ = READ * sum_k cr_k * res_b,k / real_k.
Fitting res_b,k = a_b + b_b*k splits that into a BASE part (driven by the
intercept a_b: content resident from turn 1, re-read ~T times) and a GROWTH
part (driven by the slope b_b: content that accumulates, the source of the
quadratic). A lever that trims a base bucket saves ~linearly in T; a lever
that trims a growth bucket saves ~quadratically in T (its ROI rises with
session length). That split is what ranks the levers.

Buckets -> levers:
  FLOOR              floor / tool-schema trim (YTDB-1094)        -> a
  wf_proc + wf_art   per-(role,phase) doc views                  -> a + b
  model_gen          bound/manage retained thinking + output     -> b + output
  subagent + task    sub-agent output routing (YTDB-883)         -> a + b
  (write rewarm)     cold-rewrite re-warm (YTDB-1097)            -> write overhead
  T                  fewer / coarser turns                       -> all terms

Also reports the TTL-rewarm write overhead: total cache_creation beyond the
one-time prefix build (= sum(w_k) - max real prefix), the YTDB-1097-addressable
write. Dedup by (message.id, requestId). Opus 4.8 flat rates. Pure stdlib.
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


def linfit(xs, ys):
    n = len(xs)
    if n < 2: return 0.0, (ys[0] if ys else 0.0), 0.0
    xb = sum(xs) / n; yb = sum(ys) / n
    sxx = sum((x - xb) ** 2 for x in xs)
    sxy = sum((x - xb) * (y - yb) for x, y in zip(xs, ys))
    if sxx == 0: return 0.0, yb, 0.0
    b = sxy / sxx; a = yb - b * xb
    ss_tot = sum((y - yb) ** 2 for y in ys)
    ss_res = sum((y - (a + b * x)) ** 2 for x, y in zip(xs, ys))
    return b, a, (1 - ss_res / ss_tot if ss_tot > 0 else 0.0)


def decompose(path):
    """Per-bucket {read$, write$, out$, a_tok (base), b_tok (growth slope),
    base_read$, growth_read$} + session T, ttl-rewarm write overhead $."""
    lines = [json.loads(l) for l in open(path) if l.strip()]
    read_id = {}; task_ids = set()
    resident = collections.defaultdict(float); pending = collections.defaultdict(float); seen = set()
    floor = None; cum_out = 0.0
    # per-turn records: one composition snapshot per turn (missing bucket => 0 resident)
    snaps = []
    crs = []; reals = []; ws = []
    out_total = 0.0
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
                comp["model_gen"] = comp.get("model_gen", 0) + cum_out  # retained thinking
                if floor is None: floor = max(1.0, real - sum(comp.values()))
                comp["FLOOR"] = floor
                comp["RESIDUAL"] = max(0.0, real - floor - sum(v for k, v in comp.items() if k != "FLOOR"))
                # cap F-inflated buckets to the real prefix so bucket sums equal the
                # true bill (matches wf-content-type-cost.py; scale<1 only on overflow)
                ssum = sum(comp.values()); scale = real / ssum if ssum > real else 1.0
                # snapshot scaled resident tokens by bucket (buckets absent this turn
                # are simply missing => treated as 0 resident, correctly aligned to k)
                snaps.append({bk: v * scale for bk, v in comp.items()})
                crs.append(cr); reals.append(max(1.0, real)); ws.append(w)
                out_total += outtok * OUT
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
    T = len(crs)
    ks = list(range(1, T + 1))
    all_buckets = set().union(*(s.keys() for s in snaps)) if snaps else set()
    # read/write $ per bucket = rate * sum_k flow_k * res_b,k / real_k
    sum_cr_over_real = sum(cr / r for cr, r in zip(crs, reals))            # base weight
    sum_kcr_over_real = sum(k * cr / r for k, cr, r in zip(ks, crs, reals))  # growth weight
    out = {}
    for bk in all_buckets:
        ser = [s.get(bk, 0.0) for s in snaps]  # 0 on turns before the bucket appears
        read_b = READ * sum(cr * res / r for cr, res, r in zip(crs, ser, reals))
        write_b = WRITE * sum(w * res / r for w, res, r in zip(ws, ser, reals))
        b_slope, a_int, r2 = linfit(ks, ser)
        base_read = READ * a_int * sum_cr_over_real
        growth_read = READ * b_slope * sum_kcr_over_real
        out[bk] = {"read": read_b, "write": write_b, "out": 0.0,
                   "a_tok": a_int, "b_tok": b_slope, "r2": r2,
                   "base_read": max(0.0, base_read), "growth_read": max(0.0, growth_read)}
    out.setdefault("model_gen", {"read": 0, "write": 0, "out": 0, "a_tok": 0, "b_tok": 0, "r2": 0, "base_read": 0, "growth_read": 0})
    out["model_gen"]["out"] = out_total
    # TTL-rewarm write overhead: cache_creation beyond the one-time prefix build
    rewarm_overhead = WRITE * max(0.0, sum(ws) - max(reals))
    return out, T, rewarm_overhead


PHASE = {
    "d6fb4ed8": "exec-tracks B+C", "2294a479": "exec-tracks B+C", "5a35307e": "exec-tracks B+C", "c65d3661": "exec-tracks B+C",
    "46764d14": "exec-tracks impl", "86b0deec": "exec-tracks impl", "48f12216": "exec-tracks small", "eb3a9289": "exec-tracks small",
    "7349adfa": "create-plan P0/1", "60c8ca29": "create-plan P0/1", "779b4af1": "migrate-workflow", "5640d186": "migrate-workflow"}
LEVER = {
    "FLOOR": "floor-trim (YTDB-1094)", "wf_proc": "doc-views", "wf_art": "doc-views",
    "model_gen": "bound-thinking + output", "subagent": "subagent-routing (883)",
    "task": "subagent-routing (883)", "tool_out": "(intrinsic: tool output)",
    "code": "(intrinsic: source reads)", "RESIDUAL": "(untranscribed)"}
ORDER = ["FLOOR", "model_gen", "wf_proc", "wf_art", "subagent", "task", "tool_out", "code", "RESIDUAL"]

BASE = os.environ.get("WF_PROJECT_DIR", "/home/coder/.claude/projects/-home-andrii0lomakin-Projects-ytdb-open-speedup")
_DEFAULT = ["d6fb4ed8-e29c-4ab6-a046-aa0a72736ca7", "2294a479-6125-4811-8a1d-91bda3fad3e8", "5a35307e-9757-40b0-b180-8a86e2086db0",
    "c65d3661-d17f-4ccd-a344-00ae878b8cc1", "46764d14-5dbb-4c34-8aac-ad5c5eea993a", "86b0deec-8420-49db-93e9-3f5aebf3e7f4",
    "48f12216-f339-45e7-bea9-fb545eaaf48c", "eb3a9289-651e-4143-a128-407d4671f762", "7349adfa-5d47-4717-a5fc-c8d0797b9353",
    "60c8ca29-4869-4ffc-aeb8-22b4e007ca90", "779b4af1-1222-4829-9311-c09c9d343d91", "5640d186-3a7b-4814-9229-41b0e03d97d5"]
SIDS = [a for a in sys.argv[1:] if not a.startswith("--")] or _DEFAULT

# accumulate per phase
ph = collections.defaultdict(lambda: collections.defaultdict(lambda: collections.defaultdict(float)))
ph_T = collections.defaultdict(list); ph_rewarm = collections.defaultdict(float)
for sid in SIDS:
    d, T, rw = decompose(os.path.join(BASE, sid + ".jsonl"))
    p = PHASE.get(sid[:8], "other")
    ph_T[p].append(T); ph_rewarm[p] += rw
    for bk, v in d.items():
        for kk in ("read", "write", "out", "base_read", "growth_read"):
            ph[p][bk][kk] += v[kk]

_PREF = ["exec-tracks B+C", "exec-tracks impl", "exec-tracks small", "create-plan P0/1", "migrate-workflow"]
phases = [p for p in _PREF if p in ph] + [p for p in ph if p not in _PREF]
for p in phases:
    avgT = sum(ph_T[p]) / len(ph_T[p])
    tot = sum(v["read"] + v["write"] + v["out"] for v in ph[p].values())
    print(f"\n=== {p} — {len(ph_T[p])} sess, avgT {avgT:.0f}, bucket $ {tot:.2f}, ttl-rewarm write ${ph_rewarm[p]:.2f} ===")
    print(f"  {'bucket -> lever':40}{'read$':>7}{'write$':>7}{'out$':>6}{'tot$':>7}{'base$':>7}{'grow$':>7}{'grw%':>5}")
    print("  " + "-" * 86)
    for bk in ORDER:
        if bk not in ph[p]: continue
        v = ph[p][bk]
        ttl = v["read"] + v["write"] + v["out"]
        if ttl < 0.005: continue
        br, gr = v["base_read"], v["growth_read"]
        gpct = 100 * gr / (br + gr) if (br + gr) > 0 else 0
        lab = f"{bk} -> {LEVER.get(bk, '?')}"
        print(f"  {lab:40}{v['read']:>7.2f}{v['write']:>7.2f}{v['out']:>6.2f}{ttl:>7.2f}{br:>7.2f}{gr:>7.2f}{gpct:>4.0f}%")
print("\n  [base$/grow$ = read split by fitted intercept (a, re-read ~T times) vs slope (b, quadratic).")
print("   grw% high => lever ROI rises with session length; grw% low => base, ROI linear in T.]")
print("   [ttl-rewarm write $ = cache_creation beyond the one-time prefix build = YTDB-1097-addressable.]")

# ---- per-lever rollup: $/session by lever, blended growth share ----
LEVER_BUCKETS = {
    "bound-thinking (no issue)": ["model_gen"],
    "floor-trim (YTDB-1094)": ["FLOOR"],
    "doc-views": ["wf_proc", "wf_art"],
    "subagent-routing (YTDB-883)": ["subagent", "task"],
}
print("\n\n=== LEVER ROLLUP ($/session, blended growth share) ===")
print(f"  {'lever':30}", end="")
for p in phases:
    print(f"{p.split()[-1][:7]:>9}", end="")
print()
print("  " + "-" * (30 + 9 * len(phases)))
for lev, bks in LEVER_BUCKETS.items():
    print(f"  {lev:30}", end="")
    for p in phases:
        n = len(ph_T[p])
        tot = sum(ph[p][bk]["read"] + ph[p][bk]["write"] + ph[p][bk]["out"] for bk in bks if bk in ph[p])
        gr = sum(ph[p][bk]["growth_read"] for bk in bks if bk in ph[p])
        br = sum(ph[p][bk]["base_read"] for bk in bks if bk in ph[p])
        gpct = 100 * gr / (br + gr) if (br + gr) > 0 else 0
        print(f"{tot/n:>6.2f}/{gpct:>2.0f}%", end="")
    print()
# cold-rewrite is a write-side overhead, not a bucket
print(f"  {'cold-rewrite (YTDB-1097)':30}", end="")
for p in phases:
    print(f"{ph_rewarm[p]/len(ph_T[p]):>6.2f}/wr ", end="")
print()
print("\n  [cell = $/session / growth%. cold-rewrite cell = $/session of TTL-rewarm write (all write-side).]")
print("  [fewer/coarser turns: not a bucket — cuts T, hits read (~T^1.4), write, and output at once.]")
