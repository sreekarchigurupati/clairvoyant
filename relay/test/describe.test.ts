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
