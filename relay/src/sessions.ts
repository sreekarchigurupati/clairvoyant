import { EventEmitter } from "node:events";
import path from "node:path";
import type { SessionInfo, SessionState } from "./protocol.js";

export interface PendingRequest {
  id: string;
  tool: string;
  description: string;
  mode?: string;
}

export interface Session {
  id: string;
  cwd: string;
  title: string;
  state: SessionState;
  transcriptPath: string | null;
  pending: PendingRequest | null;
}

export function titleFromCwd(cwd: string): string {
  return path.basename(cwd) || cwd;
}

/**
 * In-memory registry of attached Claude Code sessions. Emits:
 *  - "added" (session) when a new session first appears (→ start a transcript tailer)
 *  - "change" whenever the session list / pending / state changes (→ broadcast session_list)
 */
export class SessionRegistry extends EventEmitter {
  private sessions = new Map<string, Session>();

  upsert(id: string, cwd: string, transcriptPath: string | null): Session {
    const existing = this.sessions.get(id);
    if (existing) {
      if (transcriptPath && !existing.transcriptPath) existing.transcriptPath = transcriptPath;
      return existing;
    }
    const session: Session = {
      id,
      cwd,
      title: titleFromCwd(cwd),
      state: "running",
      transcriptPath,
      pending: null,
    };
    this.sessions.set(id, session);
    this.emit("added", session);
    this.emit("change");
    return session;
  }

  get(id: string): Session | undefined {
    return this.sessions.get(id);
  }

  list(): SessionInfo[] {
    return [...this.sessions.values()].map((s) => ({ id: s.id, title: s.title, state: s.state }));
  }

  setPending(id: string, req: PendingRequest): void {
    const s = this.sessions.get(id);
    if (!s) return;
    s.pending = req;
    this.emit("change");
  }

  clearPending(id: string): void {
    const s = this.sessions.get(id);
    if (!s || !s.pending) return;
    s.pending = null;
    this.emit("change");
  }

  pendingRequests(): { session: string; req: PendingRequest }[] {
    return [...this.sessions.values()]
      .filter((s) => s.pending)
      .map((s) => ({ session: s.id, req: s.pending! }));
  }

  setState(id: string, state: SessionState): void {
    const s = this.sessions.get(id);
    if (!s || s.state === state) return;
    s.state = state;
    this.emit("change");
  }
}
