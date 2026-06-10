#!/usr/bin/env python3
"""Per-turn prefix-growth analyzer for the workflow economic model.

Tests open question #1: is the cumulative cache-read cost superlinear
(approximately quadratic) in turn count, or bounded?

The per-turn cache_read_input_tokens (cr_k) IS the resident prefix re-read at
turn k. total_read_cost = READ * sum(cr_k). If the prefix grows ~linearly with
k (cr_k = a + b*k) the read integral is a*T + b*T(T-1)/2 -> two-term, with a
live quadratic component. Compaction would cap it; in the studied sessions it
does NOT fire (the user stops before compaction), so the quadratic term is
uncapped and the prefix grows monotonically to the last turn.

Two distinct events are tracked, NOT to be conflated (README convention):
  - TTL re-warm: cache_read dips (>15% below running peak) WITH a cache_write
    spike. The 5m cache expired and the whole prefix re-cached at the write
    rate. Content is NOT reduced; this is the cold-rewrite cost (YTDB-1097).
  - Compaction: real_prefix (cr+w+intok) drops >15% from peak and stays low.
    Content summarized away. Measured here as ~0 across the study set.

Pure stdlib. Dedup by (message.id, requestId), matching session-stats.py.
Opus 4.8 flat rates: read 0.50, write-5m 6.25, output 25, input 5 /MTok.
"""
import json, os, math, collections, sys

READ = 0.50 / 1e6; WRITE = 6.25 / 1e6; OUT = 25.0 / 1e6; IN = 5.0 / 1e6
DROP = 0.15  # >15% drop from running peak: cr-drop => TTL re-warm; real_prefix-drop => compaction

PHASE = {
    "d6fb4ed8": "exec-tracks B+C", "2294a479": "exec-tracks B+C",
    "5a35307e": "exec-tracks B+C", "c65d3661": "exec-tracks B+C",
    "46764d14": "exec-tracks impl", "86b0deec": "exec-tracks impl",
    "48f12216": "exec-tracks small", "eb3a9289": "exec-tracks small",
    "7349adfa": "create-plan P0/1", "60c8ca29": "create-plan P0/1",
    "779b4af1": "migrate-workflow", "5640d186": "migrate-workflow",
}
_STUDY = "/home/coder/.claude/projects/-home-andrii0lomakin-Projects-ytdb-open-speedup"
_DEFAULT = ["d6fb4ed8-e29c-4ab6-a046-aa0a72736ca7", "2294a479-6125-4811-8a1d-91bda3fad3e8",
    "5a35307e-9757-40b0-b180-8a86e2086db0", "c65d3661-d17f-4ccd-a344-00ae878b8cc1",
    "46764d14-5dbb-4c34-8aac-ad5c5eea993a", "86b0deec-8420-49db-93e9-3f5aebf3e7f4",
    "48f12216-f339-45e7-bea9-fb545eaaf48c", "eb3a9289-651e-4143-a128-407d4671f762",
    "7349adfa-5d47-4717-a5fc-c8d0797b9353", "60c8ca29-4869-4ffc-aeb8-22b4e007ca90",
    "779b4af1-1222-4829-9311-c09c9d343d91", "5640d186-3a7b-4814-9229-41b0e03d97d5"]
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


def turns(path):
    """Return ordered list of per-turn dicts {cr,w,intok,out} for deduped assistant turns."""
    out = []
    seen = set()
    for line in open(path):
        if not line.strip():
            continue
        o = json.loads(line)
        if o.get("type") != "assistant":
            continue
        m = o.get("message") or {}
        usage = m.get("usage") or {}
        mid = m.get("id"); rid = o.get("requestId")
        if not (usage and mid and rid) or (mid, rid) in seen:
            continue
        seen.add((mid, rid))
        cr = usage.get("cache_read_input_tokens") or 0
        cc = usage.get("cache_creation") or {}
        w = (cc.get("ephemeral_5m_input_tokens") or usage.get("cache_creation_input_tokens") or 0) \
            + (cc.get("ephemeral_1h_input_tokens") or 0)
        intok = usage.get("input_tokens") or 0
        outtok = usage.get("output_tokens") or 0
        out.append({"cr": cr, "w": w, "intok": intok, "out": outtok})
    return out


