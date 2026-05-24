#!/usr/bin/env bash
#
# bg-task.sh — safe background-task wrapper for implementer / orchestrator use.
#
# Closes the YTDB-971 anti-pattern (background `./mvnw` paired with `tail -f`
# or `tail -N`, no PID registration, no explicit kill, message budget burned
# on idle polling). Works for ANY long-running shell command, not just Maven —
# the wait pattern polls process-exit state (`kill -0`), so the same wrapper
# handles `./mvnw`, `./gradlew`, integration suites, Python scripts, async-
# profiler runs, anything that needs to outlive a single bash tool call.
#
# Design guarantees:
#   - Output goes straight to a log file via `> $LOG 2>&1` — no buffering pipe.
#   - PID is written to disk so the implementer can verify and `kill -TERM`.
#   - Exit code is captured to a file after the wrapped command finishes,
#     so `status` can report success vs failure without parsing the log.
#   - The single-notification wait pattern (run the printed WAIT_CMD via
#     Bash tool `run_in_background: true`) burns zero message budget while
#     the task runs.
#   - File names are jobid-suffixed (12 random hex chars) so concurrent
#     Claude agents on the same host cannot collide on /tmp.
#
# Subcommands:
#   launch "<shell command>"   start a background task; print JOB_ID, LOG,
#                              PID, plus copy-paste WAIT_CMD / STATUS_CMD /
#                              KILL_CMD / CLEANUP_CMD lines.
#   status <jobid>             print one line: STATE=running|success|failed
#                              JOB_ID=... PID=... EXIT=... ELAPSED_SEC=...
#   tail <jobid> [N]           print last N log lines (default 50).
#   kill <jobid>               SIGTERM, wait 5s, SIGKILL if still alive.
#   cleanup <jobid>            remove the four /tmp files for this job.
#   list                       enumerate every known bg job on this host.
#
# Implementer workflow:
#   eval "$(.claude/scripts/bg-task.sh launch './mvnw -pl core test')"
#   # → exports JOB_ID, LOG, PID, WAIT_CMD, STATUS_CMD, KILL_CMD, CLEANUP_CMD
#   # Now run the WAIT_CMD via the Bash tool with run_in_background: true.
#   # When the notification fires:
#   eval "$STATUS_CMD"          # prints STATE=success / failed / running
#   eval "$KILL_CMD"            # no-op if already exited
#   eval "$CLEANUP_CMD"         # remove log + pid + exit + cmd files
#

set -uo pipefail

usage() {
  cat <<'USAGE' >&2
bg-task.sh — safe background-task wrapper

Subcommands:
  launch "<shell command>"   start a background task; print job handles
  status <jobid>             one-line state report
  tail <jobid> [N]           last N log lines (default 50)
  kill <jobid>               SIGTERM, wait 5s, SIGKILL if still alive
  cleanup <jobid>            remove all files for the job
  list                       enumerate all known jobs

Run with no arguments for full usage. See header of this file for the
implementer workflow recipe.
USAGE
}

bg_base() {
  # Argument: jobid. Echoes the /tmp base path for that job's files.
  echo "/tmp/claude-code-bg-$1"
}

