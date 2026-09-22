import {defineConfig} from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
    plugins: [react()],
    server: {
        // Listen on every network interface so other devices on the LAN can
        // open the development UI. API calls still stay behind Vite's proxy.
        host: "0.0.0.0",
        port: 5173,
        proxy: {
            "/api": "http://127.0.0.1:8080"
        }
    },
    build: {
        outDir: "../src/main/resources/static",
        emptyOutDir: true
    }
});
