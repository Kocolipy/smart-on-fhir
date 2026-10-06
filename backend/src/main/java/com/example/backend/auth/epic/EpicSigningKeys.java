package com.example.backend.auth.epic;

import java.util.Optional;

/**
 * The signing keys this deployment was started with: the active key, which signs every client
 * assertion, and the optional next key, which is published only, ahead of a rotation (D14).
 *
 * <p>Built only from configuration that passed {@link EpicLoginProperties#validate}, so the two
 * {@code kid}s differ.
 *
 * @param active the key that signs
 * @param next   the key published ahead of a rotation, when one is configured
 */
public record EpicSigningKeys(EpicSigningKey active, Optional<EpicSigningKey> next) {
}
