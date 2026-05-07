#!/usr/bin/env python3
"""Per-orchestrator-session cost & cache report for Claude Code.

Groups sub-agents by workflow phase / role using the `.claude/agents/` taxonomy
plus the `subagent_type` values referenced in `.claude/workflow/*.md`. Output
goes to stderr so it can be wired to a Stop hook without polluting stdout.

Usage:
  report-workflow-cost.py <main-session.jsonl>
  echo '{"transcript_path": "..."}' | report-workflow-cost.py     # Stop hook
"""
import json
import os
import pathlib
import re
import sys

# ---------------------------------------------------------------------------
# Pricing (USD per 1M tokens). Verify against current Anthropic pricing.
# Cache writes: 5m = 1.25x input, 1h = 2x input. Cache reads = 0.1x input.
# ---------------------------------------------------------------------------
PRICES = {
    "claude-opus-4-7":          {"in": 15.0, "out": 75.0, "read": 1.50, "write_5m": 18.75, "write_1h": 30.0},
    "claude-opus-4-6":          {"in": 15.0, "out": 75.0, "read": 1.50, "write_5m": 18.75, "write_1h": 30.0},
    "claude-sonnet-4-6":        {"in":  3.0, "out": 15.0, "read": 0.30, "write_5m":  3.75, "write_1h":  6.0},
    "claude-haiku-4-5":         {"in":  1.0, "out":  5.0, "read": 0.10, "write_5m":  1.25, "write_1h":  2.0},
    "claude-haiku-4-5-20251001":{"in":  1.0, "out":  5.0, "read": 0.10, "write_5m":  1.25, "write_1h":  2.0},
}
DEFAULT_MODEL = "claude-opus-4-7"

# ---------------------------------------------------------------------------
# Workflow phase mapping. Edit freely — the script keys off these dicts.
#
# Most workflow agent types are review-* names from .claude/agents/. The
# implementer and the plan-review consistency/structural sub-agents all spawn
# as `general-purpose`, so we disambiguate them by looking at the description
# field with the regex patterns in DESCRIPTION_RULES below.
# ---------------------------------------------------------------------------
AGENT_TYPE_TO_PHASE = {
    # Phase C track-level + Phase B step-level dimensional reviews
    "review-code-quality":       "Phase B/C dim review",
    "review-bugs-concurrency":   "Phase B/C dim review",
    "review-crash-safety":       "Phase B/C dim review",
    "review-performance":        "Phase B/C dim review",
    "review-security":           "Phase B/C dim review",
    "review-test-behavior":      "Phase B/C dim review",
    "review-test-completeness":  "Phase B/C dim review",
    "review-test-concurrency":   "Phase B/C dim review",
    "review-test-crash-safety":  "Phase B/C dim review",
    "review-test-structure":     "Phase B/C dim review",
    # Top-level user-invoked review commands (not /execute-tracks phases)
    "code-reviewer":             "/code-review",
    "test-quality-reviewer":     "/test-review",
    "pr-reviewer":               "/review (PR)",
    # Claude Code built-ins
    "Explore":                   "Built-in Explore",
    "claude-code-guide":         "Built-in claude-code-guide",
}

# Description-pattern fallback for `general-purpose` (and any unmapped type),
# checked in order. First match wins.
DESCRIPTION_RULES = [
    (re.compile(r"\b(consistency|structural)\b.*\breview\b", re.I), "Phase 2 plan review"),
    (re.compile(r"\bplan[- ]review\b", re.I),                       "Phase 2 plan review"),
    (re.compile(r"\bgate (verif|check)", re.I),                     "Phase 2 plan review"),
    (re.compile(r"\bphase\s*c\b|\btrack[- ]code[- ]review\b", re.I),"Phase C review-fix impl"),
    (re.compile(r"\bphase\s*b\b|\bstep\s*\d+|\bimplement(er)?\b", re.I),"Phase B step impl"),
    (re.compile(r"\bphase\s*a\b|\btrack[- ]review\b", re.I),        "Phase A track review"),
]
GENERIC_FALLBACK = "Other (general-purpose)"


