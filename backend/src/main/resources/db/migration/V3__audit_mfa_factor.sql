-- The MFA factor an Epic Login was made with (Epic Login, D17): `idp-attested`
-- while the Epic organisation's MFA is an attestation, or the RFC 8176 factor
-- the id_token's `amr` named once the evidence is required. Carried by an Epic
-- LOGIN_SUCCESS only, and NULL for every other event — a password Login's
-- included, and every login recorded before this column existed, which an
-- append-only trail does not rewrite.
--
-- A value from the closed vocabulary in code (AuditMfaFactor), never one Epic
-- sent. The table-level grants in V1 cover the new column as they cover the
-- rest, and the append-only trigger refuses an UPDATE of it as it refuses one
-- of any other.
ALTER TABLE audit_events ADD COLUMN mfa_factor VARCHAR(16);

ALTER TABLE audit_events ADD CONSTRAINT ck_audit_events_mfa_factor
    CHECK (mfa_factor IS NULL OR mfa_factor IN (
        'idp-attested', 'mfa', 'otp', 'hwk', 'swk', 'sms', 'tel', 'sc', 'fpt',
        'face', 'iris', 'retina', 'vbm'));
