import { describe, expect, it } from "vitest";

import { DecodeError } from "../decode";
import { describeDecoder, STRING } from "../decode.testHelpers";
import { decodeFhircastRelease } from "./release";
import { RELEASE, RELEASE_WITHOUT_TOKEN } from "./fhircast.testHelpers";

// `decodeFhircastRelease`: the release the backend hands the session's FHIRcast.
const decode = (body: unknown) => decodeFhircastRelease(Response.json(body));

describe("a release carrying the SMART access token", () => {
  describeDecoder("FhircastRelease", decode, RELEASE, {
    accessToken: STRING,
    expiresAt: STRING,
    hubTopic: STRING,
    hubUrl: STRING,
    scope: ["openid", "an array"],
    softwareId: STRING,
  });
});

describe("a release of the hub alone", () => {
  describeDecoder("FhircastRelease", decode, RELEASE_WITHOUT_TOKEN, {
    hubTopic: STRING,
    hubUrl: STRING,
    softwareId: STRING,
  });
});

describe("decodeFhircastRelease", () => {
  it("refuses an expiry that is no instant", async () => {
    await expect(decode({ ...RELEASE, expiresAt: "in an hour" })).rejects.toThrow(
      new DecodeError("FhircastRelease.expiresAt is no instant"),
    );
  });

  it("refuses a scope that is not a list of strings", async () => {
    await expect(decode({ ...RELEASE, scope: ["openid", 7] })).rejects.toThrow(
      new DecodeError("FhircastRelease.scope is not strings"),
    );
  });

  it("refuses a release carrying the SMART access token without its expiry and scope", async () => {
    await expect(
      decode({ ...RELEASE_WITHOUT_TOKEN, accessToken: RELEASE.accessToken }),
    ).rejects.toThrow(new DecodeError("FhircastRelease.expiresAt is not a string"));
  });

  it("refuses a release carrying the token's scope without the token", async () => {
    await expect(decode({ ...RELEASE_WITHOUT_TOKEN, scope: RELEASE.scope })).rejects.toThrow(
      new DecodeError("FhircastRelease.accessToken is not a string"),
    );
  });
});
