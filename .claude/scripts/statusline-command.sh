#!/usr/bin/env bash
# Claude Code status line: model name | context progress bar | git branch
#
# Also writes the current context usage and threshold level to
# /tmp/claude-code-context-usage-<claude_pid>.txt so the model can read it
# on demand via Bash. Wired up via .claude/settings.json `statusLine`.
# Threshold rationale: see CLAUDE.md § Context Window Monitor.
#
# When the cwd is a *linked* git worktree, the second (cost) line gains a
# `wt:$X` figure for the spend on the current worktree's project, and that
# cost is also written to /tmp/claude-code-worktree-cost-<claude_pid>.txt for
# on-demand reading (the worktree figure and file are produced by
# session-stats.py — this script only detects the worktree and passes flags).

input=$(cat)

model=$(echo "$input" | jq -r '.model.display_name // "Unknown Model"')
used_pct=$(echo "$input" | jq -r '.context_window.used_percentage // empty')
cwd=$(echo "$input" | jq -r '.workspace.current_dir // .cwd // ""')
transcript_path=$(echo "$input" | jq -r '.transcript_path // empty')

# Git branch (skip optional locks to avoid contention)
branch=""
if [ -n "$cwd" ] && [ -d "$cwd/.git" ] || git -C "$cwd" rev-parse --git-dir >/dev/null 2>&1; then
  branch=$(git -C "$cwd" -c core.fsync=none symbolic-ref --short HEAD 2>/dev/null \
           || git -C "$cwd" -c core.fsync=none rev-parse --short HEAD 2>/dev/null)
fi

# Build context progress bar (10 chars wide)
build_bar() {
  local pct="${1:-0}"
  local filled=$(( (pct * 10 + 50) / 100 ))
  [ "$filled" -gt 10 ] && filled=10
  local empty=$(( 10 - filled ))
  local bar=""
  local i
  for (( i=0; i<filled; i++ )); do bar="${bar}#"; done
  for (( i=0; i<empty; i++ )); do bar="${bar}-"; done
  printf "%s" "$bar"
}

# Walk up the process tree to the root `claude` process PID — the same key the
# model uses via $PPID from its Bash tool. This PID names both the on-disk
# context-usage file and the worktree-cost file. Uses `ps` (portable across
# Linux/macOS) rather than /proc (Linux-only). Echoes the PID, or nothing if
# the walk hits PID 1 without finding `claude`. ($$ is the script shell's PID
# and stays stable inside this command-substitution call.)
resolve_claude_pid() {
  local pid=$$ ps_out ppid comm comm_base
  while [ "$pid" -gt 1 ] 2>/dev/null; do
    ps_out=$(ps -p "$pid" -o ppid=,comm= 2>/dev/null)
    [ -z "$ps_out" ] && break
    ppid=$(printf "%s" "$ps_out" | awk '{print $1}')
    comm=$(printf "%s" "$ps_out" | awk '{print $2}')
    # macOS ps prints comm as a full path; strip to basename for the match.
    comm_base=$(basename "$comm" 2>/dev/null)
    if [ "$comm_base" = "claude" ]; then printf "%s" "$pid"; return 0; fi
    pid=$ppid
  done
  return 0
}

# Determine threshold level. Also used for the on-disk usage file below.
if [ -n "$used_pct" ]; then
  pct_int=$(printf "%.0f" "$used_pct")
  if [ "$pct_int" -ge 50 ]; then
    level="critical"
  elif [ "$pct_int" -ge 40 ]; then
    level="warning"
  elif [ "$pct_int" -ge 25 ]; then
    level="info"
  else
    level="safe"
  fi

  # Map level → ANSI colour for the progress bar, honouring NO_COLOR
  # (non-TTY consumers like log capture and snapshot tests).
  ctx_color=""
  ctx_reset=""
  if [ -z "$NO_COLOR" ]; then
    case "$level" in
      critical) ctx_color=$'\033[31m' ;;   # red
      warning)  ctx_color=$'\033[33m' ;;   # yellow
      info)     ctx_color=$'\033[2;32m' ;; # dim green
      safe)     ctx_color=$'\033[32m' ;;   # green
    esac
    ctx_reset=$'\033[0m'
  fi
  bar=$(build_bar "$pct_int")
  ctx_str="${ctx_color}[${bar}] ${pct_int}%${ctx_reset}"
else
  ctx_str="[----------] --%"
fi

# Resolve the root claude PID once — it keys both per-session /tmp files below.
claude_pid=$(resolve_claude_pid)

# Write context usage to a per-session file for on-demand reading by the model.
if [ -n "$used_pct" ] && [ -n "$claude_pid" ]; then
  printf "ctx: %s%% level=%s" "$pct_int" "$level" \
    > "/tmp/claude-code-context-usage-${claude_pid}.txt"
fi

# Detect a *linked* git worktree: its git-dir lives under <common>/worktrees/,
# so the resolved --git-dir differs from --git-common-dir. The main checkout
# has them equal. Both are resolved to physical absolute paths so a relative
# ".git" (returned at a main checkout's root) compares equal to its absolute
# form — only a linked worktree ends up differing. When detected, the cost
# line gains the per-worktree-project figure.
is_worktree=0
if [ -n "$cwd" ]; then
  wt_gitdir=$(git -C "$cwd" rev-parse --git-dir 2>/dev/null)
  wt_common=$(git -C "$cwd" rev-parse --git-common-dir 2>/dev/null)
  if [ -n "$wt_gitdir" ] && [ -n "$wt_common" ]; then
    gd_abs=$( cd "$cwd" 2>/dev/null && cd "$wt_gitdir" 2>/dev/null && pwd -P )
    cd_abs=$( cd "$cwd" 2>/dev/null && cd "$wt_common" 2>/dev/null && pwd -P )
    if [ -n "$gd_abs" ] && [ -n "$cd_abs" ] && [ "$gd_abs" != "$cd_abs" ]; then
      is_worktree=1
    fi
  fi
fi

# Compose the line
if [ -n "$branch" ]; then
  printf "%s  |  ctx: %s  |  %s" "$model" "$ctx_str" "$branch"
else
  printf "%s  |  ctx: %s" "$model" "$ctx_str"
fi

# Second line: cost (current session + calendar month) and per-session token
# totals across orchestrator + sub-agents. In a linked worktree, also pass the
# per-worktree-project figure (--worktree) and publish its cost to a /tmp file
# (--worktree-cost-file). Best-effort — never break the primary status line if
# the helper errors out.
if [ -n "$transcript_path" ]; then
  script_dir=$(dirname "$0")
  stats_args=("$transcript_path")
  if [ "$is_worktree" -eq 1 ]; then
    stats_args+=(--worktree)
    if [ -n "$claude_pid" ]; then
      stats_args+=(--worktree-cost-file "/tmp/claude-code-worktree-cost-${claude_pid}.txt")
    fi
  fi
  stats_line=$(python3 "${script_dir}/session-stats.py" "${stats_args[@]}" 2>/dev/null)
  if [ -n "$stats_line" ]; then
    printf "\n%s" "$stats_line"
  fi
fi
