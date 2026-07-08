#!/usr/bin/env node
// Clairvoyant hook. Dependency-free. Reads hook JSON on stdin, forwards it to the relay's
// unix socket, and maps the verdict to a Claude Code decision.
//
// Registered on two events:
//   PermissionRequest — fires only when Claude Code has already decided to show a permission
//                       prompt; the relay mirrors it to the glasses and this hook answers it.
//   PreToolUse        — session tracking only (registry/transcript discovery); never answers.
//
// Any uncertainty (relay down, closed early, "pass") => no output => Claude's normal prompt.
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

function emit(reply) {
  // "allow_always" => allow this call AND persist the rules Claude suggested (updatedPermissions),
  // so future matching calls are auto-allowed and never prompt again.
  const decision =
    reply.verdict === "allow_always"
      ? { behavior: "allow", updatedPermissions: reply.updatedPermissions ?? [] }
      : { behavior: reply.verdict }; // "allow" | "deny"
  const out = JSON.stringify({
    hookSpecificOutput: {
      hookEventName: "PermissionRequest",
      decision,
    },
  });
  // Exit only after the pipe has flushed, or output can be truncated.
  process.stdout.write(out, () => process.exit(0));
}

function connect() {
  let payload;
  try {
    payload = JSON.parse(input);
  } catch {
    passNoDecision();
    return;
  }
  const isPermissionRequest = payload.hook_event_name === "PermissionRequest";
  // The hook is spawned directly by the `claude` process, so our parent PID is that
  // session's PID. Forwarding it lets the relay prune a session's tab the moment its
  // Claude process is gone — even on a crash/kill where no SessionEnd hook fires.
  payload.claude_pid = process.ppid;
  const line = JSON.stringify(payload) + "\n";

  let done = false;
  const finish = (fn) => {
    if (done) return;
    done = true;
    fn();
  };

  const sock = net.connect(SOCK);
  let buf = "";
  sock.on("connect", () => sock.write(line));
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
    if (
      isPermissionRequest &&
      (reply.verdict === "allow" || reply.verdict === "deny" || reply.verdict === "allow_always")
    ) {
      finish(() => emit(reply));
    } else {
      finish(passNoDecision);
    }
  });
  sock.on("error", () => finish(passNoDecision)); // relay not running
  sock.on("close", () => finish(passNoDecision)); // closed before a usable reply
}
