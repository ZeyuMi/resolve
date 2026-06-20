/// <reference path="./deno.d.ts" />

export const strictE2eeStatement = [
  "Resolve is running in strict E2EE mode.",
  "The cloud backend must not fetch, transform, log, or persist plaintext Todo, Strategy, Calendar, or Feishu event content.",
  "Feishu server-side sync is disabled unless RESOLVE_ALLOW_FEISHU_SERVER_CONNECTOR is explicitly set to true."
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
      "If enabled later, the Feishu connector can keep tokens off devices, but cloud function runtime will see Feishu event plaintext while calling Feishu APIs."
  };
}
