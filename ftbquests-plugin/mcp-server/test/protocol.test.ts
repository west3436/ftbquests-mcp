import { describe, it, expect } from "vitest";
import { assertCompatible, EXPECTED_PROTOCOL } from "../src/protocol.js";

describe("assertCompatible", () => {
  it("passes on match", () => { expect(() => assertCompatible(EXPECTED_PROTOCOL)).not.toThrow(); });
  it("throws on mismatch", () => { expect(() => assertCompatible(EXPECTED_PROTOCOL + 1)).toThrow(/protocol/i); });
  it("throws on missing", () => { expect(() => assertCompatible(undefined)).toThrow(/protocol/i); });
});
