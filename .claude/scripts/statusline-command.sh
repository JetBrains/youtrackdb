#!/usr/bin/env bash
# Claude Code status line: model name | context progress bar | git branch
#
# Also writes the current context usage and threshold level to
# /tmp/claude-code-context-usage-<claude_pid>.txt so the model can read it
# on demand via Bash. Wired up via .claude/settings.json `statusLine`.
# Threshold rationale: see CLAUDE.md § Context Window Monitor.

input=$(cat)

model=$(echo "$input" | jq -r '.model.display_name // "Unknown Model"')
used_pct=$(echo "$input" | jq -r '.context_window.used_percentage // empty')
cwd=$(echo "$input" | jq -r '.workspace.current_dir // .cwd // ""')

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

# Determine threshold level. Also used for the on-disk usage file below.
if [ -n "$used_pct" ]; then
  pct_int=$(printf "%.0f" "$used_pct")
  if [ "$pct_int" -ge 40 ]; then
    level="critical"
  elif [ "$pct_int" -ge 30 ]; then
    level="warning"
  elif [ "$pct_int" -ge 20 ]; then
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

# Write context usage to a per-session file for on-demand reading by the model.
# Walk up the process tree to find the root `claude` process PID — same key
# the model uses via $PPID from its Bash tool. Uses `ps` (portable across
# Linux/macOS) rather than /proc (Linux-only).
if [ -n "$used_pct" ]; then
  claude_pid=""
  pid=$$
  while [ "$pid" -gt 1 ] 2>/dev/null; do
    ps_out=$(ps -p "$pid" -o ppid=,comm= 2>/dev/null)
    [ -z "$ps_out" ] && break
    ppid=$(printf "%s" "$ps_out" | awk '{print $1}')
    comm=$(printf "%s" "$ps_out" | awk '{print $2}')
    # macOS ps prints comm as a full path; strip to basename for the match.
    comm_base=$(basename "$comm" 2>/dev/null)
    if [ "$comm_base" = "claude" ]; then claude_pid=$pid; break; fi
    pid=$ppid
  done
  if [ -n "$claude_pid" ]; then
    printf "ctx: %s%% level=%s" "$pct_int" "$level" \
      > "/tmp/claude-code-context-usage-${claude_pid}.txt"
  fi
fi

# Compose the line
if [ -n "$branch" ]; then
  printf "%s  |  ctx: %s  |  %s" "$model" "$ctx_str" "$branch"
else
  printf "%s  |  ctx: %s" "$model" "$ctx_str"
fi
