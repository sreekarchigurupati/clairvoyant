import type { AddressInfo } from "node:net";
import { WebSocketServer, WebSocket } from "ws";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createHttpServer, type DashboardState } from "../src/server.js";
import { createProxy, parseUpstream, type Proxy } from "../src/proxy.js";

let upstream: import("node:http").Server;
let upstreamWss: WebSocketServer;
let upstreamPort: number;
let proxy: Proxy;

const state: DashboardState = {
  host: "100.64.0.7",
  port: 0, // patched after listen
  token: "tok-upstream",
  glassesConnected: () => false,
  sessions: () => [{ id: "s1", title: "remote", state: "running" }],
  regenerate: () => "tok-upstream",
};

beforeEach(async () => {
  upstream = createHttpServer(state, new URL("../public", import.meta.url).pathname);
  upstreamWss = new WebSocketServer({ server: upstream, path: "/ws" });
  upstreamWss.on("connection", (ws) => ws.on("message", (d) => ws.send(`echo:${String(d)}`)));
  await new Promise<void>((res) => upstream.listen(0, "127.0.0.1", () => res()));
  upstreamPort = (upstream.address() as AddressInfo).port;
  state.port = upstreamPort;

  proxy = createProxy({
    upstreamHost: "127.0.0.1",
    upstreamPort,
    host: "192.168.1.20",
    port: 0,
  });
  await proxy.start();
});

afterEach(async () => {
  await proxy.stop();
  upstreamWss.close();
  await new Promise<void>((res) => upstream.close(() => res()));
});

describe("parseUpstream", () => {
  it("parses host and optional port", () => {
    expect(parseUpstream("100.64.0.7")).toEqual({ host: "100.64.0.7", port: 4317 });
    expect(parseUpstream("relay-box:5000")).toEqual({ host: "relay-box", port: 5000 });
  });
});

describe("proxy", () => {
  it("GET /pair advertises the proxy address but keeps the upstream token", async () => {
    const body = await (await fetch(`http://127.0.0.1:${proxy.port}/pair`)).json();
    expect(body).toEqual({
      url: `clairvoyant://pair?host=192.168.1.20&port=${proxy.port}&token=tok-upstream`,
      host: "192.168.1.20",
      port: proxy.port,
      token: "tok-upstream",
    });
  });

  it("GET /qr.svg encodes the rewritten pairing URL", async () => {
    const res = await fetch(`http://127.0.0.1:${proxy.port}/qr.svg`);
    expect(res.headers.get("content-type")).toContain("image/svg+xml");
    expect(await res.text()).toContain("<svg");
  });

  it("passes other HTTP routes through to the upstream", async () => {
    const body = await (await fetch(`http://127.0.0.1:${proxy.port}/status`)).json();
    expect(body.sessions).toEqual([{ id: "s1", title: "remote", state: "running" }]);
  });

  it("tunnels WebSocket connections to the upstream", async () => {
    const ws = new WebSocket(`ws://127.0.0.1:${proxy.port}/ws`);
    const reply = await new Promise<string>((resolve, reject) => {
      ws.on("open", () => ws.send("ping"));
      ws.on("message", (d) => resolve(String(d)));
      ws.on("error", reject);
    });
    expect(reply).toBe("echo:ping");
    ws.close();
  });
});
