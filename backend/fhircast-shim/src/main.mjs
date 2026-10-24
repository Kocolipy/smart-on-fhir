// The shim's process: its settings from the environment, then the server.
// backend/compose.yaml sets every variable; nothing here has a default, so a
// missing one stops the shim at startup rather than running it misconfigured.
import { required } from "./env.mjs";
import { createShim } from "./shim.mjs";

const port = Number(required("SHIM_PORT"));
const tokenLifetimeSeconds = Number(required("SHIM_TOKEN_LIFETIME_SECONDS"));
if (!Number.isInteger(tokenLifetimeSeconds) || tokenLifetimeSeconds <= 0) {
  console.error(
    "fhircast-shim: SHIM_TOKEN_LIFETIME_SECONDS must be a positive whole number of seconds",
  );
  process.exit(1);
}

const leaseSeconds = Number(required("SHIM_LEASE_SECONDS"));
if (!Number.isInteger(leaseSeconds) || leaseSeconds <= 0) {
  console.error(
    "fhircast-shim: SHIM_LEASE_SECONDS must be a positive whole number of seconds",
  );
  process.exit(1);
}

const shim = createShim({
  publicBaseUrl: required("SHIM_PUBLIC_BASE_URL"),
  medplumUrl: required("SHIM_MEDPLUM_URL"),
  admin: {
    email: required("SHIM_MEDPLUM_ADMIN_EMAIL"),
    password: required("SHIM_MEDPLUM_ADMIN_PASSWORD"),
  },
  tokenLifetimeSeconds,
  leaseSeconds,
  testState: process.env.SHIM_TEST_STATE === "enabled",
});

// Every interface inside the container: compose publishes the port on the
// host's 127.0.0.1 alone.
shim.listen(port, () => {
  console.log(
    `fhircast-shim: listening on ${port}, hub tokens last ${tokenLifetimeSeconds}s, leases ${leaseSeconds}s`,
  );
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    shim.closeAllConnections();
    shim.close(() => process.exit(0));
  });
}
