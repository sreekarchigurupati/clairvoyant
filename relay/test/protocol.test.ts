import { describe, expect, it } from "vitest";
import { parseClientMessage } from "../src/protocol.js";

describe("parseClientMessage", () => {
  it("parses a valid hello", () => {
    expect(parseClientMessage('{"type":"hello","token":"abc"}')).toEqual({
      type: "hello",
      token: "abc",
    });
  });

  it("parses a valid permission_response", () => {
    expect(
      parseClientMessage(
        '{"type":"permission_response","session":"s1","id":"7","decision":"allow"}',
      ),
    ).toEqual({ type: "permission_response", session: "s1", id: "7", decision: "allow" });
  });

  it("rejects invalid JSON", () => {
    expect(parseClientMessage("{not json")).toBeNull();
  });

  it("rejects unknown/invalid shapes", () => {
    expect(parseClientMessage('{"type":"hello"}')).toBeNull(); // missing token
    expect(parseClientMessage('{"type":"nope"}')).toBeNull();
    expect(
      parseClientMessage('{"type":"permission_response","session":"s","id":"1","decision":"maybe"}'),
    ).toBeNull();
  });
});
