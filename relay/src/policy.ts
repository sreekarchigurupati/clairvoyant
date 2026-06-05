import type { HookRequest } from "./protocol.js";

/** Tools Claude Code auto-allows by default; never worth escalating. Extend conservatively. */
export const READ_ONLY_TOOLS = new Set(["Read", "Grep", "Glob", "NotebookRead", "TodoWrite"]);

/** Tools that `acceptEdits` mode auto-accepts. */
export const EDIT_TOOLS = new Set(["Edit", "Write", "MultiEdit", "NotebookEdit"]);

export type PolicyAction = "escalate" | "pass";

/**
 * Decide whether a tool call should be surfaced on the glasses ("escalate") or handed
 * back to Claude Code's own permission flow ("pass" => hook emits no decision).
 *
 * Safe by construction: "pass" means Claude either auto-allows or shows its OWN terminal
 * prompt, so a wrong answer here can only relocate a prompt or add a redundant one — it can
 * never silently bypass a prompt Claude would have shown.
 */
export function decide(req: Pick<HookRequest, "tool_name" | "permission_mode">): PolicyAction {
  if (READ_ONLY_TOOLS.has(req.tool_name)) return "pass";
  switch (req.permission_mode) {
    case "bypassPermissions":
    case "plan":
      return "pass";
    case "acceptEdits":
      return EDIT_TOOLS.has(req.tool_name) ? "pass" : "escalate";
    default:
      // "default", undefined, or any unknown/future mode: escalate (non-read tools only,
      // since read-only already returned "pass" above).
      return "escalate";
  }
}
