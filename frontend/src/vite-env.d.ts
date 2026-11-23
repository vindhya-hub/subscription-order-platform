/// <reference types="vite/client" />

/** Build-time settings; all public by design (see .env.example). */
interface ImportMetaEnv {
  /** GraphQL endpoint. Relative by default so the dev proxy or nginx forwards it to the backend. */
  readonly VITE_GRAPHQL_URL?: string;
  /** Refresh interval of the live panels, in milliseconds. */
  readonly VITE_POLL_INTERVAL_MS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