def model_key(m):
    if m in PRICES:
        return m
    # match by prefix family (e.g., claude-opus-4-7-foo -> claude-opus-4-7)
    for k in PRICES:
        if m and m.startswith(k):
            return k
    return DEFAULT_MODEL


def classify(agent_type, description):
    if agent_type in AGENT_TYPE_TO_PHASE:
        return AGENT_TYPE_TO_PHASE[agent_type]
    desc = description or ""
    for pat, label in DESCRIPTION_RULES:
        if pat.search(desc):
            return label
    if agent_type == "general-purpose":
        return GENERIC_FALLBACK
    return f"Unknown ({agent_type or 'no-type'})"


def sum_usage(jsonl):
    """Aggregate per-turn usage from a Claude Code transcript JSONL."""
    a = {"in": 0, "out": 0, "read": 0, "w5": 0, "w1": 0, "cost": 0.0,
         "turns": 0, "model": None}
    if not jsonl.exists():
        return a
    with jsonl.open() as fh:
        for line in fh:
            try:
                m = json.loads(line)
            except ValueError:
                continue
            if m.get("type") != "assistant":
                continue
            msg = m.get("message", {})
            u = msg.get("usage") or {}
            if not u:
                continue
            model = msg.get("model") or DEFAULT_MODEL
            p = PRICES[model_key(model)]
            in_t = u.get("input_tokens", 0)
            out_t = u.get("output_tokens", 0)
            read_t = u.get("cache_read_input_tokens", 0)
            cc = u.get("cache_creation") or {}
            # Some turns don't break out 5m/1h; fall back to top-level total.
            w5 = cc.get("ephemeral_5m_input_tokens", 0)
            w1 = cc.get("ephemeral_1h_input_tokens", 0)
            if not (w5 or w1):
                w5 = u.get("cache_creation_input_tokens", 0)
            cost = (in_t * p["in"] + out_t * p["out"] + read_t * p["read"]
                    + w5 * p["write_5m"] + w1 * p["write_1h"]) / 1_000_000
            a["in"] += in_t; a["out"] += out_t; a["read"] += read_t
            a["w5"] += w5;   a["w1"] += w1
            a["cost"] += cost; a["turns"] += 1
            a["model"] = model
    return a


def fmt_int(n):
    return f"{n:,}"


def fmt_ratio(read, write):
    """read / write as 'N.Nx', or '   -' when write is zero."""
    if not write:
        return "   -"
    return f"{read / write:>4.1f}x"


def fmt_row(label, a, indent=2):
    pad = " " * indent
    return (f"{pad}{label:<46} model={a['model'] or '-':<26} "
            f"turns={a['turns']:>3}  "
            f"in={fmt_int(a['in']):>9}  read={fmt_int(a['read']):>11}  "
            f"w5m={fmt_int(a['w5']):>9}  w1h={fmt_int(a['w1']):>7}  "
            f"r/5m={fmt_ratio(a['read'], a['w5'])}  "
            f"r/1h={fmt_ratio(a['read'], a['w1'])}  "
            f"out={fmt_int(a['out']):>7}  ${a['cost']:>7.3f}")


def add(acc, a):
    for k in ("in", "out", "read", "w5", "w1", "cost", "turns"):
        acc[k] += a[k]


