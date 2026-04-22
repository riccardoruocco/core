package com.dotcms.enterprise.publishing.staticpublishing;

/**
 * Represents the logical key used to look up or replace the mappings between
 * a static canonical path and the vanity URLs published on S3.
 */
public final class S3VanityAliasLookup {

    private final String endpointId;
    private final String hostId;
    private final long languageId;
    private final String canonicalPath;

    /**
     * Creates a new logical key for S3 vanity mappings.
     *
     * @param endpointId endpoint identifier for the target S3 destination.
     * @param hostId host identifier associated with the static file.
     * @param languageId language identifier associated with the static file.
     * @param canonicalPath canonical static path published on S3.
     */
    public S3VanityAliasLookup(
            final String endpointId,
            final String hostId,
            final long languageId,
            final String canonicalPath) {
        this.endpointId = endpointId;
        this.hostId = hostId;
        this.languageId = languageId;
        this.canonicalPath = canonicalPath;
    }

    /**
     * Returns the S3 endpoint identifier.
     *
     * @return endpoint associated with the mapping.
     */
    public String endpointId() {
        return endpointId;
    }

    /**
     * Returns the host identifier.
     *
     * @return host associated with the static file.
     */
    public String hostId() {
        return hostId;
    }

    /**
     * Returns the language identifier.
     *
     * @return language associated with the static file.
     */
    public long languageId() {
        return languageId;
    }

    /**
     * Returns the canonical static path.
     *
     * @return canonical path published on S3.
     */
    public String canonicalPath() {
        return canonicalPath;
    }
}
