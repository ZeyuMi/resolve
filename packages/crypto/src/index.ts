export interface EncryptedPayload {
  encryptedPayload: string;
  payloadNonce: string;
  payloadVersion: 1;
}

const textEncoder = new TextEncoder();
const textDecoder = new TextDecoder();

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

export async function generateVaultKey() {
  return crypto.subtle.generateKey(
    {
      name: "AES-GCM",
      length: 256
    },
    true,
    ["encrypt", "decrypt"]
  );
}

export async function exportVaultRecoveryCode(vaultKey: CryptoKey) {
  const raw = new Uint8Array(await crypto.subtle.exportKey("raw", vaultKey));
  return bytesToBase64(raw);
}

export async function importVaultRecoveryCode(recoveryCode: string) {
  const raw = base64ToBytes(recoveryCode.trim());
  return crypto.subtle.importKey("raw", raw, "AES-GCM", true, ["encrypt", "decrypt"]);
}

export async function deriveVaultKeyFromPhrase(phrase: string, salt: string) {
  const keyMaterial = await crypto.subtle.importKey(
    "raw",
    textEncoder.encode(phrase),
    "PBKDF2",
    false,
    ["deriveKey"]
  );
  return crypto.subtle.deriveKey(
    {
      name: "PBKDF2",
      salt: textEncoder.encode(salt),
      iterations: 310_000,
      hash: "SHA-256"
    },
    keyMaterial,
    {
      name: "AES-GCM",
      length: 256
    },
    true,
    ["encrypt", "decrypt"]
  );
}

export async function encryptPayload(payload: unknown, vaultKey: CryptoKey): Promise<EncryptedPayload> {
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const plaintext = textEncoder.encode(JSON.stringify(payload));
  const ciphertext = await crypto.subtle.encrypt(
    {
      name: "AES-GCM",
      iv: nonce
    },
    vaultKey,
    plaintext
  );

  return {
    encryptedPayload: bytesToBase64(new Uint8Array(ciphertext)),
    payloadNonce: bytesToBase64(nonce),
    payloadVersion: 1
  };
}

export async function decryptPayload<T>(
  doc: {
    encryptedPayload: string;
    payloadNonce: string;
    payloadVersion: number;
  },
  vaultKey: CryptoKey
): Promise<T> {
  if (doc.payloadVersion !== 1) {
    throw new Error(`Unsupported payload version: ${doc.payloadVersion}`);
  }

  const plaintext = await crypto.subtle.decrypt(
    {
      name: "AES-GCM",
      iv: base64ToBytes(doc.payloadNonce)
    },
    vaultKey,
    base64ToBytes(doc.encryptedPayload)
  );

  return JSON.parse(textDecoder.decode(plaintext)) as T;
}
