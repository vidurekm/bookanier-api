package online.bookanear.api;

import online.bookanear.api.dto.MediaDto;
import online.bookanear.api.service.MediaService;
import online.bookanear.api.service.MediaStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@Testcontainers
class MediaServiceIT extends PostgresIntegrationTest {
    @DynamicPropertySource
    static void enableMedia(DynamicPropertyRegistry properties) {
        properties.add("app.media.enabled", () -> "true");
        properties.add("app.media.endpoint", () -> "http://localhost:9000");
        properties.add("app.media.region", () -> "us-east-1");
        properties.add("app.media.access-key", () -> "test");
        properties.add("app.media.secret-key", () -> "test");
        properties.add("app.media.bucket", () -> "test");
    }

    @MockitoBean
    MediaStorageService storage;

    @Autowired
    MediaService media;

    @Test
    void detachingAnyImageKindDeletesItsUnreferencedAssetFromDatabaseAndStorage() {
        long org = organization();
        long location = id("INSERT INTO locations(organization_id,name) VALUES(:org,'Location') RETURNING id", org);
        long member = id("INSERT INTO organization_members(organization_id,display_name) VALUES(:org,'Member') RETURNING id", org);
        long service = id("INSERT INTO services(organization_id,name) VALUES(:org,'Service') RETURNING id", org);
        long resourceType = id("INSERT INTO resource_types(organization_id,name) VALUES(:org,'Resource type') RETURNING id", org);
        long resource = id("INSERT INTO resources(organization_id,location_id,name) VALUES(:org,:location,'Resource') RETURNING id",
                Map.of("org", org, "location", location));

        for (var kind : MediaService.Kind.values()) {
            if (kind == MediaService.Kind.ORGANIZATION_LOGO) continue;
            long target = switch (kind) {
                case ORGANIZATION -> org;
                case LOCATION -> location;
                case SERVICE -> service;
                case RESOURCE -> resource;
                case MEMBER -> member;
                case RESOURCE_TYPE -> resourceType;
                case ORGANIZATION_LOGO -> throw new IllegalStateException("Logo is tested separately");
            };
            String storageKey = "test/" + kind.path;
            long mediaId = mediaAsset(org, storageKey);
            if (kind == MediaService.Kind.ORGANIZATION) {
                db.update("INSERT INTO organization_images(organization_id,media_id) VALUES(:org,:media)",
                        Map.of("org", org, "media", mediaId));
            } else {
                db.update("INSERT INTO " + kind.table + "(organization_id," + kind.column
                                + ",media_id,sort_order,is_primary) VALUES(:org,:target,:media,0,false)",
                        Map.of("org", org, "target", target, "media", mediaId));
            }

            media.detach(org, kind, target, mediaId);

            assertThat(db.queryForObject("SELECT count(*) FROM media_assets WHERE id=:id",
                    Map.of("id", mediaId), Long.class)).isZero();
            verify(storage).delete(storageKey);
        }
    }

    @Test
    void detachedAssetIsKeptWhileAnyOtherImageAssignmentStillReferencesIt() {
        long org = organization();
        long location = id("INSERT INTO locations(organization_id,name) VALUES(:org,'Location') RETURNING id", org);
        long service = id("INSERT INTO services(organization_id,name) VALUES(:org,'Service') RETURNING id", org);
        long mediaId = mediaAsset(org, "test/shared");
        db.update("INSERT INTO location_images(organization_id,location_id,media_id) VALUES(:org,:target,:media)",
                Map.of("org", org, "target", location, "media", mediaId));
        db.update("INSERT INTO service_images(organization_id,service_id,media_id) VALUES(:org,:target,:media)",
                Map.of("org", org, "target", service, "media", mediaId));

        media.detach(org, MediaService.Kind.LOCATION, location, mediaId);

        assertThat(db.queryForObject("SELECT count(*) FROM media_assets WHERE id=:id",
                Map.of("id", mediaId), Long.class)).isEqualTo(1);
        verify(storage, never()).delete("test/shared");

        media.detach(org, MediaService.Kind.SERVICE, service, mediaId);

        assertThat(db.queryForObject("SELECT count(*) FROM media_assets WHERE id=:id",
                Map.of("id", mediaId), Long.class)).isZero();
        verify(storage).delete("test/shared");
    }

    @Test
    void replacingOrganizationLogoDeletesTheOldAssetWhenItHasNoOtherAssignments() {
        long org = organization();
        long oldMediaId = mediaAsset(org, "test/old-logo");
        long newMediaId = mediaAsset(org, "test/new-logo");
        db.update("INSERT INTO organization_logos(organization_id,media_id) VALUES(:org,:media)",
                Map.of("org", org, "media", oldMediaId));

        media.attach(org, MediaService.Kind.ORGANIZATION_LOGO, org,
                new MediaDto.AttachImage(newMediaId, 0, true));

        assertThat(db.queryForObject("SELECT count(*) FROM media_assets WHERE id=:id",
                Map.of("id", oldMediaId), Long.class)).isZero();
        assertThat(db.queryForObject("SELECT media_id FROM organization_logos WHERE organization_id=:org",
                Map.of("org", org), Long.class)).isEqualTo(newMediaId);
        verify(storage).delete("test/old-logo");
    }

    private long organization() {
        return id("INSERT INTO organizations(name) VALUES('Media test') RETURNING id", Map.of());
    }

    private long mediaAsset(long org, String storageKey) {
        return id("""
                INSERT INTO media_assets(organization_id,storage_key,content_type,file_size,width,height)
                VALUES(:org,:key,'image/png',1,1,1) RETURNING id
                """, Map.of("org", org, "key", storageKey));
    }

    private long id(String sql, long org) {
        return id(sql, Map.of("org", org));
    }

    private long id(String sql, Map<String, ?> args) {
        return db.queryForObject(sql, args, Long.class);
    }
}
