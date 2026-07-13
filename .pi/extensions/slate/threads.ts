/**
 * ThreadManager: dispatch lifecycle (ExecPlan M1).
 *
 * One dispatch = one bounded action on one thread:
 *   resolve/create thread → per-thread FIFO queue → global semaphore →
 *   open/reuse worker session → inject context episodes → prompt →
 *   compress the new messages into an episode (ok or FAILED) → update store.
 *
 * Parallelism happens ACROSS threads (pi runs sibling tool calls
 * concurrently); a single thread is a serial work stream (D9).
 */

import { readFileSync } from "node:fs";
import type { ExtensionContext } from "@earendil-works/pi-coding-agent";
import { compressEpisode } from "./episodes.ts";
import type { EpisodeRecord, SlateConfig, SlateStore, ThreadRecord } from "./state.ts";
import { openWorkerSession, type WorkerSession } from "./worker.ts";

export interface DispatchOptions {
	threadId?: string;
	name?: string;
	task: string;
	contextEpisodeIds?: string[];
	model?: string;
	tools?: string[];
}

export interface UsageStats {
	turns: number;
	input: number;
	output: number;
	cost: number;
	contextTokens: number;
}

export interface DispatchProgress {
	threadId: string;
	threadName: string;
	lines: string[];
	usage: UsageStats;
	done: boolean;
	status?: "ok" | "failed";
}

export interface DispatchResult {
	episodeText: string;
	episode: EpisodeRecord;
	thread: ThreadRecord;
	usage: UsageStats;
}

class Semaphore {
	private waiters: Array<() => void> = [];
	private active = 0;
	constructor(private limit: number) {}
	async acquire(): Promise<void> {
		if (this.active < this.limit) {
			this.active++;
			return;
		}
		// Wait for a slot transferred directly by release(); do NOT increment
		// here — the releasing side keeps `active` unchanged when handing over.
		await new Promise<void>((r) => this.waiters.push(r));
	}
	release(): void {
		const next = this.waiters.shift();
		if (next) {
			// Transfer the slot to the next waiter without decrementing:
			// the slot never becomes observable as free.
			next();
		} else {
			this.active--;
		}
	}
}

export class ThreadManager {
	private live = new Map<string, WorkerSession>();
	private queues = new Map<string, Promise<unknown>>();
	private semaphore: Semaphore;

	constructor(
		private store: SlateStore,
		private config: SlateConfig,
	) {
		this.semaphore = new Semaphore(config.maxConcurrent ?? 4);
	}

	getConfig(): SlateConfig {
		return this.config;
	}

	async dispatch(
		opts: DispatchOptions,
		ctx: ExtensionContext,
		signal: AbortSignal | undefined,
		onProgress?: (p: DispatchProgress) => void,
	): Promise<DispatchResult> {
		// Pause blocks only NEW dispatches — in-flight ones already hold their
		// queue slot and are allowed to finish (their episodes still get written).
		if (this.store.paused) {
			throw new Error(
				"Slate is paused for handoff: the context budget was exceeded and new dispatches are rejected. " +
					"Reply to the user with a handoff brief (overall goal, per-thread state with episode ids, immediate next actions) " +
					"and ask them to run /slate handoff [focus] to continue in a fresh session.",
			);
		}
		const thread = this.resolveThread(opts);

		// Per-thread FIFO: chain onto the previous dispatch for this thread.
		const prev = this.queues.get(thread.id) ?? Promise.resolve();
		const run = prev
			.catch(() => undefined) // a failed predecessor must not poison the queue
			.then(() => this.runDispatch(thread, opts, ctx, signal, onProgress));
		this.queues.set(thread.id, run);
		return run;
	}

	private resolveThread(opts: DispatchOptions): ThreadRecord {
		if (opts.threadId) {
			const existing = this.store.threads.get(opts.threadId);
			if (!existing) {
				const known = [...this.store.threads.keys()].join(", ") || "none";
				throw new Error(`Unknown thread "${opts.threadId}". Known threads: ${known}. Omit "thread" to create a new one.`);
			}
			return existing;
		}
		const id = this.store.nextThreadId();
		const record: ThreadRecord = {
			id,
			name: opts.name?.trim() || id,
			sessionFile: "",
			status: "idle",
			model: opts.model,
			episodeIds: [],
			episodeSeq: 0,
			createdAt: Date.now(),
			updatedAt: Date.now(),
		};
		this.store.threads.set(id, record);
		this.store.save();
		return record;
	}

	private buildPrompt(opts: DispatchOptions): string {
		const contextIds = opts.contextEpisodeIds ?? [];
		if (contextIds.length === 0) return opts.task;
		const parts: string[] = ["## Context from prior episodes (injected by the orchestrator)", ""];
		for (const id of contextIds) {
			const episode = this.store.episodes.get(id);
			if (!episode) throw new Error(`Unknown episode "${id}". Known: ${[...this.store.episodes.keys()].join(", ") || "none"}`);
			parts.push(readFileSync(episode.file, "utf8").trim(), "");
		}
		parts.push("## Action", "", opts.task);
		return parts.join("\n");
	}

