package com.dotcms.enterprise.publishing.staticpublishing;

import com.dotcms.vanityurl.model.CachedVanityUrl;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class S3VanityAliasSupportTest {

    /**
     * Verifies that a compatible literal vanity path is converted into a
     * normalized S3 mapping.
     */
    @Test
    public void should_create_alias_maps_for_supported_literal_paths() {
        final S3VanityAliasLookup lookup =
                new S3VanityAliasLookup("endpoint-1", "host-1", 1L, "/products/widget");

        final CachedVanityUrl vanity = new CachedVanityUrl(
                "vanity-1",
                "/promo/widget/",
                1L,
                "host-1",
                "/products/widget",
                200,
                0);

        final List<S3VanityAliasMap> mappings = S3VanityAliasSupport.toAliasMaps(lookup, List.of(vanity));

        Assert.assertEquals(1, mappings.size());
        Assert.assertEquals("/promo/widget", mappings.get(0).vanityPath());
    }

    /**
     * Verifies that root paths, regex paths, and canonical duplicates are
     * rejected.
     */
    @Test
    public void should_filter_regex_root_and_duplicate_paths() {
        final S3VanityAliasLookup lookup =
                new S3VanityAliasLookup("endpoint-1", "host-1", 1L, "/products/widget");

        final CachedVanityUrl sameAsCanonical = new CachedVanityUrl(
                "vanity-1",
                "/products/widget",
                1L,
                "host-1",
                "/products/widget",
                200,
                0);

        final CachedVanityUrl regexVanity = new CachedVanityUrl(
                "vanity-2",
                "/promo/(.*)",
                1L,
                "host-1",
                "/products/widget",
                200,
                0);

        final CachedVanityUrl rootVanity = new CachedVanityUrl(
                "vanity-3",
                "/",
                1L,
                "host-1",
                "/products/widget",
                200,
                0);

        final List<S3VanityAliasMap> mappings =
                S3VanityAliasSupport.toAliasMaps(lookup, List.of(sameAsCanonical, regexVanity, rootVanity));

        Assert.assertTrue(mappings.isEmpty());
    }

    /**
     * Verifies that literal vanity paths containing dots are accepted.
     */
    @Test
    public void should_accept_literal_paths_with_dots() {
        Assert.assertTrue(S3VanityAliasSupport.isSupportedVanityPath("/promo/v1.0"));
    }

    /**
     * Verifies that escaped regex metacharacters are normalized as literal
     * vanity paths.
     */
    @Test
    public void should_normalize_escaped_literal_paths() {
        Assert.assertEquals("/promo/v1.0",
                S3VanityAliasSupport.normalizeVanityPath("/promo/v1\\.0").orElse(null));
    }
}
