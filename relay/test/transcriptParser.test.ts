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
