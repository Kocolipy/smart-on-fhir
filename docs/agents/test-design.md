# Test Design

Where a test belongs and what it asserts, for both apps. Each app's
`docs/TESTING_GUIDE.md` covers the mechanics of writing and running one; this
covers which test to write at all. Every rule below comes from tests the suite
once carried and later removed (#183, #205).

## Before writing a test

1. Name the behaviour as one rule: "a password shorter than twelve characters
   is refused", "every non-public operation refuses a caller with no session".
2. Find its **owner**: the test of the code that decides the rule — usually the
   domain object's or policy's own test class. Query the graph for the rule's
   class, then read its tests.
3. If a **sweep** covers the rule's kind, extend the sweep's table.
4. Otherwise add the case to the owner, or write a **wiring** case at the layer
   you are changing.

Done when you can name the owner, and say what the new test can fail on that
no existing test can.

## One owner per rule

A rule is tested once, by its owner, across its full table: boundaries, every
refusal, every equivalent spelling. `PasswordPolicyTests` and
`PasswordAcceptanceTests` own the password rules, `ConnectorTokenPolicyTests`
the rotation-overlap arithmetic, and `FhircastHubTests` the hub grammar.

A layer above the owner asserts **wiring**: one case per argument, proving the
input reaches the owner and the owner's answer comes back — the refusal
surfaces as the right error, nothing is written, the session survives. For
example, `ScimUserServiceTests` keeps one sub-policy refusal per verb.

A wiring case is the only check that its argument reaches the owner, so it
stays even when it looks like a repeat. `create`'s user-name case is the only
proof that `create` hands the new user's name to the policy; the expiry-cap
case is the only proof that rotation passes the token's current expiry.

A domain method is tested in its own class's tests (`ScimUser` in
`ScimUserTests`), even when another type's state drives it.

A shared mechanism is owned by its own tests (`ScheduledJobRegistrationTests`
owns how a declared counter counts). A consumer tests what it declares — its
names, descriptions and values — and leaves the mechanism to them.

## Sweeps

A **sweep** enumerates every member of a set and holds each to one rule, so a
new member is covered the moment it exists:

| Sweep                                                          | Holds                                                                                    |
| -------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| `AuthorizationContractTests`                                   | every documented operation's `security`: 401 with no session, 403 without its Permission |
| `RouteContractTests`                                           | every mapped route is a documented operation                                             |
| `ApiContractFixtureTests`, `ScimConformanceFixtureTests`       | every documented status, against the running service                                     |
| `AuditTrailServiceTests.everyKindOfEventFailsAsAdr0004Decides` | every audit event kind's fail-open or fail-closed decision, and its alert                |
| `describeDecoder` (`accounts-api.test.ts`)                     | every decoded field: present, typed, extra fields dropped                                |

A new member joins the sweep's table. A test beside a sweep asserts only what
the sweep cannot see: a side effect, a response body, an audit row.

A rendered document is owned by its whole-document equality test
(`the_rendered_schema_document_is_exactly_this`): a flag on one attribute is
asserted there, in the literal.

## Load-bearing

A test is **load-bearing** when a plausible change to the code it names makes
it fail. Two shapes pass whatever the code does:

- A probe of something that does not exist, such as a confinement test reading
  a route that refuses everyone. Probe a target the code under test would
  otherwise admit.
- A tombstone: a test that a removed feature stays removed — a retired field,
  setting, route, encoder, or a lockout that never expires. **Removing a feature
  removes its tests in the same change**, and where a check of the feature sits
  inside a test of live behaviour, only the check goes. The suite describes
  live behaviour only.

When a test cannot reach the behaviour it claims, remove it and record the gap
where the behaviour is covered, or that it is not.

## Trimming a test

Before deleting a test as a repeat, find the test that keeps each of its
assertions, and compare **oracles**: two tests of one value stay when each can
fail where the other cannot. `TraceExportTests` reads the export flag from the
deployed `telemetry.yaml` and from the effective environment — one catches a
test-profile override masking the deployed value, the other the flag set in
another file. A hand-written literal list is an independent oracle that a list
derived from the production enum is not.

Verify the trim with the app's mutation tool, scoped to the production classes
the deleted tests covered: run the identical scope before and after. The trim
holds when the two survivor sets are equal; record both scores in the pull
request.
