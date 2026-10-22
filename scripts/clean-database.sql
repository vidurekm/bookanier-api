-- DBeaver / PostgreSQL
-- Before running, replace this value with the exact database name shown by DBeaver.
DO $$
DECLARE
    expected_database constant text := 'REPLACE_WITH_DATABASE_NAME';
    tables_to_clear text;
BEGIN
    IF expected_database = 'REPLACE_WITH_DATABASE_NAME' THEN
        RAISE EXCEPTION 'Set expected_database to the exact database name before running this script.';
    END IF;

    IF current_database() <> expected_database THEN
        RAISE EXCEPTION 'Connected to database "%", expected "%". No data was deleted.',
            current_database(), expected_database;
    END IF;

    SELECT string_agg(format('%I.%I', n.nspname, c.relname), ', ' ORDER BY n.nspname, c.relname)
    INTO tables_to_clear
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relkind IN ('r', 'p')
      AND NOT c.relispartition
      AND n.nspname <> 'information_schema'
      AND n.nspname NOT LIKE 'pg\_%' ESCAPE '\'
      AND c.relname NOT IN ('flyway_schema_history', 'business_templates');

    IF tables_to_clear IS NOT NULL THEN
        EXECUTE 'TRUNCATE TABLE ' || tables_to_clear || ' RESTART IDENTITY CASCADE';
    END IF;
END
$$;
