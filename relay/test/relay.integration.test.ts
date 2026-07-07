import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { WebSocket } from "ws";
import { createRelay, type Relay } from "../src/relay.js";
import type { HookReply } from "../src/protocol.js";

let home: string;
let relay: Relay;
let sock: string;

beforeEach(async () => {
  home = fs.mkdtempSync(path.join(os.tmpdir(), "clv-relay-"));
  sock = path.join(home, "relay.sock");
  relay = createRelay({
    home,
    host: "127.0.0.1",
    port: 0,
    socketPath: sock,
    publicDir: new URL("../public", import.meta.url).pathname,
  });
  await relay.start();
});
afterEach(async () => {
  await relay.stop();
  fs.rmSync(home, { recursive: true, force: true });
});

function hookCall(req: object): Promise<HookReply | null> {
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

class GlassesClient {
  ws: WebSocket;
  messages: any[] = [];
  private waiters: { type: string; resolve: (m: any) => void }[] = [];
  constructor(port: number) {
    this.ws = new WebSocket(`ws://127.0.0.1:${port}/ws`);
    this.ws.on("message", (d) => {
      const m = JSON.parse(d.toString());
      this.messages.push(m);
      const i = this.waiters.findIndex((w) => w.type === m.type);
      if (i !== -1) this.waiters.splice(i, 1)[0].resolve(m);
    });
  }
  open() {
    return new Promise((r) => this.ws.on("open", r));
  }
  send(m: object) {
    this.ws.send(JSON.stringify(m));
  }
  waitFor(type: string): Promise<any> {
    const existing = this.messages.find((m) => m.type === type);
    if (existing) return Promise.resolve(existing);
    return new Promise((resolve) => this.waiters.push({ type, resolve }));
  }
  close() {
    this.ws.close();
  }
}

async function waitForList(g: GlassesClient, ids: string[]): Promise<void> {
  const deadline = Date.now() + 1500;
  for (;;) {
    const lists = g.messages.filter((m) => m.type === "session_list");
    const last = lists[lists.length - 1];
    if (last && ids.every((id) => last.sessions.some((s: any) => s.id === id))) return;
    if (Date.now() > deadline) throw new Error("session_list missing " + ids.join(","));
    await new Promise((r) => setTimeout(r, 10));
  }
}

const bash = (session: string) => ({
  session_id: session,
  cwd: `/Users/x/${session}`,
  tool_name: "Bash",
  tool_input: { command: "rm -rf build" },
  permission_mode: "default",
  hook_event_name: "PermissionRequest",
});
const read = (session: string) => ({
  session_id: session,
  cwd: `/Users/x/${session}`,
  tool_name: "Read",
  tool_input: { file_path: "/a" },
  permission_mode: "default",
  hook_event_name: "PreToolUse",
});

async function authed(): Promise<GlassesClient> {
  const g = new GlassesClient(relay.port);
  await g.open();
  g.send({ type: "hello", token: relay.token });
  await g.waitFor("ready");
  return g;
}

describe("relay integration", () => {
  it("passes read-only calls and registers the session", async () => {
    const g = await authed();
    expect(await hookCall(read("s1"))).toEqual({ verdict: "pass" });
    await waitForList(g, ["s1"]);
    g.close();
  });

  it("escalates a Bash call to the glasses and returns the answer", async () => {
    const g = await authed();
    const pending = hookCall(bash("s1")); // blocks until answered
    const req = await g.waitFor("permission_request");
    expect(req).toMatchObject({ session: "s1", tool: "Bash", mode: "default" });
    g.send({ type: "permission_response", session: "s1", id: req.id, decision: "allow" });
    expect(await pending).toEqual({ verdict: "allow" });
    g.close();
  });

  it("fails open (pass) when no glasses have ever paired", async () => {
    expect(await hookCall(bash("s9"))).toEqual({ verdict: "pass" });
  });

  it("tracks parallel sessions in session_list", async () => {
    const g = await authed();
    await hookCall(read("s1"));
    await hookCall(read("s2"));
    await waitForList(g, ["s1", "s2"]);
    g.close();
  });

  it("re-sends a pending permission_request to a reconnecting glasses", async () => {
    const g1 = await authed();
    const pending = hookCall(bash("s1"));
    const first = await g1.waitFor("permission_request");
    g1.close(); // drop without answering → stays pending
    await new Promise((r) => setTimeout(r, 50));

    const g2 = await authed();
    const resent = await g2.waitFor("permission_request");
    expect(resent.id).toBe(first.id);
    g2.send({ type: "permission_response", session: "s1", id: resent.id, decision: "deny" });
    expect(await pending).toEqual({ verdict: "deny" });
    g2.close();
  });
});
