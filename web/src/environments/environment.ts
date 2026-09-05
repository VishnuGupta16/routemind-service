/**
 * API base. Empty means "same origin", which is what the dev proxy (proxy.conf.json) and a
 * production reverse-proxy both give you — the Angular app calls /api/... and the platform
 * routes it to the Java service. Override here only if the API lives on another host.
 */
export const environment = {
  apiBase: '',
  // The most recent date in the loaded demo data — the app's default "as of".
  demoAsOf: '2026-07-31',
};
