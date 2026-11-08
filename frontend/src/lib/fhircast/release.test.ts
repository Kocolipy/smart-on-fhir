import { describe, expect, it } from "vitest";

import { DecodeError } from "../decode";
import { describeDecoder, STRING } from "../decode.testHelpers";
import { decodeFhircastRelease } from "./release";
import { RELEASE } from "./fhircast.testHelpers";

// `decodeFhircastRelease`: the release the backend hands the session's FHIRcast.
const decode = (body: unknown) => decodeFhircastRelease(Response.json(body));

describeDecoder("FhircastRelease", decode, RELEASE, {
  accessToken: STRING,
  expiresAt: STRING,
  hubTopic: STRING,
  hubUrl: STRING,
  scope: ["openid", "an array"],
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
});
