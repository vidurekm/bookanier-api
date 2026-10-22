CREATE TABLE organization_invitations (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id bigint NOT NULL REFERENCES organizations(id),
    member_id bigint,
    email text NOT NULL,
    display_name text NOT NULL,
    role text NOT NULL CHECK (role IN ('owner', 'admin', 'worker')),
    can_manage_bookings boolean NOT NULL DEFAULT false,
    can_approve_own_absence boolean NOT NULL DEFAULT false,
    token_hash char(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, member_id)
        REFERENCES organization_members (organization_id, id)
);

CREATE UNIQUE INDEX organization_invitations_one_pending_email
    ON organization_invitations (organization_id, lower(email))
    WHERE consumed_at IS NULL;

CREATE UNIQUE INDEX organization_invitations_one_pending_member
    ON organization_invitations (organization_id, member_id)
    WHERE consumed_at IS NULL AND member_id IS NOT NULL;
