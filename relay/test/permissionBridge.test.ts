import { describe, expect, it } from "vitest";
import { PermissionBridge } from "../src/permissionBridge.js";
import { SessionRegistry } from "../src/sessions.js";
import type { ServerMessage } from "../src/protocol.js";

function makeBridge(opts: { everPaired?: boolean; connected?: boolean } = {}) {
  const registry = new SessionRegistry();
  const sent: ServerMessage[] = [];
  const glasses = {
    everPaired: () => opts.everPaired ?? true,
    isConnected: () => opts.connected ?? true,
    send: (m: ServerMessage) => sent.push(m),
  };
  let n = 0;
  const bridge = new PermissionBridge({
    registry,
    glasses,
    resolveTranscriptPath: () => "/t/x.jsonl",
    nextId: () => String(++n),
  });
  return { bridge, registry, glasses, sent };
}

const req = (over: Partial<Record<string, unknown>> = {}) =>
  ({
    session_id: "s1",
    cwd: "/Users/x/foo",
    tool_name: "Bash",
    tool_input: { command: "rm -rf build" },
    permission_mode: "default",
    hook_event_name: "PermissionRequest",
    ...over,
  }) as any;

describe("PermissionBridge", () => {
  it("passes non-PermissionRequest events (tracking only), but registers the session", async () => {
    const { bridge, registry, sent } = makeBridge();
    const reply = await bridge.handle(
      req({ hook_event_name: "PreToolUse" }),
      new AbortController().signal,
    );
    expect(reply).toEqual({ verdict: "pass" });
    expect(sent).toHaveLength(0);
    expect(registry.get("s1")).toBeDefined();
  });

  it("records the claude_pid from the hook payload", async () => {
    const { bridge, registry } = makeBridge();
    await bridge.handle(req({ hook_event_name: "PreToolUse", claude_pid: 7777 }), new AbortController().signal);
    expect(registry.get("s1")?.pid).toBe(7777);
  });

  it("removes the session on SessionEnd instead of registering it", async () => {
    const { bridge, registry } = makeBridge();
    await bridge.handle(req({ hook_event_name: "PreToolUse" }), new AbortController().signal);
    expect(registry.get("s1")).toBeDefined();
    const reply = await bridge.handle(req({ hook_event_name: "SessionEnd" }), new AbortController().signal);
    expect(reply).toEqual({ verdict: "pass" });
    expect(registry.get("s1")).toBeUndefined();
  });

  it("escalates: sends permission_request, marks pending, resolves on allow", async () => {
    const { bridge, registry, sent } = makeBridge();
    const p = bridge.handle(req(), new AbortController().signal);
    await new Promise((r) => setTimeout(r, 0));
    expect(sent[0]).toMatchObject({ type: "permission_request", session: "s1", id: "1", tool: "Bash", mode: "default" });
    expect(registry.get("s1")?.pending?.id).toBe("1");
    bridge.resolve("1", "allow");
    expect(await p).toEqual({ verdict: "allow" });
    expect(registry.get("s1")?.pending).toBeNull();
  });

  it("resolves deny", async () => {
    const { bridge } = makeBridge();
    const p = bridge.handle(req(), new AbortController().signal);
    await new Promise((r) => setTimeout(r, 0));
    bridge.resolve("1", "deny");
    expect(await p).toEqual({ verdict: "deny" });
  });

  it("advertises canAlwaysAllow and returns updatedPermissions on allow_always", async () => {
    const { bridge, sent } = makeBridge();
    const suggestions = [{ type: "addRules", rules: [{ toolName: "Bash" }] }];
    const p = bridge.handle(req({ permission_suggestions: suggestions }), new AbortController().signal);
    await new Promise((r) => setTimeout(r, 0));
    expect(sent[0]).toMatchObject({ type: "permission_request", canAlwaysAllow: true });
    bridge.resolve("1", "allow_always");
    expect(await p).toEqual({ verdict: "allow_always", updatedPermissions: suggestions });
  });

  it("marks canAlwaysAllow false when no suggestions, and degrades allow_always to allow", async () => {
    const { bridge, sent } = makeBridge();
    const p = bridge.handle(req(), new AbortController().signal);
    await new Promise((r) => setTimeout(r, 0));
    expect(sent[0]).toMatchObject({ canAlwaysAllow: false });
    bridge.resolve("1", "allow_always"); // nothing to persist → plain allow
    expect(await p).toEqual({ verdict: "allow" });
  });

  it("sends permission_cancel to the glasses when answered elsewhere (aborted)", async () => {
    const { bridge, sent } = makeBridge();
    const ac = new AbortController();
    const p = bridge.handle(req(), ac.signal);
    await new Promise((r) => setTimeout(r, 0));
    ac.abort();
    await p;
    expect(sent).toContainEqual({ type: "permission_cancel", session: "s1", id: "1" });
  });

  it("fails open (pass) when no glasses have ever paired", async () => {
    const { bridge, sent } = makeBridge({ everPaired: false });
    const reply = await bridge.handle(req(), new AbortController().signal);
    expect(reply).toEqual({ verdict: "pass" });
    expect(sent).toHaveLength(0);
  });

  it("stays pending while disconnected (no send), still resolvable on reconnect", async () => {
    const { bridge, registry, sent } = makeBridge({ everPaired: true, connected: false });
    const p = bridge.handle(req(), new AbortController().signal);
    await new Promise((r) => setTimeout(r, 0));
    expect(sent).toHaveLength(0); // not connected now: nothing sent yet
    expect(registry.get("s1")?.pending?.id).toBe("1"); // but pending, so reconnect can re-send
    bridge.resolve("1", "allow");
    expect(await p).toEqual({ verdict: "allow" });
  });

  it("returns pass and clears pending when aborted (hook socket closed)", async () => {
    const { bridge, registry } = makeBridge();
    const ac = new AbortController();
    const p = bridge.handle(req(), ac.signal);
    await new Promise((r) => setTimeout(r, 0));
    expect(registry.get("s1")?.pending?.id).toBe("1");
    ac.abort();
    expect(await p).toEqual({ verdict: "pass" });
    expect(registry.get("s1")?.pending).toBeNull();
  });
});
