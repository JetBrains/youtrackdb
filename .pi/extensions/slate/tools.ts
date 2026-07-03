/**
 * Slate orchestrator tools (ExecPlan D3): thread / threads / episode.
 */

import { readFileSync } from "node:fs";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { renderThreadCall, renderThreadResult } from "./render.ts";
import type { SlateStore } from "./state.ts";
import type { DispatchProgress, ThreadManager } from "./threads.ts";

export function registerSlateTools(pi: ExtensionAPI, store: SlateStore, getManager: () => ThreadManager): void {
	pi.registerTool({
		name: "thread",
		label: "Thread",
		description: [
			"Dispatch ONE bounded action to a persistent worker thread and receive back an episode",
			"(a compressed, structured record of what the thread did).",
			"Omit `thread` to create a new thread (give it a short `name`); pass an existing thread id",
			"to continue that thread — it retains the context of all its previous actions.",
			"Use `context` to inject prior episodes (by id, from any thread) into the action.",
			"Threads are serial; to parallelize, dispatch to DIFFERENT threads in one message.",
			"`model` (\"provider/id\") and `tools` apply only when creating a new thread.",
			"An episode header of STATUS: FAILED means the action failed — read it and adapt.",
		].join(" "),
		promptSnippet: "Dispatch one bounded action to a persistent worker thread; returns a compressed episode",
		promptGuidelines: [
			"Use the thread tool to delegate bounded tactical actions to worker threads; dispatch independent actions to different threads in the same message to run them in parallel.",
			"Pass prior episode ids in the thread tool's context parameter instead of restating their content.",
		],
		parameters: Type.Object({
			thread: Type.Optional(Type.String({ description: "Existing thread id to continue (e.g. \"t1\")" })),
			name: Type.Optional(Type.String({ description: "Short name for a NEW thread (e.g. \"recon\")" })),
			task: Type.String({ description: "The single bounded action to execute" }),
			context: Type.Optional(
				Type.Array(Type.String(), { description: "Episode ids to inject as context (e.g. [\"t1.e2\"])" }),
			),
			model: Type.Optional(Type.String({ description: "Worker model \"provider/id\" (new threads only)" })),
			tools: Type.Optional(Type.Array(Type.String(), { description: "Worker tool allowlist (new threads only)" })),
		}),

		async execute(_toolCallId, params, signal, onUpdate, ctx) {
			const onProgress = (p: DispatchProgress) => {
				onUpdate?.({
					content: [
						{
							type: "text",
							text: `[${p.threadName}] ${p.done ? (p.status ?? "done") : "running"}\n${p.lines.slice(-8).join("\n")}`,
						},
					],
					details: { threadId: p.threadId, threadName: p.threadName, lines: p.lines, usage: p.usage, done: p.done },
				});
			};

			const result = await getManager().dispatch(
				{
					threadId: params.thread,
					name: params.name,
					task: params.task,
					contextEpisodeIds: params.context,
					model: params.model,
					tools: params.tools,
				},
				ctx,
				signal,
				onProgress,
			);

			const headline = `[episode ${result.episode.id} | thread ${result.thread.id} "${result.thread.name}" | STATUS: ${result.episode.status === "ok" ? "OK" : "FAILED"}]`;
			return {
				content: [{ type: "text", text: `${headline}\n\n${result.episodeText}` }],
				details: {
					threadId: result.thread.id,
					threadName: result.thread.name,
					episodeId: result.episode.id,
					status: result.episode.status,
					episodeFile: result.episode.file,
					usage: result.usage,
					done: true,
				},
			};
		},

		renderCall(args, theme) {
			return renderThreadCall(args as never, theme as never);
		},
		renderResult(result, options, theme) {
			return renderThreadResult(result as never, options as never, theme as never);
		},
	});

	pi.registerTool({
		name: "threads",
		label: "Threads",
		description:
			"List all worker threads: id, name, status, episodes so far, and last activity. " +
			"Use this to decide whether to continue an existing thread or create a new one.",
		promptSnippet: "List worker threads and their episodes",
		parameters: Type.Object({}),
		async execute() {
			const threads = [...store.threads.values()];
			if (threads.length === 0) {
				return { content: [{ type: "text", text: "No threads yet. Use the thread tool to create one." }], details: { count: 0 } };
			}
			const lines = threads.map((t) => {
				const episodes = t.episodeIds.length > 0 ? t.episodeIds.join(", ") : "(none)";
				return `${t.id} "${t.name}" [${t.status}]${t.model ? ` model=${t.model}` : ""} — episodes: ${episodes} — updated ${new Date(t.updatedAt).toISOString()}`;
			});
			return { content: [{ type: "text", text: lines.join("\n") }], details: { count: threads.length } };
		},
	});

	pi.registerTool({
		name: "episode",
		label: "Episode",
		description: "Fetch the full text of a stored episode by id (e.g. \"t1.e2\").",
		promptSnippet: "Fetch a stored episode's full text by id",
		parameters: Type.Object({
			id: Type.String({ description: "Episode id, e.g. \"t1.e2\"" }),
		}),
		async execute(_toolCallId, params) {
			const episode = store.episodes.get(params.id);
			if (!episode) {
				const known = [...store.episodes.keys()].join(", ") || "none";
				throw new Error(`Unknown episode "${params.id}". Known episodes: ${known}`);
			}
			return {
				content: [{ type: "text", text: readFileSync(episode.file, "utf8") }],
				details: { id: episode.id, threadId: episode.threadId, status: episode.status },
			};
		},
	});
}
