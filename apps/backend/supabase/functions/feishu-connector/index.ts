/// <reference path="../_shared/deno.d.ts" />

import { corsResponse, jsonResponse, methodNotAllowed, readJson } from "../_shared/http.ts";
import { buildFeishuAuthorizeUrl, isFeishuAuthorizationRequiredError, type FeishuServerConfig } from "../_shared/feishuApi.ts";
import { serverDecryptJson, serverEncryptJson } from "../_shared/serverCrypto.ts";
import {
  feishuConnectorDisabledBody,
  isFeishuServerConnectorAllowed
} from "../_shared/security.ts";
import {
  createFeishuEventForUser,
  deleteFeishuEventForUser,
  readServerCalendarEvents,
  syncFeishuForUser,
  updateFeishuEventForUser
} from "../_shared/feishuSync.ts";
import { encodeFilter, getAuthenticatedUser, restInsert, restPatch, restSelect, restUpsert } from "../_shared/supabaseRest.ts";

type ConnectorRequest =
  | {
      action: "configure";
      appId: string;
      appSecret: string;
      redirectUri?: string;
      startOAuth?: boolean;
    }
  | {
      action: "status" | "start_oauth" | "sync_now" | "disconnect";
    }
  | {
      action: "list_events";
      startsAt?: string;
      endsAt?: string;
    }
  | {
      action: "create_event";
      title: string;
      description?: string;
      location?: string;
      startsAt: string;
      endsAt?: string;
      calendarId?: string;
      timezone?: string;
    }
  | {
      action: "update_event";
      calendarId: string;
      eventId: string;
      title?: string;
      description?: string;
      location?: string;
      startsAt?: string;
      endsAt?: string;
      timezone?: string;
    }
  | {
      action: "delete_event";
      calendarId: string;
      eventId: string;
    };

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") {
    return corsResponse();
  }

  if (!["GET", "POST"].includes(request.method)) {
    return methodNotAllowed(["GET", "POST"]);
  }

  if (!isFeishuServerConnectorAllowed()) {
    return jsonResponse(feishuConnectorDisabledBody(), { status: 409 });
  }

  try {
    const user = await getAuthenticatedUser(request);

    if (request.method === "GET") {
      return jsonResponse(await statusForUser(user.id));
    }

    const body = await readJson<ConnectorRequest>(request);
    switch (body.action) {
      case "status":
        return jsonResponse(await statusForUser(user.id));
      case "configure":
        return jsonResponse(await configure(user.id, body));
      case "start_oauth":
        return jsonResponse(await startOAuth(user.id));
      case "sync_now":
        return jsonResponse(await syncFeishuForUser(user.id));
      case "list_events":
        return jsonResponse({
          events: await readServerCalendarEvents(user.id, body.startsAt, body.endsAt)
        });
      case "create_event":
        return jsonResponse(await createFeishuEventForUser(user.id, body));
      case "update_event":
        return jsonResponse(await updateFeishuEventForUser(user.id, body));
      case "delete_event":
        return jsonResponse(await deleteFeishuEventForUser(user.id, body));
      case "disconnect":
        return jsonResponse(await disconnect(user.id));
      default:
        return jsonResponse({ error: "unknown_action" }, { status: 400 });
    }
  } catch (error) {
    if (isAuthError(error)) {
      return jsonResponse(
        {
          error: "auth_failed",
          status: "needs_auth",
          message: "Sign in again."
        },
        { status: 401 }
      );
    }
    if (isFeishuAuthorizationRequiredError(error)) {
      return jsonResponse(
        {
          error: "feishu_needs_auth",
          status: "needs_auth",
          message: error.message
        },
        { status: 409 }
      );
    }
    return jsonResponse(
      {
        error: "calendar_sync_failed",
        message: error instanceof Error ? error.message : "Calendar sync failed."
      },
      { status: 500 }
    );
  }
});

function isAuthError(error: unknown) {
  const message = error instanceof Error ? error.message.toLowerCase() : String(error).toLowerCase();
  return (
    message.includes("missing authorization") ||
    message.includes("invalid supabase session") ||
    message.includes("jwt") ||
    message.includes("invalid claim") ||
    message.includes("invalid bearer") ||
    message.includes("bearer token")
  );
}

async function statusForUser(userId: string) {
  const rows = await restSelect<{
    status: string;
    mode: string;
    default_calendar_id?: string | null;
    last_server_sync_at?: string | null;
    updated_at: string;
    server_encrypted_config?: string | null;
    server_encrypted_token_set?: string | null;
  }>(
    "resolve_feishu_connections",
    `select=status,mode,default_calendar_id,last_server_sync_at,updated_at,server_encrypted_config,server_encrypted_token_set&user_id=eq.${encodeFilter(userId)}&limit=1`
  );
  const row = rows[0];
  const hasGlobalConfig = Boolean(globalFeishuConfig());
  const status = row?.status ?? "not_connected";
  const hasToken = Boolean(row?.server_encrypted_token_set);
  const needsAuthorization = status === "needs_auth" || status === "not_connected" || !hasToken;
  return {
    configured: Boolean(row?.server_encrypted_config) || hasGlobalConfig,
    connected: hasToken && !needsAuthorization,
    needsAuthorization,
    status,
    mode: row?.mode ?? "server_connector_opt_in",
    defaultCalendarId: row?.default_calendar_id ?? undefined,
    lastServerSyncAt: row?.last_server_sync_at ?? undefined,
    updatedAt: row?.updated_at ?? undefined,
    privacy:
      "Calendar is server-managed: Feishu event plaintext is visible to the Edge Function at runtime and encrypted at rest with RESOLVE_SERVER_SECRET."
  };
}

