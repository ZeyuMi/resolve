export const securityHeaders = {
  "content-security-policy": "default-src 'none'; frame-ancestors 'none'",
  "referrer-policy": "no-referrer",
  "x-content-type-options": "nosniff",
  "x-robots-tag": "noindex, nofollow, noarchive"
};

export const corsHeaders = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, POST, OPTIONS",
  "access-control-allow-headers": "authorization, apikey, content-type, x-client-info",
  "access-control-max-age": "86400"
};

export function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body, null, 2), {
    ...init,
    headers: {
      "content-type": "application/json; charset=utf-8",
      ...corsHeaders,
      ...securityHeaders,
      ...init.headers
    }
  });
}

export function corsResponse() {
  return new Response(null, {
    status: 204,
    headers: {
      ...corsHeaders,
      ...securityHeaders
    }
  });
}

export function methodNotAllowed(allowed: string[]) {
  return jsonResponse(
    {
      error: "method_not_allowed",
      allowed
    },
    {
      status: 405,
      headers: {
        allow: allowed.join(", ")
      }
    }
  );
}

export async function readJson<T>(request: Request): Promise<T> {
  const contentType = request.headers.get("content-type") ?? "";
  if (!contentType.includes("application/json")) {
    throw new Error("Expected application/json request body.");
  }
  return request.json() as Promise<T>;
}
