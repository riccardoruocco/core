package com.dotcms.enterprise.publishing.staticpublishing;

import com.dotcms.business.WrapInTransaction;
import com.dotmarketing.common.db.DotConnect;
import com.dotmarketing.exception.DotDataException;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handles persistence for mappings between static canonical paths and vanity
 * URLs published as aliases on S3 endpoints.
 */
public class S3VanityAliasMapRepository {

    public static final String TABLE_NAME = "s3_vanity_alias_map";

    private static final String DELETE_BY_LOOKUP_SQL =
            "DELETE FROM s3_vanity_alias_map "
                    + " WHERE endpoint_id = ?"
                    + "   AND host_id = ?"
                    + "   AND language_id = ?"
                    + "   AND canonical_path = ?";

    private static final String FIND_BY_LOOKUP_SQL =
            "SELECT endpoint_id, vanity_url_id, host_id, language_id, canonical_path, vanity_path "
                    + "  FROM s3_vanity_alias_map "
                    + " WHERE endpoint_id = ?"
                    + "   AND host_id = ?"
                    + "   AND language_id = ?"
                    + "   AND canonical_path = ?"
                    + " ORDER BY vanity_path";

    private static final String INSERT_SQL =
            "INSERT INTO s3_vanity_alias_map "
                    + "    (endpoint_id, vanity_url_id, host_id, language_id, canonical_path, vanity_path, mod_date) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    /**
     * Replaces all mappings associated with a canonical path.
     *
     * @param lookup logical key for the canonical static file.
     * @param mappings mappings to persist after replacement.
     * @throws DotDataException when persistence fails.
     */
    @WrapInTransaction
    public void replaceMappings(
            final S3VanityAliasLookup lookup,
            final Collection<S3VanityAliasMap> mappings) throws DotDataException {

        deleteMappings(lookup);
        insertMappings(mappings);
    }

    /**
     * Retrieves all persisted mappings for a canonical static path.
     *
     * @param lookup logical key for the canonical static file.
     * @return persisted mappings for the given key.
     * @throws DotDataException when the query fails.
     */
    public List<S3VanityAliasMap> findMappings(final S3VanityAliasLookup lookup)
            throws DotDataException {

        final List<Map<String, Object>> rows = new DotConnect()
                .setSQL(FIND_BY_LOOKUP_SQL)
                .addParam(lookup.endpointId())
                .addParam(lookup.hostId())
                .addParam(lookup.languageId())
                .addParam(lookup.canonicalPath())
                .loadObjectResults();

        return rows.stream()
                .map(this::toAliasMap)
                .collect(Collectors.toList());
    }

    /**
     * Deletes all mappings associated with a canonical static path.
     *
     * @param lookup logical key for the canonical static file.
     * @throws DotDataException when deletion fails.
     */
    @WrapInTransaction
    public void deleteMappings(final S3VanityAliasLookup lookup) throws DotDataException {
        new DotConnect().executeUpdate(
                DELETE_BY_LOOKUP_SQL,
                lookup.endpointId(),
                lookup.hostId(),
                lookup.languageId(),
                lookup.canonicalPath());
    }

    /**
     * Inserts the provided mappings without affecting other records.
     *
     * @param mappings mappings to persist.
     * @throws DotDataException when insertion fails.
     */
    @WrapInTransaction
    public void insertMappings(final Collection<S3VanityAliasMap> mappings)
            throws DotDataException {

        final Date now = new Date();
        for (final S3VanityAliasMap mapping : mappings) {
            new DotConnect().executeUpdate(
                    INSERT_SQL,
                    mapping.endpointId(),
                    mapping.vanityUrlId(),
                    mapping.hostId(),
                    mapping.languageId(),
                    mapping.canonicalPath(),
                    mapping.vanityPath(),
                    now);
        }
    }

    /**
     * Converts a SQL row into the domain object used by the publisher.
     *
     * @param row row read from the database.
     * @return typed mapping corresponding to the row.
     */
    private S3VanityAliasMap toAliasMap(final Map<String, Object> row) {
        return new S3VanityAliasMap(
                String.valueOf(row.get("endpoint_id")),
                row.get("vanity_url_id") == null ? null : String.valueOf(row.get("vanity_url_id")),
                String.valueOf(row.get("host_id")),
                ((Number) row.get("language_id")).longValue(),
                String.valueOf(row.get("canonical_path")),
                String.valueOf(row.get("vanity_path")));
    }
}
