// The shim's and the seed's settings come from the environment
// backend/compose.yaml sets, and none has a default: a missing one stops the
// process at startup rather than running it misconfigured.

/** The variable `name`, which must be set and not blank. */
export function required(name) {
  const value = process.env[name];
  if (value === undefined || value.trim() === "") {
    throw new Error(`${name} is required`);
  }
  return value;
}
