import http from "node:http";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { WebSocket } from "ws";
import { GlassesServer } from "../src/glasses.js";

let httpServer: http.Server;
let port: number;
let glasses: GlassesServer;
const onResponse = vi.fn();
const onAuth = vi.fn();

beforeEach(async () => {
  onResponse.mockReset();
  onAuth.mockReset();
  httpServer = http.createServer();
  glasses = new GlassesServer(httpServer, () => "secret", onResponse, onAuth);
  await new Promise<void>((res) => httpServer.listen(0, "127.0.0.1", () => res()));
  port = (httpServer.address() as import("node:net").AddressInfo).port;
});

afterEach(async () => {
  glasses.close();
  await new Promise<void>((res) => httpServer.close(() => res()));
});

function connect(): WebSocket {
  return new WebSocket(`ws://127.0.0.1:${port}/ws`);
}
function nextMessage(ws: WebSocket): Promise<any> {
  return new Promise((resolve) => ws.once("message", (d) => resolve(JSON.parse(d.toString()))));
}

describe("GlassesServer", () => {
  it("accepts a correct token: replies ready, sets everPaired/connected, fires onAuth", async () => {
    expect(glasses.everPaired()).toBe(false);
    const ws = connect();
    await new Promise((r) => ws.on("open", r));
    ws.send(JSON.stringify({ type: "hello", token: "secret" }));
    expect(await nextMessage(ws)).toEqual({ type: "ready" });
    expect(glasses.everPaired()).toBe(true);
    expect(glasses.isConnected()).toBe(true);
    expect(onAuth).toHaveBeenCalledTimes(1);
    ws.close();
  });

  it("rejects a bad token with an error and closes", async () => {
    const ws = connect();
    await new Promise((r) => ws.on("open", r));
    ws.send(JSON.stringify({ type: "hello", token: "wrong" }));
    expect(await nextMessage(ws)).toMatchObject({ type: "error", code: "bad_token" });
    await new Promise((r) => ws.on("close", r));
    expect(glasses.isConnected()).toBe(false);
  });

  it("routes permission_response to onResponse after auth", async () => {
    const ws = connect();
    await new Promise((r) => ws.on("open", r));
    ws.send(JSON.stringify({ type: "hello", token: "secret" }));
    await nextMessage(ws); // ready
    ws.send(JSON.stringify({ type: "permission_response", session: "s1", id: "3", decision: "deny" }));
    await vi.waitFor(() => expect(onResponse).toHaveBeenCalledWith("s1", "3", "deny"));
    ws.close();
  });

  it("broadcasts send() to authed clients only", async () => {
    const ws = connect();
    await new Promise((r) => ws.on("open", r));
    ws.send(JSON.stringify({ type: "hello", token: "secret" }));
    await nextMessage(ws); // ready
    const got = nextMessage(ws);
    glasses.send({ type: "session_list", sessions: [{ id: "s1", title: "foo", state: "running" }] });
    expect(await got).toEqual({ type: "session_list", sessions: [{ id: "s1", title: "foo", state: "running" }] });
    ws.close();
  });
});
