import fs from "node:fs";
import * as http from "node:http";
import path from "node:path";
import QRCode from "qrcode";

export interface DashboardState {
  host: string;
  port: number;
  token: string;
  glassesConnected: () => boolean;
  sessions: () => { id: string; title: string; state: string }[];
  regenerate: () => string;
}

function pairUrl(state: DashboardState): string {
  return `clairvoyant://pair?host=${state.host}&port=${state.port}&token=${state.token}`;
}

function sendJson(res: http.ServerResponse, body: unknown): void {
  res.writeHead(200, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
}

export function createHttpServer(state: DashboardState, publicDir: string): http.Server {
  return http.createServer((req, res) => {
    void handle(req, res).catch(() => {
      if (!res.headersSent) res.writeHead(500);
      res.end("error");
    });
  });

  async function handle(req: http.IncomingMessage, res: http.ServerResponse): Promise<void> {
    const method = req.method ?? "GET";
    const url = (req.url ?? "/").split("?")[0];

    if (method === "GET" && (url === "/" || url === "/index.html")) {
      const file = path.join(publicDir, "dashboard.html");
      try {
        const html = fs.readFileSync(file);
        res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
        res.end(html);
      } catch {
        res.writeHead(404);
        res.end("dashboard.html not found");
      }
      return;
    }
    if (method === "GET" && url === "/pair") {
      sendJson(res, { url: pairUrl(state), host: state.host, port: state.port, token: state.token });
      return;
    }
    if (method === "GET" && url === "/status") {
      sendJson(res, {
        glassesConnected: state.glassesConnected(),
        sessions: state.sessions(),
        host: state.host,
        port: state.port,
      });
      return;
    }
    if (method === "GET" && url === "/qr.svg") {
      const svg = await QRCode.toString(pairUrl(state), { type: "svg", margin: 1 });
      res.writeHead(200, { "content-type": "image/svg+xml" });
      res.end(svg);
      return;
    }
    if (method === "GET" && url === "/qrcode.min.js") {
      const file = path.join(publicDir, "qrcode.min.js");
      try {
        const js = fs.readFileSync(file);
        res.writeHead(200, { "content-type": "text/javascript; charset=utf-8" });
        res.end(js);
      } catch {
        res.writeHead(404);
        res.end("qrcode.min.js not found");
      }
      return;
    }
    if (method === "POST" && url === "/regenerate-token") {
      const token = state.regenerate();
      sendJson(res, { token });
      return;
    }
    res.writeHead(404);
    res.end("not found");
  }
}
