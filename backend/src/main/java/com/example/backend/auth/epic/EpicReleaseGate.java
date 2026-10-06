package com.example.backend.auth.epic;

/**
 * Whether this deployment serves Epic Login at all.
 *
 * <p>Open only when the switch is on and the configuration passed
 * {@link EpicLoginProperties#validate}, so an open gate is a statement that every required
 * setting is present and well formed. A closed gate says nothing about the other settings: with
 * the switch off none of them is read, and the deployment needs none of them.
 *
 * @param open whether the {@code /api/auth/epic/**} routes answer
 */
public record EpicReleaseGate(boolean open) {
}
