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

  it("GET /qrcode.min.js serves the client-side QR bundle", async () => {
    const res = await fetch(`${base}/qrcode.min.js`);
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("text/javascript");
    expect(await res.text()).toContain("QRCode");
  });
});
