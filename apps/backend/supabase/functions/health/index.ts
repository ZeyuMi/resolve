/// <reference path="../_shared/deno.d.ts" />

import { jsonResponse, methodNotAllowed } from "../_shared/http.ts";

Deno.serve((request) => {
  if (request.method !== "GET") {
    return methodNotAllowed(["GET"]);
  }

  return jsonResponse({
    ok: true,
    service: "resolve-backend",
    privacy: "encrypted-payloads-only",
    timestamp: new Date().toISOString()
  });
});
