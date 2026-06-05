import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { WebSocket } from "ws";
import { createRelay, type Relay } from "../src/relay.js";

let home: string;
let relay: Relay;
let sock: string;

beforeEach(async () => {
  home = fs.mkdtempSync(path.join(os.tmpdir(), "clv-mon-"));
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

function hookCall(req: object): Promise<unknown> {
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
        resolve(JSON.parse(buf.slice(0, nl)));
        c.end();
      }
    });
    c.on("close", () => {
      if (!got) resolve(null);
    });
    c.on("error", reject);
  });
}

function writeTranscript(session: string): void {
  const proj = path.join(home, ".claude", "projects", "-Users-x-foo");
  fs.mkdirSync(proj, { recursive: true });
  const line = JSON.stringify({
    type: "assistant",
    isSidechain: false,
    message: { content: [{ type: "text", text: "streamed hello" }], stop_reason: "end_turn" },
  });
  fs.writeFileSync(path.join(proj, `${session}.jsonl`), line + "\n");
}

describe("relay monitoring", () => {
  it("streams transcript events to the glasses when a session is added", async () => {
    writeTranscript("s1"); // transcript exists before the session becomes known

    const messages: any[] = [];
    const g = new WebSocket(`ws://127.0.0.1:${relay.port}/ws`);
    g.on("message", (d) => messages.push(JSON.parse(d.toString())));
    await new Promise((r) => g.on("open", r));
    g.send(JSON.stringify({ type: "hello", token: relay.token }));

    const wait = async (pred: () => boolean, label: string) => {
      const deadline = Date.now() + 1500;
      while (!pred()) {
        if (Date.now() > deadline) throw new Error(`${label}: ${JSON.stringify(messages)}`);
        await new Promise((r) => setTimeout(r, 10));
      }
    };
    await wait(() => messages.some((m) => m.type === "ready"), "no ready");

    await hookCall({
      session_id: "s1",
      cwd: "/Users/x/foo",
      tool_name: "Read",
      tool_input: { file_path: "/a" },
      permission_mode: "default",
    });

    await wait(
      () => messages.some((m) => m.type === "assistant_delta" && m.text === "streamed hello"),
      "no assistant_delta",
    );
    g.close();
  });
});
