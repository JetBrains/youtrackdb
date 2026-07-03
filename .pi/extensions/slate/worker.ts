/**
 * Worker sessions: in-process pi SDK AgentSessions with the recursion guard.
 *
 * A worker loads NO extensions, skills, prompt templates, or themes
 * (DefaultResourceLoader no* options) — so a worker can never see slate tools
 * (ExecPlan D7, depth-1 guard). Worker conversations persist under
 * .pi/slate/threads/*.jsonl and are reopened via SessionManager.open.
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
	type ExtensionContext,
} from "@earendil-works/pi-coding-agent";

export type WorkerSession = Awaited<ReturnType<typeof createAgentSession>>["session"];

export const DEFAULT_WORKER_TOOLS = ["read", "bash", "edit", "write", "grep", "find", "ls"];

const WORKER_PREAMBLE = [
	"You are a worker thread executing ONE bounded action for an orchestrator.",
	"Do the action fully, then stop.",
	"Your final message must state: what you did, what you found, files you touched,",
	"and anything the orchestrator must know.",
].join(" ");

export function threadsDir(cwd: string): string {
	return resolve(cwd, ".pi/slate/threads");
}

export function episodesDir(cwd: string): string {
	return resolve(cwd, ".pi/slate/episodes");
}

/** Resolve a "provider/id" model string against the registry; throws a clear error if unknown. */
export function resolveModel(ctx: ExtensionContext, spec: string) {
	const slash = spec.indexOf("/");
	if (slash <= 0) throw new Error(`Invalid model spec "${spec}" — expected "provider/id"`);
	const model = ctx.modelRegistry.find(spec.slice(0, slash), spec.slice(slash + 1));
	if (!model) throw new Error(`Unknown model "${spec}" — not found in the model registry`);
	return model;
}

export async function openWorkerSession(opts: {
	ctx: ExtensionContext;
	sessionFile?: string; // resume when provided, else create new under .pi/slate/threads/
	model?: string; // "provider/id"
	tools?: string[];
}): Promise<WorkerSession> {
	const { ctx } = opts;
	const dir = threadsDir(ctx.cwd);
	mkdirSync(dir, { recursive: true });

	const agentDir = getAgentDir();
	const authStorage = AuthStorage.create();
	const modelRegistry = ModelRegistry.create(authStorage);

	const loader = new DefaultResourceLoader({
		cwd: ctx.cwd,
		agentDir,
		noExtensions: true, // recursion guard (D7)
		noSkills: true,
		noPromptTemplates: true,
		noThemes: true,
		appendSystemPrompt: [WORKER_PREAMBLE],
	});
	await loader.reload();

	const model = opts.model ? resolveModel(ctx, opts.model) : ctx.model;

	const sessionManager = opts.sessionFile
		? SessionManager.open(opts.sessionFile)
		: SessionManager.create(ctx.cwd, dir);

	const { session } = await createAgentSession({
		cwd: ctx.cwd,
		agentDir,
		authStorage,
		modelRegistry,
		model: model ?? undefined,
		tools: opts.tools && opts.tools.length > 0 ? opts.tools : DEFAULT_WORKER_TOOLS,
		resourceLoader: loader,
		sessionManager,
	});
	return session;
}
