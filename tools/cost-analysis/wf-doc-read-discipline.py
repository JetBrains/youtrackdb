#!/usr/bin/env python3
"""Doc-view discipline audit: realized partial-read savings + TOC-filter violations.

The workflow already practices a doc-view discipline: per-phase prompt files,
load-on-demand pointers from CLAUDE.md, and partial Reads (offset/limit).
This analyzer measures, over the study transcripts (orchestrator + sub-agents):

  1. REALIZED SAVINGS - for every workflow-doc file a transcript touched, the
     line-range UNION it actually read vs the full file. The uncovered remainder,
     priced at the resident-tail rate from the first-read turn, is what full-file
     reads would have added. That is the saving the discipline already banks.
  2. VIOLATIONS - where the filtering breaks down:
       chunked-full  union coverage >= 90% of the file via 2+ partial reads
                     (paid like a full read; offset/limit was cosmetic)
       big-full      a single full read (no offset/limit) of a file > 5K tokens
       re-read       a later Read overlaps >= 50% with lines already resident in
                     the SAME transcript (no compaction in these sessions, so the
                     content was still in the prefix; pure duplicate)

All file geometry is derived FROM THE LOGS, not from disk: Read results are
cat -n numbered, so each result yields its exact line range and token size,
and a read that returns fewer lines than requested hit EOF - pinning the
file's true total length as of the study. Files whose EOF was never observed
get total = max line seen (coverage 100%, conservative: no phantom savings).
For _workflow/ artifacts that grow during a session, the max observed EOF
total is used.

Each event is priced with the resident-tail model: tokens * (WRITE + READ *
(T - k)) for a read landing at turn k of a T-turn transcript - the same model
the other analyzers validate against the true bill. Pure stdlib.
"""
import json, os, glob, re, sys, collections

WRITE = 6.25 / 1e6; READ = 0.50 / 1e6
F = 1.12
READ_CAP = 2000  # Read tool default line cap
PROC = ("/.claude/workflow/", "/.claude/agents/", "/.claude/skills/", "/.claude/docs/", "/.claude/output-styles/")
ART = ("/docs/adr/",)
NUMLINE = re.compile(r"^\s*(\d+)\t", re.M)

PHASE = {
    "d6fb4ed8": "exec-tracks B+C", "2294a479": "exec-tracks B+C", "5a35307e": "exec-tracks B+C", "c65d3661": "exec-tracks B+C",
    "46764d14": "exec-tracks impl", "86b0deec": "exec-tracks impl", "48f12216": "exec-tracks small", "eb3a9289": "exec-tracks small",
    "7349adfa": "create-plan P0/1", "60c8ca29": "create-plan P0/1", "779b4af1": "migrate-workflow", "5640d186": "migrate-workflow"}
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


def is_doc(fp):
    return any(s in fp for s in PROC) or any(s in fp for s in ART)


def ctext(c):
    if isinstance(c, str): return c
    if isinstance(c, list): return "".join(x.get("text", "") for x in c if isinstance(x, dict))
    return ""


def parse_read(text):
    """Parse a cat -n Read result: (start_line, end_line, n_lines, tokens) or None."""
    nums = [int(m) for m in NUMLINE.findall(text)]
    if not nums: return None
    return nums[0], nums[-1], len(nums), len(text) / 4 * F


def union_len(ranges):
    tot = 0; cur_e = -10
    for s, e in sorted(ranges):
        if s <= cur_e + 1:
            if e > cur_e: tot += e - cur_e; cur_e = e
        else:
            tot += e - s + 1; cur_e = e
    return tot


def overlap(r, ranges):
    s, e = r; cov = 0
    for a, b in ranges:
        lo, hi = max(s, a), min(e, b)
        if lo <= hi: cov += hi - lo + 1
    return min(cov, e - s + 1)


def scan(path):
    """Yield read events from one transcript: {file, turn, partial, start, end,
    n, tokens, eof_total or None} plus the transcript's deduped turn count."""
    read_req = {}; seen = set(); turns = 0
    events = []
    for line in open(path):
        if not line.strip(): continue
        o = json.loads(line)
        t = o.get("type")
        if t == "assistant":
            m = o.get("message") or {}; usage = m.get("usage") or {}
            for b in m.get("content") or []:
                if isinstance(b, dict) and b.get("type") == "tool_use" and b.get("name") == "Read":
                    read_req[b.get("id")] = b.get("input") or {}
            mid = m.get("id"); rid = o.get("requestId")
            if usage and mid and rid and (mid, rid) not in seen:
                seen.add((mid, rid)); turns += 1
        elif t == "user":
            c = o.get("message", {}).get("content")
            if isinstance(c, list):
                for b in c:
                    if not (isinstance(b, dict) and b.get("type") == "tool_result"): continue
                    inp = read_req.pop(b.get("tool_use_id"), None)
                    if inp is None: continue
                    fp = inp.get("file_path", "")
                    if not is_doc(fp): continue
                    parsed = parse_read(ctext(b.get("content")))
                    if not parsed: continue  # error result / empty file
                    s, e, n, tok = parsed
                    lim = inp.get("limit"); off = inp.get("offset")
                    requested = lim if lim is not None else READ_CAP
                    # fewer lines back than asked for => the read ran into EOF,
                    # so the file's true length (at this moment) is exactly e
                    eof_total = e if n < requested else None
                    events.append({"file": fp, "turn": turns + 1,
                                   "partial": off is not None or lim is not None,
                                   "start": s, "end": e, "n": n, "tokens": tok,
                                   "eof_total": eof_total})
    return events, max(turns, 1)