cmd_launch() {
  local user_cmd="$*"
  if [ -z "$user_cmd" ]; then
    echo "bg-task.sh launch: missing command" >&2
    echo "usage: bg-task.sh launch \"<shell command>\"" >&2
    return 2
  fi

  # 12 hex chars from /dev/urandom — virtually no collision risk across
  # concurrent agents on the same host.
  local jobid
  jobid=$(head -c 6 /dev/urandom | od -An -tx1 | tr -d ' \n')
  local base
  base=$(bg_base "$jobid")

  # Record the command (used by `list` and for post-mortem inspection).
  printf '%s\n' "$user_cmd" > "${base}.cmd"

  # The wrapper subshell does three things:
  #   1. Run the user command in a child bash via `bash -c`. Routing through
  #      a child shell is load-bearing: if the user command itself calls
  #      `exit <n>` (or has `&& exit 0` tacked on), an `eval` in this
  #      subshell would propagate the exit and terminate the wrapper before
  #      reaching the printf below, so .exit never gets written and the
  #      WAIT_CMD blocks forever. With `bash -c`, the child takes the
  #      `exit`, the wrapper carries on, and `$?` captures the child's
  #      return code.
  #   2. Capture the exit code into ${base}.exit.
  #   3. Exit, releasing the PID so the .exit file's authoritative state
  #      becomes visible to status / WAIT_CMD / list.
  # The full pipeline (bash -c + redirect + printf) is wrapped in a
  # subshell that we background with `&`, so `$!` gives us one stable PID
  # for the whole job.
  #
  # The outer `</dev/null >/dev/null 2>&1` on the subshell line is critical
  # for callers that capture this function via `$(...)`. Command substitution
  # waits until every process that inherited the capture pipe's stdout
  # closes it. By default the backgrounded subshell inherits stdout from the
  # function, even if `bash -c` redirects its own stdout to a log file; the
  # subshell shell itself still holds the FD. Routing the subshell's FDs to
  # /dev/null before the inner redirects releases the capture pipe so
  # `$(.../bg-task.sh launch ...)` returns immediately. The inner `>
  # "${base}.log" 2>&1` then takes precedence for bash -c's output.
  (
    bash -c "$user_cmd" > "${base}.log" 2>&1
    printf '%s\n' "$?" > "${base}.exit"
  ) </dev/null >/dev/null 2>&1 &
  local pid=$!
  printf '%s\n' "$pid" > "${base}.pid"

  # The launch output is designed to be `eval`-able into the caller's shell
  # so every helper command is a single variable reference.
  #
  # WAIT_CMD watches for the .exit file rather than for `kill -0 <pid>` to
  # return false. The wrapper subshell reparents to init when the launch
  # script returns, and on this host init does not reap zombies promptly,
  # so a wrapper PID can sit in state `Z <defunct>` for many seconds after
  # the user command finished. `kill -0` returns true on zombies, so a
  # kill-based wait loop never terminates. The .exit file is the
  # authoritative completion signal: the wrapper writes it immediately
  # after the user command's exit code is known, and `kill` below writes a
  # synthetic value when an external kill cuts the wrapper off before it
  # reaches the printf.
  #
  # Default polling interval is 30 s. For short tasks set BG_TASK_POLL_SEC=5
  # before launch (or override the printed WAIT_CMD in the caller's shell).
  local poll_sec="${BG_TASK_POLL_SEC:-30}"
  cat <<EOM
JOB_ID=${jobid}
LOG=${base}.log
PID=${pid}
WAIT_CMD='until [ -f "${base}.exit" ]; do sleep ${poll_sec}; done'
STATUS_CMD='.claude/scripts/bg-task.sh status ${jobid}'
TAIL_CMD='.claude/scripts/bg-task.sh tail ${jobid}'
KILL_CMD='.claude/scripts/bg-task.sh kill ${jobid}'
CLEANUP_CMD='.claude/scripts/bg-task.sh cleanup ${jobid}'
export JOB_ID LOG PID WAIT_CMD STATUS_CMD TAIL_CMD KILL_CMD CLEANUP_CMD
EOM
}

cmd_status() {
  local jobid="${1:-}"
  if [ -z "$jobid" ]; then
    echo "bg-task.sh status: missing jobid" >&2
    return 2
  fi
  local base
  base=$(bg_base "$jobid")

  if [ ! -f "${base}.pid" ]; then
    echo "STATE=unknown JOB_ID=${jobid} REASON=no_pid_file"
    return 1
  fi
  local pid
  pid=$(cat "${base}.pid")

  # The .exit file is the authoritative completion signal. The wrapper
  # subshell writes it immediately after the user command's exit code is
  # known; `kill` writes a synthetic value before sending SIGKILL. Check
  # for it before `kill -0`, because `kill -0` returns true on zombies
  # (defunct processes whose PID is still in the table) and on this host
  # init does not reap reparented zombies promptly — see the wait-pattern
  # comment in cmd_launch above.
  if [ -f "${base}.exit" ]; then
    local code
    code=$(cat "${base}.exit")
    if [ "$code" = "0" ]; then
      echo "STATE=success JOB_ID=${jobid} PID=${pid} EXIT=0"
      return 0
    fi
    echo "STATE=failed JOB_ID=${jobid} PID=${pid} EXIT=${code}"
    return 0
  fi

  # No .exit file yet. Either the process is genuinely still running or
  # the wrapper was killed off-script (a signal that didn't go through
  # `bg-task.sh kill`) before reaching the printf.
  if kill -0 "$pid" 2>/dev/null; then
    local started elapsed
    started=$(stat -c %Y "${base}.pid" 2>/dev/null || echo 0)
    elapsed=$(( $(date +%s) - started ))
    echo "STATE=running JOB_ID=${jobid} PID=${pid} ELAPSED_SEC=${elapsed}"
    return 0
  fi

  # PID gone and no .exit file. Surface as a distinct state so the caller
  # can decide whether to retry or treat as failed.
  echo "STATE=exited_no_code JOB_ID=${jobid} PID=${pid}"
  return 1
}

