-- The patient module's one table (/docs/domain/patient.md). The FHIR R5
-- `Patient` the service serves is rendered from these columns, not stored: there
-- is no JSON column, and no `meta`. Types follow V1's choices (UNIQUEIDENTIFIER
-- ids assigned by the application, VARCHAR as UTF-8 under the database
-- collation, four bytes per code point where any Unicode text may appear).
--
-- Age is not a column. It is derived from `birth_date` wherever it is shown, so
-- no stored value can go stale.

CREATE TABLE patients (
    id          UNIQUEIDENTIFIER NOT NULL,

    -- The one identifier, under Synapxe's NRIC NamingSystem. Its shape is not
    -- checked: the values seeded today are dummies, each with a deliberately
    -- wrong check letter. Unique, because an NRIC names one person. ASCII.
    nric        VARCHAR(64)      NOT NULL,

    -- The official HumanName. `text` is required and authoritative; the split
    -- into family and given names is optional, because many Singapore names do
    -- not split cleanly. `name_given` holds the given names in order, one space
    -- between each. 256 code points each.
    name_text   VARCHAR(1024)    NOT NULL,
    name_family VARCHAR(1024),
    name_given  VARCHAR(1024),

    -- FHIR's administrative gender, as its code.
    gender      VARCHAR(16)      NOT NULL,

    birth_date  DATE             NOT NULL,

    CONSTRAINT pk_patients PRIMARY KEY (id),
    CONSTRAINT uq_patients_nric UNIQUE (nric),
    CONSTRAINT ck_patients_gender
        CHECK (gender IN ('male', 'female', 'other', 'unknown'))
);

-- `backend_app`, the role V1 makes the app login (its `app_login` placeholder) a member of,
-- reads patients for `GET /api/patient/{id}` and inserts them for the
-- development fixtures' seeder (PatientSeedService), which runs on the app pool
-- because a deployed service never holds the owner login. No UPDATE and no
-- DELETE: nothing in the service changes or removes a patient.
GRANT SELECT, INSERT ON patients TO backend_app;
