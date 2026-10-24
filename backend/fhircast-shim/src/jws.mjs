import { createPublicKey, verify } from "node:crypto";

/**
 * The algorithms an assertion may be signed with, by the key type each needs:
 * the two SMART Backend Services names for asymmetric client authentication.
 */
const ALGORITHMS = {
  ES384: {
    hash: "sha384",
    fits: (jwk) => jwk.kty === "EC" && jwk.crv === "P-384",
    dsaEncoding: "ieee-p1363",
  },
  RS384: { hash: "sha384", fits: (jwk) => jwk.kty === "RSA" },
};

/** The algorithms a registered key may sign with. */
export const SIGNING_ALGORITHMS = Object.keys(ALGORITHMS);

/** Whether `jwk` is a public key one of {@link SIGNING_ALGORITHMS} can verify with. */
export function isUsablePublicKey(jwk) {
  if (typeof jwk !== "object" || jwk === null || "d" in jwk) return false;
  if (!Object.values(ALGORITHMS).some(({ fits }) => fits(jwk))) return false;
  try {
    createPublicKey({ key: jwk, format: "jwk" });
    return true;
  } catch {
    return false;
  }
}

/**
 * The claims of a compact JWS whose signature `jwk` verifies, under an
 * algorithm that fits the key; `undefined` for anything else.
 */
export function verifiedClaims(compact, jwk) {
  const parts = typeof compact === "string" ? compact.split(".") : [];
  if (parts.length !== 3) return undefined;
  const [header, payload, signature] = parts;
  const algorithm = ALGORITHMS[decodeJson(header)?.alg];
  if (algorithm === undefined || !algorithm.fits(jwk)) return undefined;
  let valid;
  try {
    valid = verify(
      algorithm.hash,
      Buffer.from(`${header}.${payload}`),
      {
        key: createPublicKey({ key: jwk, format: "jwk" }),
        dsaEncoding: algorithm.dsaEncoding,
      },
      Buffer.from(signature, "base64url"),
    );
  } catch {
    return undefined;
  }
  return valid ? decodeJson(payload) : undefined;
}

/** The JSON object a base64url segment encodes, or `undefined`. */
export function decodeJson(segment) {
  try {
    const value = JSON.parse(
      Buffer.from(segment ?? "", "base64url").toString("utf8"),
    );
    return typeof value === "object" && value !== null ? value : undefined;
  } catch {
    return undefined;
  }
}
