import { localeMetadata } from "./scripts/locale-metadata.ts";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [localeMetadata(), react()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    proxy: Object.fromEntries(
      ["/api", "/a", "/mcp"].map((path) => [
        path,
        { target: "http://127.0.0.1:8080", changeOrigin: false },
      ]),
    ),
  },
  build: {
    manifest: true,
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            {
              name: "core",
              test: /[\\/]src[\\/](?:lib[\\/]|App\.tsx$|main\.tsx$|locales[\\/]en\.json$|pages[\\/]Timeline\.tsx$|components[\\/]core[\\/](?:NoteCard|Editor|Icon|Menu|Toast|ConfirmDialog)\.tsx$|features[\\/]Slots\.tsx$)/,
            },
          ],
        },
      },
    },
  },
});
