/**
 * Handoff guard — context-budget discipline for long-running agent work.
 *
 * Mechanisms (in order of importance):
 *
 * 1. Context snapshot file (agent-readable, Claude-Code style):
 *    After every turn, writes ${TMPDIR:-/tmp}/pi-context/<pi-pid>.json with
 *    { pid, sessionFile, model, tokens, contextWindow, percent, thresholdPercent,
 *      overThreshold, updatedAt }.
 *    The agent's bash tool runs as a direct child of the pi process, so the
 *    agent can always self-check with:
 *        cat ${TMPDIR:-/tmp}/pi-context/$PPID.json
 *    The file is removed on session shutdown.
 *
 * 2. One-time in-band notice: when usage crosses the threshold (default 40%),
 *    a message is queued to the agent itself (deliverAs "steer") instructing it
 *    to persist durable state to files and propose /handoff.
 *    (No footer status — pi's built-in footer already shows context usage.)
 *
 * /handoff [focus...] — starts a fresh session. The kickoff message comes from
 * `.pi/handoff.md` when present (project-specific resumption instructions),
 * else a generic fallback; the optional [focus...] argument is appended.
 *
 * Slate awareness: when the slate extension's orchestrator mode is active
 * (detected via the active tool set), the in-band notice and /handoff are
 * deferred to slate's own pause/handoff flow (/slate handoff), which also
 * carries thread/episode state into the new session. The snapshot file is
 * still written.
 */

import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { CONFIG_DIR_NAME } from "@earendil-works/pi-coding-agent";

const THRESHOLD_PERCENT = 40;
const CTX_DIR = join(tmpdir(), "pi-context");
const CTX_FILE = join(CTX_DIR, `${process.pid}.json`);

/** Slate orchestrator mode restricts tools to read-only + slate's `thread` family. */
function slateOrchestratorActive(pi: ExtensionAPI): boolean {
	const tools = pi.getActiveTools();
	return tools.includes("thread") && !tools.includes("bash") && !tools.includes("edit");
}

export default function (pi: ExtensionAPI) {
	let warned = false;

	const writeSnapshot = (ctx: ExtensionContext) => {
		const usage = ctx.getContextUsage();
		const pct = usage?.percent ?? null;
		const snapshot = {
			pid: process.pid,
			sessionFile: ctx.sessionManager.getSessionFile() ?? null,
			model: ctx.model ? `${ctx.model.provider}/${ctx.model.id}` : null,
			tokens: usage?.tokens ?? null,
			contextWindow: usage?.contextWindow ?? null,
			percent: pct == null ? null : Math.round(pct * 10) / 10,
			thresholdPercent: THRESHOLD_PERCENT,
			overThreshold: pct != null && pct >= THRESHOLD_PERCENT,
			updatedAt: new Date().toISOString(),
		};
		try {
			mkdirSync(CTX_DIR, { recursive: true });
			writeFileSync(CTX_FILE, `${JSON.stringify(snapshot, null, 2)}\n`, "utf8");
		} catch {
			/* never break the session over telemetry */
		}
		return snapshot;
	};

	const refresh = (ctx: ExtensionContext) => {
		const snap = writeSnapshot(ctx);
		if (snap.percent == null) return;
		const pct = Math.round(snap.percent);
		// Slate's auto-pause owns the over-budget response in orchestrator mode.
		if (snap.overThreshold && !warned && !slateOrchestratorActive(pi)) {
			warned = true;
			ctx.ui.notify(
				`Context at ${pct}% (threshold ${THRESHOLD_PERCENT}%). ` +
					`Ask the agent to persist durable state (plans, progress notes), then run /handoff.`,
				"warning",
			);
			pi.sendMessage(
				{
					customType: "handoff-guard",
					content:
						`[handoff-guard] Context usage is ${pct}%, over the ${THRESHOLD_PERCENT}% budget. ` +
						`Finish the current step only. Then: (1) persist all durable state to files ` +
						`(plans, progress, decisions) so work can resume from files alone, ` +
						`(2) tell the user it is time to run /handoff. Do not start new work in this session.`,
					display: true,
				},
				{ deliverAs: "steer" },
			);
		}
		if (!snap.overThreshold) warned = false;
	};

	pi.on("turn_end", async (_event, ctx) => refresh(ctx));
	pi.on("agent_end", async (_event, ctx) => refresh(ctx));
	pi.on("session_start", async (_event, ctx) => {
		warned = false;
		refresh(ctx);
	});
	pi.on("session_shutdown", async () => {
		try {
			rmSync(CTX_FILE, { force: true });
		} catch {
			/* ignore */
		}
	});

	pi.registerCommand("handoff", {
		description: "Fresh session that resumes work from persisted state (context hygiene)",
		handler: async (args, ctx) => {
			if (slateOrchestratorActive(pi)) {
				ctx.ui.notify(
					"Slate orchestrator mode is active — use /slate handoff instead (it carries thread/episode state into the new session).",
					"warning",
				);
				return;
			}
			await ctx.waitForIdle();
			const focus = args?.trim();
			const template = join(ctx.cwd, CONFIG_DIR_NAME, "handoff.md");
			const base = existsSync(template)
				? readFileSync(template, "utf8").trim()
				: [
						"Fresh-session handoff (context hygiene; the previous session neared its context budget).",
						"Read the project instructions (AGENTS.md and linked documents), locate the current plan/progress notes, and continue from the persisted state.",
					].join("\n");
			const kickoff = focus ? `${base}\nImmediate focus: ${focus}` : base;

			const parentSession = ctx.sessionManager.getSessionFile();
			await ctx.newSession({
				parentSession,
				withSession: async (fresh) => {
					await fresh.sendUserMessage(kickoff);
				},
			});
		},
	});
}
