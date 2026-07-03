/**
 * TUI rendering for the `thread` tool (ExecPlan M3).
 *
 * Collapsed: one-line header (status icon, thread, task preview), streaming
 * progress lines while running, Key Findings digest + usage when done.
 * Expanded (Ctrl+O): full episode markdown + usage stats.
 */

import { getMarkdownTheme, keyHint } from "@earendil-works/pi-coding-agent";
import { Container, Markdown, Spacer, Text } from "@earendil-works/pi-tui";
import type { UsageStats } from "./threads.ts";

// Minimal structural theme type (avoids depending on exact Theme export).
type ThemeLike = {
	fg: (color: string, text: string) => string;
	bold: (text: string) => string;
};

interface ThreadCallArgs {
	thread?: string;
	name?: string;
	task?: string;
	context?: string[];
	model?: string;
}

interface ThreadDetails {
	threadId?: string;
	threadName?: string;
	episodeId?: string;
	status?: "ok" | "failed";
	lines?: string[];
	usage?: UsageStats;
	done?: boolean;
}

function formatTokens(n: number): string {
	if (n < 1000) return String(n);
	if (n < 1_000_000) return `${Math.round(n / 1000)}k`;
	return `${(n / 1_000_000).toFixed(1)}M`;
}

function formatUsage(u: UsageStats | undefined): string {
	if (!u) return "";
	const parts: string[] = [];
	if (u.turns) parts.push(`${u.turns} turn${u.turns > 1 ? "s" : ""}`);
	if (u.input) parts.push(`↑${formatTokens(u.input)}`);
	if (u.output) parts.push(`↓${formatTokens(u.output)}`);
	if (u.cost) parts.push(`$${u.cost.toFixed(4)}`);
	if (u.contextTokens) parts.push(`ctx:${formatTokens(u.contextTokens)}`);
	return parts.join(" ");
}

function contentText(result: { content?: Array<{ type: string; text?: string }> }): string {
	return (result.content ?? [])
		.filter((c) => c.type === "text")
		.map((c) => c.text ?? "")
		.join("\n");
}

/** Extract one `## Section` body from episode markdown. */
function extractSection(episode: string, section: string): string {
	const re = new RegExp(`^## ${section}\\s*$`, "m");
	const m = re.exec(episode);
	if (!m) return "";
	const rest = episode.slice(m.index + m[0].length);
	const next = rest.search(/^## /m);
	return (next >= 0 ? rest.slice(0, next) : rest).trim();
}

export function renderThreadCall(args: ThreadCallArgs, theme: ThemeLike) {
	let text = theme.fg("toolTitle", theme.bold("thread "));
	text += theme.fg("accent", args.thread ?? (args.name ? `new:"${args.name}"` : "new"));
	if (args.model) text += theme.fg("muted", ` [${args.model}]`);
	if (args.context && args.context.length > 0) text += theme.fg("muted", ` ⇐ ${args.context.join(", ")}`);
	const task = (args.task ?? "").replace(/\s+/g, " ");
	text += `\n  ${theme.fg("dim", task.length > 100 ? `${task.slice(0, 100)}...` : task)}`;
	return new Text(text, 0, 0);
}

export function renderThreadResult(
	result: { content?: Array<{ type: string; text?: string }>; details?: unknown },
	options: { expanded: boolean; isPartial?: boolean },
	theme: ThemeLike,
) {
	const details = (result.details ?? {}) as ThreadDetails;
	const name = details.threadName ?? details.threadId ?? "thread";

	// Streaming: show live progress lines.
	if (options.isPartial || !details.done) {
		const lines = (details.lines ?? []).slice(-6);
		let text = `${theme.fg("warning", "⏳")} ${theme.fg("toolTitle", theme.bold(name))} ${theme.fg("muted", "running")}`;
		for (const line of lines) {
			text += `\n  ${theme.fg("dim", line.length > 110 ? `${line.slice(0, 110)}...` : line)}`;
		}
		return new Text(text, 0, 0);
	}

	const failed = details.status === "failed";
	const icon = failed ? theme.fg("error", "✗") : theme.fg("success", "✓");
	const episodeLabel = `${details.episodeId ?? "episode"}${failed ? " FAILED" : ""}`;
	const usageStr = formatUsage(details.usage);
	const full = contentText(result);

	if (options.expanded) {
		const container = new Container();
		container.addChild(
			new Text(
				`${icon} ${theme.fg("toolTitle", theme.bold(name))} ${theme.fg(failed ? "error" : "accent", episodeLabel)}`,
				0,
				0,
			),
		);
		container.addChild(new Spacer(1));
		container.addChild(new Markdown(full.trim(), 0, 0, getMarkdownTheme()));
		if (usageStr) {
			container.addChild(new Spacer(1));
			container.addChild(new Text(theme.fg("dim", usageStr), 0, 0));
		}
		return container;
	}

	// Collapsed: headline + Key Findings digest (or Open Issues when failed).
	let text = `${icon} ${theme.fg("toolTitle", theme.bold(name))} ${theme.fg(failed ? "error" : "accent", episodeLabel)}`;
	const digestSection = failed ? "Open Issues" : "Key Findings";
	const digest = extractSection(full, digestSection);
	if (digest) {
		const digestLines = digest.split("\n").slice(0, 8);
		text += `\n${theme.fg("muted", `— ${digestSection}:`)}`;
		for (const line of digestLines) {
			text += `\n  ${theme.fg("toolOutput", line.length > 110 ? `${line.slice(0, 110)}...` : line)}`;
		}
		if (digest.split("\n").length > 8) text += `\n  ${theme.fg("muted", "…")}`;
	}
	if (usageStr) text += `\n${theme.fg("dim", usageStr)}`;
	text += `\n${theme.fg("muted", `(${keyHint("app.tools.expand", "to expand")})`)}`;
	return new Text(text, 0, 0);
}
