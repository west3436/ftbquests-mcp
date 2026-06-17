export const EXPECTED_PROTOCOL = 1;

export function assertCompatible(serverProtocol: number | undefined): void {
  if (serverProtocol !== EXPECTED_PROTOCOL) {
    throw new Error(
      `FTB Quests bridge protocol mismatch: expected ${EXPECTED_PROTOCOL}, got ${serverProtocol ?? "unknown"}. ` +
      `Update the mod and/or the companion plugin so their protocol versions match.`
    );
  }
}
