package com.dotmarketing.startup.runonce;

import com.dotcms.enterprise.publishing.staticpublishing.S3VanityAliasMapRepository;
import com.dotmarketing.common.db.DotDatabaseMetaData;
import com.dotmarketing.db.DbConnectionFactory;
import com.dotmarketing.startup.AbstractJDBCStartupTask;
import com.dotmarketing.util.Logger;
import java.sql.SQLException;

/**
 * Creates the table that persists mappings between static canonical paths
 * published on S3 and their vanity URLs materialized as aliases.
 */
public class Task260422CreateS3VanityAliasMapTable extends AbstractJDBCStartupTask {

    private static final String POSTGRES_SCRIPT =
            "CREATE TABLE IF NOT EXISTS s3_vanity_alias_map ("
                    + " endpoint_id varchar(36) not null,"
                    + " vanity_url_id varchar(36),"
                    + " host_id varchar(36) not null,"
                    + " language_id bigint not null,"
                    + " canonical_path varchar(1024) not null,"
                    + " vanity_path varchar(1024) not null,"
                    + " mod_date timestamp not null,"
                    + " CONSTRAINT pk_s3_vanity_alias_map PRIMARY KEY"
                    + " (endpoint_id, host_id, language_id, canonical_path, vanity_path)"
                    + ");"
                    + "CREATE INDEX IF NOT EXISTS idx_s3_vanity_alias_map_lkp"
                    + " ON s3_vanity_alias_map (endpoint_id, host_id, language_id, canonical_path);";

    private static final String MYSQL_SCRIPT =
            "CREATE TABLE IF NOT EXISTS s3_vanity_alias_map ("
                    + " endpoint_id varchar(36) not null,"
                    + " vanity_url_id varchar(36),"
                    + " host_id varchar(36) not null,"
                    + " language_id bigint not null,"
                    + " canonical_path varchar(1024) not null,"
                    + " vanity_path varchar(1024) not null,"
                    + " mod_date timestamp not null,"
                    + " CONSTRAINT pk_s3_vanity_alias_map PRIMARY KEY"
                    + " (endpoint_id, host_id, language_id, canonical_path(255), vanity_path(255))"
                    + ");"
                    + "CREATE INDEX idx_s3_vanity_alias_map_lkp"
                    + " ON s3_vanity_alias_map (endpoint_id, host_id, language_id, canonical_path(255));";

    private static final String ORACLE_SCRIPT =
            "CREATE TABLE s3_vanity_alias_map ("
                    + " endpoint_id varchar2(36 char) not null,"
                    + " vanity_url_id varchar2(36 char),"
                    + " host_id varchar2(36 char) not null,"
                    + " language_id number(19,0) not null,"
                    + " canonical_path varchar2(1024 char) not null,"
                    + " vanity_path varchar2(1024 char) not null,"
                    + " mod_date timestamp not null,"
                    + " CONSTRAINT pk_s3_vanity_alias_map PRIMARY KEY"
                    + " (endpoint_id, host_id, language_id, canonical_path, vanity_path)"
                    + ");"
                    + "CREATE INDEX idx_s3_vanity_alias_map_lkp"
                    + " ON s3_vanity_alias_map (endpoint_id, host_id, language_id, canonical_path);";

    private static final String MSSQL_SCRIPT =
            "CREATE TABLE s3_vanity_alias_map ("
                    + " endpoint_id nvarchar(36) not null,"
                    + " vanity_url_id nvarchar(36),"
                    + " host_id nvarchar(36) not null,"
                    + " language_id bigint not null,"
                    + " canonical_path nvarchar(1024) not null,"
                    + " vanity_path nvarchar(1024) not null,"
                    + " mod_date datetime2 not null,"
                    + " CONSTRAINT pk_s3_vanity_alias_map PRIMARY KEY"
                    + " (endpoint_id, host_id, language_id, canonical_path, vanity_path)"
                    + ");"
                    + "CREATE INDEX idx_s3_vanity_alias_map_lkp"
                    + " ON s3_vanity_alias_map (endpoint_id, host_id, language_id, canonical_path);";

    /**
     * Runs the task only when the table does not exist yet.
     *
     * @return {@code true} when the table must be created.
     */
    @Override
    public boolean forceRun() {
        try {
            return !new DotDatabaseMetaData().tableExists(
                    DbConnectionFactory.getConnection(),
                    S3VanityAliasMapRepository.TABLE_NAME);
        } catch (final SQLException e) {
            Logger.error(this, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Returns the PostgreSQL script used to create the table and lookup index.
     *
     * @return PostgreSQL SQL script.
     */
    @Override
    public String getPostgresScript() {
        return POSTGRES_SCRIPT;
    }

    /**
     * Returns the MySQL script used to create the table and lookup index.
     *
     * @return MySQL SQL script.
     */
    @Override
    public String getMySQLScript() {
        return MYSQL_SCRIPT;
    }

    /**
     * Returns the Oracle script used to create the table and lookup index.
     *
     * @return Oracle SQL script.
     */
    @Override
    public String getOracleScript() {
        return ORACLE_SCRIPT;
    }

    /**
     * Returns the Microsoft SQL Server script used to create the table and
     * lookup index.
     *
     * @return MSSQL script.
     */
    @Override
    public String getMSSQLScript() {
        return MSSQL_SCRIPT;
    }
}
