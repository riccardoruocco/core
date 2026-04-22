package com.dotcms.enterprise.publishing.staticpublishing;

import com.dotcms.vanityurl.model.CachedVanityUrl;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Contains the pure logic required to translate dotCMS Vanity URLs into
 * static aliases compatible with S3 publishing.
 */
public final class S3VanityAliasSupport {

    private S3VanityAliasSupport() {
    }

    /**
     * Converts a collection of Vanity URLs into persistable S3 mappings.
     *
     * @param lookup logical key for the canonical static file.
     * @param vanityUrls Vanity URL candidates to evaluate.
     * @return valid mappings, deduplicated by vanity path.
     */
    public static List<S3VanityAliasMap> toAliasMaps(
            final S3VanityAliasLookup lookup,
            final Collection<CachedVanityUrl> vanityUrls) {

        final Map<String, S3VanityAliasMap> aliasesByPath = new LinkedHashMap<>();

        vanityUrls.stream()
                .filter(Objects::nonNull)
                .map(vanityUrl -> toAliasMap(lookup, vanityUrl))
                .flatMap(Optional::stream)
                .forEach(alias -> aliasesByPath.putIfAbsent(alias.vanityPath(), alias));

        return List.copyOf(aliasesByPath.values());
    }

    /**
     * Translates a single Vanity URL into a persistable mapping when supported.
     *
     * @param lookup logical key for the canonical static file.
     * @param vanityUrl Vanity URL to evaluate.
     * @return optional mapping when the Vanity URL is compatible with static S3 publishing.
     */
    public static Optional<S3VanityAliasMap> toAliasMap(
            final S3VanityAliasLookup lookup,
            final CachedVanityUrl vanityUrl) {

        return normalizeVanityPath(vanityUrl.url)
                .filter(vanityPath -> !lookup.canonicalPath().equals(vanityPath))
                .map(vanityPath -> new S3VanityAliasMap(
                        lookup.endpointId(),
                        vanityUrl.vanityUrlId,
                        lookup.hostId(),
                        lookup.languageId(),
                        lookup.canonicalPath(),
                        vanityPath));
    }

    /**
     * Verifies whether the vanity path can be represented as a static S3 key
     * without introducing ambiguity or regex semantics.
     *
     * @param vanityPath vanity path to validate.
     * @return {@code true} when the path can be published statically.
     */
    public static boolean isSupportedVanityPath(final String vanityPath) {
        return normalizeVanityPath(vanityPath).isPresent();
    }

    /**
     * Normalizes a vanity path into a format compatible with static
     * publishing, rejecting unsupported cases.
     *
     * @param vanityPath raw vanity path.
     * @return normalized path when supported.
     */
    public static Optional<String> normalizeVanityPath(final String vanityPath) {
        if (vanityPath == null || vanityPath.isBlank()) {
            return Optional.empty();
        }

        final String trimmedPath = vanityPath.trim();
        if (!trimmedPath.startsWith("/") || "/".equals(trimmedPath)) {
            return Optional.empty();
        }

        if (trimmedPath.contains("?") || trimmedPath.contains("#")) {
            return Optional.empty();
        }

        final StringBuilder normalizedPath = new StringBuilder(trimmedPath.length());
        for (int index = 0; index < trimmedPath.length(); index++) {
            final char currentChar = trimmedPath.charAt(index);

            if (currentChar == '\\') {
                if (index + 1 >= trimmedPath.length()) {
                    return Optional.empty();
                }

                final char escapedChar = trimmedPath.charAt(++index);
                if (!isEscapableRegexChar(escapedChar)) {
                    return Optional.empty();
                }

                normalizedPath.append(escapedChar);
                continue;
            }

            if (isRegexMetaChar(currentChar)) {
                return Optional.empty();
            }

            normalizedPath.append(currentChar);
        }

        final String normalizedLiteralPath = normalizedPath.length() > 1
                && normalizedPath.charAt(normalizedPath.length() - 1) == '/'
                ? normalizedPath.substring(0, normalizedPath.length() - 1)
                : normalizedPath.toString();

        return normalizedLiteralPath.isBlank() ? Optional.empty() : Optional.of(normalizedLiteralPath);
    }

    private static boolean isEscapableRegexChar(final char character) {
        return isRegexMetaChar(character) || character == '\\' || character == '.';
    }

    private static boolean isRegexMetaChar(final char character) {
        switch (character) {
            case '[':
            case ']':
            case '(':
            case ')':
            case '{':
            case '}':
            case '*':
            case '+':
            case '?':
            case '|':
            case '^':
            case '$':
                return true;
            default:
                return false;
        }
    }
}
