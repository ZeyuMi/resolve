/// <reference path="./deno.d.ts" />

export const strictE2eeStatement = [
  "Resolve keeps Todo and Strategy in strict E2EE mode.",
  "Calendar can use the server-side Feishu connector only when explicitly enabled.",
  "When enabled, the Edge Function runtime can see Feishu event plaintext while calling Feishu APIs, but database storage remains server-secret encrypted."
].join(" ");

export function isFeishuServerConnectorAllowed() {
  return Deno.env.get("RESOLVE_ALLOW_FEISHU_SERVER_CONNECTOR") === "true";
}

export function feishuConnectorDisabledBody() {
  return {
    status: "disabled",
    mode: "client_e2ee",
    reason: strictE2eeStatement,
    safePath: "Keep Feishu sync on Mac/Android clients, then upload encrypted calendar payloads to Supabase.",
    optInRisk:
      "Enable RESOLVE_ALLOW_FEISHU_SERVER_CONNECTOR=true only if calendar runtime plaintext on the backend is acceptable."
  };
}
