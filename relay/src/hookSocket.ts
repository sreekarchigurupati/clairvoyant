import fs from "node:fs";
import * as net from "node:net";
import type { HookReply, HookRequest } from "./protocol.js";

export type HookHandler = (req: HookRequest, signal: AbortSignal) => Promise<HookReply>;

export class HookSocketServer {
  private server: net.Server;

  constructor(
    private readonly socketPath: string,
    private readonly handler: HookHandler,
  ) {
    this.server = net.createServer((sock) => this.onConnection(sock));
  }

  async start(): Promise<void> {
    try {
      fs.unlinkSync(this.socketPath); // clear a stale socket from a previous run
    } catch {
      /* nothing to remove */
    }
    await new Promise<void>((resolve, reject) => {
      const onError = (err: Error) => reject(err);
      this.server.once("error", onError);
      this.server.listen(this.socketPath, () => {
        this.server.off("error", onError);
        resolve();
      });
    });
  }

  async stop(): Promise<void> {
    await new Promise<void>((resolve) => this.server.close(() => resolve()));
    try {
      fs.unlinkSync(this.socketPath);
    } catch {
      /* already gone */
    }
  }

  private onConnection(sock: net.Socket): void {
    const ac = new AbortController();
    let buf = "";
    let handled = false;
    let responded = false;

    const reply = (r: HookReply) => {
      responded = true;
      if (!sock.destroyed) sock.end(JSON.stringify(r) + "\n");
    };

    sock.on("data", (d) => {
      if (handled) return;
      buf += d.toString("utf8");
      const nl = buf.indexOf("\n");
      if (nl === -1) return;
      handled = true;
      const line = buf.slice(0, nl);
      let req: HookRequest;
      try {
        req = JSON.parse(line) as HookRequest;
      } catch {
        reply({ verdict: "pass" });
        return;
      }
      this.handler(req, ac.signal)
        .then((r) => reply(r))
        .catch(() => {
          if (!responded) reply({ verdict: "pass" });
        });
    });

    sock.on("close", () => {
      if (!responded) ac.abort();
    });
    sock.on("error", () => {
      if (!responded) ac.abort();
    });
  }
}
