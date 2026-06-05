# Clairvoyant Relay (Node companion) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the host-side Node/TypeScript relay that attaches to the user's running Claude Code terminal sessions (via a PreToolUse hook) and gives the glasses a remote monitor + approve surface over a token-authenticated LAN WebSocket.

**Architecture:** A single Node process exposes (a) a local **Unix-socket** endpoint the PreToolUse hook calls on every tool call, (b) a **WebSocket** server the glasses connect to, and (c) an **HTTP dashboard** with a pairing QR. A `PermissionBridge` decides per call whether to *pass* (let Claude's own flow run) or *escalate* (route to the glasses and block the hook until answered). A `TranscriptTailer` streams each session's JSONL for monitoring. Pure logic (protocol, policy, parsing) is isolated from I/O (sockets, fs) for fast TDD.

**Tech Stack:** Node ≥ 22, TypeScript (ESM, NodeNext), `ws` (WebSocket), `qrcode` (server-side QR SVG), `vitest` (tests), `tsx` (dev) / `tsc` (build). The hook program is dependency-free plain JS (`node:net`).

**Spec:** `docs/superpowers/specs/2026-06-03-glasses-relay-pairing-design.md` (revised 2026-06-05). This plan covers the spec's Components 1, 1a, 2 and the transcript tailer — i.e. the whole Node companion. The Kotlin glasses app (Component 3) is out of scope here.

**Working directory:** all paths below are relative to a new `relay/` directory at the repo root, unless stated otherwise. Run all `npm`/`npx` commands from inside `relay/`.

**Key invariant (safe by construction):** the relay returns a hard `allow`/`deny` to the hook **only** when the glasses user explicitly answered an escalated request. Every other outcome is `pass` (the hook emits no decision → Claude's normal flow runs: auto-allow, or its own terminal prompt). Therefore no classification mistake in this relay can ever silently bypass a permission prompt Claude would have shown — it can only move a prompt (glasses↔terminal) or add a redundant one.

---

## Module map (`relay/`)

| File | Responsibility | I/O? |
|---|---|---|
| `src/protocol.ts` | Shared message types (hook IPC + WS) + `parseClientMessage` guard | pure |
| `src/describe.ts` | `describeTool(name, input)` → short human string | pure |
| `src/policy.ts` | `decide(req)` → `"escalate" \| "pass"` (mode-aware) | pure |
| `src/paths.ts` | `~/.clairvoyant` paths; transcript path resolution by session-id glob | fs |
| `src/token.ts` | load/generate/regenerate channel token (chmod 600) | fs |
| `src/address.ts` | LAN IPv4 discovery (injectable) | os |
| `src/sessions.ts` | `SessionRegistry` (map + EventEmitter) | pure |
| `src/hookSocket.ts` | `HookSocketServer` (unix socket, abort-on-close) | net |
| `src/permissionBridge.ts` | `PermissionBridge` (pass/escalate/await/fail-open/cancel) | logic |
| `src/glasses.ts` | `GlassesServer` (WS auth, broadcast, response routing, everPaired) | ws |
| `src/server.ts` | HTTP dashboard routes (`/`, `/pair`, `/status`, `/qr.svg`, `/regenerate-token`) | http |
| `src/transcriptParser.ts` | `parseTranscriptLine(line, session)` → events[] | pure |
| `src/transcriptTailer.ts` | `TranscriptTailer` (backfill + tail JSONL) | fs |
| `src/relay.ts` | `createRelay(config)` composition root + lifecycle | wiring |
| `src/install.ts` | `installHook(settingsPath, cmd)` into Claude `settings.json` | fs |
| `src/index.ts` | CLI dispatch (`start` \| `install-hook`) | entry |
| `hook/clairvoyant-hook.mjs` | dependency-free PreToolUse hook program | net |
| `public/dashboard.html` | pairing/status page (uses `/qr.svg`, `/pair`, `/status`) | view |

---

## Phase 1 — Foundation & pure logic

### Task 1: Scaffold the `relay/` project

**Files:**
- Create: `relay/package.json`, `relay/tsconfig.json`, `relay/vitest.config.ts`, `relay/.gitignore`
- Create: `relay/src/_smoke.ts`, `relay/test/smoke.test.ts`

- [ ] **Step 1: Create `relay/package.json`**

```json
{
  "name": "clairvoyant-relay",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "bin": { "clairvoyant-relay": "dist/index.js" },
  "scripts": {
    "dev": "tsx src/index.ts",
    "build": "tsc -p tsconfig.json",
    "start": "node dist/index.js",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "qrcode": "^1.5.4",
    "ws": "^8.18.0"
  },
  "devDependencies": {
    "@types/node": "^22.9.0",
    "@types/qrcode": "^1.5.5",
    "@types/ws": "^8.5.13",
    "tsx": "^4.19.0",
    "typescript": "^5.6.0",
    "vitest": "^2.1.0"
  }
}
```

- [ ] **Step 2: Create `relay/tsconfig.json`**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "lib": ["ES2022"],
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "forceConsistentCasingInFileNames": true,
    "resolveJsonModule": true,
    "declaration": false,
    "outDir": "dist",
    "rootDir": "src"
  },
  "include": ["src"]
}
```

- [ ] **Step 3: Create `relay/vitest.config.ts`**

```ts
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node",
    include: ["test/**/*.test.ts"],
  },
});
```

- [ ] **Step 4: Create `relay/.gitignore`**

```
node_modules/
dist/
```

- [ ] **Step 5: Write the smoke source + test (proves `.js`→`.ts` ESM resolution works under vitest)**

`relay/src/_smoke.ts`:
```ts
export const smoke = (a: number, b: number): number => a + b;
```

`relay/test/smoke.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { smoke } from "../src/_smoke.js";

describe("scaffold", () => {
  it("resolves a .js specifier to its .ts sibling and runs", () => {
    expect(smoke(1, 1)).toBe(2);
  });
});
```

- [ ] **Step 6: Install deps and run the smoke test**

Run: `cd relay && npm install && npx vitest run test/smoke.test.ts`
Expected: PASS — `Test Files  1 passed (1)`, `Tests  1 passed (1)`. (If module resolution fails here, stop and fix tsconfig/vitest before continuing.)

- [ ] **Step 7: Commit**

```bash
git add relay/package.json relay/tsconfig.json relay/vitest.config.ts relay/.gitignore relay/src/_smoke.ts relay/test/smoke.test.ts relay/package-lock.json
git commit -m "feat(relay): scaffold Node/TS project with vitest"
```

---

### Task 2: `protocol.ts` — message types + client-message parser

**Files:**
- Create: `relay/src/protocol.ts`
- Test: `relay/test/protocol.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/protocol.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { parseClientMessage } from "../src/protocol.js";

