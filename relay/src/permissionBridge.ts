import { describeTool } from "./describe.js";
import type { HookReply, HookRequest, PermissionSuggestion, ServerMessage } from "./protocol.js";
import type { SessionRegistry } from "./sessions.js";

export interface GlassesGateway {
  everPaired(): boolean;
  isConnected(): boolean;
  send(msg: ServerMessage): void;
}

export interface BridgeDeps {
  registry: SessionRegistry;
  glasses: GlassesGateway;
  resolveTranscriptPath: (sessionId: string, provided?: string) => string | null;
  nextId: () => string;
}

type Resolution = "allow" | "deny" | "allow_always" | "cancel";

interface Pending {
  resolve: (r: Resolution) => void;
  session: string;
  suggestions: PermissionSuggestion[];
}

export class PermissionBridge {
  private readonly pending = new Map<string, Pending>();

  constructor(private readonly deps: BridgeDeps) {}

  async handle(req: HookRequest, signal: AbortSignal): Promise<HookReply> {
    const transcriptPath = this.deps.resolveTranscriptPath(req.session_id, req.transcript_path);
    this.deps.registry.upsert(req.session_id, req.cwd, transcriptPath);

    // Only PermissionRequest events escalate: that hook fires after Claude Code's own
    // permission evaluation has already decided to show a prompt, so the glasses mirror
    // real prompts 1:1. Anything else (PreToolUse) is session tracking only.
    if (req.hook_event_name !== "PermissionRequest") return { verdict: "pass" };
    if (!this.deps.glasses.everPaired()) return { verdict: "pass" }; // fail-open: no device exists

    // Claude offers "don't ask again" only when it hands us suggested rules.
    const suggestions = Array.isArray(req.permission_suggestions) ? req.permission_suggestions : [];
    const id = this.deps.nextId();
    const pendingReq = {
      id,
      tool: req.tool_name,
      description: describeTool(req.tool_name, req.tool_input),
      mode: req.permission_mode,
      canAlwaysAllow: suggestions.length > 0,
    };
    this.deps.registry.setPending(req.session_id, pendingReq);

    const message: ServerMessage = {
      type: "permission_request",
      session: req.session_id,
      ...pendingReq,
    };
    if (this.deps.glasses.isConnected()) this.deps.glasses.send(message);

    const resolution = await new Promise<Resolution>((resolve) => {
      this.pending.set(id, { resolve, session: req.session_id, suggestions });
      if (signal.aborted) resolve("cancel");
      else signal.addEventListener("abort", () => resolve("cancel"), { once: true });
    });

    this.pending.delete(id);
    this.deps.registry.clearPending(req.session_id);

    if (resolution === "cancel") {
      // Answered elsewhere (terminal / another device). Tell the glasses to drop the prompt.
      this.deps.glasses.send({ type: "permission_cancel", session: req.session_id, id });
      return { verdict: "pass" }; // hook went away → Claude falls through
    }
    if (resolution === "allow_always") {
      return { verdict: "allow_always", updatedPermissions: suggestions };
    }
    return { verdict: resolution };
  }

  /** Called when a glasses `permission_response` arrives. */
  resolve(id: string, decision: "allow" | "deny" | "allow_always"): void {
    const entry = this.pending.get(id);
    if (!entry) return;
    // "always allow" with nothing to persist degrades to a plain allow.
    if (decision === "allow_always" && entry.suggestions.length === 0) {
      entry.resolve("allow");
      return;
    }
    entry.resolve(decision);
  }
}
