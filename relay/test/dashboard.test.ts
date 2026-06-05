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