describe("parseClientMessage", () => {
  it("parses a valid hello", () => {
    expect(parseClientMessage('{"type":"hello","token":"abc"}')).toEqual({
      type: "hello",
      token: "abc",
    });
  });

  it("parses a valid permission_response", () => {
    expect(
      parseClientMessage(
        '{"type":"permission_response","session":"s1","id":"7","decision":"allow"}',
      ),
    ).toEqual({ type: "permission_response", session: "s1", id: "7", decision: "allow" });
  });

  it("rejects invalid JSON", () => {
    expect(parseClientMessage("{not json")).toBeNull();
  });

  it("rejects unknown/invalid shapes", () => {
    expect(parseClientMessage('{"type":"hello"}')).toBeNull(); // missing token
    expect(parseClientMessage('{"type":"nope"}')).toBeNull();
    expect(
      parseClientMessage('{"type":"permission_response","session":"s","id":"1","decision":"maybe"}'),
    ).toBeNull();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/protocol.test.ts`
Expected: FAIL — `Cannot find module '../src/protocol.js'`.

- [ ] **Step 3: Write the implementation**

`relay/src/protocol.ts`:
```ts
// ---- Hook IPC (relay <-> hook program), newline-delimited JSON over a unix socket ----

export interface HookRequest {
  session_id: string;
  cwd: string;
  tool_name: string;
  tool_input: Record<string, unknown>;
  permission_mode?: string;
  transcript_path?: string;
  hook_event_name?: string;
}

export type Verdict = "allow" | "deny" | "pass";

export interface HookReply {
  verdict: Verdict;
  reason?: string;
}

// ---- WebSocket protocol (glasses <-> relay) ----

export type SessionState = "idle" | "running" | "thinking";

export interface SessionInfo {
  id: string;
  title: string;
  state: SessionState;
}

export type ClientMessage =
  | { type: "hello"; token: string }
  | { type: "permission_response"; session: string; id: string; decision: "allow" | "deny" };

export type ServerMessage =
  | { type: "ready" }
  | { type: "session_list"; sessions: SessionInfo[] }
  | { type: "assistant_delta"; session: string; text: string }
  | { type: "turn_done"; session: string }
  | { type: "tool_use"; session: string; id: string; name: string; summary: string }
  | { type: "permission_request"; session: string; id: string; tool: string; description: string; mode?: string }
  | { type: "status"; session: string; state: SessionState }
  | { type: "error"; code: string; message: string };

export function parseClientMessage(raw: string): ClientMessage | null {
  let obj: unknown;
  try {
    obj = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof obj !== "object" || obj === null) return null;
  const m = obj as Record<string, unknown>;
  if (m.type === "hello" && typeof m.token === "string") {
    return { type: "hello", token: m.token };
  }
  if (
    m.type === "permission_response" &&
    typeof m.session === "string" &&
    typeof m.id === "string" &&
    (m.decision === "allow" || m.decision === "deny")
  ) {
    return { type: "permission_response", session: m.session, id: m.id, decision: m.decision };
  }
  return null;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/protocol.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add relay/src/protocol.ts relay/test/protocol.test.ts
git commit -m "feat(relay): protocol types + parseClientMessage"
```

---

### Task 3: `describe.ts` — human-readable tool summaries

**Files:**
- Create: `relay/src/describe.ts`
- Test: `relay/test/describe.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/describe.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { describeTool } from "../src/describe.js";

describe("describeTool", () => {
  it("summarizes Bash by its command", () => {
    expect(describeTool("Bash", { command: "git push origin main" })).toBe("git push origin main");
  });

  it("truncates very long Bash commands", () => {
    const long = "echo " + "x".repeat(300);
    const out = describeTool("Bash", { command: long });
    expect(out.length).toBeLessThanOrEqual(200);
  });

  it("summarizes file tools by path", () => {
    expect(describeTool("Write", { file_path: "/tmp/a.txt" })).toBe("Write /tmp/a.txt");
    expect(describeTool("Edit", { file_path: "/tmp/b.ts" })).toBe("Edit /tmp/b.ts");
  });

  it("falls back to the tool name", () => {
    expect(describeTool("WebFetch", { url: "https://x" })).toContain("WebFetch");
    expect(describeTool("MysteryTool", {})).toBe("MysteryTool");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/describe.test.ts`
Expected: FAIL — `Cannot find module '../src/describe.js'`.

- [ ] **Step 3: Write the implementation**

`relay/src/describe.ts`:
```ts
const MAX = 200;

function clip(s: string): string {
  return s.length > MAX ? s.slice(0, MAX - 1) + "…" : s;
}

export function describeTool(toolName: string, input: Record<string, unknown>): string {
  switch (toolName) {
    case "Bash": {
      const cmd = typeof input.command === "string" ? input.command : "";
      return clip(cmd || "Bash command");
    }
    case "Edit":
    case "Write":
    case "MultiEdit":
    case "NotebookEdit": {
      const p = (input.file_path ?? input.notebook_path ?? "") as string;
      return clip(`${toolName} ${p}`.trim());
    }
    case "Read":
    case "Glob":
    case "Grep": {
      const p = (input.file_path ?? input.path ?? input.pattern ?? "") as string;
      return clip(`${toolName} ${p}`.trim());
    }
    case "WebFetch":
    case "WebSearch": {
      const q = (input.url ?? input.query ?? "") as string;
      return clip(`${toolName} ${q}`.trim());
    }
    default:
      return toolName;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/describe.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add relay/src/describe.ts relay/test/describe.test.ts
git commit -m "feat(relay): describeTool summaries"
```

---

### Task 4: `policy.ts` — mode-aware escalation decision (the core)

**Files:**
- Create: `relay/src/policy.ts`
- Test: `relay/test/policy.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/policy.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { decide } from "../src/policy.js";

const req = (tool_name: string, permission_mode?: string) => ({ tool_name, permission_mode });

describe("decide", () => {
  it("passes read-only tools regardless of mode", () => {
    for (const t of ["Read", "Grep", "Glob", "NotebookRead", "TodoWrite"]) {
      expect(decide(req(t, "default"))).toBe("pass");
      expect(decide(req(t, undefined))).toBe("pass");
    }
  });

  it("escalates side-effecting tools in default mode", () => {
    expect(decide(req("Bash", "default"))).toBe("escalate");
    expect(decide(req("Write", "default"))).toBe("escalate");
    expect(decide(req("WebFetch", "default"))).toBe("escalate");
  });

  it("passes everything in bypassPermissions", () => {
    expect(decide(req("Bash", "bypassPermissions"))).toBe("pass");
    expect(decide(req("Write", "bypassPermissions"))).toBe("pass");
  });

  it("passes everything in plan mode", () => {
    expect(decide(req("Bash", "plan"))).toBe("pass");
  });

  it("in acceptEdits, passes edit tools but escalates the rest", () => {
    expect(decide(req("Edit", "acceptEdits"))).toBe("pass");
    expect(decide(req("Write", "acceptEdits"))).toBe("pass");
    expect(decide(req("MultiEdit", "acceptEdits"))).toBe("pass");
    expect(decide(req("NotebookEdit", "acceptEdits"))).toBe("pass");
    expect(decide(req("Bash", "acceptEdits"))).toBe("escalate");
  });

  it("treats unknown modes conservatively (escalate non-read)", () => {
    expect(decide(req("Bash", "someFutureMode"))).toBe("escalate");
    expect(decide(req("Read", "someFutureMode"))).toBe("pass");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/policy.test.ts`
Expected: FAIL — `Cannot find module '../src/policy.js'`.

- [ ] **Step 3: Write the implementation**

`relay/src/policy.ts`:
```ts
import type { HookRequest } from "./protocol.js";

/** Tools Claude Code auto-allows by default; never worth escalating. Extend conservatively. */
export const READ_ONLY_TOOLS = new Set(["Read", "Grep", "Glob", "NotebookRead", "TodoWrite"]);

/** Tools that `acceptEdits` mode auto-accepts. */
export const EDIT_TOOLS = new Set(["Edit", "Write", "MultiEdit", "NotebookEdit"]);

export type PolicyAction = "escalate" | "pass";

/**
 * Decide whether a tool call should be surfaced on the glasses ("escalate") or handed
 * back to Claude Code's own permission flow ("pass" => hook emits no decision).
 *
 * Safe by construction: "pass" means Claude either auto-allows or shows its OWN terminal
 * prompt, so a wrong answer here can only relocate a prompt or add a redundant one — it can
 * never silently bypass a prompt Claude would have shown.
 */
export function decide(req: Pick<HookRequest, "tool_name" | "permission_mode">): PolicyAction {
  if (READ_ONLY_TOOLS.has(req.tool_name)) return "pass";
  switch (req.permission_mode) {
    case "bypassPermissions":
    case "plan":
      return "pass";
    case "acceptEdits":
      return EDIT_TOOLS.has(req.tool_name) ? "pass" : "escalate";
    default:
      // "default", undefined, or any unknown/future mode: escalate (non-read tools only,
      // since read-only already returned "pass" above).
      return "escalate";
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/policy.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add relay/src/policy.ts relay/test/policy.test.ts
git commit -m "feat(relay): mode-aware escalation policy"
```

---

### Task 5: `paths.ts` — filesystem paths + transcript resolution

**Files:**
- Create: `relay/src/paths.ts`
- Test: `relay/test/paths.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/paths.test.ts`:
```ts
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  clairvoyantDir,
  ensureClairvoyantDir,
  resolveTranscriptPath,
  sockPath,
  tokenPath,
} from "../src/paths.js";

let home: string;
beforeEach(() => {
  home = fs.mkdtempSync(path.join(os.tmpdir(), "clv-paths-"));
});
afterEach(() => {
  fs.rmSync(home, { recursive: true, force: true });
});

describe("paths", () => {
  it("derives the dotdir + file paths under home", () => {
    expect(clairvoyantDir(home)).toBe(path.join(home, ".clairvoyant"));
    expect(tokenPath(home)).toBe(path.join(home, ".clairvoyant", "channel-token"));
    expect(sockPath(home)).toBe(path.join(home, ".clairvoyant", "relay.sock"));
  });

  it("ensureClairvoyantDir creates the dir", () => {
    ensureClairvoyantDir(home);
    expect(fs.existsSync(clairvoyantDir(home))).toBe(true);
  });

  it("resolves a transcript by globally-unique session id across project dirs", () => {
    const proj = path.join(home, ".claude", "projects", "-Users-x-proj");
    fs.mkdirSync(proj, { recursive: true });
    const file = path.join(proj, "SESSION-123.jsonl");
    fs.writeFileSync(file, "{}\n");
    expect(resolveTranscriptPath("SESSION-123", { home })).toBe(file);
  });

  it("prefers an existing hook-provided path", () => {
    const provided = path.join(home, "provided.jsonl");
    fs.writeFileSync(provided, "{}\n");
    expect(resolveTranscriptPath("whatever", { home, provided })).toBe(provided);
  });

  it("returns null when no transcript exists", () => {
    expect(resolveTranscriptPath("missing", { home })).toBeNull();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/paths.test.ts`
Expected: FAIL — `Cannot find module '../src/paths.js'`.

- [ ] **Step 3: Write the implementation**

`relay/src/paths.ts`:
```ts
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

export function clairvoyantDir(home: string = os.homedir()): string {
  return path.join(home, ".clairvoyant");
}

export function tokenPath(home: string = os.homedir()): string {
  return path.join(clairvoyantDir(home), "channel-token");
}

export function sockPath(home: string = os.homedir()): string {
  return path.join(clairvoyantDir(home), "relay.sock");
}

export function claudeProjectsDir(home: string = os.homedir()): string {
  return path.join(home, ".claude", "projects");
}

export function ensureClairvoyantDir(home: string = os.homedir()): void {
  fs.mkdirSync(clairvoyantDir(home), { recursive: true, mode: 0o700 });
}

/**
 * Resolve a session's transcript JSONL. Prefer a hook-provided path if it exists; otherwise
 * locate the file by its globally-unique session id (the filename is `<session_id>.jsonl`),
 * scanning every project dir — so we never depend on guessing Claude's cwd→dir encoding.
 */
export function resolveTranscriptPath(
  sessionId: string,
  opts: { home?: string; provided?: string } = {},
): string | null {
  if (opts.provided && fs.existsSync(opts.provided)) return opts.provided;
  const dir = claudeProjectsDir(opts.home);
  let projects: string[];
  try {
    projects = fs.readdirSync(dir);
  } catch {
    return null;
  }
  for (const proj of projects) {
    const candidate = path.join(dir, proj, `${sessionId}.jsonl`);
    if (fs.existsSync(candidate)) return candidate;
  }
  return null;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/paths.test.ts`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add relay/src/paths.ts relay/test/paths.test.ts
git commit -m "feat(relay): paths + session-id transcript resolution"
```

---

### Task 6: `token.ts` — channel token (load/generate/regenerate, chmod 600)

**Files:**
- Create: `relay/src/token.ts`
- Test: `relay/test/token.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/token.test.ts`:
```ts
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { loadOrCreateToken, regenerateToken } from "../src/token.js";
import { tokenPath } from "../src/paths.js";

let home: string;
beforeEach(() => {
  home = fs.mkdtempSync(path.join(os.tmpdir(), "clv-token-"));
});
afterEach(() => {
  fs.rmSync(home, { recursive: true, force: true });
});

describe("token", () => {
  it("generates and persists a token, then reuses it", () => {
    const a = loadOrCreateToken(home);
    expect(a).toMatch(/^[A-Za-z0-9_-]{16,}$/);
    expect(fs.existsSync(tokenPath(home))).toBe(true);
    expect(loadOrCreateToken(home)).toBe(a); // stable across calls
  });

  it("writes the token file as 0600", () => {
    loadOrCreateToken(home);
    const mode = fs.statSync(tokenPath(home)).mode & 0o777;
    expect(mode).toBe(0o600);
  });

  it("regenerate replaces the token", () => {
    const a = loadOrCreateToken(home);
    const b = regenerateToken(home);
    expect(b).not.toBe(a);
    expect(loadOrCreateToken(home)).toBe(b);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/token.test.ts`
Expected: FAIL — `Cannot find module '../src/token.js'`.

- [ ] **Step 3: Write the implementation**

`relay/src/token.ts`:
```ts
import crypto from "node:crypto";
import fs from "node:fs";
import { ensureClairvoyantDir, tokenPath } from "./paths.js";

function writeToken(home: string | undefined): string {
  ensureClairvoyantDir(home);
  const p = tokenPath(home);
  const token = crypto.randomBytes(24).toString("base64url");
  fs.writeFileSync(p, token, { mode: 0o600 });
  fs.chmodSync(p, 0o600); // enforce even if the file pre-existed with looser perms
  return token;
}

export function loadOrCreateToken(home?: string): string {
  const p = tokenPath(home);
  if (fs.existsSync(p)) {
    const existing = fs.readFileSync(p, "utf8").trim();
    if (existing) return existing;
  }
  return writeToken(home);
}

export function regenerateToken(home?: string): string {
  return writeToken(home);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/token.test.ts`
Expected: PASS (3 tests).

> Note: the 0600 assertion assumes a Unix host (macOS/Linux), which matches the target.

- [ ] **Step 5: Commit**

```bash
git add relay/src/token.ts relay/test/token.test.ts
git commit -m "feat(relay): channel token management"
```

---

### Task 7: `address.ts` — LAN IPv4 discovery

**Files:**
- Create: `relay/src/address.ts`
- Test: `relay/test/address.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/address.test.ts`:
```ts
import type * as os from "node:os";
import { describe, expect, it } from "vitest";
import { lanIPv4 } from "../src/address.js";

const ifaces = (entries: Record<string, { family: string; internal: boolean; address: string }[]>) =>
  entries as unknown as NodeJS.Dict<os.NetworkInterfaceInfo[]>;

describe("lanIPv4", () => {
  it("returns the first non-internal IPv4", () => {
    expect(
      lanIPv4(
        ifaces({
          lo0: [{ family: "IPv4", internal: true, address: "127.0.0.1" }],
          en0: [{ family: "IPv4", internal: false, address: "192.168.1.42" }],
        }),
      ),
    ).toBe("192.168.1.42");
  });

  it("skips IPv6 and internal, falling back to loopback", () => {
    expect(
      lanIPv4(
        ifaces({
          lo0: [{ family: "IPv4", internal: true, address: "127.0.0.1" }],
          en0: [{ family: "IPv6", internal: false, address: "fe80::1" }],
        }),
      ),
    ).toBe("127.0.0.1");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/address.test.ts`
Expected: FAIL — `Cannot find module '../src/address.js'`.

- [ ] **Step 3: Write the implementation**

`relay/src/address.ts`:
```ts
import * as os from "node:os";

/**
 * Best-effort LAN IPv4 for the pairing QR. Injectable for tests. Falls back to loopback
 * (the dashboard still shows it; the user can correct via the plaintext field).
 */
export function lanIPv4(
  ifaces: NodeJS.Dict<os.NetworkInterfaceInfo[]> = os.networkInterfaces(),
): string {
  for (const list of Object.values(ifaces)) {
    for (const ni of list ?? []) {
      if (ni.family === "IPv4" && !ni.internal) return ni.address;
    }
  }
  return "127.0.0.1";
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/address.test.ts`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add relay/src/address.ts relay/test/address.test.ts
git commit -m "feat(relay): LAN IPv4 discovery"
```

---

## Phase 2 — State & servers

### Task 8: `sessions.ts` — the session registry

**Files:**
- Create: `relay/src/sessions.ts`
- Test: `relay/test/sessions.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/sessions.test.ts`:
```ts
import { describe, expect, it, vi } from "vitest";
import { SessionRegistry } from "../src/sessions.js";

describe("SessionRegistry", () => {
  it("adds a session on first upsert and emits added + change", () => {
    const reg = new SessionRegistry();
    const added = vi.fn();
    const change = vi.fn();
    reg.on("added", added);
    reg.on("change", change);

    reg.upsert("s1", "/Users/x/projects/foo", "/t/s1.jsonl");

    expect(added).toHaveBeenCalledTimes(1);
    expect(change).toHaveBeenCalledTimes(1);
    expect(reg.list()).toEqual([{ id: "s1", title: "foo", state: "running" }]);
    expect(reg.get("s1")?.transcriptPath).toBe("/t/s1.jsonl");
  });

  it("does not re-add an existing session, but backfills a missing transcript path", () => {
    const reg = new SessionRegistry();
    reg.upsert("s1", "/Users/x/foo", null);
    const added = vi.fn();
    reg.on("added", added);
    reg.upsert("s1", "/Users/x/foo", "/t/s1.jsonl");
    expect(added).not.toHaveBeenCalled();
    expect(reg.get("s1")?.transcriptPath).toBe("/t/s1.jsonl");
  });

  it("tracks pending requests and exposes them for reconnect re-send", () => {
    const reg = new SessionRegistry();
    reg.upsert("s1", "/x", null);
    reg.setPending("s1", { id: "9", tool: "Bash", description: "ls", mode: "default" });
    expect(reg.get("s1")?.pending?.id).toBe("9");
    expect(reg.pendingRequests()).toEqual([
      { session: "s1", req: { id: "9", tool: "Bash", description: "ls", mode: "default" } },
    ]);
    reg.clearPending("s1");
    expect(reg.get("s1")?.pending).toBeNull();
    expect(reg.pendingRequests()).toEqual([]);
  });

  it("derives a title from the cwd basename", () => {
    const reg = new SessionRegistry();
    reg.upsert("s1", "/Users/x/projects/clairvoyant", null);
    expect(reg.list()[0].title).toBe("clairvoyant");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/sessions.test.ts`
Expected: FAIL — `Cannot find module '../src/sessions.js'`.

- [ ] **Step 3: Write the implementation**

`relay/src/sessions.ts`:
```ts
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/sessions.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add relay/src/sessions.ts relay/test/sessions.test.ts
git commit -m "feat(relay): session registry"
```

---

### Task 9: `hookSocket.ts` — Unix-socket server for the hook

The hook opens a connection, writes one JSON line (the PreToolUse payload), waits, reads one JSON line (the verdict), and closes. The handler is async and may block (while the glasses decide). If the connection closes **before** a verdict is written (Claude's hook timeout or Ctrl-C killed the hook), we abort the handler so the bridge can drop the pending request.

**Files:**
- Create: `relay/src/hookSocket.ts`
- Test: `relay/test/hookSocket.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/hookSocket.test.ts`:
```ts
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/hookSocket.test.ts`
Expected: FAIL — `Cannot find module '../src/hookSocket.js'`.

- [ ] **Step 3: Write the implementation**

`relay/src/hookSocket.ts`:
```ts
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/hookSocket.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add relay/src/hookSocket.ts relay/test/hookSocket.test.ts
git commit -m "feat(relay): unix-socket hook server with abort-on-close"
```

---

### Task 10: `permissionBridge.ts` — pass / escalate / await / fail-open / cancel

Ties policy + registry + glasses together for one hook request's lifecycle. No own timeout: the relay waits for either a `permission_response` or the hook socket closing (which `HookSocketServer` surfaces as an abort = Claude's hook timeout/Ctrl-C). On abort it returns `pass`, so Claude falls through to its terminal prompt — matching the chosen "long timeout, fall through" semantics.

**Files:**
- Create: `relay/src/permissionBridge.ts`
- Test: `relay/test/permissionBridge.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/permissionBridge.test.ts`:
```ts
import { describe, expect, it, vi } from "vitest";
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

const req = (over: Partial<Record<string, unknown>> = {}) => ({
  session_id: "s1",
  cwd: "/Users/x/foo",
  tool_name: "Bash",
  tool_input: { command: "rm -rf build" },
  permission_mode: "default",
  ...over,
}) as any;

describe("PermissionBridge", () => {
  it("passes read-only tools without bothering the glasses, but registers the session", async () => {
    const { bridge, registry, sent } = makeBridge();
    const reply = await bridge.handle(req({ tool_name: "Read", tool_input: { file_path: "/a" } }), new AbortController().signal);
    expect(reply).toEqual({ verdict: "pass" });
    expect(sent).toHaveLength(0);
    expect(registry.get("s1")).toBeDefined();
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/permissionBridge.test.ts`
Expected: FAIL — `Cannot find module '../src/permissionBridge.js'`.

- [ ] **Step 3: Write the implementation**

`relay/src/permissionBridge.ts`:
```ts
import { describeTool } from "./describe.js";
import { decide } from "./policy.js";
import type { HookReply, HookRequest, ServerMessage } from "./protocol.js";
import type { SessionRegistry } from "./sessions.js";

export interface GlassesGateway {
  everPaired(): boolean;
  isConnected(): boolean;
  send(msg: ServerMessage): void;
}

export interface BridgeDeps {
  registry: SessionRegistry;
  glasses: GlassesGateway;
  resolveTranscriptPath: (sessionId: string, provided?: string) => string | null;
  nextId: () => string;
}

type Resolution = "allow" | "deny" | "cancel";

export class PermissionBridge {
  private readonly pending = new Map<string, (r: Resolution) => void>();

  constructor(private readonly deps: BridgeDeps) {}

  async handle(req: HookRequest, signal: AbortSignal): Promise<HookReply> {
    const transcriptPath = this.deps.resolveTranscriptPath(req.session_id, req.transcript_path);
    this.deps.registry.upsert(req.session_id, req.cwd, transcriptPath);

    if (decide(req) === "pass") return { verdict: "pass" };
    if (!this.deps.glasses.everPaired()) return { verdict: "pass" }; // fail-open: no device exists

    const id = this.deps.nextId();
    const pendingReq = {
      id,
      tool: req.tool_name,
      description: describeTool(req.tool_name, req.tool_input),
      mode: req.permission_mode,
    };
    this.deps.registry.setPending(req.session_id, pendingReq);

    const message: ServerMessage = {
      type: "permission_request",
      session: req.session_id,
      ...pendingReq,
    };
    if (this.deps.glasses.isConnected()) this.deps.glasses.send(message);

    const resolution = await new Promise<Resolution>((resolve) => {
      this.pending.set(id, resolve);
      if (signal.aborted) resolve("cancel");
      else signal.addEventListener("abort", () => resolve("cancel"), { once: true });
    });

    this.pending.delete(id);
    this.deps.registry.clearPending(req.session_id);

    if (resolution === "cancel") return { verdict: "pass" }; // hook went away → Claude falls through
    return { verdict: resolution };
  }

  /** Called when a glasses `permission_response` arrives. */
  resolve(id: string, decision: "allow" | "deny"): void {
    const resolver = this.pending.get(id);
    if (resolver) resolver(decision);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/permissionBridge.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add relay/src/permissionBridge.ts relay/test/permissionBridge.test.ts
git commit -m "feat(relay): permission bridge (pass/escalate/await/fail-open/cancel)"
```

---

### Task 11: `glasses.ts` — WebSocket server (auth, broadcast, response routing)

**Files:**
- Create: `relay/src/glasses.ts`
- Test: `relay/test/glasses.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/glasses.test.ts`:
```ts
import http from "node:http";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { WebSocket } from "ws";
import { GlassesServer } from "../src/glasses.js";

let httpServer: http.Server;
let port: number;
let glasses: GlassesServer;
const onResponse = vi.fn();
const onAuth = vi.fn();

beforeEach(async () => {
  onResponse.mockReset();
  onAuth.mockReset();
  httpServer = http.createServer();
  glasses = new GlassesServer(httpServer, () => "secret", onResponse, onAuth);
  await new Promise<void>((res) => httpServer.listen(0, "127.0.0.1", () => res()));
  port = (httpServer.address() as import("node:net").AddressInfo).port;
});

afterEach(async () => {
  glasses.close();
  await new Promise<void>((res) => httpServer.close(() => res()));
});

function connect(): WebSocket {
  return new WebSocket(`ws://127.0.0.1:${port}/ws`);
}
function nextMessage(ws: WebSocket): Promise<any> {
  return new Promise((resolve) => ws.once("message", (d) => resolve(JSON.parse(d.toString()))));
}

describe("GlassesServer", () => {
  it("accepts a correct token: replies ready, sets everPaired/connected, fires onAuth", async () => {
    expect(glasses.everPaired()).toBe(false);
    const ws = connect();
    await new Promise((r) => ws.on("open", r));
    ws.send(JSON.stringify({ type: "hello", token: "secret" }));
    expect(await nextMessage(ws)).toEqual({ type: "ready" });
    expect(glasses.everPaired()).toBe(true);
    expect(glasses.isConnected()).toBe(true);
    expect(onAuth).toHaveBeenCalledTimes(1);
    ws.close();
  });

  it("rejects a bad token with an error and closes", async () => {
    const ws = connect();
    await new Promise((r) => ws.on("open", r));
    ws.send(JSON.stringify({ type: "hello", token: "wrong" }));
    expect(await nextMessage(ws)).toMatchObject({ type: "error", code: "bad_token" });
    await new Promise((r) => ws.on("close", r));
    expect(glasses.isConnected()).toBe(false);
  });

  it("routes permission_response to onResponse after auth", async () => {
    const ws = connect();
    await new Promise((r) => ws.on("open", r));
    ws.send(JSON.stringify({ type: "hello", token: "secret" }));
    await nextMessage(ws); // ready
    ws.send(JSON.stringify({ type: "permission_response", session: "s1", id: "3", decision: "deny" }));
    await vi.waitFor(() => expect(onResponse).toHaveBeenCalledWith("s1", "3", "deny"));
    ws.close();
  });

  it("broadcasts send() to authed clients only", async () => {
    const ws = connect();
    await new Promise((r) => ws.on("open", r));
    ws.send(JSON.stringify({ type: "hello", token: "secret" }));
    await nextMessage(ws); // ready
    const got = nextMessage(ws);
    glasses.send({ type: "session_list", sessions: [{ id: "s1", title: "foo", state: "running" }] });
    expect(await got).toEqual({ type: "session_list", sessions: [{ id: "s1", title: "foo", state: "running" }] });
    ws.close();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/glasses.test.ts`
Expected: FAIL — `Cannot find module '../src/glasses.js'`.

- [ ] **Step 3: Write the implementation**

`relay/src/glasses.ts`:
```ts
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
          ws.send(
            JSON.stringify({
              type: "error",
              code: "bad_token",
              message: "Pairing expired, re-scan QR.",
            } satisfies ServerMessage),
          );
          ws.close();
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/glasses.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add relay/src/glasses.ts relay/test/glasses.test.ts
git commit -m "feat(relay): glasses WebSocket server with token auth"
```

---

### Task 12: `server.ts` — HTTP dashboard routes (incl. server-side QR)

**Files:**
- Create: `relay/src/server.ts`
- Test: `relay/test/server.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/server.test.ts`:
```ts
import type { AddressInfo } from "node:net";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createHttpServer, type DashboardState } from "../src/server.js";

let server: import("node:http").Server;
let base: string;
let currentToken = "tok-1";

const state: DashboardState = {
  host: "192.168.1.5",
  port: 4317,
  get token() {
    return currentToken;
  },
  glassesConnected: () => true,
  sessions: () => [{ id: "s1", title: "foo", state: "running" }],
  regenerate: () => {
    currentToken = "tok-2";
    return currentToken;
  },
};

beforeEach(async () => {
  currentToken = "tok-1";
  // publicDir points at the real public/ folder created in Task 20; for this test we only
  // need the routes that don't read a file, plus a tolerant "/" (404 if file missing is fine).
  server = createHttpServer(state, new URL("../public", import.meta.url).pathname);
  await new Promise<void>((res) => server.listen(0, "127.0.0.1", () => res()));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
afterEach(async () => {
  await new Promise<void>((res) => server.close(() => res()));
});

describe("dashboard HTTP", () => {
  it("GET /pair returns the pairing URL with current host/port/token", async () => {
    const res = await fetch(`${base}/pair`);
    const body = await res.json();
    expect(body.url).toBe("clairvoyant://pair?host=192.168.1.5&port=4317&token=tok-1");
    expect(body).toMatchObject({ host: "192.168.1.5", port: 4317, token: "tok-1" });
  });

  it("GET /status reports glasses + sessions", async () => {
    const res = await fetch(`${base}/status`);
    expect(await res.json()).toEqual({
      glassesConnected: true,
      sessions: [{ id: "s1", title: "foo", state: "running" }],
      host: "192.168.1.5",
      port: 4317,
    });
  });

  it("GET /qr.svg returns an SVG of the pairing URL", async () => {
    const res = await fetch(`${base}/qr.svg`);
    expect(res.headers.get("content-type")).toContain("image/svg+xml");
    expect(await res.text()).toContain("<svg");
  });

  it("POST /regenerate-token rotates the token", async () => {
    const res = await fetch(`${base}/regenerate-token`, { method: "POST" });
    expect(await res.json()).toEqual({ token: "tok-2" });
    const after = await (await fetch(`${base}/pair`)).json();
    expect(after.token).toBe("tok-2");
  });

  it("unknown route → 404", async () => {
    expect((await fetch(`${base}/nope`)).status).toBe(404);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/server.test.ts`
Expected: FAIL — `Cannot find module '../src/server.js'`.

- [ ] **Step 3: Write the implementation**

`relay/src/server.ts`:
```ts
import fs from "node:fs";
import * as http from "node:http";
import path from "node:path";
import QRCode from "qrcode";

export interface DashboardState {
  host: string;
  port: number;
  token: string;
  glassesConnected: () => boolean;
  sessions: () => { id: string; title: string; state: string }[];
  regenerate: () => string;
}

function pairUrl(state: DashboardState): string {
  return `clairvoyant://pair?host=${state.host}&port=${state.port}&token=${state.token}`;
}

function sendJson(res: http.ServerResponse, body: unknown): void {
  res.writeHead(200, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
}

export function createHttpServer(state: DashboardState, publicDir: string): http.Server {
  return http.createServer((req, res) => {
    void handle(req, res).catch(() => {
      if (!res.headersSent) res.writeHead(500);
      res.end("error");
    });
  });

  async function handle(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const method = req.method ?? "GET";
    const url = (req.url ?? "/").split("?")[0];

    if (method === "GET" && (url === "/" || url === "/index.html")) {
      const file = path.join(publicDir, "dashboard.html");
      try {
        const html = fs.readFileSync(file);
        res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
        res.end(html);
      } catch {
        res.writeHead(404);
        res.end("dashboard.html not found");
      }
      return;
    }
    if (method === "GET" && url === "/pair") {
      sendJson(res, { url: pairUrl(state), host: state.host, port: state.port, token: state.token });
      return;
    }
    if (method === "GET" && url === "/status") {
      sendJson(res, {
        glassesConnected: state.glassesConnected(),
        sessions: state.sessions(),
        host: state.host,
        port: state.port,
      });
      return;
    }
    if (method === "GET" && url === "/qr.svg") {
      const svg = await QRCode.toString(pairUrl(state), { type: "svg", margin: 1 });
      res.writeHead(200, { "content-type": "image/svg+xml" });
      res.end(svg);
      return;
    }
    if (method === "POST" && url === "/regenerate-token") {
      const token = state.regenerate();
      sendJson(res, { token });
      return;
    }
    res.writeHead(404);
    res.end("not found");
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/server.test.ts`
Expected: PASS (5 tests). (The `/` route 404s until Task 20 adds `public/dashboard.html`; this test does not hit `/`.)

- [ ] **Step 5: Commit**

```bash
git add relay/src/server.ts relay/test/server.test.ts
git commit -m "feat(relay): HTTP dashboard routes + server-side QR"
```

---

## Phase 3 — Compose, hook program, install, CLI

### Task 13: `relay.ts` — composition root + end-to-end integration test

Wires registry + bridge + hook socket + glasses + http. On glasses (re)connect, sends the full `session_list` and re-sends every pending `permission_request`. (The transcript tailer is added in Task 19; this version is a complete, working *approval* relay.)

**Files:**
- Create: `relay/src/relay.ts`
- Test: `relay/test/relay.integration.test.ts`

- [ ] **Step 1: Write the failing integration test**

`relay/test/relay.integration.test.ts`:
```ts
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
});
const read = (session: string) => ({
  session_id: session,
  cwd: `/Users/x/${session}`,
  tool_name: "Read",
  tool_input: { file_path: "/a" },
  permission_mode: "default",
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/relay.integration.test.ts`
Expected: FAIL — `Cannot find module '../src/relay.js'`.

- [ ] **Step 3: Write the implementation**

`relay/src/relay.ts`:
```ts
import type { AddressInfo } from "node:net";
import { lanIPv4 } from "./address.js";
import { GlassesServer } from "./glasses.js";
import { HookSocketServer } from "./hookSocket.js";
import { PermissionBridge } from "./permissionBridge.js";
import { resolveTranscriptPath, sockPath } from "./paths.js";
import { createHttpServer, type DashboardState } from "./server.js";
import { SessionRegistry } from "./sessions.js";
import { loadOrCreateToken, regenerateToken } from "./token.js";

export interface RelayConfig {
  home?: string;
  host?: string;
  port?: number;
  publicDir?: string;
  socketPath?: string;
}

export interface Relay {
  start(): Promise<void>;
  stop(): Promise<void>;
  readonly host: string;
  readonly port: number;
  readonly token: string;
}

export function createRelay(config: RelayConfig = {}): Relay {
  const home = config.home;
  let token = loadOrCreateToken(home);
  const host = config.host ?? lanIPv4();
  const desiredPort = config.port ?? 4317;
  const publicDir = config.publicDir ?? new URL("../public", import.meta.url).pathname;
  const socket = config.socketPath ?? sockPath(home);

  const registry = new SessionRegistry();
  let idCounter = 0;
  const nextId = () => String(++idCounter);

  let glasses!: GlassesServer;
  let runtimePort = desiredPort;

  const dashboardState: DashboardState = {
    host,
    get port() {
      return runtimePort;
    },
    get token() {
      return token;
    },
    glassesConnected: () => glasses.isConnected(),
    sessions: () => registry.list(),
    regenerate: () => {
      token = regenerateToken(home);
      return token;
    },
  };

  const httpServer = createHttpServer(dashboardState, publicDir);

  const bridge = new PermissionBridge({
    registry,
    glasses: {
      everPaired: () => glasses.everPaired(),
      isConnected: () => glasses.isConnected(),
      send: (m) => glasses.send(m),
    },
    resolveTranscriptPath: (sessionId, provided) => resolveTranscriptPath(sessionId, { home, provided }),
    nextId,
  });

  glasses = new GlassesServer(
    httpServer,
    () => token,
    (_session, id, decision) => bridge.resolve(id, decision),
    () => {
      glasses.send({ type: "session_list", sessions: registry.list() });
      for (const { session, req } of registry.pendingRequests()) {
        glasses.send({ type: "permission_request", session, ...req });
      }
    },
  );

  const hookServer = new HookSocketServer(socket, (req, signal) => bridge.handle(req, signal));

  registry.on("change", () => {
    glasses.send({ type: "session_list", sessions: registry.list() });
  });

  return {
    get host() {
      return host;
    },
    get port() {
      return runtimePort;
    },
    get token() {
      return token;
    },
    async start() {
      await hookServer.start();
      await new Promise<void>((resolve) => httpServer.listen(desiredPort, () => resolve()));
      runtimePort = (httpServer.address() as AddressInfo).port;
    },
    async stop() {
      glasses.close();
      await hookServer.stop();
      await new Promise<void>((resolve) => httpServer.close(() => resolve()));
    },
  };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/relay.integration.test.ts`
Expected: PASS (5 tests).

- [ ] **Step 5: Run the whole suite**

Run: `npx vitest run`
Expected: PASS — all test files green.

- [ ] **Step 6: Commit**

```bash
git add relay/src/relay.ts relay/test/relay.integration.test.ts
git commit -m "feat(relay): compose relay + end-to-end integration tests"
```

---

### Task 14: `hook/clairvoyant-hook.mjs` — the PreToolUse hook program

Dependency-free, no build step. Reads the PreToolUse JSON on stdin, forwards it verbatim to the relay unix socket, and maps the verdict: `allow`/`deny` → a `permissionDecision`; `pass`, a missing relay, or a closed-without-reply connection → **no output** (Claude runs its normal flow). Always exits 0.

**Files:**
- Create: `relay/hook/clairvoyant-hook.mjs`
- Test: `relay/test/hook.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/hook.test.ts`:
```ts
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

function runHook(env: Record<string, string>, payload: object): Promise<{ stdout: string; code: number | null }> {
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

describe("clairvoyant-hook.mjs", () => {
  it("emits permissionDecision=allow when the relay replies allow", async () => {
    const server = await fakeRelay(sock, { verdict: "allow", reason: "glasses" });
    const { stdout, code } = await runHook({ CLAIRVOYANT_SOCK: sock }, { tool_name: "Bash", tool_input: { command: "ls" } });
    server.close();
    expect(code).toBe(0);
    expect(JSON.parse(stdout)).toEqual({
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "allow",
        permissionDecisionReason: "glasses",
      },
    });
  });

  it("emits permissionDecision=deny when the relay replies deny", async () => {
    const server = await fakeRelay(sock, { verdict: "deny" });
    const { stdout } = await runHook({ CLAIRVOYANT_SOCK: sock }, { tool_name: "Bash", tool_input: {} });
    server.close();
    expect(JSON.parse(stdout).hookSpecificOutput.permissionDecision).toBe("deny");
  });

  it("emits nothing on pass", async () => {
    const server = await fakeRelay(sock, { verdict: "pass" });
    const { stdout, code } = await runHook({ CLAIRVOYANT_SOCK: sock }, { tool_name: "Read", tool_input: {} });
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/hook.test.ts`
Expected: FAIL — spawn cannot find `../hook/clairvoyant-hook.mjs` (child `error`/non-zero), i.e. the test rejects/fails.

- [ ] **Step 3: Write the implementation**

`relay/hook/clairvoyant-hook.mjs`:
```js
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/hook.test.ts`
Expected: PASS (5 tests).

- [ ] **Step 5: Make the hook executable (optional, since install invokes it via `node`)**

Run: `chmod +x relay/hook/clairvoyant-hook.mjs`

- [ ] **Step 6: Commit**

```bash
git add relay/hook/clairvoyant-hook.mjs relay/test/hook.test.ts
git commit -m "feat(relay): dependency-free PreToolUse hook program"
```

---

### Task 15: `install.ts` — install the hook into Claude `settings.json`

**Files:**
- Create: `relay/src/install.ts`
- Test: `relay/test/install.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/install.test.ts`:
```ts
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { DEFAULT_HOOK_TIMEOUT, installHook } from "../src/install.js";

let dir: string;
let settings: string;
beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), "clv-install-"));
  settings = path.join(dir, ".claude", "settings.json");
});
afterEach(() => {
  fs.rmSync(dir, { recursive: true, force: true });
});
const read = () => JSON.parse(fs.readFileSync(settings, "utf8"));

describe("installHook", () => {
  it("creates settings with a PreToolUse hook (matcher * + high timeout)", () => {
    const r = installHook(settings, 'node "/abs/hook.mjs"');
    expect(r.changed).toBe(true);
    const s = read();
    expect(s.hooks.PreToolUse).toHaveLength(1);
    expect(s.hooks.PreToolUse[0]).toEqual({
      matcher: "*",
      hooks: [{ type: "command", command: 'node "/abs/hook.mjs"', timeout: DEFAULT_HOOK_TIMEOUT }],
    });
  });

  it("is idempotent", () => {
    installHook(settings, "node /abs/hook.mjs");
    const r2 = installHook(settings, "node /abs/hook.mjs");
    expect(r2.changed).toBe(false);
    expect(read().hooks.PreToolUse).toHaveLength(1);
  });

  it("preserves existing settings and other hooks", () => {
    fs.mkdirSync(path.dirname(settings), { recursive: true });
    fs.writeFileSync(
      settings,
      JSON.stringify({
        model: "opus",
        hooks: { PreToolUse: [{ matcher: "Bash", hooks: [{ type: "command", command: "other" }] }] },
      }),
    );
    installHook(settings, "node /abs/hook.mjs");
    const s = read();
    expect(s.model).toBe("opus");
    expect(s.hooks.PreToolUse).toHaveLength(2);
    expect(s.hooks.PreToolUse.some((e: any) => e.hooks[0].command === "other")).toBe(true);
  });

  it("warns when deny rules exist", () => {
    fs.mkdirSync(path.dirname(settings), { recursive: true });
    fs.writeFileSync(settings, JSON.stringify({ permissions: { deny: ["Bash(rm:*)"] } }));
    const r = installHook(settings, "node /abs/hook.mjs");
    expect(r.warnings.join(" ")).toMatch(/deny/);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/install.test.ts`
Expected: FAIL — `Cannot find module '../src/install.js'`.

- [ ] **Step 3: Write the implementation**

`relay/src/install.ts`:
```ts
import fs from "node:fs";
import path from "node:path";

export interface InstallResult {
  changed: boolean;
  warnings: string[];
}

/** 12h — long enough that escalated requests effectively "stay pending". */
export const DEFAULT_HOOK_TIMEOUT = 43200;

export function installHook(
  settingsPath: string,
  hookCommand: string,
  opts: { timeout?: number } = {},
): InstallResult {
  const timeout = opts.timeout ?? DEFAULT_HOOK_TIMEOUT;
  const warnings: string[] = [];

  let settings: Record<string, any> = {};
  if (fs.existsSync(settingsPath)) {
    const raw = fs.readFileSync(settingsPath, "utf8").trim();
    if (raw) settings = JSON.parse(raw);
  }

  settings.hooks ??= {};
  settings.hooks.PreToolUse ??= [];

  const deny = settings.permissions?.deny;
  if (Array.isArray(deny) && deny.length > 0) {
    warnings.push(
      `permissions.deny has ${deny.length} rule(s); a matching deny still blocks a glasses-approved call (deny > hook).`,
    );
  }

  const already = settings.hooks.PreToolUse.some((entry: any) =>
    (entry?.hooks ?? []).some((h: any) => h?.command === hookCommand),
  );
  if (already) return { changed: false, warnings };

  settings.hooks.PreToolUse.push({
    matcher: "*",
    hooks: [{ type: "command", command: hookCommand, timeout }],
  });

  fs.mkdirSync(path.dirname(settingsPath), { recursive: true });
  fs.writeFileSync(settingsPath, JSON.stringify(settings, null, 2) + "\n");
  return { changed: true, warnings };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/install.test.ts`
Expected: PASS (4 tests).

> Note: `matcher: "*"` is the value we believe matches all tools; Task 21 confirms this empirically against live Claude Code. If the live check shows a different convention (e.g. omitting `matcher`), change the single object literal here.

- [ ] **Step 5: Commit**

```bash
git add relay/src/install.ts relay/test/install.test.ts
git commit -m "feat(relay): install PreToolUse hook into settings.json"
```

---

### Task 16: `index.ts` — CLI entry (`start` | `install-hook`)

**Files:**
- Create: `relay/src/index.ts`
- Test: `relay/test/cli.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/cli.test.ts`:
```ts
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { hookCommand, main } from "../src/index.js";

let dir: string;
beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), "clv-cli-"));
});
afterEach(() => {
  fs.rmSync(dir, { recursive: true, force: true });
  vi.restoreAllMocks();
});

describe("CLI", () => {
  it("hookCommand points at the bundled hook program via node", () => {
    expect(hookCommand()).toMatch(/^node "/);
    expect(hookCommand()).toMatch(/clairvoyant-hook\.mjs/);
  });

  it("install-hook writes the hook into the given settings file", async () => {
    const settings = path.join(dir, "settings.json");
    vi.spyOn(console, "log").mockImplementation(() => {});
    await main(["install-hook", settings]);
    const s = JSON.parse(fs.readFileSync(settings, "utf8"));
    expect(s.hooks.PreToolUse[0].hooks[0].command).toMatch(/clairvoyant-hook\.mjs/);
    expect(s.hooks.PreToolUse[0].matcher).toBe("*");
  });

  it("unknown command sets a non-zero exit code", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    process.exitCode = 0;
    await main(["bogus"]);
    expect(process.exitCode).toBe(1);
    process.exitCode = 0;
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/cli.test.ts`
Expected: FAIL — `Cannot find module '../src/index.js'`.

- [ ] **Step 3: Write the implementation**

`relay/src/index.ts`:
```ts
#!/usr/bin/env node
import path from "node:path";
import { fileURLToPath } from "node:url";
import { installHook } from "./install.js";
import { sockPath, tokenPath } from "./paths.js";
import { createRelay } from "./relay.js";

export function hookCommand(): string {
  const hookPath = fileURLToPath(new URL("../hook/clairvoyant-hook.mjs", import.meta.url));
  return `node "${hookPath}"`;
}

function defaultSettingsPath(): string {
  return path.join(process.env.HOME ?? process.cwd(), ".claude", "settings.json");
}

export async function main(argv: string[] = process.argv.slice(2)): Promise<void> {
  const cmd = argv[0] ?? "start";

  if (cmd === "install-hook") {
    const settings = argv[1] ?? defaultSettingsPath();
    const result = installHook(settings, hookCommand());
    console.log(
      result.changed ? `Installed PreToolUse hook into ${settings}` : `Hook already present in ${settings}`,
    );
    for (const w of result.warnings) console.warn("⚠ " + w);
    return;
  }

  if (cmd === "start") {
    const relay = createRelay();
    await relay.start();
    console.log("Clairvoyant relay listening:");
    console.log(`  dashboard  http://${relay.host}:${relay.port}/`);
    console.log(`  hook IPC   ${sockPath()}`);
    console.log(`  token file ${tokenPath()}`);
    console.log("Open the dashboard and scan the QR with the glasses to pair.");
    return;
  }

  console.error(`Unknown command: ${cmd}. Usage: clairvoyant-relay [start|install-hook [settingsPath]]`);
  process.exitCode = 1;
}

const invokedDirectly =
  !!process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
if (invokedDirectly) {
  main().catch((err) => {
    console.error(err);
    process.exitCode = 1;
  });
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/cli.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the whole suite + a real start smoke test**

Run: `npx vitest run`
Expected: PASS — all files green.

Run: `npx tsx src/index.ts start` (then Ctrl-C after you see the listening banner)
Expected: prints the dashboard URL, hook IPC path, and token file path; `http://<host>:<port>/status` returns JSON if you curl it in another terminal.

- [ ] **Step 6: Commit**

```bash
git add relay/src/index.ts relay/test/cli.test.ts
git commit -m "feat(relay): CLI entry (start | install-hook)"
```

> **Milestone — approval companion works.** At this point the relay gates real tool calls end-to-end (everything except the dashboard HTML view and live transcript monitoring). Phase 4 adds monitoring; Phase 5 adds the dashboard page and the live-Claude verification.

---

## Phase 4 — Transcript monitoring

### Task 17: `transcriptParser.ts` — parse a JSONL line into protocol events

Maps a Claude Code transcript line (real schema, validated 2026-06-05) to glasses events: assistant `text` → `assistant_delta`; `tool_use` → `tool_use`; an assistant message whose `stop_reason` is not `tool_use` → `turn_done`. Skips `thinking`, user `tool_result`, sidechain (subagent) lines, metadata lines, and malformed JSON.

**Files:**
- Create: `relay/test/fixtures/transcript-sample.jsonl`
- Create: `relay/src/transcriptParser.ts`
- Test: `relay/test/transcriptParser.test.ts`

- [ ] **Step 1: Create the fixture** `relay/test/fixtures/transcript-sample.jsonl` (exactly these 6 lines; the last is intentionally malformed):

```
{"type":"assistant","isSidechain":false,"sessionId":"S","uuid":"u1","message":{"role":"assistant","content":[{"type":"text","text":"On it - reading the file."}],"stop_reason":"end_turn"}}
{"type":"assistant","isSidechain":false,"sessionId":"S","uuid":"u2","message":{"role":"assistant","content":[{"type":"thinking","thinking":"Let me check"},{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls -la"}}],"stop_reason":"tool_use"}}
{"type":"user","isSidechain":false,"sessionId":"S","uuid":"u3","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"total 0"}]}}
{"type":"assistant","isSidechain":true,"sessionId":"S","uuid":"u4","message":{"role":"assistant","content":[{"type":"text","text":"subagent chatter"}],"stop_reason":"end_turn"}}
{"type":"mode","mode":"default","sessionId":"S"}
{"type":"assistant", BROKEN
```

- [ ] **Step 2: Write the failing test**

`relay/test/transcriptParser.test.ts`:
```ts
import fs from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { parseTranscriptLine } from "../src/transcriptParser.js";

const fixture = fileURLToPath(new URL("./fixtures/transcript-sample.jsonl", import.meta.url));
const lines = fs.readFileSync(fixture, "utf8").split("\n").filter(Boolean);

describe("parseTranscriptLine", () => {
  it("emits assistant_delta then turn_done for an end_turn text message", () => {
    expect(parseTranscriptLine(lines[0], "S")).toEqual([
      { type: "assistant_delta", session: "S", text: "On it - reading the file." },
      { type: "turn_done", session: "S" },
    ]);
  });

  it("emits tool_use (skipping thinking) and no turn_done when stop_reason is tool_use", () => {
    expect(parseTranscriptLine(lines[1], "S")).toEqual([
      { type: "tool_use", session: "S", id: "toolu_1", name: "Bash", summary: "ls -la" },
    ]);
  });

  it("skips user tool_result lines in v1", () => {
    expect(parseTranscriptLine(lines[2], "S")).toEqual([]);
  });

  it("skips sidechain (subagent) lines", () => {
    expect(parseTranscriptLine(lines[3], "S")).toEqual([]);
  });

  it("ignores non-message metadata lines", () => {
    expect(parseTranscriptLine(lines[4], "S")).toEqual([]);
  });

  it("ignores malformed JSON", () => {
    expect(parseTranscriptLine(lines[5], "S")).toEqual([]);
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `npx vitest run test/transcriptParser.test.ts`
Expected: FAIL — `Cannot find module '../src/transcriptParser.js'`.

- [ ] **Step 4: Write the implementation**

`relay/src/transcriptParser.ts`:
```ts
import { describeTool } from "./describe.js";
import type { ServerMessage } from "./protocol.js";

/**
 * Parse one Claude Code transcript JSONL line into zero or more glasses events.
 * Best-effort: the JSONL schema is internal and may change; unknown shapes yield [].
 */
export function parseTranscriptLine(line: string, session: string): ServerMessage[] {
  let obj: any;
  try {
    obj = JSON.parse(line);
  } catch {
    return [];
  }
  if (!obj || typeof obj !== "object") return [];
  if (obj.isSidechain) return []; // subagent traffic — not shown in v1
  if (obj.type !== "assistant") return []; // v1 streams assistant text + tool_use only

  const content = obj.message?.content;
  if (!Array.isArray(content)) return [];

  const out: ServerMessage[] = [];
  for (const c of content) {
    if (c?.type === "text" && typeof c.text === "string" && c.text.length > 0) {
      out.push({ type: "assistant_delta", session, text: c.text });
    } else if (c?.type === "tool_use" && typeof c.name === "string") {
      out.push({
        type: "tool_use",
        session,
        id: String(c.id ?? ""),
        name: c.name,
        summary: describeTool(c.name, c.input ?? {}),
      });
    }
    // "thinking" and other block types are intentionally skipped in v1
  }
  const stop = obj.message?.stop_reason;
  if (stop && stop !== "tool_use") out.push({ type: "turn_done", session });
  return out;
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `npx vitest run test/transcriptParser.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add relay/src/transcriptParser.ts relay/test/transcriptParser.test.ts relay/test/fixtures/transcript-sample.jsonl
git commit -m "feat(relay): transcript line parser"
```

---

### Task 18: `transcriptTailer.ts` — backfill + tail a session's JSONL

Polling tailer (no `fs.watch`, for deterministic behavior). On `start()` it backfills the last N lines, then a low-frequency interval calls `pump()`, which reads only newly-appended bytes and buffers partial trailing lines. `pump()` is public so tests can drive it deterministically.

**Files:**
- Create: `relay/src/transcriptTailer.ts`
- Test: `relay/test/transcriptTailer.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/transcriptTailer.test.ts`:
```ts
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TranscriptTailer } from "../src/transcriptTailer.js";
import type { ServerMessage } from "../src/protocol.js";

const A_TEXT = `{"type":"assistant","isSidechain":false,"message":{"content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn"}}`;
const A_TOOL = `{"type":"assistant","isSidechain":false,"message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}],"stop_reason":"tool_use"}}`;

let dir: string;
let file: string;
let events: ServerMessage[];
let tailer: TranscriptTailer;

beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), "clv-tail-"));
  file = path.join(dir, "S.jsonl");
  events = [];
});
afterEach(() => {
  tailer?.stop();
  fs.rmSync(dir, { recursive: true, force: true });
});

function make(opts?: { backfillLines?: number }) {
  tailer = new TranscriptTailer(file, "S", (m) => events.push(m), { ...opts, pollIntervalMs: 10_000 });
  return tailer;
}

describe("TranscriptTailer", () => {
  it("backfills existing lines on start", () => {
    fs.writeFileSync(file, A_TEXT + "\n" + A_TOOL + "\n");
    make().start();
    expect(events).toEqual([
      { type: "assistant_delta", session: "S", text: "hello" },
      { type: "turn_done", session: "S" },
      { type: "tool_use", session: "S", id: "t1", name: "Bash", summary: "ls" },
    ]);
  });

  it("respects backfillLines", () => {
    fs.writeFileSync(file, A_TEXT + "\n" + A_TOOL + "\n");
    make({ backfillLines: 1 }).start();
    expect(events).toEqual([{ type: "tool_use", session: "S", id: "t1", name: "Bash", summary: "ls" }]);
  });

  it("emits newly appended lines on pump(), only-new each time", () => {
    fs.writeFileSync(file, "");
    make().start();
    expect(events).toEqual([]);
    fs.appendFileSync(file, A_TEXT + "\n");
    tailer.pump();
    expect(events).toEqual([
      { type: "assistant_delta", session: "S", text: "hello" },
      { type: "turn_done", session: "S" },
    ]);
    fs.appendFileSync(file, A_TOOL + "\n");
    tailer.pump();
    expect(events.at(-1)).toEqual({ type: "tool_use", session: "S", id: "t1", name: "Bash", summary: "ls" });
  });

  it("buffers a partial line until its newline arrives", () => {
    fs.writeFileSync(file, "");
    make().start();
    fs.appendFileSync(file, A_TEXT.slice(0, 20)); // partial, no newline
    tailer.pump();
    expect(events).toEqual([]);
    fs.appendFileSync(file, A_TEXT.slice(20) + "\n");
    tailer.pump();
    expect(events[0]).toEqual({ type: "assistant_delta", session: "S", text: "hello" });
  });

  it("ignores malformed appended lines without throwing", () => {
    fs.writeFileSync(file, "");
    make().start();
    fs.appendFileSync(file, "{broken\n");
    expect(() => tailer.pump()).not.toThrow();
    expect(events).toEqual([]);
  });

  it("starts before the file exists and picks it up on pump()", () => {
    make().start(); // file absent
    expect(events).toEqual([]);
    fs.writeFileSync(file, A_TEXT + "\n");
    tailer.pump();
    expect(events).toEqual([
      { type: "assistant_delta", session: "S", text: "hello" },
      { type: "turn_done", session: "S" },
    ]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/transcriptTailer.test.ts`
Expected: FAIL — `Cannot find module '../src/transcriptTailer.js'`.

- [ ] **Step 3: Write the implementation**

`relay/src/transcriptTailer.ts`:
```ts
import * as fs from "node:fs";
import { parseTranscriptLine } from "./transcriptParser.js";
import type { ServerMessage } from "./protocol.js";

export class TranscriptTailer {
  private pos = 0;
  private carry = "";
  private timer?: ReturnType<typeof setInterval>;
  private started = false;

  constructor(
    private readonly file: string,
    private readonly session: string,
    private readonly emit: (msg: ServerMessage) => void,
    private readonly opts: { backfillLines?: number; pollIntervalMs?: number } = {},
  ) {}

  start(): void {
    if (this.started) return;
    this.started = true;
    this.backfill();
    this.timer = setInterval(() => this.pump(), this.opts.pollIntervalMs ?? 300);
    this.timer.unref?.();
  }

  stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = undefined;
    this.started = false;
  }

  /** Read bytes appended since the last read and emit parsed events. Public for tests. */
  pump(): void {
    let stat: fs.Stats;
    try {
      stat = fs.statSync(this.file);
    } catch {
      return; // not present yet
    }
    if (stat.size < this.pos) {
      this.pos = 0; // truncated/rotated — restart
      this.carry = "";
    }
    if (stat.size === this.pos) return;
    const fd = fs.openSync(this.file, "r");
    try {
      const length = stat.size - this.pos;
      const buf = Buffer.alloc(length);
      fs.readSync(fd, buf, 0, length, this.pos);
      this.pos = stat.size;
      this.consume(buf.toString("utf8"));
    } finally {
      fs.closeSync(fd);
    }
  }

  private consume(chunk: string): void {
    this.carry += chunk;
    let nl: number;
    while ((nl = this.carry.indexOf("\n")) !== -1) {
      const line = this.carry.slice(0, nl);
      this.carry = this.carry.slice(nl + 1);
      if (line) for (const m of parseTranscriptLine(line, this.session)) this.emit(m);
    }
  }

  private backfill(): void {
    let content: string;
    try {
      content = fs.readFileSync(this.file, "utf8");
    } catch {
      return; // not present yet; pump() picks it up once it appears
    }
    this.pos = Buffer.byteLength(content, "utf8");
    const lines = content.split("\n").filter(Boolean);
    const n = this.opts.backfillLines ?? 40;
    for (const line of lines.slice(-n)) {
      for (const m of parseTranscriptLine(line, this.session)) this.emit(m);
    }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/transcriptTailer.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add relay/src/transcriptTailer.ts relay/test/transcriptTailer.test.ts
git commit -m "feat(relay): transcript tailer (backfill + poll)"
```

---

### Task 19: Wire the tailer into `relay.ts`

When a session is first added, start a `TranscriptTailer` for its resolved transcript and forward its events to the glasses. Stop all tailers on `relay.stop()`.

**Files:**
- Modify: `relay/src/relay.ts` (replace the whole file with the version below)
- Test: `relay/test/monitoring.integration.test.ts`

- [ ] **Step 1: Write the failing monitoring test**

`relay/test/monitoring.integration.test.ts`:
```ts
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/monitoring.integration.test.ts`
Expected: FAIL — no `assistant_delta` arrives (the tailer is not wired yet).

- [ ] **Step 3: Replace `relay/src/relay.ts` with the wired version**

`relay/src/relay.ts`:
```ts
import type { AddressInfo } from "node:net";
import { lanIPv4 } from "./address.js";
import { GlassesServer } from "./glasses.js";
import { HookSocketServer } from "./hookSocket.js";
import { PermissionBridge } from "./permissionBridge.js";
import { resolveTranscriptPath, sockPath } from "./paths.js";
import { createHttpServer, type DashboardState } from "./server.js";
import { SessionRegistry } from "./sessions.js";
import { loadOrCreateToken, regenerateToken } from "./token.js";
import { TranscriptTailer } from "./transcriptTailer.js";

export interface RelayConfig {
  home?: string;
  host?: string;
  port?: number;
  publicDir?: string;
  socketPath?: string;
}

export interface Relay {
  start(): Promise<void>;
  stop(): Promise<void>;
  readonly host: string;
  readonly port: number;
  readonly token: string;
}

export function createRelay(config: RelayConfig = {}): Relay {
  const home = config.home;
  let token = loadOrCreateToken(home);
  const host = config.host ?? lanIPv4();
  const desiredPort = config.port ?? 4317;
  const publicDir = config.publicDir ?? new URL("../public", import.meta.url).pathname;
  const socket = config.socketPath ?? sockPath(home);

  const registry = new SessionRegistry();
  const tailers = new Map<string, TranscriptTailer>();
  let idCounter = 0;
  const nextId = () => String(++idCounter);

  let glasses!: GlassesServer;
  let runtimePort = desiredPort;

  const dashboardState: DashboardState = {
    host,
    get port() {
      return runtimePort;
    },
    get token() {
      return token;
    },
    glassesConnected: () => glasses.isConnected(),
    sessions: () => registry.list(),
    regenerate: () => {
      token = regenerateToken(home);
      return token;
    },
  };

  const httpServer = createHttpServer(dashboardState, publicDir);

  const bridge = new PermissionBridge({
    registry,
    glasses: {
      everPaired: () => glasses.everPaired(),
      isConnected: () => glasses.isConnected(),
      send: (m) => glasses.send(m),
    },
    resolveTranscriptPath: (sessionId, provided) => resolveTranscriptPath(sessionId, { home, provided }),
    nextId,
  });

  glasses = new GlassesServer(
    httpServer,
    () => token,
    (_session, id, decision) => bridge.resolve(id, decision),
    () => {
      glasses.send({ type: "session_list", sessions: registry.list() });
      for (const { session, req } of registry.pendingRequests()) {
        glasses.send({ type: "permission_request", session, ...req });
      }
    },
  );

  const hookServer = new HookSocketServer(socket, (req, signal) => bridge.handle(req, signal));

  registry.on("change", () => {
    glasses.send({ type: "session_list", sessions: registry.list() });
  });

  // Start a transcript tailer for each newly-attached session (best-effort monitoring).
  registry.on("added", (session: { id: string; transcriptPath: string | null }) => {
    if (!session.transcriptPath || tailers.has(session.id)) return;
    const tailer = new TranscriptTailer(session.transcriptPath, session.id, (m) => glasses.send(m));
    tailers.set(session.id, tailer);
    tailer.start();
  });

  return {
    get host() {
      return host;
    },
    get port() {
      return runtimePort;
    },
    get token() {
      return token;
    },
    async start() {
      await hookServer.start();
      await new Promise<void>((resolve) => httpServer.listen(desiredPort, () => resolve()));
      runtimePort = (httpServer.address() as AddressInfo).port;
    },
    async stop() {
      for (const tailer of tailers.values()) tailer.stop();
      tailers.clear();
      glasses.close();
      await hookServer.stop();
      await new Promise<void>((resolve) => httpServer.close(() => resolve()));
    },
  };
}
```

- [ ] **Step 4: Run the monitoring test + the full suite**

Run: `npx vitest run test/monitoring.integration.test.ts`
Expected: PASS (1 test).

Run: `npx vitest run`
Expected: PASS — all files green.

- [ ] **Step 5: Commit**

```bash
git add relay/src/relay.ts relay/test/monitoring.integration.test.ts
git commit -m "feat(relay): stream transcripts to glasses (tailer wiring)"
```

---

## Phase 5 — Dashboard page, live verification, docs

### Task 20: `public/dashboard.html` — pairing/status page

**Files:**
- Create: `relay/public/dashboard.html`
- Test: `relay/test/dashboard.test.ts`

- [ ] **Step 1: Write the failing test**

`relay/test/dashboard.test.ts`:
```ts
import type { AddressInfo } from "node:net";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createHttpServer, type DashboardState } from "../src/server.js";

let server: import("node:http").Server;
let base: string;
const state: DashboardState = {
  host: "127.0.0.1",
  port: 4317,
  token: "t",
  glassesConnected: () => false,
  sessions: () => [],
  regenerate: () => "t",
};

beforeEach(async () => {
  server = createHttpServer(state, new URL("../public", import.meta.url).pathname);
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", () => r()));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
afterEach(async () => {
  await new Promise<void>((r) => server.close(() => r()));
});

describe("dashboard page", () => {
  it("serves dashboard.html at / with the expected hooks", async () => {
    const res = await fetch(`${base}/`);
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("text/html");
    const html = await res.text();
    expect(html).toContain("Clairvoyant");
    expect(html).toContain("/qr.svg");
    expect(html).toContain('id="regen"');
    expect(html).toContain("/status");
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `npx vitest run test/dashboard.test.ts`
Expected: FAIL — `/` returns 404 ("dashboard.html not found").

- [ ] **Step 3: Write the page**

`relay/public/dashboard.html`:
```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>Clairvoyant Relay</title>
    <style>
      :root { --bg:#0b1417; --card:#10201f; --teal:#39d0c4; --fg:#e6f5f2; --dim:#7fa6a0; }
      * { box-sizing: border-box; }
      body { margin:0; font:16px/1.5 -apple-system,Segoe UI,Roboto,sans-serif; background:var(--bg); color:var(--fg); }
      .wrap { max-width:720px; margin:0 auto; padding:32px 20px; }
      h1 { color:var(--teal); font-weight:700; letter-spacing:.5px; margin:0 0 4px; }
      .sub { color:var(--dim); margin:0 0 28px; }
      .card { background:var(--card); border:1px solid #1d3633; border-radius:14px; padding:24px; margin-bottom:20px; }
      .qr { display:flex; gap:24px; align-items:center; flex-wrap:wrap; }
      .qr img { width:240px; height:240px; background:#fff; border-radius:12px; padding:10px; }
      code { background:#0a1413; padding:2px 6px; border-radius:6px; color:var(--teal); word-break:break-all; }
      .row { margin:6px 0; }
      .label { color:var(--dim); font-size:13px; text-transform:uppercase; letter-spacing:.6px; }
      .dot { display:inline-block; width:10px; height:10px; border-radius:50%; margin-right:8px; vertical-align:middle; }
      .on { background:var(--teal); box-shadow:0 0 8px var(--teal); }
      .off { background:#5a6b68; }
      ul { margin:8px 0 0; padding-left:18px; }
      button { background:var(--teal); color:#03100e; border:0; border-radius:10px; padding:10px 16px; font-weight:700; cursor:pointer; }
      button:hover { filter:brightness(1.08); }
      .muted { color:var(--dim); font-size:13px; }
    </style>
  </head>
  <body>
    <div class="wrap">
      <h1>Clairvoyant</h1>
      <p class="sub">Pair your glasses to monitor &amp; approve Claude Code remotely.</p>

      <div class="card">
        <div class="qr">
          <img id="qr" alt="pairing QR" src="/qr.svg" />
          <div>
            <div class="row"><span class="label">Host</span><br /><code id="host">…</code></div>
            <div class="row"><span class="label">Port</span><br /><code id="port">…</code></div>
            <div class="row"><span class="label">Token</span><br /><code id="token">…</code></div>
            <div class="row" style="margin-top:14px;"><button id="regen">Regenerate token</button></div>
            <p class="muted">Regenerating invalidates the current QR. Re-scan on the glasses.</p>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="row"><span id="dot" class="dot off"></span><span id="conn">No glasses connected</span></div>
        <div class="row">
          <span class="label">Sessions</span>
          <ul id="sessions"><li class="muted">none yet — run Claude Code in a terminal</li></ul>
        </div>
      </div>
    </div>

    <script>
      async function loadPair() {
        const p = await (await fetch("/pair")).json();
        document.getElementById("host").textContent = p.host;
        document.getElementById("port").textContent = String(p.port);
        document.getElementById("token").textContent = p.token;
      }
      function refreshQr() {
        document.getElementById("qr").src = "/qr.svg?t=" + Date.now();
      }
      async function poll() {
        try {
          const s = await (await fetch("/status")).json();
          document.getElementById("dot").className = "dot " + (s.glassesConnected ? "on" : "off");
          document.getElementById("conn").textContent = s.glassesConnected
            ? "Glasses connected"
            : "No glasses connected";
          const ul = document.getElementById("sessions");
          ul.innerHTML = s.sessions.length
            ? s.sessions.map((x) => `<li>${x.title} <span class="muted">(${x.state})</span></li>`).join("")
            : '<li class="muted">none yet — run Claude Code in a terminal</li>';
        } catch {}
      }
      document.getElementById("regen").addEventListener("click", async () => {
        await fetch("/regenerate-token", { method: "POST" });
        await loadPair();
        refreshQr();
      });
      loadPair();
      poll();
      setInterval(poll, 2000);
    </script>
  </body>
</html>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `npx vitest run test/dashboard.test.ts`
Expected: PASS (1 test).

- [ ] **Step 5: Visual check**

Run: `npx tsx src/index.ts start`, open the printed dashboard URL in a browser.
Expected: a teal page with a scannable QR, host/port/token shown, a "Regenerate token" button (clicking it changes the token + QR), and a live "No glasses connected" status that flips to connected once a glasses (or stand-in client) authenticates.

- [ ] **Step 6: Commit**

```bash
git add relay/public/dashboard.html relay/test/dashboard.test.ts
git commit -m "feat(relay): dashboard pairing/status page"
```

---

### Task 21: Live verification against real Claude Code (empirical hook contract)

The unit/integration suite proves the relay's *internal* logic. This task confirms the **external** contract the design rests on — the PreToolUse payload shape, that `matcher:"*"` fires, and that `allow`/`deny`/no-decision behave as expected — which earlier came from research, not observation. **No code is assumed correct here until observed.** Some steps require running `claude` interactively; do that in a separate terminal (or prefix a one-off with `!` in this session).

**Files:**
- Create: `relay/scripts/fake-glasses.mjs` (a stand-in glasses client for testing without the Android app)

- [ ] **Step 1: Add the stand-in glasses client** `relay/scripts/fake-glasses.mjs`:

```js
// Stand-in for the glasses: authenticates, logs every server message, and auto-answers
// permission_request (allow by default; set CLV_AUTO=deny to deny). Run from relay/.
import { WebSocket } from "ws";

const url = process.argv[2] ?? "ws://127.0.0.1:4317/ws";
const token = process.argv[3] ?? process.env.CLV_TOKEN;
if (!token) {
  console.error("usage: node scripts/fake-glasses.mjs <ws-url> <token>   (or set CLV_TOKEN)");
  process.exit(1);
}
const ws = new WebSocket(url);
ws.on("open", () => {
  console.log("connected → hello");
  ws.send(JSON.stringify({ type: "hello", token }));
});
ws.on("message", (d) => {
  const m = JSON.parse(d.toString());
  console.log("<-", JSON.stringify(m));
  if (m.type === "permission_request") {
    const decision = process.env.CLV_AUTO === "deny" ? "deny" : "allow";
    console.log("->", decision, "for", m.tool, "—", m.description);
    ws.send(JSON.stringify({ type: "permission_response", session: m.session, id: m.id, decision }));
  }
});
ws.on("close", () => console.log("closed"));
ws.on("error", (e) => console.error("error:", e.message));
```

- [ ] **Step 2: Capture the REAL PreToolUse payload (independent of the relay)**

In a scratch project, install a logging-only hook and run Claude:
```bash
mkdir -p /tmp/clv-scratch/.claude
cat > /tmp/clv-scratch/.claude/settings.json <<'JSON'
{ "hooks": { "PreToolUse": [ { "matcher": "*",
  "hooks": [ { "type": "command", "command": "jq -c . >> /tmp/clv-hook-payloads.jsonl", "timeout": 30 } ] } ] } }
JSON
rm -f /tmp/clv-hook-payloads.jsonl
( cd /tmp/clv-scratch && claude )    # ask it to run e.g. `ls` and to read a file; approve at the terminal, then quit
jq . /tmp/clv-hook-payloads.jsonl
```

- [ ] **Step 3: Confirm the contract and reconcile with the code**

Verify in `/tmp/clv-hook-payloads.jsonl`:
- [ ] `matcher:"*"` actually fired (you see entries for multiple tools, e.g. Read *and* Bash). **If not**, change the matcher in `src/install.ts` (try `""` or omitting `matcher`) and re-run; update the install test.
- [ ] fields `session_id`, `cwd`, `tool_name`, `tool_input`, `permission_mode` are present with those names. **If `permission_mode` is absent/renamed**, adjust `HookRequest` + `policy.ts` (the safe fallback still protects: unknown/absent mode → escalate non-read).
- [ ] note whether `transcript_path` is present (informational — the relay resolves by session-id glob regardless).

Record what you observed in a comment block at the top of `src/policy.ts` (one line: "PreToolUse fields confirmed 2026-06-…: …"). If any field differed, make the change **with a test** before proceeding.

- [ ] **Step 4: End-to-end through the relay + stand-in glasses**

```bash
# terminal A — relay
cd relay && npx tsx src/index.ts start            # note the dashboard port (default 4317)

# fresh scratch settings containing ONLY the relay hook
rm -f /tmp/clv-scratch/.claude/settings.json
cd relay && npx tsx src/index.ts install-hook /tmp/clv-scratch/.claude/settings.json

# terminal B — stand-in glasses (pairs → everPaired=true, auto-allows)
cd relay && CLV_TOKEN=$(cat ~/.clairvoyant/channel-token) node scripts/fake-glasses.mjs ws://127.0.0.1:4317/ws

# terminal C — real Claude in the scratch project
cd /tmp/clv-scratch && claude        # ask it to run a Bash command, and to Read a file
```

Confirm by observation:
- [ ] a **Bash** call shows a `permission_request` in terminal B and Claude proceeds with **no terminal prompt** (auto-allowed by the stand-in).
- [ ] a **Read** call produces **no** `permission_request` (passed through; Claude auto-allows).
- [ ] set `CLV_AUTO=deny` and restart terminal B → a Bash call is **denied** (Claude reports the tool was blocked).
- [ ] **stays pending:** start a Bash call, Ctrl-C the stand-in (terminal B) before it answers → terminal C waits; restart the stand-in → the request is **re-sent** and answered.
- [ ] **fail-open:** stop the relay (terminal A) → a Bash call falls back to Claude's **normal terminal prompt**; the terminal stays usable.
- [ ] **timeout behavior:** confirm what Claude actually does when the hook is killed/timed out (it should fall through to the terminal prompt, not auto-deny). Note the real default timeout if visible. This validates the "long timeout, fall through" choice.

- [ ] **Step 5: Commit the stand-in client (+ any fixes from Step 3)**

```bash
git add relay/scripts/fake-glasses.mjs
git commit -m "test(relay): stand-in glasses client + live verification notes"
```

> If Step 3/4 surfaced a contract mismatch, the fix + its test should already be committed under the relevant module before this commit.

---

### Task 22: README + final build & full suite

**Files:**
- Create: `relay/README.md`

- [ ] **Step 1: Write `relay/README.md`**

````markdown
# Clairvoyant Relay

Host-side companion that lets the Clairvoyant glasses **monitor and approve** the Claude Code
sessions you already run in your terminals. Claude stays authenticated on this machine; the
glasses only ever get `host + port + token`.

## How it works

- A **PreToolUse hook** (installed in your Claude `settings.json`) calls the relay over a local
  Unix socket on every tool call. The relay decides to **pass** (let Claude's own permission
  flow run) or **escalate** to the glasses and block until you answer.
- Escalation is **mode-aware** and **safe by construction**: anything not explicitly answered on
  the glasses falls back to Claude's own decision (auto-allow or its terminal prompt), so the
  relay can never silently bypass a prompt.
- A **WebSocket** server streams each session's transcript to the glasses and carries
  approve/deny. An **HTTP dashboard** shows a pairing QR.

## Setup

```bash
cd relay
npm install
npm run build

# install the PreToolUse hook into your user settings (~/.claude/settings.json)
node dist/index.js install-hook
# …or into a specific project: node dist/index.js install-hook /path/to/project/.claude/settings.json

# run the relay
node dist/index.js start          # prod
npm run dev -- start              # or, from source
```

Open the printed dashboard URL, scan the QR with the glasses (same Wi-Fi LAN), and run Claude
Code as usual in any terminal.

## Notes & limits (v1)

- LAN only (plaintext WebSocket on a trusted network). The token is a bearer secret; the QR is a
  credential. Use "Regenerate token" to invalidate a leaked QR.
- Transcript monitoring is best-effort (the JSONL schema is internal); approval does not depend
  on it. Subagent (sidechain) output is not shown.
- The hook is installed with a ~12h timeout so escalated requests effectively stay pending; on
  timeout Claude falls through to its terminal prompt.
- Without a stand-in or real glasses ever paired in a run, the hook **fails open** (terminal
  prompt), so your terminal is never blocked by a device that isn't there.

## Development

```bash
npm test            # vitest, full suite
npm run test:watch
npm run build       # tsc type-check + emit to dist/
```
````

- [ ] **Step 2: Final type-check + full test run**

Run: `npm run build`
Expected: completes with no TypeScript errors (emits `dist/`).

Run: `npx vitest run`
Expected: PASS — every test file green.

- [ ] **Step 3: Commit**

```bash
git add relay/README.md
git commit -m "docs(relay): README + usage"
```

---

## Deferred in v1 (intentional — not gaps)

- **`status` message + live state.** The `status` type and `SessionState` exist; v1 surfaces state only via the `state` field of each `session_list` entry and leaves every session as `"running"` (no `status` messages, no `setState` calls). Richer state transitions are a later refinement.
- **`thinking` blocks and user `tool_result` content** are not streamed — v1 sends assistant text + `tool_use` + `turn_done` only.
- **No transcript replay** to a glasses that connects *after* a session's initial backfill (it gets live appends from connection onward). Pending **permission requests** *are* re-sent on reconnect.
- **Permission-rule (allow/deny/ask) matching is not implemented.** The mode-aware policy + pass-through fallback covers correctness; honoring fine-grained rules (to reduce chattiness) is future work.

## Done criteria

- [ ] `npx vitest run` is fully green (protocol, describe, policy, paths, token, address, sessions, hookSocket, permissionBridge, glasses, server, relay integration, hook, install, cli, transcriptParser, transcriptTailer, monitoring integration, dashboard).
- [ ] `npm run build` type-checks and emits `dist/`.
- [ ] `node dist/index.js install-hook` installs a PreToolUse hook; `node dist/index.js start` serves the dashboard with a scannable QR.
- [ ] Task 21 observed the real Claude Code contract and any mismatch was reconciled in code + tests.
- [ ] End-to-end (Task 21): Bash escalates to a paired client; Read passes; deny works; pending survives a client drop and is re-sent; fail-open works when the relay is down.

## Notes for the executor

- Run every command from inside `relay/`.
- Follow TDD order strictly: write the test, watch it fail for the *expected* reason, implement, watch it pass, commit.
- The integration tests use real sockets/ports on `127.0.0.1` with ephemeral ports and temp `HOME` dirs — they are isolated and parallel-safe.
- Do **not** weaken the safe-by-construction invariant: the relay returns hard `allow`/`deny` only from an explicit glasses answer; every other path is `pass`.
- This plan delivers the Node companion (spec Components 1, 1a, 2 + tailer). The Kotlin glasses client (Component 3) is a separate effort.
