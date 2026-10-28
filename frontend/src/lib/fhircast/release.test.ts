import { describe, expect, it } from "vitest";

import { decodeFhircastRelease } from "./release";
import { RELEASE } from "./fhircast.testHelpers";

// `decodeFhircastRelease`: the release the backend hands the session's FHIRcast.

describe("decodeFhircastRelease", () => {
  const decode = (body: unknown) => decodeFhircastRelease(Response.json(body));

  it("reads the release", async () => {
    expect(await decode(RELEASE)).toEqual(RELEASE);
  });

  it.each(["accessToken", "expiresAt", "hubUrl", "hubTopic"])(
    "refuses a release with no %s",
    async (key) => {
      await expect(decode({ ...RELEASE, [key]: undefined })).rejects.toThrow(
        `FhircastRelease.${key}`,
      );
    },
  );

  it("refuses a scope that is not a list of strings", async () => {
    await expect(decode({ ...RELEASE, scope: ["openid", 7] })).rejects.toThrow("scope");
  });
});
