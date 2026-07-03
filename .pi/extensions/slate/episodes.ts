/**
 * Episode compression (ExecPlan D5, D6, D8).
 *
 * An episode is the compressed, structured record of ONE completed thread
 * action. It is produced by a single LLM call over the messages generated
 * during that action, stored at .pi/slate/episodes/<id>.md, and returned to
 * the orchestrator as the tool result — it IS the synchronization mechanism.
 *
 * Compressor model resolution (D5): config episodeModel → newest available
 * Anthropic Sonnet → the worker's own model.
 */

import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { complete } from "@earendil-works/pi-ai/compat";
import {
	convertToLlm,
	serializeConversation,
	type ExtensionContext,
} from "@earendil-works/pi-coding-agent";

const MAX_TRANSCRIPT_CHARS = 300_000;
const COMPRESSOR_MAX_TOKENS = 4096;

const EPISODE_SECTIONS = [
	"## Intent",
	"## Actions Taken",
	"## Key Findings",
	"## Artifacts Changed",
	"## Open Issues",
	"## Handoff Notes",
];

function compressorPrompt(task: string, transcript: string): string {
	return `You are compressing one completed action of a worker thread into an episode:
a durable, structured record another agent will rely on WITHOUT seeing the
raw transcript. Retain decisions, discoveries, exact identifiers (paths,
symbols, commands, versions, error messages) and outcomes. Drop tactical
noise (retries, scrolling, dead ends — unless a dead end is itself a finding).
Note: the transcript covers only THIS action; the thread may legitimately use
context from its earlier actions that you cannot see — do not flag that as
fabrication.
Target 300-800 words. Output ONLY markdown with EXACTLY these sections:

${EPISODE_SECTIONS.join("\n")}

The action's task was:
${task}

Transcript:
${transcript}`;
}

async function resolveCompressorModel(
	ctx: ExtensionContext,
	configured: string | undefined,
	workerModel: { provider: string; id: string } | undefined,
) {
	if (configured) {
		const slash = configured.indexOf("/");
		if (slash > 0) {
			const m = ctx.modelRegistry.find(configured.slice(0, slash), configured.slice(slash + 1));
			if (m) return m;
		}
	}
	try {
		const available = await ctx.modelRegistry.getAvailable();
		const sonnets = available
			.filter((m: { provider: string; id: string }) => m.provider === "anthropic" && m.id.includes("sonnet"))
			.sort((a: { id: string }, b: { id: string }) => b.id.localeCompare(a.id));
		if (sonnets.length > 0) return sonnets[0];
	} catch {
		/* fall through */
	}
	if (workerModel) return ctx.modelRegistry.find(workerModel.provider, workerModel.id) ?? undefined;
	return undefined;
}

export interface CompressEpisodeOptions {
	ctx: ExtensionContext;
	episodeId: string;
	threadId: string;
	threadName: string;
	task: string;
	status: "ok" | "failed";
	diagnostics?: string; // failure diagnostics (D6)
	messages: unknown[]; // AgentMessages produced during this action
	workerModel?: { provider: string; id: string };
	configuredModel?: string;
	signal?: AbortSignal;
}

export interface CompressedEpisode {
	text: string; // full episode markdown (header + body) as returned to the orchestrator
	file: string;
	compressor: string; // model used, or "(uncompressed fallback)"
}

function lastAssistantText(messages: unknown[]): string {
	for (let i = messages.length - 1; i >= 0; i--) {
		const m = messages[i] as { role?: string; content?: Array<{ type: string; text?: string }> };
		if (m.role === "assistant" && Array.isArray(m.content)) {
			const text = m.content
				.filter((c) => c.type === "text")
				.map((c) => c.text ?? "")
				.join("\n")
				.trim();
			if (text) return text;
		}
	}
	return "(no output)";
}

export async function compressEpisode(opts: CompressEpisodeOptions): Promise<CompressedEpisode> {
	const { ctx } = opts;
	const dir = join(ctx.cwd, ".pi/slate/episodes");
	mkdirSync(dir, { recursive: true });
	const file = join(dir, `${opts.episodeId}.md`);

	let body: string | undefined;
	let compressor = "(uncompressed fallback)";

	try {
		const model = await resolveCompressorModel(ctx, opts.configuredModel, opts.workerModel);
		if (model) {
			const auth = await ctx.modelRegistry.getApiKeyAndHeaders(model);
			if (auth.ok && auth.apiKey) {
				let transcript = serializeConversation(convertToLlm(opts.messages as never));
				if (transcript.length > MAX_TRANSCRIPT_CHARS) {
					transcript = `[transcript head truncated]\n...${transcript.slice(-MAX_TRANSCRIPT_CHARS)}`;
				}
				if (opts.diagnostics) {
					transcript += `\n\n[dispatch diagnostics: ${opts.diagnostics}]`;
				}
				const response = await complete(
					model,
					{
						messages: [
							{
								role: "user" as const,
								content: [{ type: "text" as const, text: compressorPrompt(opts.task, transcript) }],
								timestamp: Date.now(),
							},
						],
					},
					{
						apiKey: auth.apiKey,
						headers: auth.headers,
						env: auth.env,
						maxTokens: COMPRESSOR_MAX_TOKENS,
						signal: opts.signal,
					},
				);
				const text = response.content
					.filter((c: { type: string }): c is { type: "text"; text: string } => c.type === "text")
					.map((c: { text: string }) => c.text)
					.join("\n")
					.trim();
				if (text) {
					body = text;
					compressor = `${model.provider}/${model.id}`;
				}
			}
		}
	} catch {
		/* fall back below */
	}

	if (!body) {
		body = [
			"## Intent",
			opts.task,
			"",
			"## Key Findings",
			"(episode compression unavailable — raw final worker output follows)",
			"",
			lastAssistantText(opts.messages).slice(0, 8000),
			...(opts.diagnostics ? ["", "## Open Issues", opts.diagnostics] : []),
		].join("\n");
	}

	const statusLabel = opts.status === "ok" ? "OK" : "FAILED";
	const header = [
		`# Episode ${opts.episodeId} — thread ${opts.threadId} (${opts.threadName}) — STATUS: ${statusLabel}`,
		"",
		`> task: ${opts.task.replace(/\s+/g, " ").slice(0, 200)}`,
		`> date: ${new Date().toISOString()} | compressor: ${compressor}`,
		...(opts.status === "failed" && opts.diagnostics ? [`> failure: ${opts.diagnostics.slice(0, 300)}`] : []),
		"",
		"",
	].join("\n");

	const text = `${header}${body}\n`;
	writeFileSync(file, text, "utf8");
	return { text, file, compressor };
}
