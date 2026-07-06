import * as http from "node:http";
import * as net from "node:net";
import type { AddressInfo } from "node:net";
import QRCode from "qrcode";
import { lanIPv4 } from "./address.js";

/**
 * LAN-side proxy for a relay reachable only over an overlay network (e.g. Tailscale).
 * Runs on a machine that is on both the glasses' Wi-Fi LAN and the tailnet, and pipes
 * HTTP + WebSocket traffic verbatim to the upstream relay — except `/pair` and
 * `/qr.svg`, which are rewritten to advertise the proxy's own LAN address so the
 * pairing QR points the glasses here. The channel token passes through unchanged;
 * authentication still terminates at the upstream relay.
 */
export interface ProxyConfig {
  upstreamHost: string;
  upstreamPort: number;
  /** LAN address to advertise in the pairing QR. Default: best-effort LAN IPv4. */
  host?: string;
  /** LAN port to listen on. Default: 4317 (0 picks a free port). */
  port?: number;
}

export interface Proxy {
  start(): Promise<void>;
  stop(): Promise<void>;
  readonly host: string;
  readonly port: number;
}

export function parseUpstream(spec: string): { host: string; port: number } {
  const idx = spec.lastIndexOf(":");
  if (idx === -1) return { host: spec, port: 4317 };
  const port = Number(spec.slice(idx + 1));
  if (!Number.isInteger(port) || port <= 0) return { host: spec, port: 4317 };
  return { host: spec.slice(0, idx), port };
}

export function createProxy(config: ProxyConfig): Proxy {
  const host = config.host ?? lanIPv4();
  const desiredPort = config.port ?? 4317;
  let runtimePort = desiredPort;

  function pairUrl(token: string): string {
    return `clairvoyant://pair?host=${host}&port=${runtimePort}&token=${token}`;
  }

  async function upstreamToken(): Promise<string> {
    const res = await fetch(`http://${config.upstreamHost}:${config.upstreamPort}/pair`);
    const body = (await res.json()) as { token: string };
    return body.token;
  }

  const server = http.createServer((req, res) => {
    void handle(req, res).catch(() => {
      if (!res.headersSent) res.writeHead(502);
      res.end("upstream unreachable");
    });
  });

  async function handle(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const method = req.method ?? "GET";
    const url = (req.url ?? "/").split("?")[0];

    if (method === "GET" && url === "/pair") {
      const token = await upstreamToken();
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ url: pairUrl(token), host, port: runtimePort, token }));
      return;
    }
    if (method === "GET" && url === "/qr.svg") {
      const token = await upstreamToken();
      const svg = await QRCode.toString(pairUrl(token), { type: "svg", margin: 1 });
      res.writeHead(200, { "content-type": "image/svg+xml" });
      res.end(svg);
      return;
    }

    const upstream = http.request(
      {
        host: config.upstreamHost,
        port: config.upstreamPort,
        method,
        path: req.url ?? "/",
        headers: { ...req.headers, host: `${config.upstreamHost}:${config.upstreamPort}` },
      },
      (upRes) => {
        res.writeHead(upRes.statusCode ?? 502, upRes.headers);
        upRes.pipe(res);
      },
    );
    upstream.on("error", () => {
      if (!res.headersSent) res.writeHead(502);
      res.end("upstream unreachable");
    });
    req.pipe(upstream);
  }

  // WebSocket (and any other Upgrade): splice a raw TCP tunnel to the upstream.
  server.on("upgrade", (req, socket, head) => {
    const upstream = net.connect(config.upstreamPort, config.upstreamHost, () => {
      const lines = [`${req.method} ${req.url} HTTP/1.1`];
      for (let i = 0; i < req.rawHeaders.length; i += 2) {
        const name = req.rawHeaders[i];
        const value =
          name.toLowerCase() === "host"
            ? `${config.upstreamHost}:${config.upstreamPort}`
            : req.rawHeaders[i + 1];
        lines.push(`${name}: ${value}`);
      }
      upstream.write(lines.join("\r\n") + "\r\n\r\n");
      if (head.length) upstream.write(head);
      upstream.pipe(socket);
      socket.pipe(upstream);
    });
    const drop = () => {
      upstream.destroy();
      socket.destroy();
    };
    upstream.on("error", drop);
    socket.on("error", drop);
  });

  return {
    get host() {
      return host;
    },
    get port() {
      return runtimePort;
    },
    async start() {
      await new Promise<void>((resolve) => server.listen(desiredPort, () => resolve()));
      runtimePort = (server.address() as AddressInfo).port;
    },
    async stop() {
      await new Promise<void>((resolve) => server.close(() => resolve()));
      server.closeAllConnections?.();
    },
  };
}
