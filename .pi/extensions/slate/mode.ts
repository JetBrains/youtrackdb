/**
 * Orchestrator mode (ExecPlan M2, D4).
 *
 * `/slate` toggles orchestrator mode:
 *   - active tools restricted to read-only + slate tools (no bash/edit/write):
 *     delegation becomes the natural behavior;
 *   - the thread-weaving doctrine is appended to the system prompt each turn;
 *   - a widget above the editor shows live thread status;
 *   - the mode persists in slate state and is re-applied on session restore.
 */

import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import type { SlateStore } from "./state.ts";

const ORCHESTRATOR_TOOLS = ["read", "grep", "find", "ls", "thread", "threads", "episode"];

const DOCTRINE = `

# Slate orchestrator mode

You are the orchestrator of a thread-weaving system. You strategize; worker
threads execute. Rules:

1. Do tactical work ONLY by dispatching bounded actions via the \`thread\` tool
   (one action = one clear, completable task). You cannot edit files or run
   commands yourself.
2. Dispatch independent actions in PARALLEL by emitting several \`thread\`
   calls in one turn. Never serialize what can run concurrently.
3. Reuse a thread for follow-up actions in the same work stream — it
   remembers its prior episodes. Create a new thread for a new work stream.
4. Compose context by reference: pass prior episode ids in \`context\` instead
   of restating their content.
5. Your read-only tools (read/grep/find/ls) are for cheap orientation only;
   anything substantial goes to a thread.
6. After every episode, update your strategy. Episodes marked STATUS: FAILED
   require adaptation, not blind retry.
7. Keep your own messages strategic: goals, task routing, synthesis.`;

export function registerSlateMode(pi: ExtensionAPI, store: SlateStore): void {
	let savedTools: string[] | undefined;
	let uiCtx: ExtensionContext | undefined;

	const updateWidget = () => {
		if (!uiCtx?.hasUI) return;
		if (!store.orchestratorMode) {
			uiCtx.ui.setWidget("slate", undefined);
			uiCtx.ui.setStatus("slate", undefined);
			return;
		}
		uiCtx.ui.setStatus("slate", "slate: orchestrator");
		const threads = [...store.threads.values()];
		const lines = [
			`slate ⋅ orchestrator mode ⋅ ${threads.length} thread${threads.length === 1 ? "" : "s"}`,
			...threads.map(
				(t) =>
					`  ${t.status === "running" ? "⏳" : "·"} ${t.id} ${t.name} [${t.status}] ${t.episodeIds.length} episode${t.episodeIds.length === 1 ? "" : "s"}`,
			),
		];
		uiCtx.ui.setWidget("slate", lines);
	};

	const setMode = (on: boolean, persist: boolean) => {
		if (on && !store.orchestratorMode) {
			savedTools = pi.getActiveTools();
			pi.setActiveTools(ORCHESTRATOR_TOOLS);
		} else if (!on && store.orchestratorMode) {
			pi.setActiveTools(savedTools ?? [...pi.getAllTools().map((t) => t.name)]);
			savedTools = undefined;
		}
		store.orchestratorMode = on;
		if (persist) store.save();
		updateWidget();
	};

	// Widget refresh whenever slate state changes (dispatch start/end, new threads).
	store.onDidChange = updateWidget;

	pi.registerCommand("slate", {
		description: "Toggle Slate orchestrator mode (on/off; no arg toggles)",
		handler: async (args, ctx) => {
			uiCtx = ctx;
			const arg = args?.trim().toLowerCase();
			const target = arg === "on" ? true : arg === "off" ? false : !store.orchestratorMode;
			setMode(target, true);
			if (ctx.hasUI) {
				ctx.ui.notify(
					target
						? "Slate orchestrator mode ON — tactical tools removed; delegate via the thread tool."
						: "Slate orchestrator mode OFF — full toolset restored.",
					"info",
				);
			}
		},
	});

	pi.on("before_agent_start", async (event) => {
		if (!store.orchestratorMode) return;
		return { systemPrompt: event.systemPrompt + DOCTRINE };
	});

	pi.on("session_start", async (_event, ctx) => {
		uiCtx = ctx;
		// store.restore() ran in index.ts before this handler is invoked in
		// registration order; re-apply the persisted mode to the fresh runtime.
		if (store.orchestratorMode) {
			savedTools = pi.getActiveTools();
			pi.setActiveTools(ORCHESTRATOR_TOOLS);
		}
		updateWidget();
	});
}
