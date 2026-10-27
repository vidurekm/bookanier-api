package online.bookanear.api.service;

import online.bookanear.api.dto.MediaDto;
import online.bookanear.api.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "app.media", name = "enabled", havingValue = "true")
public class MediaService {
    private final NamedParameterJdbcTemplate db;
    private final MediaStorageService storage;
    private final MediaImageProcessor processor;
    private final long maxBytes;

    public MediaService(NamedParameterJdbcTemplate db, MediaStorageService storage,
                        MediaImageProcessor processor, @Value("${app.media.max-file-bytes:10485760}") long maxBytes) {
        this.db = db;
        this.storage = storage;
        this.processor = processor;
        this.maxBytes = maxBytes;
    }

    public enum Kind {
        ORGANIZATION("organization", "organization_images", "organization_id", "organizations"),
        ORGANIZATION_LOGO("organization-logo", "organization_logos", "organization_id", "organizations"),
        LOCATION("location", "location_images", "location_id", "locations"),
        SERVICE("service", "service_images", "service_id", "services"),
        RESOURCE("resource", "resource_images", "resource_id", "resources"),
        MEMBER("member", "member_images", "member_id", "organization_members"),
        RESOURCE_TYPE("resource-type", "resource_type_images", "resource_type_id", "resource_types");

        public final String path, table, column, entityTable;
        Kind(String path, String table, String column, String entityTable) {
            this.path = path;
            this.table = table;
            this.column = column;
            this.entityTable = entityTable;
        }
        public static Kind parse(String path) {
            for (Kind k : values()) if (k.path.equals(path)) return k;
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported image target");
        }
    }

    public record StoredMedia(long id, String contentType, byte[] bytes) {}

    public MediaDto.ImageInfo upload(long org, MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Image is empty or too large");
        }
        byte[] input;
        try { input = file.getBytes(); }
        catch (IOException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot read uploaded image"); }
        if (input.length > maxBytes) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Image is too large");
        var image = processor.process(input);
        if (image.bytes().length > maxBytes) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Processed image is too large");
        String key = "org/" + org + "/images/" + UUID.randomUUID() +
                (image.contentType().equals("image/png") ? ".png" : ".jpg");
        storage.put(key, image.bytes(), image.contentType());
        try {
            Long id = db.queryForObject("""
                INSERT INTO media_assets (organization_id,storage_key,content_type,file_size,width,height)
                VALUES (:org,:key,:type,:size,:width,:height) RETURNING id
                """, Map.of("org", org, "key", key, "type", image.contentType(),
                    "size", image.bytes().length, "width", image.width(), "height", image.height()), Long.class);
            if (id == null) throw new IllegalStateException("Media insert returned no ID");
            return new MediaDto.ImageInfo(id, publicUrl(id), image.contentType(), image.width(), image.height(), 0, false);
        } catch (RuntimeException e) {
            try { storage.delete(key); } catch (RuntimeException cleanupFailure) { e.addSuppressed(cleanupFailure); }
            throw e;
        }
    }

    @Transactional
    public MediaDto.ImageInfo attach(long org, Kind kind, long targetId, MediaDto.AttachImage request) {
        lockTarget(org, kind, targetId);
        long mediaId = request.mediaId();
        var asset = asset(org, mediaId);
        Map<String, Object> args = Map.of("org", org, "target", targetId, "media", mediaId,
                "sort", request.sortOrder(), "primary", request.primary());
        if (kind == Kind.ORGANIZATION_LOGO) {
            db.update("DELETE FROM organization_logos WHERE organization_id=:org", args);
            db.update("INSERT INTO organization_logos (organization_id,media_id) VALUES (:org,:media)", args);
            return new MediaDto.ImageInfo(mediaId, publicUrl(mediaId), (String) asset.get("content_type"),
                    ((Number) asset.get("width")).intValue(), ((Number) asset.get("height")).intValue(), 0, true);
        }
        if (request.primary()) {
            db.update("UPDATE " + kind.table + " SET is_primary=false WHERE organization_id=:org AND " +
                    kind.column + "=:target AND is_primary=true", args);
        }
        // Identifiers are exclusively taken from the Kind enum, never user input.
        db.update("INSERT INTO " + kind.table + " (organization_id," +
                (kind == Kind.ORGANIZATION ? "" : kind.column + ",") +
                "media_id,sort_order,is_primary) VALUES (:org," +
                (kind == Kind.ORGANIZATION ? "" : ":target,") +
                ":media,:sort,:primary) ON CONFLICT (" +
                (kind == Kind.ORGANIZATION ? "organization_id,media_id" : "organization_id," + kind.column + ",media_id") +
                ") DO UPDATE SET sort_order=EXCLUDED.sort_order,is_primary=EXCLUDED.is_primary", args);
        return new MediaDto.ImageInfo(mediaId, publicUrl(mediaId), (String) asset.get("content_type"),
                ((Number) asset.get("width")).intValue(), ((Number) asset.get("height")).intValue(),
                request.sortOrder(), request.primary());
    }

