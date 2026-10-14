package com.example.backend.scim.infrastructure.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * Database representation of a SCIM User: the profile half of a resource whose
 * identity and version live in {@code scim_resources}.
 *
 * <p>The two tables are one aggregate, and {@code @MapsId} is what says so: the User's
 * primary key IS the resource's id, taken from the association rather than assigned
 * twice, so there is no way to write a User row whose key disagrees with its resource
 * row. {@code cascade = PERSIST} lets the pair be written by one {@code save} in the
 * right order — the resource first, because the User's foreign key points at it.
 *
 * <p>The association is eager because every read of a User renders {@code meta} and
 * the ETag, both of which come from the resource row: making it lazy would turn every
 * single read into two queries and every listing into N+1, for data that is never not
 * wanted.
 *
 * <p>Eager is not the same as one statement, though: left to the mapping, Hibernate reads the
 * resource and the emails in statements after the User's. {@link #WITH_RESOURCE_AND_EMAILS}
 * names the graph that fetches all three in one, for the queries a deletion can race.
 */
@Entity
@Table(name = "scim_users")
@NamedEntityGraph(
        name = ScimUserEntity.WITH_RESOURCE_AND_EMAILS,
        attributeNodes = {@NamedAttributeNode("resource"), @NamedAttributeNode("emails")})
// Only changed columns are written. A SCIM replacement loads this row and changes the profile;
// without this, Hibernate's UPDATE would also write back the failure-run columns as they were
// read, erasing a failed login the login path counted in between (it writes them without the
// resource lock the SCIM write holds).
@DynamicUpdate
public class ScimUserEntity {

    /** The entity graph reading a User with its resource row and its emails in one statement. */
    public static final String WITH_RESOURCE_AND_EMAILS = "ScimUserEntity.withResourceAndEmails";

    @Id
    @Column(name = "resource_id", nullable = false, updatable = false)
    private UUID resourceId;

    /**
     * The resource row carrying this User's id, version and timestamps.
     *
     * <p>{@code @MapsId} derives {@link #resourceId} from it, so the id is stated once.
     */
    @MapsId
    @OneToOne(optional = false, fetch = FetchType.EAGER, cascade = CascadeType.PERSIST)
    @JoinColumn(name = "resource_id", nullable = false, updatable = false)
    private ScimResourceEntity resource;

    @Column(nullable = false, length = 256)
    private String userName;

    /**
     * The normalized form uniqueness is decided on, stored rather than computed in the
     * index: the normalization rule belongs to the domain, and a functional index would
     * be a second implementation of it in SQL that could drift.
     */
    @Column(nullable = false, length = 256, unique = true)
    private String normalizedUserName;

    /**
     * The authentication state the login surface writes: credential, failure run, lock
     * instant. Embedded so the three travel as one value, matching the domain's
     * {@code ScimLoginState} and the narrow port operation that writes them.
     */
    @Embedded
    private ScimLoginStateValue login;

    @Column(nullable = false)
    private boolean active;

    @Column(length = 256)
    private String displayName;

    @Column(length = 256)
    private String formattedName;

    @Column(length = 256)
    private String familyName;

    @Column(length = 256)
    private String givenName;

    @Column(length = 256)
    private String middleName;

    @Column(length = 256)
    private String honorificPrefix;

    @Column(length = 256)
    private String honorificSuffix;

    @Column(length = 64)
    private String preferredLanguage;

    @Column(length = 64)
    private String locale;

    @Column(length = 64)
    private String timezone;

    /**
     * The multi-valued {@code emails} attribute, in the order the connector sent.
     *
     * <p>{@code @OrderColumn} rather than an unordered collection, because SCIM renders
     * the values back and a set that reshuffled between reads would make a
     * diff-based client rewrite the resource forever. Eager for the same reason the
     * resource association is: every rendering of a User needs it.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "scim_user_emails",
            joinColumns = @JoinColumn(name = "resource_id", nullable = false))
    @OrderColumn(name = "ordinal", nullable = false)
    private List<ScimUserEmailValue> emails = new ArrayList<>();

    protected ScimUserEntity() {
    }

    public ScimUserEntity(
            ScimResourceEntity resource,
            String userName,
            String normalizedUserName,
            ScimLoginStateValue login,
            boolean active,
            String displayName,
            String formattedName,
            String familyName,
            String givenName,
            String middleName,
            String honorificPrefix,
            String honorificSuffix,
            String preferredLanguage,
            String locale,
            String timezone,
            List<ScimUserEmailValue> emails) {
        this.resource = resource;
        this.userName = userName;
        this.normalizedUserName = normalizedUserName;
        this.login = login;
        this.active = active;
        this.displayName = displayName;
        this.formattedName = formattedName;
        this.familyName = familyName;
        this.givenName = givenName;
        this.middleName = middleName;
        this.honorificPrefix = honorificPrefix;
        this.honorificSuffix = honorificSuffix;
        this.preferredLanguage = preferredLanguage;
        this.locale = locale;
        this.timezone = timezone;
        this.emails = new ArrayList<>(emails);
    }

    public ScimResourceEntity getResource() {
        return resource;
    }

    /**
     * Replaces every profile column with the given values.
     *
     * <p>The emails are replaced by assigning a NEW collection. A new collection is removed and
     * re-inserted whole, which is what the two partial unique indexes on {@code scim_user_emails}
     * need: an in-place, position-by-position update could briefly hold the primary flag or a
     * {@code (type, value)} pair on two rows at once — moving primacy from the first email to the
     * second is exactly that — and a non-deferrable unique index refuses the intermediate state.
     * Reassigned even when equal: this is called only for a write that changed something, and a
     * handful of rows re-inserted is cheaper than a comparison that must be kept right.
     */
    public void replaceProfile(
            String userName,
            String normalizedUserName,
            boolean active,
            String displayName,
            String formattedName,
            String familyName,
            String givenName,
            String middleName,
            String honorificPrefix,
            String honorificSuffix,
            String preferredLanguage,
            String locale,
            String timezone,
            List<ScimUserEmailValue> emails) {
        this.userName = userName;
        this.normalizedUserName = normalizedUserName;
        this.active = active;
        this.displayName = displayName;
        this.formattedName = formattedName;
        this.familyName = familyName;
        this.givenName = givenName;
        this.middleName = middleName;
        this.honorificPrefix = honorificPrefix;
        this.honorificSuffix = honorificSuffix;
        this.preferredLanguage = preferredLanguage;
        this.locale = locale;
        this.timezone = timezone;
        this.emails = new ArrayList<>(emails);
    }

    public String getUserName() {
        return userName;
    }

    public ScimLoginStateValue getLogin() {
        return login;
    }

    public boolean isActive() {
        return active;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getFormattedName() {
        return formattedName;
    }

    public String getFamilyName() {
        return familyName;
    }

    public String getGivenName() {
        return givenName;
    }

    public String getMiddleName() {
        return middleName;
    }

    public String getHonorificPrefix() {
        return honorificPrefix;
    }

    public String getHonorificSuffix() {
        return honorificSuffix;
    }

    public String getPreferredLanguage() {
        return preferredLanguage;
    }

    public String getLocale() {
        return locale;
    }

    public String getTimezone() {
        return timezone;
    }

    public List<ScimUserEmailValue> getEmails() {
        return List.copyOf(emails);
    }
}
