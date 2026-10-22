package online.bookanear.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Safely adopt databases originally created by executing V1..V6 SQL manually.
 * We NEVER re-apply CREATE TABLE migrations to a populated database.
 * Schema inspection intentionally fails closed if an old installation is incomplete.
 * V3 alone may be missing: V7 repairs its columns and trigger.
 */
@Configuration
public class LegacyFlywayAdoptionConfig {
    private static final Logger log = LoggerFactory.getLogger(LegacyFlywayAdoptionConfig.class);
    private static final List<String> REQUIRED_TABLES = List.of(
            "organizations", "users", "organization_members", "locations",
            "member_locations", "customers", "services", "service_versions",
            "worker_services", "location_services", "schedule_rules", "availability_overrides",
            "resources", "service_resource_requirements", "booking_policies", "reservations",
            "reservation_items", "reservation_resources", "reservation_tokens", "reservation_events",
            "notification_outbox", "payments", "payment_refunds", "waitlist_entries",
            "group_sessions", "group_session_attendees", "service_price_overrides", "organization_registration_tokens",
            "resource_types", "reservation_item_resource_demands", "schedule_date_overrides", "schedule_date_override_intervals",
            "media_assets", "organization_images", "organization_logos", "location_images", "service_images",
            "resource_images", "member_images", "resource_type_images", "business_templates",
            "organization_booking_config", "event_series", "event_occurrences", "event_registrations"
    );

    @Bean
    FlywayMigrationStrategy safeExistingDatabaseMigration() {
        return flyway -> {
            // Use a plain JdbcTemplate only after Flyway starts migration.
            // Injecting Spring's JdbcTemplate into this @Bean creates a cycle:
            // FlywayInitializer -> FlywayMigrationStrategy -> JdbcTemplate -> FlywayInitializer.
            JdbcTemplate db = new JdbcTemplate(flyway.getConfiguration().getDataSource());
            if (!exists(db, "flyway_schema_history") && exists(db, "organizations")) {
                var missing = REQUIRED_TABLES.stream().filter(table -> !exists(db, table)).toList();
                if (!missing.isEmpty()) {
                    throw new IllegalStateException("Existing database contains data but lacks pre-Flyway tables: "
                            + missing + ". Do not baseline it. Back up your database, apply the missing "
                            + "V1-V6 migrations in order, then restart.");
                }
                for (String column : List.of("resource_type_id", "capacity")) {
                    // Existing V2 columns are necessary before the V7 repair is safe.
                    if (!hasColumn(db, "resources", column)) {
                        throw new IllegalStateException("Pre-Flyway database is missing resources." + column
                                + ". Complete V2 before enabling automatic migrations.");
                    }
                }
                if (!hasColumn(db, "service_resource_requirements", "resource_type_id")) {
                    throw new IllegalStateException("Pre-Flyway database has incomplete V2: "
                            + "service_resource_requirements.resource_type_id is absent.");
                }
                log.warn("[FLYWAY] Legacy populated database detected; verifying completed V1,V2,V4,V5,V6 "
                        + "and baselining at V6. V7 will reconcile optional missing V3 columns.");
                flyway.baseline(); // baselineVersion=6; preserves ALL existing data
            }
            flyway.migrate();
        };
    }

    private static boolean exists(JdbcTemplate db, String table) {
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT to_regclass(current_schema() || '.' || ?) IS NOT NULL", Boolean.class, table));
    }

    private static boolean hasColumn(JdbcTemplate db, String table, String column) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name=? AND column_name=?)
                """, Boolean.class, table, column));
    }
}
