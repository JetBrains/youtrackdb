/**
 * Slate — thread-weaving agent architecture for pi.
 *
 * M0 PROTOTYPE (see slate-dev/EXECPLAN.md, Milestone 0).
 * Registers a throwaway tool `slate_proto` that proves the core mechanics:
 *   - an in-process worker AgentSession with a persistent session file
 *     under .pi/slate/threads/
 *   - recursion guard: workers load NO extensions (noExtensions: true),
 *     so they can never see slate tools
 *   - streaming progress into the orchestrator TUI via onUpdate
 *   - resume: pass `sessionFile` from a prior result to continue the same
 *     worker with its accumulated context
 *   - abort propagation from the orchestrator's Esc to the worker
 *
 * This file is replaced by the real architecture in M1.
 */

import { mkdirSync } from "node:fs";
import { resolve } from "node:path";
import {
	AuthStorage,
	createAgentSession,
	DefaultResourceLoader,
	getAgentDir,
	ModelRegistry,
	SessionManager,
	type ExtensionAPI,
} from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

const WORKER_PREAMBLE = [
	"You are a worker thread executing ONE bounded action for an orchestrator.",
	"Do the action fully, then stop.",
	"Your final message must state: what you did, what you found, files you touched,",
	"and anything the orchestrator must know.",
].join(" ");

const WORKER_TOOLS = ["read", "bash", "edit", "write", "grep", "find", "ls"];

export default function (pi: ExtensionAPI) {
	pi.registerTool({
		name: "slate_proto",
		label: "Slate Proto (M0)",
		description:
			"M0 prototype: execute one bounded task in a persistent in-process worker session " +
			"with isolated context. Pass `sessionFile` from a previous result to resume that " +
			"worker and its accumulated context.",
		parameters: Type.Object({
			task: Type.String({ description: "The single bounded task for the worker" }),
			sessionFile: Type.Optional(
				Type.String({
					description: "Worker session file from a prior slate_proto result (resumes that worker)",
				}),
			),
		}),

		async execute(_toolCallId, params, signal, onUpdate, ctx) {
			const threadsDir = resolve(ctx.cwd, ".pi/slate/threads");
			mkdirSync(threadsDir, { recursive: true });

			const agentDir = getAgentDir();
			const authStorage = AuthStorage.create();
			const modelRegistry = ModelRegistry.create(authStorage);

			// Recursion guard: no extensions (=> no slate tools), no skills/prompts/themes.
			const loader = new DefaultResourceLoader({
				cwd: ctx.cwd,
				agentDir,
				noExtensions: true,
				noSkills: true,
				noPromptTemplates: true,
				noThemes: true,
				appendSystemPrompt: [WORKER_PREAMBLE],
			});
			await loader.reload();

			const sessionManager = params.sessionFile
				? SessionManager.open(params.sessionFile)
				: SessionManager.create(ctx.cwd, threadsDir);

			const { session } = await createAgentSession({
				cwd: ctx.cwd,
				agentDir,
				authStorage,
				modelRegistry,
				model: ctx.model,
				tools: WORKER_TOOLS,
				resourceLoader: loader,
				sessionManager,
			});

			const progress: string[] = [];
			let turns = 0;
			const emit = () => {
				onUpdate?.({
					content: [{ type: "text", text: progress.slice(-8).join("\n") || "(starting...)" }],
					details: { sessionFile: session.sessionFile, turns, progress },
				});
			};

			const unsubscribe = session.subscribe((event) => {
				if (event.type === "tool_execution_start") {
					progress.push(`→ ${event.toolName}`);
					emit();
				} else if (event.type === "message_end" && event.message.role === "assistant") {
					turns++;
					const text = event.message.content
						.filter((c: { type: string }) => c.type === "text")
						.map((c: { text?: string }) => c.text ?? "")
						.join(" ")
						.trim();
					if (text) {
						progress.push(text.length > 100 ? `${text.slice(0, 100)}...` : text);
						emit();
					}
				}
			});

			const onAbort = () => void session.abort();
			if (signal?.aborted) throw new Error("Aborted before worker start");
			signal?.addEventListener("abort", onAbort, { once: true });

			try {
				await session.prompt(params.task);

				if (signal?.aborted) throw new Error("Worker aborted by orchestrator");

				// Extract the worker's final assistant text.
				let final = "";
				for (let i = session.messages.length - 1; i >= 0; i--) {
					const m = session.messages[i];
					if (m.role === "assistant") {
						const text = m.content
							.filter((c: { type: string }) => c.type === "text")
							.map((c: { text?: string }) => c.text ?? "")
							.join("\n")
							.trim();
						if (text) {
							final = text;
							break;
						}
					}
				}

				return {
					content: [
						{
							type: "text",
							text: `${final || "(no output)"}\n\n[worker sessionFile: ${session.sessionFile}]`,
						},
					],
					details: { sessionFile: session.sessionFile, turns, progress },
				};
			} finally {
				unsubscribe();
				signal?.removeEventListener("abort", onAbort);
				session.dispose();
			}
		},
	});
}