# ---- pass 1: global file geometry from the logs ----
transcripts = []  # (sid, phase, who, events, T)
file_total = {}                              # file -> exact total lines (max EOF seen)
file_tok = collections.defaultdict(float)    # file -> observed tokens
file_lines = collections.defaultdict(int)    # file -> observed numbered lines
for sid in SIDS:
    phase = PHASE.get(sid[:8], "other")
    stem = os.path.join(BASE, sid)
    paths = [(stem + ".jsonl", "orch")] + [(p, "sub") for p in sorted(glob.glob(os.path.join(stem, "subagents", "agent-*.jsonl")))]
    for path, who in paths:
        if not os.path.exists(path): continue
        events, T = scan(path)
        if events: transcripts.append((sid, phase, who, events, T))
        for ev in events:
            f = ev["file"]
            file_tok[f] += ev["tokens"]; file_lines[f] += ev["n"]
            if ev["eof_total"] is not None:
                file_total[f] = max(file_total.get(f, 0), ev["eof_total"])
            file_total[f] = max(file_total.get(f, 0), ev["end"])  # lower bound

eof_known = sum(1 for f in file_total if any(
    ev["eof_total"] is not None for _, _, _, evs, _ in transcripts for ev in evs if ev["file"] == f))

# ---- pass 2: audit each transcript with exact geometry ----
ph_sav = collections.defaultdict(float)
ph_viol = collections.defaultdict(lambda: collections.defaultdict(float))
ph_doccost = collections.defaultdict(float)
viol_list = []
for sid, phase, who, events, T in transcripts:
    rate = lambda k: WRITE + READ * max(0, T - k)
    per_file_ranges = collections.defaultdict(list)
    by_file = collections.defaultdict(list)
    for ev in events:
        ph_doccost[phase] += ev["tokens"] * rate(ev["turn"])
        ev["dup"] = overlap((ev["start"], ev["end"]), per_file_ranges[ev["file"]])
        per_file_ranges[ev["file"]].append((ev["start"], ev["end"]))
        by_file[ev["file"]].append(ev)
    for fp, evs in by_file.items():
        lines_total = file_total[fp]
        tpl = file_tok[fp] / max(1, file_lines[fp])  # tokens per line, observed
        cov_lines = min(union_len([(e["start"], e["end"]) for e in evs]), lines_total)
        cov_frac = cov_lines / lines_total if lines_total else 1.0
        first_turn = min(e["turn"] for e in evs)
        uncovered_tok = (lines_total - cov_lines) * tpl
        if uncovered_tok > 0:
            ph_sav[phase] += uncovered_tok * rate(first_turn)
        n_partial = sum(1 for e in evs if e["partial"])
        if cov_frac >= 0.9 and n_partial >= 2 and len(evs) >= 2:
            cost = sum(e["tokens"] for e in evs) * rate(first_turn)
            ph_viol[phase]["chunked-full"] += cost
            viol_list.append((cost, "chunked-full", sid[:8], who, fp, f"{len(evs)} reads cover {100*cov_frac:.0f}%"))
        for e in evs:
            if not e["partial"] and e["tokens"] > 5000:
                cost = e["tokens"] * rate(e["turn"])
                ph_viol[phase]["big-full"] += cost
                viol_list.append((cost, "big-full", sid[:8], who, fp, f"{e['tokens']/1000:.1f}K tok in one read"))
            if e["dup"] > 0 and e["dup"] >= 0.5 * (e["end"] - e["start"] + 1):
                cost = e["dup"] * tpl * rate(e["turn"])
                ph_viol[phase]["re-read"] += cost
                viol_list.append((cost, "re-read", sid[:8], who, fp, f"{e['dup']} lines re-read at turn {e['turn']}"))

print("DOC-VIEW DISCIPLINE AUDIT (orch + sub-agents; geometry from logs; resident-tail pricing)\n")
print(f"  {'phase':18}{'doc cost$':>10}{'saved$ (realized)':>19}{'chunked-full$':>15}{'big-full$':>11}{'re-read$':>10}")
print("  " + "-" * 85)
tot_s = tot_c = 0.0; tot_v = collections.defaultdict(float)
_PREF = ["exec-tracks B+C", "exec-tracks impl", "exec-tracks small", "create-plan P0/1", "migrate-workflow"]
for p in [x for x in _PREF if x in set(list(ph_sav) + list(ph_doccost))]:
    v = ph_viol[p]
    print(f"  {p:18}{ph_doccost[p]:>10.2f}{ph_sav[p]:>19.2f}{v['chunked-full']:>15.2f}{v['big-full']:>11.2f}{v['re-read']:>10.2f}")
    tot_s += ph_sav[p]; tot_c += ph_doccost[p]
    for k in v: tot_v[k] += v[k]
print("  " + "-" * 85)
print(f"  {'ALL':18}{tot_c:>10.2f}{tot_s:>19.2f}{tot_v['chunked-full']:>15.2f}{tot_v['big-full']:>11.2f}{tot_v['re-read']:>10.2f}")
nf = len(file_total)
print(f"\n  [{nf} doc files touched; EOF (exact size) observed for {eof_known}; rest use max-line lower bound")
print("   => their coverage reads as 100% and contributes no savings (conservative).]")
print("  [saved$ = (file - union-of-ranges-read) priced at the resident tail from first touch:")
print("   what full-file reads would have ADDED. Violations are priced the same way.]")

print("\nTOP VIOLATIONS")
for cost, kind, sid8, who, fp, note in sorted(viol_list, reverse=True)[:15]:
    i = fp.find("/.claude/")
    if i < 0: i = fp.find("/docs/adr/")
    print(f"  ${cost:5.2f}  {kind:12} {sid8}/{who:4} {fp[i:] if i >= 0 else fp}  ({note})")
