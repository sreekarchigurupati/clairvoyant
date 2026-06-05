import type { AddressInfo } from "node:net";
import { lanIPv4 } from "./address.js";
import { GlassesServer } from "./glasses.js";
import { HookSocketServer } from "./hookSocket.js";
import { PermissionBridge } from "./permissionBridge.js";
import { resolveTranscriptPath, sockPath } from "./paths.js";
import { createHttpServer, type DashboardState } from "./server.js";
import { SessionRegistry } from "./sessions.js";
import { loadOrCreateToken, regenerateToken } from "./token.js";
import { TranscriptTailer } from "./transcriptTailer.js";

export interface RelayConfig {
  home?: string;
  host?: string;
  port?: number;
  publicDir?: string;
  socketPath?: string;
}

export interface Relay {
  start(): Promise<void>;
  stop(): Promise<void>;
  readonly host: string;
  readonly port: number;
  readonly token: string;
}

export function createRelay(config: RelayConfig = {}): Relay {
  const home = config.home;
  let token = loadOrCreateToken(home);
  const host = config.host ?? lanIPv4();
  const desiredPort = config.port ?? 4317;
  const publicDir = config.publicDir ?? new URL("../public", import.meta.url).pathname;
  const socket = config.socketPath ?? sockPath(home);

  const registry = new SessionRegistry();
  const tailers = new Map<string, TranscriptTailer>();
  let idCounter = 0;
  const nextId = () => String(++idCounter);

  let glasses!: GlassesServer;
  let runtimePort = desiredPort;

  const dashboardState: DashboardState = {
    host,
    get port() {
      return runtimePort;
    },
    get token() {
      return token;
    },
    glassesConnected: () => glasses.isConnected(),
    sessions: () => registry.list(),
    regenerate: () => {
      token = regenerateToken(home);
      return token;
    },
  };

  const httpServer = createHttpServer(dashboardState, publicDir);

  const bridge = new PermissionBridge({
    registry,
    glasses: {
      everPaired: () => glasses.everPaired(),
      isConnected: () => glasses.isConnected(),
      send: (m) => glasses.send(m),
    },
    resolveTranscriptPath: (sessionId, provided) => resolveTranscriptPath(sessionId, { home, provided }),
    nextId,
  });

  glasses = new GlassesServer(
    httpServer,
    () => token,
    (_session, id, decision) => bridge.resolve(id, decision),
    () => {
      glasses.send({ type: "session_list", sessions: registry.list() });
      for (const { session, req } of registry.pendingRequests()) {
        glasses.send({ type: "permission_request", session, ...req });
      }
    },
  );

  const hookServer = new HookSocketServer(socket, (req, signal) => bridge.handle(req, signal));

  registry.on("change", () => {
    glasses.send({ type: "session_list", sessions: registry.list() });
  });

  // Start a transcript tailer for each newly-attached session (best-effort monitoring).
  registry.on("added", (session: { id: string; transcriptPath: string | null }) => {
    if (!session.transcriptPath || tailers.has(session.id)) return;
    const tailer = new TranscriptTailer(session.transcriptPath, session.id, (m) => glasses.send(m));
    tailers.set(session.id, tailer);
    tailer.start();
  });

  return {
    get host() {
      return host;
    },
    get port() {
      return runtimePort;
    },
    get token() {
      return token;
    },
    async start() {
      await hookServer.start();
      await new Promise<void>((resolve) => httpServer.listen(desiredPort, () => resolve()));
      runtimePort = (httpServer.address() as AddressInfo).port;
    },
    async stop() {
      for (const tailer of tailers.values()) tailer.stop();
      tailers.clear();
      glasses.close();
      await hookServer.stop();
      await new Promise<void>((resolve) => httpServer.close(() => resolve()));
    },
  };
}
