package com.example.backend.scim.application;

import java.util.List;
import java.util.UUID;

/**
 * One thing a SCIM PATCH asks to be done to a Group, as a closed set.
 *
 * <p>Sealed, and that is the design. RFC 7644 §3.5.2 defines PATCH as operations over
 * attribute paths, which is an open-ended grammar; a Group in this directory has exactly three
 * writable attributes — {@code displayName}, {@code members} and the calling connector's
 * {@code externalId} — so the set of things a PATCH can actually mean is small and finite.
 * Naming each of them is what lets the use case apply a PATCH as a fold over typed values
 * instead of interpreting paths at the point of writing — and what makes an unsupported path
 * a refusal at the adapter, where the request is still a request, rather than a surprise
 * deeper in.
 *
 * <p>Each variant carries resolved ids rather than the submitted JSON, so nothing below the
 * adapter parses anything. A member id that is not a UUID is not representable here at all.
 *
 * <p>Deliberately absent: any operation on {@code id}, {@code meta} or the reverse
 * membership view. They are read-only, and the way this refuses them is by having no variant
 * they could be expressed as.
 */
public sealed interface ScimGroupPatchOperation {

    /**
     * Every User this operation names — added, removed or replacing the membership — so a write
     * can lock them before it reads the Group. With the Group's current members, a superset of
     * every User whose membership the operation can move. Abstract, so a new variant has to say
     * which Users it names rather than inherit "none".
     */
    List<UUID> namedUserIds();

    /**
     * Set the Group's label. {@code add} and {@code replace} on {@code displayName} are the
     * same operation — the attribute is single-valued, so adding to it is replacing it, as
     * RFC 7644 §3.5.2.1 says of a single-valued attribute.
     */
    record SetDisplayName(String displayName) implements ScimGroupPatchOperation {

        @Override
        public List<UUID> namedUserIds() {
            return List.of();
        }
    }

    /**
     * Set the calling connector's alias. {@code add} and {@code replace} are one operation, for the
     * reason they are on {@code displayName}: the attribute is single-valued.
     */
    record SetExternalId(String externalId) implements ScimGroupPatchOperation {

        @Override
        public List<UUID> namedUserIds() {
            return List.of();
        }
    }

    /** Remove the calling connector's alias; another connector's alias is not reachable. */
    record RemoveExternalId() implements ScimGroupPatchOperation {

        @Override
        public List<UUID> namedUserIds() {
            return List.of();
        }
    }

    /**
     * Add these Users to the membership, leaving existing members in place. Ids already
     * present are not an error: the de-duplication in {@code ScimGroup} makes adding a
     * current member a no-op rather than a conflict, which is what a provisioning system
     * re-sending its desired state needs.
     */
    record AddMembers(List<UUID> userIds) implements ScimGroupPatchOperation {

        @Override
        public List<UUID> namedUserIds() {
            return userIds;
        }
    }

    /**
     * Remove these Users from the membership. An id that is not currently a member is not an
     * error, for the same reason: a connector converging on a desired state should not have
     * to know what the current one is.
     */
    record RemoveMembers(List<UUID> userIds) implements ScimGroupPatchOperation {

        @Override
        public List<UUID> namedUserIds() {
            return userIds;
        }
    }

    /**
     * Replace the whole membership with these Users — {@code replace} on {@code members} with
     * no value path, which RFC 7644 defines as replacing the attribute rather than merging
     * into it.
     */
    record ReplaceMembers(List<UUID> userIds) implements ScimGroupPatchOperation {

        @Override
        public List<UUID> namedUserIds() {
            return userIds;
        }
    }

    /**
     * Remove every member — {@code remove} on {@code members} with no value path.
     *
     * <p>A variant of its own rather than {@link RemoveMembers} with the current membership
     * passed in, because the adapter does not know the current membership and must not have
     * to read it: "remove all" is what the request said, and resolving it against the stored
     * state belongs to the use case that holds the transaction.
     */
    record RemoveAllMembers() implements ScimGroupPatchOperation {

        @Override
        public List<UUID> namedUserIds() {
            return List.of();
        }
    }
}
