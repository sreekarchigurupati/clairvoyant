import { describeTool } from "./describe.js";
import type { ServerMessage } from "./protocol.js";

/**
 * Parse one Claude Code transcript JSONL line into zero or more glasses events.
 * Best-effort: the JSONL schema is internal and may change; unknown shapes yield [].
 */
export function parseTranscriptLine(line: string, session: string): ServerMessage[] {
  let obj: any;
  try {
    obj = JSON.parse(line);
  } catch {
    return [];
  }
  if (!obj || typeof obj !== "object") return [];
  if (obj.isSidechain) return []; // subagent traffic — not shown in v1
  if (obj.type !== "assistant") return []; // v1 streams assistant text + tool_use only

  const content = obj.message?.content;
  if (!Array.isArray(content)) return [];

  const out: ServerMessage[] = [];
  for (const c of content) {
    if (c?.type === "text" && typeof c.text === "string" && c.text.length > 0) {
      out.push({ type: "assistant_delta", session, text: c.text });
    } else if (c?.type === "tool_use" && typeof c.name === "string") {
      out.push({
        type: "tool_use",
        session,
        id: String(c.id ?? ""),
        name: c.name,
        summary: describeTool(c.name, c.input ?? {}),
      });
    }
    // "thinking" and other block types are intentionally skipped in v1
  }
  const stop = obj.message?.stop_reason;
  if (stop && stop !== "tool_use") out.push({ type: "turn_done", session });
  return out;
}