    @Transactional
    public void detach(long org, Kind kind, long targetId, long mediaId) {
        lockTarget(org, kind, targetId);
        Map<String, Object> args = Map.of("org", org, "target", targetId, "media", mediaId);
        if (kind == Kind.ORGANIZATION_LOGO) {
            int removed = db.update("DELETE FROM organization_logos WHERE organization_id=:org AND media_id=:media", args);
            if (removed == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Image assignment not found");
            return;
        }
        int n = db.update("DELETE FROM " + kind.table + " WHERE organization_id=:org AND " +
                kind.column + "=:target AND media_id=:media", args);
        if (n == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Image assignment not found");
        if (kind == Kind.ORGANIZATION) {
            db.update("""
                    UPDATE organization_images SET is_primary=true
                    WHERE organization_id=:org AND media_id=(
                        SELECT media_id FROM organization_images
                        WHERE organization_id=:org ORDER BY sort_order,media_id LIMIT 1
                    ) AND NOT EXISTS (
                        SELECT 1 FROM organization_images
                        WHERE organization_id=:org AND is_primary=true
                    )
                    """, args);
        }
    }

    public List<MediaDto.ImageInfo> list(long org, Kind kind, long targetId, boolean publicOnly) {
        if (publicOnly) {
            Integer active = db.queryForObject("SELECT count(*) FROM organizations WHERE id=:org AND registration_status='active'", Map.of("org", org), Integer.class);
            if (active == null || active == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Organization not found");
        }
        ensureTarget(org, kind, targetId);
        if (kind == Kind.ORGANIZATION_LOGO) {
            return db.query("""
                    SELECT a.id,a.content_type,a.width,a.height
                    FROM organization_logos i
                    JOIN media_assets a ON a.organization_id=i.organization_id AND a.id=i.media_id
                    WHERE i.organization_id=:org
                    """, Map.of("org", org), (rs, row) ->
                    new MediaDto.ImageInfo(rs.getLong("id"), publicUrl(rs.getLong("id")),
                            rs.getString("content_type"), rs.getInt("width"), rs.getInt("height"), 0, true));
        }
        String sql = "SELECT a.id,a.content_type,a.width,a.height,i.sort_order,i.is_primary FROM " + kind.table +
                " i JOIN media_assets a ON a.organization_id=i.organization_id AND a.id=i.media_id " +
                "WHERE i.organization_id=:org AND i." + kind.column + "=:target ORDER BY i.is_primary DESC,i.sort_order,a.id";
        return db.query(sql, Map.of("org", org, "target", targetId), (rs, row) ->
                new MediaDto.ImageInfo(rs.getLong("id"), publicUrl(rs.getLong("id")),
                        rs.getString("content_type"), rs.getInt("width"), rs.getInt("height"),
                        rs.getInt("sort_order"), rs.getBoolean("is_primary")));
    }

    public StoredMedia read(long id, Long orgOrNull) {
        String query = "SELECT a.organization_id,a.storage_key,a.content_type FROM media_assets a WHERE a.id=:id" +
                (orgOrNull == null ? " AND EXISTS (SELECT 1 FROM organizations o WHERE o.id=a.organization_id AND o.registration_status='active')" +
                        publicAssignmentExists() : " AND a.organization_id=:org");
        Map<String, ?> params = orgOrNull == null ? Map.of("id", id) : Map.of("id", id, "org", orgOrNull);
        var matches = db.queryForList(query, params);
        if (matches.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Image not found");
        var row = matches.getFirst();
        return new StoredMedia(id, (String) row.get("content_type"), storage.get((String) row.get("storage_key")));
    }

    /** Public delivery requires at least one explicit assignment. Unattached uploads remain private. */
    private String publicAssignmentExists() {
        StringBuilder sql = new StringBuilder(" AND (");
        boolean first = true;
        for (Kind k : Kind.values()) {
            if (!first) sql.append(" OR ");
            first = false;
            sql.append("EXISTS (SELECT 1 FROM ").append(k.table)
                    .append(" i WHERE i.organization_id=a.organization_id AND i.media_id=a.id)");
        }
        return sql.append(')').toString();
    }

    /** Delete only unattached assets; on storage failure keep the database record for retry. */
    public void delete(long org, long mediaId) {
        var a = asset(org, mediaId);
        for (Kind k : Kind.values()) {
            Integer refs = db.queryForObject("SELECT count(*) FROM " + k.table +
                    " WHERE organization_id=:org AND media_id=:media", Map.of("org", org, "media", mediaId), Integer.class);
            if (refs != null && refs > 0) throw new ApiException(HttpStatus.CONFLICT, "Detach image from all objects first");
        }

        storage.delete((String) a.get("storage_key"));
        db.update("DELETE FROM media_assets WHERE organization_id=:org AND id=:media",
                Map.of("org", org, "media", mediaId));
    }

    public void deleteOrganizationAssets(long org) {
        var assets = db.queryForList("SELECT storage_key FROM media_assets WHERE organization_id=:org",
                Map.of("org", org));
        for (var asset : assets) {
            storage.delete((String) asset.get("storage_key"));
        }
    }

    private Map<String, Object> asset(long org, long id) {
        var rows = db.queryForList("SELECT storage_key,content_type,width,height FROM media_assets WHERE organization_id=:org AND id=:id",
                Map.of("org", org, "id", id));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Image not found");
        return rows.getFirst();
    }

    private void ensureTarget(long org, Kind kind, long id) {
        if ((kind == Kind.ORGANIZATION || kind == Kind.ORGANIZATION_LOGO) && id != org) throw new ApiException(HttpStatus.NOT_FOUND, "Organization not found");
        Integer found = db.queryForObject("SELECT count(*) FROM " + kind.entityTable +
                " WHERE " +                 (kind == Kind.ORGANIZATION || kind == Kind.ORGANIZATION_LOGO ? "id=:target" : "organization_id=:org AND id=:target"),
                Map.of("org", org, "target", id), Integer.class);
        if (found == null || found == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Image target not found");
    }

    private void lockTarget(long org, Kind kind, long id) {
        ensureTarget(org, kind, id);
        db.queryForList("SELECT id FROM " + kind.entityTable + " WHERE " +
                (kind == Kind.ORGANIZATION || kind == Kind.ORGANIZATION_LOGO ? "id=:target" : "organization_id=:org AND id=:target") + " FOR UPDATE",
                Map.of("org", org, "target", id));
    }

    private String publicUrl(long mediaId) { return "/api/v1/public/media/" + mediaId; }
}
