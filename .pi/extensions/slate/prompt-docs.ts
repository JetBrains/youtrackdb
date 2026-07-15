/**
 * Role-guideline prompt docs (config: orchestratorPromptDocs / workerPromptDocs).
 *
 * Role-specific guidance is split out of AGENTS.md into per-role markdown
 * docs and injected into the matching role's system prompt only —
 * orchestrator docs while orchestrator mode is on (mode.ts), worker docs
 * into every worker session (worker.ts). Each role's always-loaded surface
 * then carries only what that role needs (context-as-RAM discipline, P10).
 * Missing/unreadable files and malformed config entries are skipped
 * silently, so the extension works before the docs exist.
 */

import { readFileSync } from "node:fs";
import { relative, resolve } from "node:path";

export const DEFAULT_ORCHESTRATOR_PROMPT_DOCS = [
	"docs-internal/agents/orchestrator-guidelines.md",
];
export const DEFAULT_WORKER_PROMPT_DOCS = ["docs-internal/agents/thread-guidelines.md"];

/**
 * Read each doc (relative paths resolved against cwd) and return one
 * formatted prompt block per file that exists; missing/unreadable/empty
 * files and non-string entries are skipped without error. Blocks carry NO
 * separators — each caller joins/prefixes for its own prompt shape.
 */
export function loadPromptDocs(cwd: string, paths: string[]): string[] {
	// The declared type is a runtime lie: callers pass values straight from
	// user-edited .pi/slate.json, so a whole-value shape error (e.g.
	// orchestratorPromptDocs: 42, or a string — which for...of would iterate
	// char by char) reaches this loop unchecked. Malformed config must never
	// break prompt assembly, so bail out to "no docs" instead of throwing.
	if (!Array.isArray(paths)) return [];
	const blocks: string[] = [];
	for (const path of paths) {
		// The whole per-entry body is guarded: paths come from user-edited
		// .pi/slate.json, and a throw escaping here (e.g. resolve() on a
		// non-string entry) would abort the caller's prompt hook — on the
		// orchestrator path that drops the ENTIRE systemPrompt modification
		// for the turn, doctrine included.
		try {
			if (typeof path !== "string") continue;
			const file = resolve(cwd, path);
			const content = readFileSync(file, "utf8").trim();
			if (!content) continue;
			blocks.push(`# Role guidelines (injected from ${relative(cwd, file)})\n\n${content}`);
		} catch {
			/* missing/unreadable doc → skip */
		}
	}
	return blocks;
}