async function configure(
  userId: string,
  input: Extract<ConnectorRequest, { action: "configure" }>
) {
  if (!input.appId.trim() || !input.appSecret.trim()) {
    return {
      error: "missing_config",
      message: "appId and appSecret are required."
    };
  }
  const config: FeishuServerConfig = {
    appId: input.appId.trim(),
    appSecret: input.appSecret.trim(),
    redirectUri: input.redirectUri?.trim() || defaultRedirectUri()
  };
  const encryptedConfig = await serverEncryptJson(config);
  const now = new Date().toISOString();

  await restUpsert(
    "resolve_feishu_connections",
    {
      user_id: userId,
      mode: "server_connector_opt_in",
      status: "needs_auth",
      server_encrypted_config: encryptedConfig.encrypted,
      server_config_nonce: encryptedConfig.nonce,
      updated_at: now
    },
    "user_id"
  );

  return input.startOAuth
    ? startOAuth(userId)
    : {
        status: "needs_auth",
        redirectUri: config.redirectUri
      };
}

async function startOAuth(userId: string) {
  const config = await resolveServerConfig(userId);
  if (!config) {
    return {
      error: "missing_config",
      message: "Feishu is not configured on the Resolve backend."
    };
  }
  const state = crypto.randomUUID();
  const expiresAt = new Date(Date.now() + 10 * 60 * 1000).toISOString();
  const encryptedConfig = await serverEncryptJson(config);
  await restInsert("resolve_feishu_oauth_states", {
    user_id: userId,
    state,
    server_encrypted_config: encryptedConfig.encrypted,
    server_config_nonce: encryptedConfig.nonce,
    redirect_uri: config.redirectUri,
    expires_at: expiresAt
  });

  return {
    status: "needs_auth",
    authorizeUrl: buildFeishuAuthorizeUrl(config, state),
    redirectUri: config.redirectUri,
    expiresAt
  };
}

async function disconnect(userId: string) {
  await restPatch(
    "resolve_feishu_connections",
    `user_id=eq.${encodeFilter(userId)}`,
    {
      status: "not_connected",
      server_encrypted_token_set: null,
      server_token_nonce: null,
      updated_at: new Date().toISOString()
    }
  );
  return {
    status: "not_connected"
  };
}

function defaultRedirectUri() {
  const baseUrl = Deno.env.get("RESOLVE_PUBLIC_FUNCTIONS_URL") ?? Deno.env.get("SUPABASE_URL");
  if (!baseUrl) throw new Error("Missing SUPABASE_URL.");
  return `${baseUrl.replace(/\/$/, "")}/functions/v1/feishu-oauth-callback`;
}

async function resolveServerConfig(userId: string): Promise<FeishuServerConfig | null> {
  const rows = await restSelect<{
    server_encrypted_config?: string | null;
    server_config_nonce?: string | null;
  }>(
    "resolve_feishu_connections",
    `select=server_encrypted_config,server_config_nonce&user_id=eq.${encodeFilter(userId)}&mode=eq.server_connector_opt_in&limit=1`
  );
  const connection = rows[0];
  if (connection?.server_encrypted_config && connection.server_config_nonce) {
    return serverDecryptJson<FeishuServerConfig>(
      connection.server_encrypted_config,
      connection.server_config_nonce
    );
  }

  const config = globalFeishuConfig();
  if (!config) return null;

  const encryptedConfig = await serverEncryptJson(config);
  await restUpsert(
    "resolve_feishu_connections",
    {
      user_id: userId,
      mode: "server_connector_opt_in",
      status: "needs_auth",
      server_encrypted_config: encryptedConfig.encrypted,
      server_config_nonce: encryptedConfig.nonce,
      updated_at: new Date().toISOString()
    },
    "user_id"
  );
  return config;
}

function globalFeishuConfig(): FeishuServerConfig | null {
  const appId = Deno.env.get("RESOLVE_FEISHU_APP_ID") ?? Deno.env.get("FEISHU_APP_ID");
  const appSecret = Deno.env.get("RESOLVE_FEISHU_APP_SECRET") ?? Deno.env.get("FEISHU_APP_SECRET");
  if (!appId || !appSecret) return null;
  return {
    appId,
    appSecret,
    redirectUri: Deno.env.get("RESOLVE_FEISHU_REDIRECT_URI") ?? defaultRedirectUri()
  };
}
