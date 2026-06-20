/// <reference path="../_shared/deno.d.ts" />

import { FeishuServerClient, type FeishuServerConfig } from "../_shared/feishuApi.ts";
import { securityHeaders } from "../_shared/http.ts";
import { serverDecryptJson, serverEncryptJson } from "../_shared/serverCrypto.ts";
import { syncFeishuForUser } from "../_shared/feishuSync.ts";
import { encodeFilter, restPatch, restSelect, restUpsert } from "../_shared/supabaseRest.ts";

Deno.serve(async (request) => {
  if (request.method !== "GET") {
    return html("Resolve Feishu", "Unsupported callback method.", 405);
  }

  const url = new URL(request.url);
  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");
  const error = url.searchParams.get("error");

  if (error) {
    return html("Resolve Feishu", "Feishu authorization was cancelled or rejected.", 400);
  }
  if (!code || !state) {
    return html("Resolve Feishu", "Missing Feishu authorization code or state.", 400);
  }

  const rows = await restSelect<{
    id: string;
    user_id: string;
    server_encrypted_config: string;
    server_config_nonce: string;
    redirect_uri: string;
  }>(
    "resolve_feishu_oauth_states",
    [
      "select=id,user_id,server_encrypted_config,server_config_nonce,redirect_uri",
      `state=eq.${encodeFilter(state)}`,
      "consumed_at=is.null",
      `expires_at=gt.${encodeFilter(new Date().toISOString())}`,
      "limit=1"
    ].join("&")
  );
  const oauthState = rows[0];
  if (!oauthState) {
    return html("Resolve Feishu", "This Feishu authorization link is expired or invalid.", 400);
  }

  const config = await serverDecryptJson<FeishuServerConfig>(
    oauthState.server_encrypted_config,
    oauthState.server_config_nonce
  );
  const tokenSet = await FeishuServerClient.exchangeCode(config, code);
  const encryptedToken = await serverEncryptJson(tokenSet);
  const encryptedConfig = await serverEncryptJson(config);
  const now = new Date().toISOString();

  await restUpsert(
    "resolve_feishu_connections",
    {
      user_id: oauthState.user_id,
      mode: "server_connector_opt_in",
      status: "connected",
      server_encrypted_config: encryptedConfig.encrypted,
      server_config_nonce: encryptedConfig.nonce,
      server_encrypted_token_set: encryptedToken.encrypted,
      server_token_nonce: encryptedToken.nonce,
      updated_at: now
    },
    "user_id"
  );
  await restPatch(
    "resolve_feishu_oauth_states",
    `id=eq.${encodeFilter(oauthState.id)}`,
    {
      consumed_at: now
    }
  );

  try {
    await syncFeishuForUser(oauthState.user_id);
  } catch {
    await restPatch(
      "resolve_feishu_connections",
      `user_id=eq.${encodeFilter(oauthState.user_id)}`,
      {
        status: "error",
        updated_at: new Date().toISOString()
      }
    );
    return html(
      "Resolve Feishu",
      "Feishu is connected, but the first calendar sync failed. Return to Resolve and tap Sync.",
      200
    );
  }

  return html("Resolve Feishu", "Feishu is connected and calendar sync has started. You can return to Resolve.", 200);
});

function html(title: string, message: string, status: number) {
  return new Response(
    `<!doctype html><html><head><meta charset="utf-8"><title>${escapeHtml(title)}</title></head>
<body style="font: -apple-system-body; padding: 48px; color: #1d1d1f;">
<h1>${escapeHtml(title)}</h1><p>${escapeHtml(message)}</p></body></html>`,
    {
      status,
      headers: {
        "content-type": "text/html; charset=utf-8",
        ...securityHeaders
      }
    }
  );
}

function escapeHtml(value: string) {
  return value.replace(/[&<>"']/g, (char) => {
    switch (char) {
      case "&":
        return "&amp;";
      case "<":
        return "&lt;";
      case ">":
        return "&gt;";
      case "\"":
        return "&quot;";
      default:
        return "&#039;";
    }
  });
}
