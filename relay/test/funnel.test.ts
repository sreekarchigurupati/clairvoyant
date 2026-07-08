import { describe, expect, it } from "vitest";
import { enableFunnel, findTailscaleBin, type Exec } from "../src/funnel.js";

const RUNNING = JSON.stringify({
  BackendState: "Running",
  Self: { DNSName: "mac.tail1234.ts.net." },
});

function fakeExec(
  map: Record<string, { stdout?: string; stderr?: string; code?: number }>,
): Exec & { calls: string[][] } {
  const calls: string[][] = [];
  const exec: Exec = async (_bin, args) => {
    calls.push(args);
    const key = args[0] === "status" ? "status" : args.includes("off") ? "off" : "funnel";
    const r = map[key] ?? {};
    return { stdout: r.stdout ?? "", stderr: r.stderr ?? "", code: r.code ?? 0 };
  };
  return Object.assign(exec, { calls });
}

describe("findTailscaleBin", () => {
  it("finds tailscale on PATH", () => {
    const exists = (p: string) => p === "/usr/local/bin/tailscale";
    expect(findTailscaleBin(exists, "/usr/bin:/usr/local/bin")).toBe("/usr/local/bin/tailscale");
  });

  it("falls back to the macOS app bundle CLI", () => {
    const exists = (p: string) => p === "/Applications/Tailscale.app/Contents/MacOS/Tailscale";
    expect(findTailscaleBin(exists, "/usr/bin")).toBe(
      "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
    );
  });

  it("returns null when absent", () => {
    expect(findTailscaleBin(() => false, "/usr/bin")).toBeNull();
  });
});

describe("enableFunnel", () => {
  it("mounts /ws and returns the DNS name", async () => {
    const exec = fakeExec({ status: { stdout: RUNNING } });
    const f = await enableFunnel(4317, exec, "/bin/ts");
    expect(f).toMatchObject({ host: "mac.tail1234.ts.net", port: 443, tls: true });
    expect(exec.calls).toContainEqual([
      "funnel",
      "--bg",
      "--set-path",
      "/ws",
      "http://127.0.0.1:4317/ws",
    ]);
  });

  it("throws when the daemon is stopped", async () => {
    const exec = fakeExec({ status: { stdout: JSON.stringify({ BackendState: "Stopped" }) } });
    await expect(enableFunnel(4317, exec, "/bin/ts")).rejects.toThrow(/Stopped[\s\S]*tailscale up/);
  });

  it("throws when the status command itself fails", async () => {
    const exec = fakeExec({ status: { code: 1, stderr: "failed to connect to local tailscaled" } });
    await expect(enableFunnel(4317, exec, "/bin/ts")).rejects.toThrow(/failed to connect/);
  });

  it("surfaces the CLI error when enabling fails", async () => {
    const exec = fakeExec({
      status: { stdout: RUNNING },
      funnel: { code: 1, stderr: "Funnel not enabled on your tailnet" },
    });
    await expect(enableFunnel(4317, exec, "/bin/ts")).rejects.toThrow(/Funnel not enabled/);
  });

  it("disable() turns the /ws mount off, ignoring failures", async () => {
    const exec = fakeExec({ status: { stdout: RUNNING }, off: { code: 1 } });
    const f = await enableFunnel(4317, exec, "/bin/ts");
    await f.disable(); // must not throw
    expect(exec.calls).toContainEqual(["funnel", "--set-path", "/ws", "off"]);
  });
});