	private async runDispatch(
		thread: ThreadRecord,
		opts: DispatchOptions,
		ctx: ExtensionContext,
		signal: AbortSignal | undefined,
		onProgress?: (p: DispatchProgress) => void,
	): Promise<DispatchResult> {
		const prompt = this.buildPrompt(opts); // may throw on unknown episode ids (before any state change)
		await this.semaphore.acquire();
		try {
			return await this.runDispatchInner(thread, opts, prompt, ctx, signal, onProgress);
		} finally {
			this.semaphore.release();
		}
	}

	private async runDispatchInner(
		thread: ThreadRecord,
		opts: DispatchOptions,
		prompt: string,
		ctx: ExtensionContext,
		signal: AbortSignal | undefined,
		onProgress?: (p: DispatchProgress) => void,
	): Promise<DispatchResult> {
		const usage: UsageStats = { turns: 0, input: 0, output: 0, cost: 0, contextTokens: 0 };
		const lines: string[] = [];
		const emit = (done: boolean, status?: "ok" | "failed") =>
			onProgress?.({ threadId: thread.id, threadName: thread.name, lines, usage, done, status });

		let session: WorkerSession | undefined;
		let unsubscribe: (() => void) | undefined;
		let onAbort: (() => void) | undefined;
		let messagesBefore = 0;
		let status: "ok" | "failed" = "ok";
		let diagnostics: string | undefined;

		try {
			thread.status = "running";
			thread.updatedAt = Date.now();
			this.store.save();
			emit(false);

			session = this.live.get(thread.id);
			if (!session) {
				session = await openWorkerSession({
					ctx,
					sessionFile: thread.sessionFile || undefined,
					model: thread.model,
					tools: opts.tools ?? this.config.workerTools,
					promptDocs: this.config.workerPromptDocs,
				});
				this.live.set(thread.id, session);
				if (!thread.sessionFile && session.sessionFile) {
					thread.sessionFile = session.sessionFile;
					this.store.save();
				}
			}

			messagesBefore = session.messages.length;

			unsubscribe = session.subscribe((event: { type: string; [k: string]: unknown }) => {
				if (event.type === "tool_execution_start") {
					lines.push(`→ ${(event as unknown as { toolName: string }).toolName}`);
					emit(false);
				} else if (event.type === "message_end") {
					const msg = (event as unknown as { message: { role: string; content?: Array<{ type: string; text?: string }>; usage?: { input?: number; output?: number; totalTokens?: number; cost?: { total?: number } }; stopReason?: string; errorMessage?: string } }).message;
					if (msg.role !== "assistant") return;
					usage.turns++;
					usage.input += msg.usage?.input ?? 0;
					usage.output += msg.usage?.output ?? 0;
					usage.cost += msg.usage?.cost?.total ?? 0;
					usage.contextTokens = msg.usage?.totalTokens ?? usage.contextTokens;
					const text = (msg.content ?? [])
						.filter((c) => c.type === "text")
						.map((c) => c.text ?? "")
						.join(" ")
						.trim();
					if (text) lines.push(text.length > 120 ? `${text.slice(0, 120)}...` : text);
					if (msg.stopReason === "error" || msg.stopReason === "aborted") {
						status = "failed";
						diagnostics = msg.errorMessage ?? `worker stopReason: ${msg.stopReason}`;
					}
					emit(false);
				}
			});

			if (signal?.aborted) throw new Error("aborted before worker start");
			onAbort = () => void session?.abort();
			signal?.addEventListener("abort", onAbort, { once: true });

			await session.prompt(prompt);

			if (signal?.aborted) {
				status = "failed";
				diagnostics = diagnostics ?? "aborted by orchestrator";
			}
		} catch (error) {
			status = "failed";
			diagnostics = error instanceof Error ? error.message : String(error);
		} finally {
			unsubscribe?.();
			if (onAbort) signal?.removeEventListener("abort", onAbort);
		}

		const actionMessages = session ? session.messages.slice(messagesBefore) : [];

		const episodeId = `${thread.id}.e${++thread.episodeSeq}`;
		const compressed = await compressEpisode({
			ctx,
			episodeId,
			threadId: thread.id,
			threadName: thread.name,
			task: opts.task,
			status,
			diagnostics,
			messages: actionMessages as unknown[],
			workerModel: session?.model ? { provider: session.model.provider, id: session.model.id } : undefined,
			configuredModel: this.config.episodeModel,
			signal: signal?.aborted ? undefined : signal,
		});

		const episode: EpisodeRecord = {
			id: episodeId,
			threadId: thread.id,
			task: opts.task,
			status,
			file: compressed.file,
			createdAt: Date.now(),
		};
		this.store.episodes.set(episodeId, episode);
		thread.episodeIds.push(episodeId);
		thread.status = "idle";
		thread.updatedAt = Date.now();
		this.store.save();

		emit(true, status);

		return { episodeText: compressed.text, episode, thread, usage };
	}

	disposeAll(): void {
		for (const session of this.live.values()) {
			try {
				session.dispose();
			} catch {
				/* ignore */
			}
		}
		this.live.clear();
	}
}