def linfit(xs, ys):
    """Least-squares slope, intercept, R^2 for ys ~ a + b*x."""
    n = len(xs)
    if n < 2:
        return 0.0, (ys[0] if ys else 0.0), 0.0
    xb = sum(xs) / n; yb = sum(ys) / n
    sxx = sum((x - xb) ** 2 for x in xs)
    sxy = sum((x - xb) * (y - yb) for x, y in zip(xs, ys))
    if sxx == 0:
        return 0.0, yb, 0.0
    b = sxy / sxx; a = yb - b * xb
    ss_tot = sum((y - yb) ** 2 for y in ys)
    ss_res = sum((y - (a + b * x)) ** 2 for x, y in zip(xs, ys))
    r2 = 1 - ss_res / ss_tot if ss_tot > 0 else 0.0
    return b, a, r2


def drop_count(series):
    """Count >DROP drops below the running peak (used for both cr and real_prefix)."""
    resets = 0; peak = 0.0
    for v in series:
        if peak > 0 and v < (1 - DROP) * peak:
            resets += 1
            peak = v  # restart the peak after a reset
        else:
            peak = max(peak, v)
    return resets


rows = []
for sid in SIDS:
    ts = turns(os.path.join(BASE, sid + ".jsonl"))
    if not ts:
        continue
    crs = [t["cr"] for t in ts]
    reals = [t["cr"] + t["w"] + t["intok"] for t in ts]  # billed prefix each turn
    T = len(ts)
    ks = list(range(1, T + 1))
    total_read = READ * sum(crs)
    total_write = WRITE * sum(t["w"] for t in ts)
    total_out = OUT * sum(t["out"] for t in ts)
    total_in = IN * sum(t["intok"] for t in ts)
    total_cost = total_read + total_write + total_out + total_in
    mean_prefix = sum(crs) / T
    b, a, r2 = linfit(ks, crs)
    # share of read cost attributable to the GROWTH term b*k vs the flat base a,
    # using the fitted line integral: flat = a*T, growth = b*T(T-1)/2.
    flat_int = a * T
    growth_int = b * T * (T - 1) / 2
    fit_int = flat_int + growth_int
    growth_share = growth_int / fit_int if fit_int > 0 else 0.0
    # first-decile vs last-decile mean prefix (model-free growth signal)
    d = max(1, T // 10)
    first_dec = sum(crs[:d]) / d
    last_dec = sum(crs[-d:]) / d
    growth_ratio = last_dec / first_dec if first_dec > 0 else float("nan")
    ttl_rewarm = drop_count(crs)          # cache_read dips => 5m TTL expiry, re-cached
    compaction = drop_count(reals)        # real_prefix sustained drop => content reduced
    final_peak = reals[-1] / max(reals)   # 1.0 => prefix peaks at last turn (no relief)
    rows.append({
        "sid": sid[:8], "phase": PHASE.get(sid[:8], "other"), "T": T,
        "mean_prefix": mean_prefix, "max_prefix": max(crs),
        "slope": b, "r2": r2, "growth_share": growth_share,
        "growth_ratio": growth_ratio, "ttl_rewarm": ttl_rewarm,
        "compaction": compaction, "final_peak": final_peak,
        "read$": total_read, "write$": total_write, "out$": total_out,
        "in$": total_in, "cost$": total_cost,
    })

# ---- per-session table ----
print("PER-SESSION PREFIX GROWTH (orchestrator transcript)\n")
hdr = (f"  {'sid':9}{'phase':18}{'T':>4}{'meanPfx':>9}{'maxPfx':>9}"
       f"{'slope':>8}{'r2':>6}{'grw%':>6}{'lastT/1st':>10}{'ttlRW':>6}{'cmpct':>6}"
       f"{'fin/pk':>7}{'read$':>8}{'cost$':>8}")
print(hdr)
print("  " + "-" * (len(hdr) - 2))
for r in rows:
    print(f"  {r['sid']:9}{r['phase']:18}{r['T']:>4}{r['mean_prefix']/1000:>8.0f}K"
          f"{r['max_prefix']/1000:>8.0f}K{r['slope']:>8.0f}{r['r2']:>6.2f}"
          f"{100*r['growth_share']:>5.0f}%{r['growth_ratio']:>10.2f}{r['ttl_rewarm']:>6}"
          f"{r['compaction']:>6}{r['final_peak']:>7.2f}{r['read$']:>8.2f}{r['cost$']:>8.2f}")
print("\n  [ttlRW = 5m-TTL cache re-warm events (cold-rewrite, YTDB-1097); cache_read dips + cache_write spike]")
print("  [cmpct = true compaction (real_prefix sustained drop); fin/pk = last-turn prefix / peak prefix]")
print("  [fin/pk = 1.00 => prefix peaks at the last turn: monotonic growth, no compaction relief]")

# ---- cross-session power-law fits ----
def powerfit(xs, ys):
    """alpha, c, r^2 for ys = c * xs^alpha via log-log least squares."""
    lx = [math.log(x) for x in xs]; ly = [math.log(y) for y in ys]
    alpha, lc, r2 = linfit(lx, ly)
    return alpha, math.exp(lc), r2

Ts = [r["T"] for r in rows]
reads = [r["read$"] for r in rows]
costs = [r["cost$"] for r in rows]
means = [r["mean_prefix"] for r in rows]

print("\nCROSS-SESSION SCALING (12 sessions, all phases pooled)")
a_read, _, r2_read = powerfit(Ts, reads)
a_cost, _, r2_cost = powerfit(Ts, costs)
a_mean, _, r2_mean = powerfit(Ts, means)
print(f"  total read $   ~ T^{a_read:.2f}   (log-log R^2={r2_read:.2f})")
print(f"  total cost $   ~ T^{a_cost:.2f}   (log-log R^2={r2_cost:.2f})")
print(f"  mean prefix    ~ T^{a_mean:.2f}   (log-log R^2={r2_mean:.2f})")
print("  [alpha~1 => linear/compaction-bounded; alpha~2 => quadratic prefix growth]")
print("  [identity: read$ = T * mean_prefix * READ, so a_read ~= 1 + a_mean]")

# Pearson r of mean_prefix vs T (does the prefix get bigger in longer sessions?)
n = len(rows)
xb = sum(Ts) / n; yb = sum(means) / n
cov = sum((x - xb) * (y - yb) for x, y in zip(Ts, means))
sx = math.sqrt(sum((x - xb) ** 2 for x in Ts)); sy = math.sqrt(sum((y - yb) ** 2 for y in means))
pear = cov / (sx * sy) if sx * sy > 0 else 0.0
print(f"  Pearson(mean_prefix, T) = {pear:.2f}")

# ---- per-phase aggregate growth signal ----
print("\nPER-PHASE GROWTH SIGNAL (mean over sessions in phase)")
byph = collections.defaultdict(list)
for r in rows:
    byph[r["phase"]].append(r)
print(f"  {'phase':18}{'n':>3}{'avgT':>6}{'avg meanPfx':>13}{'avg slope':>11}"
      f"{'avg grw%':>10}{'avg ttlRW':>11}{'avg cmpct':>11}")
for ph, rs in byph.items():
    nph = len(rs)
    print(f"  {ph:18}{nph:>3}{sum(x['T'] for x in rs)/nph:>6.0f}"
          f"{sum(x['mean_prefix'] for x in rs)/nph/1000:>11.0f}K"
          f"{sum(x['slope'] for x in rs)/nph:>11.0f}"
          f"{100*sum(x['growth_share'] for x in rs)/nph:>9.0f}%"
          f"{sum(x['ttl_rewarm'] for x in rs)/nph:>11.1f}"
          f"{sum(x['compaction'] for x in rs)/nph:>11.1f}")
