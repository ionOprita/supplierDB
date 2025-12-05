package ro.sellfluence.db.versions;

import java.sql.Connection;
import java.sql.SQLException;

import static ro.sellfluence.db.versions.EmagMirrorDBVersion1.executeStatement;

class EmagMirrorDBVersion30 {
    /**
     * Create tables for the passkey authentication.
     *
     * @param db database connection to use.
     * @throws SQLException all errors are passed back to the caller.
     */
    static void version30(Connection db) throws SQLException {
        userTable(db);
        credentialsTable(db);
        challengeTable(db);
    }

    private static void userTable(Connection db) throws SQLException {
        executeStatement(db, """
                create table app_user (
                  id                 bigserial primary key,
                  username           varchar(255) not null unique,
                  -- WebAuthn requires a stable, opaque user handle (byte array).
                  -- Keep it unique and never change it.
                  user_handle        bytea not null unique,
                  created_at         timestamptz not null default now(),
                  updated_at         timestamptz not null default now(),
                  role text check (role in ('user', 'admin')) -- NULL means unauthorized.
                );
                """);
        executeStatement(db, """
                create index idx_app_user_user_handle on app_user(user_handle);
                """);
    }

    private static void credentialsTable(Connection db) throws SQLException {
        executeStatement(db, """
                create table webauthn_credential (
                  id                   bigserial primary key,
                  user_id              bigint not null references app_user(id) on delete cascade,
                
                  -- Credential ID returned by the browser; MUST be globally unique.
                  credential_id        bytea  not null unique,
                
                  -- COSE-encoded public key (what Yubico calls getPublicKeyCose()).
                  public_key_cose      bytea  not null,
                
                  -- Signature counter / signCount from authenticator; use to detect clones.
                  sign_count           bigint not null default 0,
                
                  -- Authenticator AAGUID (nullable - not all attestation flows provide it).
                  aaguid               uuid,
                
                  -- Transports the authenticator reported (usb/nfc/ble/internal).
                  transports           text[],
                
                  -- Passkey UX bits (optional; useful for account settings UI)
                  is_discoverable      boolean not null default true,  -- resident key (passkey) vs non-resident
                  backup_eligible      boolean,
                  backup_state         boolean,
                
                  -- Friendly label the user can edit (e.g., “MacBook Touch ID”, “iPhone”)
                  label                varchar(255),
                
                  -- Lifecycle flags
                  is_revoked           boolean not null default false,
                
                  -- Timestamps
                  created_at           timestamptz not null default now(),
                  last_used_at         timestamptz
                );
                """);
        executeStatement(db, """
                create index idx_webauthn_cred_user on webauthn_credential(user_id);
                """);
        executeStatement(db, """
                create index idx_webauthn_cred_label on webauthn_credential(label);
                """);
    }

    private static void challengeTable(Connection db) throws SQLException {
        executeStatement(db, """
                create type webauthn_flow as enum ('registration', 'authentication');
                """);
        executeStatement(db, """
                create table webauthn_challenge (
                    id            bigserial primary key,
                    flow          webauthn_flow not null,
                    user_id       bigint references app_user(id) on delete cascade,
                
                    -- Origin/RP checks (optional; handy for auditing multi-env dev)
                    rp_id         varchar(255) not null,
                    origin        varchar(1024) not null,
                
                    -- The challenge plus other stuff.
                    options_json jsonb,
                
                    -- Validity window
                    created_at    timestamptz not null default now(),
                    expires_at    timestamptz not null,
                    used_at       timestamptz,
                
                    -- Prevent reuse
                    is_used       boolean not null default false
                    );
                """);
        executeStatement(db, """
                create index idx_webauthn_challenge_user on webauthn_challenge(user_id);
                """);
        executeStatement(db, """
                create index idx_webauthn_challenge_expires on webauthn_challenge(expires_at);
                """);
    }
}