cmd_tail() {
  local jobid="${1:-}"
  local n="${2:-50}"
  if [ -z "$jobid" ]; then
    echo "bg-task.sh tail: missing jobid" >&2
    return 2
  fi
  local base
  base=$(bg_base "$jobid")
  if [ ! -f "${base}.log" ]; then
    echo "bg-task.sh tail: no log file for ${jobid}" >&2
    return 1
  fi
  tail -n "$n" "${base}.log"
}

cmd_kill() {
  local jobid="${1:-}"
  if [ -z "$jobid" ]; then
    echo "bg-task.sh kill: missing jobid" >&2
    return 2
  fi
  local base
  base=$(bg_base "$jobid")
  if [ ! -f "${base}.pid" ]; then
    echo "no pid file for ${jobid} (already cleaned up?)"
    return 0
  fi
  local pid
  pid=$(cat "${base}.pid")
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "${jobid} (PID ${pid}) already exited"
    return 0
  fi
  # SIGTERM first, give the process up to 5 seconds to exit cleanly.
  kill -TERM "$pid" 2>/dev/null || true
  local i
  for i in 1 2 3 4 5; do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  local how
  if kill -0 "$pid" 2>/dev/null; then
    kill -KILL "$pid" 2>/dev/null || true
    how="SIGKILL"
  else
    how="SIGTERM"
  fi
  # Write a synthetic exit value so WAIT_CMD (`until [ -f .exit ]`) unblocks
  # for any caller that was waiting on this job. Don't clobber a real exit
  # code that the wrapper subshell already wrote.
  if [ ! -f "${base}.exit" ]; then
    printf '%s\n' "killed-${how}" > "${base}.exit"
  fi
  echo "killed ${jobid} (PID ${pid}) with ${how}"
}

cmd_cleanup() {
  local jobid="${1:-}"
  if [ -z "$jobid" ]; then
    echo "bg-task.sh cleanup: missing jobid" >&2
    return 2
  fi
  local base
  base=$(bg_base "$jobid")
  rm -f "${base}.log" "${base}.pid" "${base}.exit" "${base}.cmd"
  echo "cleaned up ${jobid}"
}

cmd_list() {
  local found=0
  for pidfile in /tmp/claude-code-bg-*.pid; do
    [ -f "$pidfile" ] || continue
    found=1
    local jobid pid cmd state
    jobid=$(basename "$pidfile" .pid)
    jobid=${jobid#claude-code-bg-}
    pid=$(cat "$pidfile")
    cmd=$(cat "/tmp/claude-code-bg-${jobid}.cmd" 2>/dev/null || echo '(no cmd file)')
    # Same ordering as cmd_status: .exit file is authoritative; fall back to
    # kill -0 only when no exit file exists. Avoids the zombie false-positive.
    if [ -f "/tmp/claude-code-bg-${jobid}.exit" ]; then
      local exit_code
      exit_code=$(cat "/tmp/claude-code-bg-${jobid}.exit")
      if [ "$exit_code" = "0" ]; then
        state="success"
      else
        state="failed(exit=${exit_code})"
      fi
    elif kill -0 "$pid" 2>/dev/null; then
      state="running"
    else
      state="exited_no_code"
    fi
    printf '%s %s PID=%s CMD=%s\n' "$jobid" "$state" "$pid" "$cmd"
  done
  [ "$found" -eq 0 ] && echo "(no bg-task jobs on this host)"
}

main() {
  local sub="${1:-}"
  if [ -z "$sub" ]; then
    usage
    exit 2
  fi
  shift
  case "$sub" in
    launch)  cmd_launch "$@" ;;
    status)  cmd_status "$@" ;;
    tail)    cmd_tail "$@" ;;
    kill)    cmd_kill "$@" ;;
    cleanup) cmd_cleanup "$@" ;;
    list)    cmd_list "$@" ;;
    -h|--help|help) usage; exit 0 ;;
    *) echo "bg-task.sh: unknown subcommand: $sub" >&2; usage; exit 2 ;;
  esac
}

main "$@"
