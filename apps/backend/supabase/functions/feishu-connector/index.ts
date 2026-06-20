/// <reference path="../_shared/deno.d.ts" />

import { jsonResponse, methodNotAllowed, readJson } from "../_shared/http.ts";
import {
  feishuConnectorDisabledBody,
  isFeishuServerConnectorAllowed
} from "../_shared/security.ts";

interface ConnectorRequest {
  action: "status" | "start_oauth" | "sync_now";
}

Deno.serve(async (request) => {
  if (!["GET", "POST"].includes(request.method)) {
    return methodNotAllowed(["GET", "POST"]);
  }

  if (!isFeishuServerConnectorAllowed()) {
    return jsonResponse(feishuConnectorDisabledBody(), { status: 409 });
  }

  if (request.method === "GET") {
    return jsonResponse({
      status: "opt_in_enabled",
      warning:
        "Server-side Feishu connector is enabled. Make sure this is intentional because Feishu event plaintext is visible to the function runtime."
    });
  }

  const body = await readJson<ConnectorRequest>(request);
  if (!["status", "start_oauth", "sync_now"].includes(body.action)) {
    return jsonResponse(
      {
        error: "unknown_action"
      },
      { status: 400 }
    );
  }

  return jsonResponse(
    {
      error: "not_implemented",
      action: body.action,
      message:
        "The opt-in Feishu server connector is intentionally not implemented until the user accepts the runtime plaintext tradeoff."
    },
    { status: 501 }
  );
});
