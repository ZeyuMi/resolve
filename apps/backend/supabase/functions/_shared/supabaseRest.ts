/// <reference path="./deno.d.ts" />

export interface AuthenticatedUser {
  id: string;
  email?: string;
}

function projectUrl() {
  const url = Deno.env.get("SUPABASE_URL");
  if (!url) throw new Error("Missing SUPABASE_URL.");
  return url.replace(/\/$/, "");
}

function serviceRoleKey() {
  const modernKeys = Deno.env.get("SUPABASE_SECRET_KEYS");
  if (modernKeys) {
    const parsed = JSON.parse(modernKeys) as Record<string, string>;
    const key = parsed.default ?? Object.values(parsed)[0];
    if (key) return key;
  }

  const legacy = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (legacy) return legacy;
  throw new Error("Missing Supabase service role key.");
}

function headers(extra?: HeadersInit) {
  const key = serviceRoleKey();
  return {
    apikey: key,
    authorization: `Bearer ${key}`,
    "content-type": "application/json",
    ...extra
  };
}

async function parseResponse<T>(response: Response): Promise<T> {
  const text = await response.text();
  if (!response.ok) {
    throw new Error(text || `Supabase REST failed with HTTP ${response.status}`);
  }
  return text ? (JSON.parse(text) as T) : ([] as T);
}

export async function getAuthenticatedUser(request: Request): Promise<AuthenticatedUser> {
  const authorization = request.headers.get("authorization");
  if (!authorization) {
    throw new Error("Missing authorization header.");
  }

  const response = await fetch(`${projectUrl()}/auth/v1/user`, {
    headers: {
      apikey: serviceRoleKey(),
      authorization
    }
  });
  const user = await parseResponse<{ id?: string; email?: string }>(response);
  if (!user.id) throw new Error("Invalid Supabase session.");
  return {
    id: user.id,
    email: user.email
  };
}

export function encodeFilter(value: string) {
  return encodeURIComponent(value);
}

export async function restSelect<T>(table: string, query: string) {
  const response = await fetch(`${projectUrl()}/rest/v1/${table}?${query}`, {
    headers: headers()
  });
  return parseResponse<T[]>(response);
}

export async function restInsert<T>(table: string, rows: T | T[]) {
  const response = await fetch(`${projectUrl()}/rest/v1/${table}`, {
    method: "POST",
    headers: headers({
      prefer: "return=representation"
    }),
    body: JSON.stringify(rows)
  });
  return parseResponse<T[]>(response);
}

export async function restUpsert<T>(table: string, rows: T | T[], onConflict: string) {
  const response = await fetch(
    `${projectUrl()}/rest/v1/${table}?on_conflict=${encodeURIComponent(onConflict)}`,
    {
      method: "POST",
      headers: headers({
        prefer: "resolution=merge-duplicates,return=representation"
      }),
      body: JSON.stringify(rows)
    }
  );
  return parseResponse<T[]>(response);
}

export async function restPatch<T>(table: string, query: string, patch: Record<string, unknown>) {
  const response = await fetch(`${projectUrl()}/rest/v1/${table}?${query}`, {
    method: "PATCH",
    headers: headers({
      prefer: "return=representation"
    }),
    body: JSON.stringify(patch)
  });
  return parseResponse<T[]>(response);
}
