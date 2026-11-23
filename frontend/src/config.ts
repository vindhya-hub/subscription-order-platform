/** Runtime configuration, read once from the VITE_* build variables documented in .env.example. */
function positiveInteger(value: string | undefined, fallback: number): number {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

export const config = {
  graphqlUrl: import.meta.env.VITE_GRAPHQL_URL || '/graphql',
  pollIntervalMs: positiveInteger(import.meta.env.VITE_POLL_INTERVAL_MS, 2000),
} as const;
