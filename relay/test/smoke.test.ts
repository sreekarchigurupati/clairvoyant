import { describe, expect, it } from "vitest";
import { smoke } from "../src/_smoke.js";

describe("scaffold", () => {
  it("resolves a .js specifier to its .ts sibling and runs", () => {
    expect(smoke(1, 1)).toBe(2);
  });
});
