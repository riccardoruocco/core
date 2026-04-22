package com.dotcms.enterprise.publishing.staticpublishing;

/**
 * Describes the persisted relationship between a canonical static file and a
 * single vanity URL published as an alias on the same S3 endpoint.
 */
public final class S3VanityAliasMap {

    private final String endpointId;
    private final String vanityUrlId;
    private final String hostId;
    private final long languageId;
    private final String canonicalPath;
    private final String vanityPath;

    /**
     * Creates a new mapping between a canonical path and a static vanity path.
     *
     * @param endpointId endpoint identifier for the target S3 destination.
     * @param vanityUrlId identifier of the Vanity URL that produced the alias.
     * @param hostId host identifier associated with the static file.
     * @param languageId language identifier associated with the static file.
     * @param canonicalPath canonical static path published on S3.
     * @param vanityPath vanity static path published on S3.
     */
    public S3VanityAliasMap(
            final String endpointId,
            final String vanityUrlId,
            final String hostId,
            final long languageId,
            final String canonicalPath,
            final String vanityPath) {
        this.endpointId = endpointId;
        this.vanityUrlId = vanityUrlId;
        this.hostId = hostId;
        this.languageId = languageId;
        this.canonicalPath = canonicalPath;
        this.vanityPath = vanityPath;
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
     * Returns the source Vanity URL identifier.
     *
     * @return Vanity URL identifier.
     */
    public String vanityUrlId() {
        return vanityUrlId;
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

    /**
     * Returns the static vanity path.
     *
     * @return vanity path published on S3.
     */
    public String vanityPath() {
        return vanityPath;
    }
}
