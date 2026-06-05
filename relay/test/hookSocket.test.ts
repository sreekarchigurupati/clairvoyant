import net from "node:net";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { HookSocketServer } from "../src/hookSocket.js";
import type { HookReply, HookRequest } from "../src/protocol.js";

let dir: string;
let sock: string;
beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), "clv-hsock-"));
  sock = path.join(dir, "relay.sock");
});
afterEach(() => {
  fs.rmSync(dir, { recursive: true, force: true });
});

/** Send one request line over the unix socket and resolve with the reply line (or null on close-without-reply). */
function call(sock: string, req: object): Promise<HookReply | null> {
  return new Promise((resolve, reject) => {
    const c = net.connect(sock);
    let buf = "";
    let got = false;
    c.on("connect", () => c.write(JSON.stringify(req) + "\n"));
    c.on("data", (d) => {
      buf += d.toString();
      const nl = buf.indexOf("\n");
      if (nl !== -1 && !got) {
        got = true;
        resolve(JSON.parse(buf.slice(0, nl)) as HookReply);
        c.end();
      }
    });
    c.on("close", () => {
      if (!got) resolve(null);
    });
    c.on("error", reject);
  });
}

describe("HookSocketServer", () => {
  it("invokes the handler with the parsed payload and returns its reply", async () => {
    let seen: HookRequest | undefined;
    const server = new HookSocketServer(sock, async (req) => {
      seen = req;
      return { verdict: "allow", reason: "ok" } satisfies HookReply;
    });
    await server.start();
    const reply = await call(sock, {
      session_id: "s1",
      cwd: "/x",
      tool_name: "Bash",
      tool_input: { command: "ls" },
      permission_mode: "default",
    });
    expect(seen?.tool_name).toBe("Bash");
    expect(reply).toEqual({ verdict: "allow", reason: "ok" });
    await server.stop();
  });

  it("aborts the handler when the client disconnects before a reply", async () => {
    let aborted = false;
    const server = new HookSocketServer(sock, async (_req, signal) => {
      await new Promise<void>((res) => signal.addEventListener("abort", () => res(), { once: true }));
      aborted = true;
      return { verdict: "pass" };
    });
    await server.start();

    const c = net.connect(sock);
    await new Promise<void>((res) => c.on("connect", () => res()));
    c.write(JSON.stringify({ session_id: "s", cwd: "/x", tool_name: "Bash", tool_input: {} }) + "\n");
    await new Promise((r) => setTimeout(r, 50));
    c.destroy(); // simulate Claude killing the hook
    await new Promise((r) => setTimeout(r, 50));
    expect(aborted).toBe(true);
    await server.stop();
  });

  it("replies pass on malformed JSON", async () => {
    const server = new HookSocketServer(sock, async () => ({ verdict: "allow" }));
    await server.start();
    const reply = await new Promise<HookReply | null>((resolve) => {
      const c = net.connect(sock);
      let buf = "";
      c.on("connect", () => c.write("{not json\n"));
      c.on("data", (d) => {
        buf += d.toString();
        const nl = buf.indexOf("\n");
        if (nl !== -1) {
          resolve(JSON.parse(buf.slice(0, nl)) as HookReply);
          c.end();
        }
      });
      c.on("close", () => resolve(null));
    });
    expect(reply).toEqual({ verdict: "pass" });
    await server.stop();
  });
});
