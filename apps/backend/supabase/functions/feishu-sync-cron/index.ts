/// <reference path="../_shared/deno.d.ts" />

import { jsonResponse, methodNotAllowed } from "../_shared/http.ts";
import {
  feishuConnectorDisabledBody,
  isFeishuServerConnectorAllowed
} from "../_shared/security.ts";

Deno.serve((request) => {
  if (request.method !== "POST") {
    return methodNotAllowed(["POST"]);
  }

  if (!isFeishuServerConnectorAllowed()) {
    return jsonResponse({
      ...feishuConnectorDisabledBody(),
      cron: "skipped"
    });
  }

  return jsonResponse(
    {
      error: "not_implemented",
      cron: "skipped",
      message:
        "Server-side Feishu cron sync requires explicit product/security approval because it can see Feishu plaintext at runtime."
    },
    { status: 501 }
  );
});
