import * as os from "node:os";

/**
 * Best-effort LAN IPv4 for the pairing QR. Injectable for tests. Falls back to loopback
 * (the dashboard still shows it; the user can correct via the plaintext field).
 */
export function lanIPv4(
  ifaces: NodeJS.Dict<os.NetworkInterfaceInfo[]> = os.networkInterfaces(),
): string {
  for (const list of Object.values(ifaces)) {
    for (const ni of list ?? []) {
      if (ni.family === "IPv4" && !ni.internal) return ni.address;
    }
  }
  return "127.0.0.1";
}
