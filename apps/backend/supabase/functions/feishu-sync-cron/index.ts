/// <reference path="../_shared/deno.d.ts" />

import { jsonResponse, methodNotAllowed } from "../_shared/http.ts";
import {
  feishuConnectorDisabledBody,
  isFeishuServerConnectorAllowed
} from "../_shared/security.ts";
import { isFeishuAuthorizationRequiredError } from "../_shared/feishuApi.ts";
import { syncFeishuForUser } from "../_shared/feishuSync.ts";
import { restSelect } from "../_shared/supabaseRest.ts";

Deno.serve(async (request) => {
  if (request.method !== "POST") {
    return methodNotAllowed(["POST"]);
  }

  if (!isFeishuServerConnectorAllowed()) {
    return jsonResponse({
      ...feishuConnectorDisabledBody(),
      cron: "skipped"
    });
  }

  const connections = await restSelect<{ user_id: string }>(
    "resolve_feishu_connections",
    [
      "select=user_id",
      "mode=eq.server_connector_opt_in",
      "status=neq.not_connected",
      "server_encrypted_token_set=not.is.null"
    ].join("&")
  );
  const results = [];
  for (const connection of connections) {
    try {
      results.push(await syncFeishuForUser(connection.user_id));
    } catch (error) {
      results.push({
        userId: connection.user_id,
        status: isFeishuAuthorizationRequiredError(error) ? "needs_auth" : "error",
        error: error instanceof Error ? error.message : "Feishu sync failed."
      });
    }
  }

  return jsonResponse({
    syncedUsers: results.length,
    results
  });
});
