import { spawn } from "node:child_process";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

const HOOK = fileURLToPath(new URL("../hook/clairvoyant-hook.mjs", import.meta.url));

let dir: string;
let sock: string;
beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), "clv-hook-"));
  sock = path.join(dir, "relay.sock");
});
afterEach(() => {
  fs.rmSync(dir, { recursive: true, force: true });
});

function fakeRelay(sockPath: string, reply: object | null): Promise<net.Server> {
  return new Promise((resolve) => {
    const server = net.createServer((c) => {
      let buf = "";
      c.on("data", (d) => {
        buf += d.toString();
        if (buf.includes("\n")) {
          if (reply) c.end(JSON.stringify(reply) + "\n");
          else c.end();
        }
      });
    });
    server.listen(sockPath, () => resolve(server));
  });
}

function runHook(
  env: Record<string, string>,
  payload: object,
): Promise<{ stdout: string; code: number | null }> {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [HOOK], { env: { ...process.env, ...env } });
    let stdout = "";
    child.stdout.on("data", (d) => (stdout += d.toString()));
    child.on("error", reject);
    child.on("close", (code) => resolve({ stdout, code }));
    child.stdin.write(JSON.stringify(payload));
    child.stdin.end();
  });
}

const permReq = (over: object = {}) => ({
  hook_event_name: "PermissionRequest",
  tool_name: "Bash",
  tool_input: { command: "ls" },
  ...over,
});

describe("clairvoyant-hook.mjs", () => {
  it("emits decision allow on PermissionRequest when the relay replies allow", async () => {
    const server = await fakeRelay(sock, { verdict: "allow", reason: "glasses" });
    const { stdout, code } = await runHook({ CLAIRVOYANT_SOCK: sock }, permReq());
    server.close();
    expect(code).toBe(0);
    expect(JSON.parse(stdout)).toEqual({
      hookSpecificOutput: {
        hookEventName: "PermissionRequest",
        decision: { behavior: "allow" },
      },
    });
  });

  it("emits decision deny on PermissionRequest when the relay replies deny", async () => {
    const server = await fakeRelay(sock, { verdict: "deny" });
    const { stdout } = await runHook({ CLAIRVOYANT_SOCK: sock }, permReq());
    server.close();
    expect(JSON.parse(stdout).hookSpecificOutput.decision).toEqual({ behavior: "deny" });
  });

  it("emits allow + updatedPermissions on allow_always", async () => {
    const rules = [{ type: "addRules", rules: [{ toolName: "Bash", ruleContent: "ls:*" }], behavior: "allow", destination: "localSettings" }];
    const server = await fakeRelay(sock, { verdict: "allow_always", updatedPermissions: rules });
    const { stdout } = await runHook({ CLAIRVOYANT_SOCK: sock }, permReq());
    server.close();
    expect(JSON.parse(stdout).hookSpecificOutput.decision).toEqual({
      behavior: "allow",
      updatedPermissions: rules,
    });
  });

  it("emits nothing on pass", async () => {
    const server = await fakeRelay(sock, { verdict: "pass" });
    const { stdout, code } = await runHook({ CLAIRVOYANT_SOCK: sock }, permReq());
    server.close();
    expect(stdout.trim()).toBe("");
    expect(code).toBe(0);
  });

  it("never emits a decision for PreToolUse (tracking only), even if the relay replies allow", async () => {
    const server = await fakeRelay(sock, { verdict: "allow" });
    const { stdout, code } = await runHook(
      { CLAIRVOYANT_SOCK: sock },
      permReq({ hook_event_name: "PreToolUse" }),
    );
    server.close();
    expect(stdout.trim()).toBe("");
    expect(code).toBe(0);
  });

  it("fails open (nothing, exit 0) when the socket is absent", async () => {
    const { stdout, code } = await runHook({ CLAIRVOYANT_SOCK: path.join(dir, "nope.sock") }, { tool_name: "Bash", tool_input: {} });
    expect(stdout.trim()).toBe("");
    expect(code).toBe(0);
  });

  it("fails open when the relay closes without replying", async () => {
    const server = await fakeRelay(sock, null);
    const { stdout, code } = await runHook({ CLAIRVOYANT_SOCK: sock }, { tool_name: "Bash", tool_input: {} });
    server.close();
    expect(stdout.trim()).toBe("");
    expect(code).toBe(0);
  });
});
