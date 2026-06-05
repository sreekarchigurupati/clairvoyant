import type * as os from "node:os";
import { describe, expect, it } from "vitest";
import { lanIPv4 } from "../src/address.js";

const ifaces = (entries: Record<string, { family: string; internal: boolean; address: string }[]>) =>
  entries as unknown as NodeJS.Dict<os.NetworkInterfaceInfo[]>;

describe("lanIPv4", () => {
  it("returns the first non-internal IPv4", () => {
    expect(
      lanIPv4(
        ifaces({
          lo0: [{ family: "IPv4", internal: true, address: "127.0.0.1" }],
          en0: [{ family: "IPv4", internal: false, address: "192.168.1.42" }],
        }),
      ),
    ).toBe("192.168.1.42");
  });

  it("skips IPv6 and internal, falling back to loopback", () => {
    expect(
      lanIPv4(
        ifaces({
          lo0: [{ family: "IPv4", internal: true, address: "127.0.0.1" }],
          en0: [{ family: "IPv6", internal: false, address: "fe80::1" }],
        }),
      ),
    ).toBe("127.0.0.1");
  });
});
