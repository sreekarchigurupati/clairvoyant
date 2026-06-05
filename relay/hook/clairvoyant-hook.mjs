#!/usr/bin/env node
// Clairvoyant PreToolUse hook. Dependency-free. Reads PreToolUse JSON on stdin, forwards it
// to the relay's unix socket, and maps the verdict to a Claude Code permission decision.
// Any uncertainty (relay down, closed early, "pass") => no output => Claude's normal flow.
import net from "node:net";
import os from "node:os";
import path from "node:path";

const SOCK = process.env.CLAIRVOYANT_SOCK || path.join(os.homedir(), ".clairvoyant", "relay.sock");

let input = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (d) => (input += d));
process.stdin.on("end", connect);

function passNoDecision() {
  process.exit(0); // no stdout: Claude Code runs its own permission flow
}

function emit(verdict, reason) {
  const out = JSON.stringify({
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: verdict,
      permissionDecisionReason: reason || "Clairvoyant glasses",
    },
  });
  // Exit only after the pipe has flushed, or output can be truncated.
  process.stdout.write(out, () => process.exit(0));
}

function connect() {
  let done = false;
  const finish = (fn) => {
    if (done) return;
    done = true;
    fn();
  };

  const sock = net.connect(SOCK);
  let buf = "";
  sock.on("connect", () => sock.write(input.endsWith("\n") ? input : input + "\n"));
  sock.on("data", (d) => {
    buf += d.toString();
    const nl = buf.indexOf("\n");
    if (nl === -1) return;
    let reply;
    try {
      reply = JSON.parse(buf.slice(0, nl));
    } catch {
      finish(passNoDecision);
      return;
    }
    if (reply.verdict === "allow" || reply.verdict === "deny") {
      finish(() => emit(reply.verdict, reply.reason));
    } else {
      finish(passNoDecision);
    }
  });
  sock.on("error", () => finish(passNoDecision)); // relay not running
  sock.on("close", () => finish(passNoDecision)); // closed before a usable reply
}
