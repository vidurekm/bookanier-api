ALTER TABLE organization_members
    ADD COLUMN can_manage_own_bookings boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT organization_members_booking_permissions_exclusive
        CHECK (NOT (can_manage_bookings AND can_manage_own_bookings));

ALTER TABLE organization_invitations
    ADD COLUMN can_manage_own_bookings boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT organization_invitations_booking_permissions_exclusive
        CHECK (NOT (can_manage_bookings AND can_manage_own_bookings));
