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
