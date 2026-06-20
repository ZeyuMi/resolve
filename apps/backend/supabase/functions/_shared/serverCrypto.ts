/// <reference path="./deno.d.ts" />

const encoder = new TextEncoder();
const decoder = new TextDecoder();

function bytesToBase64(bytes: Uint8Array) {
  let binary = "";
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary);
}

function base64ToBytes(value: string) {
  const binary = atob(value);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

async function serverKey() {
  const secret = Deno.env.get("RESOLVE_SERVER_SECRET");
  if (!secret || secret.length < 32) {
    throw new Error("Missing RESOLVE_SERVER_SECRET. Use a random 32+ character secret.");
  }

  const material = await crypto.subtle.digest("SHA-256", encoder.encode(secret));
  return crypto.subtle.importKey("raw", material, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

export async function serverEncryptJson(value: unknown) {
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt(
    {
      name: "AES-GCM",
      iv: nonce
    },
    await serverKey(),
    encoder.encode(JSON.stringify(value))
  );

  return {
    encrypted: bytesToBase64(new Uint8Array(ciphertext)),
    nonce: bytesToBase64(nonce)
  };
}

export async function serverDecryptJson<T>(encrypted: string, nonce: string): Promise<T> {
  const plaintext = await crypto.subtle.decrypt(
    {
      name: "AES-GCM",
      iv: base64ToBytes(nonce)
    },
    await serverKey(),
    base64ToBytes(encrypted)
  );

  return JSON.parse(decoder.decode(plaintext)) as T;
}
