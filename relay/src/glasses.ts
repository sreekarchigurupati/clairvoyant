import type { Server as HttpServer } from "node:http";
import { WebSocket, WebSocketServer } from "ws";
import { parseClientMessage } from "./protocol.js";
import type { ServerMessage } from "./protocol.js";

export type ResponseCallback = (session: string, id: string, decision: "allow" | "deny") => void;
export type AuthCallback = () => void;

export class GlassesServer {
  private readonly wss: WebSocketServer;
  private readonly clients = new Set<WebSocket>();
  private paired = false;

  constructor(
    httpServer: HttpServer,
    private readonly getToken: () => string,
    private readonly onResponse: ResponseCallback,
    private readonly onAuth: AuthCallback,
  ) {
    this.wss = new WebSocketServer({ server: httpServer, path: "/ws" });
    this.wss.on("connection", (ws) => this.onConnection(ws));
  }

  everPaired(): boolean {
    return this.paired;
  }

  isConnected(): boolean {
    return this.clients.size > 0;
  }

  send(msg: ServerMessage): void {
    const data = JSON.stringify(msg);
    for (const ws of this.clients) {
      if (ws.readyState === WebSocket.OPEN) ws.send(data);
    }
  }

  close(): void {
    for (const ws of this.clients) ws.terminate();
    this.clients.clear();
    this.wss.close();
  }

  private onConnection(ws: WebSocket): void {
    let authed = false;
    ws.on("message", (data) => {
      const msg = parseClientMessage(data.toString());
      if (!msg) return;
      if (!authed) {
        if (msg.type === "hello" && msg.token === this.getToken()) {
          authed = true;
          this.paired = true;
          this.clients.add(ws);
          ws.send(JSON.stringify({ type: "ready" } satisfies ServerMessage));
          this.onAuth();
        } else {
          // Close only after the error frame is flushed, so the client reliably sees the
          // bad_token reason before the socket closes (otherwise it looks like a transient
          // drop and the client reconnect-loops with the same bad token).
          ws.send(
            JSON.stringify({
              type: "error",
              code: "bad_token",
              message: "Pairing expired, re-scan QR.",
            } satisfies ServerMessage),
            () => ws.close(),
          );
        }
        return;
      }
      if (msg.type === "permission_response") {
        this.onResponse(msg.session, msg.id, msg.decision);
      }
    });
    ws.on("close", () => this.clients.delete(ws));
    ws.on("error", () => this.clients.delete(ws));
  }
}
