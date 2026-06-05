import { describeTool } from "./describe.js";
import { decide } from "./policy.js";
import type { HookReply, HookRequest, ServerMessage } from "./protocol.js";
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

type Resolution = "allow" | "deny" | "cancel";

export class PermissionBridge {
  private readonly pending = new Map<string, (r: Resolution) => void>();

  constructor(private readonly deps: BridgeDeps) {}

  async handle(req: HookRequest, signal: AbortSignal): Promise<HookReply> {
    const transcriptPath = this.deps.resolveTranscriptPath(req.session_id, req.transcript_path);
    this.deps.registry.upsert(req.session_id, req.cwd, transcriptPath);

    if (decide(req) === "pass") return { verdict: "pass" };
    if (!this.deps.glasses.everPaired()) return { verdict: "pass" }; // fail-open: no device exists

    const id = this.deps.nextId();
    const pendingReq = {
      id,
      tool: req.tool_name,
      description: describeTool(req.tool_name, req.tool_input),
      mode: req.permission_mode,
    };
    this.deps.registry.setPending(req.session_id, pendingReq);

    const message: ServerMessage = {
      type: "permission_request",
      session: req.session_id,
      ...pendingReq,
    };
    if (this.deps.glasses.isConnected()) this.deps.glasses.send(message);

    const resolution = await new Promise<Resolution>((resolve) => {
      this.pending.set(id, resolve);
      if (signal.aborted) resolve("cancel");
      else signal.addEventListener("abort", () => resolve("cancel"), { once: true });
    });

    this.pending.delete(id);
    this.deps.registry.clearPending(req.session_id);

    if (resolution === "cancel") return { verdict: "pass" }; // hook went away → Claude falls through
    return { verdict: resolution };
  }

  /** Called when a glasses `permission_response` arrives. */
  resolve(id: string, decision: "allow" | "deny"): void {
    const resolver = this.pending.get(id);
    if (resolver) resolver(decision);
  }
}
