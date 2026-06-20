import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: false,
    allowedHosts: [".loca.lt"],
    proxy: {
      "/feishu-openapi": {
        target: "https://open.feishu.cn/open-apis",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/feishu-openapi/, "")
      }
    }
  }
});