def main():
    if len(sys.argv) > 1:
        main_jsonl = pathlib.Path(sys.argv[1]).expanduser()
    else:
        try:
            payload = json.load(sys.stdin)
            main_jsonl = pathlib.Path(payload["transcript_path"]).expanduser()
        except Exception:
            print(f"usage: {sys.argv[0]} <session.jsonl>", file=sys.stderr)
            sys.exit(2)

    if not main_jsonl.exists():
        print(f"transcript not found: {main_jsonl}", file=sys.stderr)
        sys.exit(0)  # Stop hook should not error

    sub_dir = main_jsonl.parent / main_jsonl.stem / "subagents"
    print("=== Workflow Cost Report ===", file=sys.stderr)
    print(f"session: {main_jsonl.name}", file=sys.stderr)

    # Group sub-agents by phase label.
    groups = {}
    if sub_dir.is_dir():
        for sub in sorted(sub_dir.glob("agent-*.jsonl")):
            meta_path = sub.with_suffix(".meta.json")
            meta = {}
            if meta_path.exists():
                try:
                    meta = json.loads(meta_path.read_text())
                except ValueError:
                    pass
            agent_type = meta.get("agentType") or ""
            desc = meta.get("description") or ""
            label = classify(agent_type, desc)
            groups.setdefault(label, []).append((sub, agent_type, desc))

    grand = {k: 0 for k in ("in", "out", "read", "w5", "w1", "turns")}
    grand["cost"] = 0.0

    for label in sorted(groups):
        items = groups[label]
        sub_total = {k: 0 for k in ("in", "out", "read", "w5", "w1", "turns")}
        sub_total["cost"] = 0.0; sub_total["model"] = None
        print(f"\n[{label}]  ({len(items)} sub-agent(s))", file=sys.stderr)
        for sub, agent_type, desc in items:
            a = sum_usage(sub)
            row_label = f"{agent_type or '?'}: {desc[:34]}"
            print(fmt_row(row_label, a), file=sys.stderr)
            add(sub_total, a); sub_total["model"] = a["model"]
        print(fmt_row(f"-- subtotal ({label})", sub_total, indent=2),
              file=sys.stderr)
        add(grand, sub_total)

    print("", file=sys.stderr)
    orch = sum_usage(main_jsonl)
    print(fmt_row("[orchestrator]", orch, indent=0), file=sys.stderr)
    add(grand, orch)
    grand["model"] = "(mixed)" if groups else orch["model"]
    print(fmt_row("[grand total]", grand, indent=0), file=sys.stderr)

    # Cache split summary (5m vs 1h tells you whether ENABLE_PROMPT_CACHING_1H
    # is in effect; 1h writes only appear when the 1h beta is enabled).
    total_writes = grand["w5"] + grand["w1"]
    if total_writes:
        pct_1h = 100.0 * grand["w1"] / total_writes
        print(f"\ncache writes: 5m={fmt_int(grand['w5'])}  "
              f"1h={fmt_int(grand['w1'])}  ({pct_1h:.1f}% are 1h)",
              file=sys.stderr)

    print_legend()


def print_legend():
    """Footer explaining each column. Multiplier = price relative to base
    input price for the model. Output uses its own base rate."""
    print(
        "\nlegend (per-1M token pricing relative to model's base input rate):\n"
        "  turns  API request/response cycles in this session\n"
        "  in     fresh non-cached input_tokens (tail of request not in cache)         x1.00\n"
        "  read   cache_read_input_tokens (hit on a previously-written breakpoint)     x0.10\n"
        "  w5m    cache writes with 5-minute TTL (default)                             x1.25\n"
        "  w1h    cache writes with 1-hour TTL (requires ENABLE_PROMPT_CACHING_1H=1)   x2.00\n"
        "  r/5m   read / w5m  -- reuse ratio against 5m writes (higher is better)\n"
        "  r/1h   read / w1h  -- reuse ratio against 1h writes (only when 1h is enabled)\n"
        "  out    output_tokens (model-generated)                                  base out\n"
        "  model  Claude model id (sets the per-1M rate)\n"
        "interpretation:\n"
        "  high read / low w5m   -> cache reuse is healthy\n"
        "  low read  / high w5m  -> prefix is being rewritten too often\n"
        "                          (TTL expired across a long gap, or tools/system changed)\n"
        "  w1h=0 everywhere      -> 1h beta is off; long gaps (e.g., implementer waits)\n"
        "                          force the orchestrator to re-write prefixes on resume",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
