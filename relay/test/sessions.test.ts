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

  it("records a pid and exposes it via withPid; remove drops the session", () => {
    const reg = new SessionRegistry();
    const removed = vi.fn();
    reg.on("removed", removed);
    reg.upsert("s1", "/x", null, 4242);
    reg.upsert("s2", "/y", null); // no pid → not a liveness candidate
    expect(reg.withPid()).toEqual([{ id: "s1", pid: 4242 }]);

    reg.remove("s1");
    expect(removed).toHaveBeenCalledWith("s1");
    expect(reg.get("s1")).toBeUndefined();
    expect(reg.withPid()).toEqual([]);
  });

  it("backfills a pid on a later upsert without a pid clobbering it", () => {
    const reg = new SessionRegistry();
    reg.upsert("s1", "/x", null); // first hook had no pid
    reg.upsert("s1", "/x", null, 99);
    expect(reg.get("s1")?.pid).toBe(99);
    reg.upsert("s1", "/x", null); // a later pid-less event must not wipe it
    expect(reg.get("s1")?.pid).toBe(99);
  });

  it("remove is a no-op for an unknown session", () => {
    const reg = new SessionRegistry();
    const removed = vi.fn();
    reg.on("removed", removed);
    reg.remove("nope");
    expect(removed).not.toHaveBeenCalled();
  });
});
