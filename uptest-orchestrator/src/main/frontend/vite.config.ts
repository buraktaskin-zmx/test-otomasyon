import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/*
  Output goes straight into the Spring Boot static tree, so `npm run build` is
  the only step between editing a component and the orchestrator serving it.

  During the migration the bundle lands in static/ui/ and is reachable at
  /ui/ — the old app.js UI keeps answering at /. Nothing is shared between the
  two, so a half-finished panel here cannot break the working studio. The last
  step of the migration moves this to the root and deletes the old files.

  base must match the serving path: Vite writes absolute asset URLs into
  index.html, and "/assets/x.js" would 404 under /ui/.
*/
export default defineConfig({
  plugins: [react()],
  base: "/ui/",
  build: {
    outDir: "../resources/static/ui",
    // outDir sits outside the Vite root, which Vite refuses to wipe unless it
    // is told to in so many words. It is ours alone, so wiping is correct:
    // a stale hashed bundle left behind would be served forever.
    emptyOutDir: true,
    // Kept on purpose. The people who debug this run it on a VDI with no
    // dev server, so a readable stack trace there is worth the extra files.
    sourcemap: true,
  },
  server: {
    // `npm run dev` only serves the UI; the orchestrator still owns /api.
    proxy: {
      "/api": "http://localhost:8035",
    },
  },
});
