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
