/**
 * Auto-pause + handoff: context-budget discipline for the orchestrator.
 *
 * Monitoring: after every turn, when orchestrator mode is on and context usage
 * crosses pauseThresholdPercent (default 40), the store is paused — the thread
 * tool then rejects NEW dispatches (in-flight ones finish) — and the
 * orchestrator is steered to produce a handoff brief for the user.
 *
 * /slate handoff [focus] → startHandoff(): captures the orchestrator's last
 * assistant message as the brief, writes .pi/slate/pending-handoff.json
 * (state snapshot + brief + parent session), and opens a fresh session.
 *
 * Adoption: session replacement tears down this extension instance, so
 * in-memory state cannot cross over; and the fresh session's own file has no
 * slate-state entries for restore() to find (they live in the parent's file).
 * The pending file bridges the gap: the NEW instance's session_start handler
 * adopts the snapshot when the fresh session's parentSession header matches.
 */

import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import type { ExtensionAPI, ExtensionCommandContext, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { orchestratorCostUsd, type SlateConfig, type SlateSnapshot, type SlateStore } from "./state.ts";

const DEFAULT_PAUSE_THRESHOLD_PERCENT = 40;
/** A pending-handoff file older than this cannot belong to an in-flight handoff. */
const PENDING_MAX_AGE_MS = 15 * 60 * 1000;
const BRIEF_MAX_CHARS = 6000;

interface PendingHandoff {
	parentSession: string | undefined; // undefined = in-memory session; adoption then never matches
	createdAt: number;
	brief: string;
	snapshot: SlateSnapshot;
}

export interface SlateHandoffHooks {
	startHandoff(ctx: ExtensionCommandContext, focus?: string): Promise<void>;
}

function pendingFile(cwd: string): string {
	return join(cwd, ".pi/slate/pending-handoff.json");
}

/** Last assistant message text on the current branch (the handoff brief). */
function lastAssistantText(ctx: ExtensionCommandContext): string {
	let text = "";
	for (const entry of ctx.sessionManager.getBranch()) {
		const e = entry as {
			type: string;
			message?: { role?: string; content?: string | Array<{ type: string; text?: string }> };
		};
		if (e.type !== "message" || e.message?.role !== "assistant") continue;
		const content = e.message.content;
		// Entries come from JSON on disk; content may not match the declared
		// shape. Skip anything that is neither string nor block array.
		if (typeof content !== "string" && !Array.isArray(content)) continue;
		const t = (typeof content === "string"
			? content
			: content
					.filter((c) => c.type === "text")
					.map((c) => c.text ?? "")
					.join("\n")
		).trim();
		if (t) text = t;
	}
	return text.length > BRIEF_MAX_CHARS ? `${text.slice(0, BRIEF_MAX_CHARS)}\n[... brief truncated]` : text;
}

function buildKickoff(cwd: string, brief: string, focus?: string): string {
	const template = join(cwd, ".pi/slate-handoff.md");
	const base = existsSync(template)
		? readFileSync(template, "utf8").trim()
		: [
				"Slate orchestrator handoff (context hygiene; the previous orchestrator exceeded its context budget).",
				"Orchestrator mode and all worker threads/episodes from the previous session are already restored:",
				"use `threads` to list them and `episode` to fetch details. Continue the work.",
			].join("\n");
	const parts = [base];
	if (brief) parts.push("", "## Handoff brief from the previous orchestrator", "", brief);
	if (focus) parts.push("", `Immediate focus: ${focus}`);
	return parts.join("\n");
}

export function registerSlateHandoff(
	pi: ExtensionAPI,
	store: SlateStore,
	getConfig: () => SlateConfig,
): SlateHandoffHooks {
	const checkBudget = (ctx: ExtensionContext) => {
		if (!store.orchestratorMode || store.paused) return;
		const percent = ctx.getContextUsage()?.percent;
		if (percent == null) return;
		// .pi/slate.json is user-edited: accept only finite (0, 100] thresholds.
		const configured = getConfig().pauseThresholdPercent;
		const threshold =
			typeof configured === "number" && Number.isFinite(configured) && configured > 0 && configured <= 100
				? configured
				: DEFAULT_PAUSE_THRESHOLD_PERCENT;
		if (percent < threshold) return;

		store.paused = true;
		store.save();
		const pct = Math.round(percent);
		if (ctx.hasUI) {
			ctx.ui.notify(
				`slate: context at ${pct}% (budget ${threshold}%) — paused. Run /slate handoff [focus] to continue in a fresh session.`,
				"warning",
			);
		}
		pi.sendMessage(
			{
				customType: "slate-pause",
				content: [
					`[slate] Context is at ${pct}% — over the ${threshold}% budget. Slate auto-paused: the thread tool now REJECTS new dispatches.`,
					"Finish nothing new. Reply to the user with:",
					"(1) a concise HANDOFF BRIEF — overall goal, per-thread state with episode ids, immediate next actions;",
					"(2) instructions: run /slate handoff [optional focus] to continue in a fresh session where all threads and episodes are restored automatically;",
					"alternatively, start a new pi session manually, run /slate on, and have the new orchestrator read the episode files under .pi/slate/episodes/.",
				].join("\n"),
				display: true,
			},
			{ deliverAs: "steer", triggerTurn: true },
		);
	};

	pi.on("turn_end", async (_event, ctx) => checkBudget(ctx));
	pi.on("agent_end", async (_event, ctx) => checkBudget(ctx));

	// Adopt a pending handoff into a fresh session. Registered AFTER index.ts's
	// restore handler (so a branch that already carries slate state wins) and
	// BEFORE mode.ts's (so its tool restriction sees the adopted mode).
	pi.on("session_start", async (_event, ctx) => {
		if (store.threads.size > 0 || store.orchestratorMode) return; // state already restored on this branch
		const file = pendingFile(ctx.cwd);
		try {
			if (!existsSync(file)) return;
			const pending = JSON.parse(readFileSync(file, "utf8")) as PendingHandoff;
			const age = Date.now() - (pending.createdAt ?? 0);
			const stale = age >= PENDING_MAX_AGE_MS;
			const matches = !!pending.parentSession && pending.parentSession === ctx.sessionManager.getHeader()?.parentSession;
			if (!matches || stale) {
				// Never adopt into an unrelated session; reap the file only once it
				// can no longer belong to an in-flight handoff.
				if (stale) rmSync(file, { force: true });
				return;
			}
			store.adoptSnapshot(pending.snapshot, ctx);
			store.paused = false;
			store.save();
			rmSync(file, { force: true });
			if (ctx.hasUI) {
				const t = store.threads.size;
				const e = store.episodes.size;
				ctx.ui.notify(
					`slate: handoff state restored (${t} thread${t === 1 ? "" : "s"}, ${e} episode${e === 1 ? "" : "s"}).`,
					"info",
				);
			}
		} catch {
			/* a broken pending file must never break session start */
		}
	});

	const startHandoff = async (ctx: ExtensionCommandContext, focus?: string): Promise<void> => {
		await ctx.waitForIdle();

		const brief = lastAssistantText(ctx);
		const parentSession = ctx.sessionManager.getSessionFile();
		const pending: PendingHandoff = {
			parentSession,
			createdAt: Date.now(),
			brief,
			// The successor starts unpaused and in orchestrator mode regardless of
			// the current (paused) state.
			snapshot: {
				...store.snapshot(),
				paused: false,
				orchestratorMode: true,
				// The successor's own branch sum starts at zero, so bank the parent's
				// billed orchestrator spend (plus anything already carried) — the
				// displayed total must survive repeated handoffs.
				carriedCostUsd: store.carriedCostUsd + orchestratorCostUsd(ctx),
			},
		};
		const file = pendingFile(ctx.cwd);
		mkdirSync(dirname(file), { recursive: true });
		writeFileSync(file, `${JSON.stringify(pending, null, 2)}\n`, "utf8");

		const kickoff = buildKickoff(ctx.cwd, brief, focus);
		// catch, NOT finally: on success the NEW session's adoption handler has
		// already consumed and deleted the pending file (session_start fires
		// inside newSession) — cleaning up there would be wrong.
		try {
			const { cancelled } = await ctx.newSession({
				parentSession,
				withSession: async (fresh) => {
					await fresh.sendUserMessage(kickoff);
				},
			});
			if (cancelled) {
				// Left behind, the pending file could be adopted by an unintended fork
				// or session sharing this parent within the 15-min window.
				rmSync(file, { force: true });
				store.paused = false;
				store.save();
				if (ctx.hasUI) ctx.ui.notify("slate: handoff cancelled — pause cleared, pending state removed.", "warning");
			}
		} catch (error) {
			try {
				rmSync(file, { force: true });
			} catch {
				/* ignore */
			}
			// Best-effort: if the replacement partially happened, the old pi/ctx
			// are stale and these calls themselves throw.
			try {
				store.paused = false;
				store.save();
				if (ctx.hasUI) {
					ctx.ui.notify(
						`slate: handoff failed — ${error instanceof Error ? error.message : String(error)}. Pause cleared; pending state removed.`,
						"error",
					);
				}
			} catch {
				/* stale pi/ctx after partial replacement */
			}
			throw error;
		}
	};

	return { startHandoff };
}
