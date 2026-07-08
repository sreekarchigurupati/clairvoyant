import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { hookCommand, main, parseAdvertiseUrl, parseStartOptions } from "../src/index.js";

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

  it("parseStartOptions reads --host and --port flags", () => {
    expect(parseStartOptions(["--host", "100.64.0.7", "--port", "5000"], {})).toEqual({
      host: "100.64.0.7",
      port: 5000,
    });
  });

  it("parseStartOptions falls back to CLV_HOST / CLV_PORT env", () => {
    expect(parseStartOptions([], { CLV_HOST: "100.64.0.7", CLV_PORT: "5000" })).toEqual({
      host: "100.64.0.7",
      port: 5000,
    });
  });

  it("parseStartOptions prefers flags over env and defaults to empty", () => {
    expect(parseStartOptions(["--host", "a"], { CLV_HOST: "b" })).toEqual({ host: "a" });
    expect(parseStartOptions([], {})).toEqual({});
  });

  it("parseStartOptions reads --funnel", () => {
    expect(parseStartOptions(["--funnel"], {})).toEqual({ funnel: true });
  });

  it("parseStartOptions reads --advertise-url", () => {
    expect(parseStartOptions(["--advertise-url", "https://x.ngrok.io"], {})).toEqual({
      advertiseUrl: "https://x.ngrok.io",
    });
  });

  it("parseStartOptions rejects --funnel with --advertise-url", () => {
    expect(() => parseStartOptions(["--funnel", "--advertise-url", "https://x.io"], {})).toThrow(
      /mutually exclusive/,
    );
  });

  it("parseAdvertiseUrl: https defaults to 443/tls", () => {
    expect(parseAdvertiseUrl("https://x.ngrok.io")).toEqual({
      host: "x.ngrok.io",
      port: 443,
      tls: true,
    });
  });

  it("parseAdvertiseUrl: explicit port and http", () => {
    expect(parseAdvertiseUrl("http://tun.example.com:8080")).toEqual({
      host: "tun.example.com",
      port: 8080,
      tls: false,
    });
  });

  it("unknown command sets a non-zero exit code", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    process.exitCode = 0;
    await main(["bogus"]);
    expect(process.exitCode).toBe(1);
    process.exitCode = 0;
  });
});
