package com.abnamro.assignment.config;

import java.util.Locale;
import java.util.Optional;

/**
 * Single source of truth for everything the suite needs to know about its environment.
 *
 * <p>Every setting can be supplied either as a JVM system property
 * ({@code -Dgitlab.token=...}) or as the equivalent environment variable
 * ({@code GITLAB_TOKEN}). System properties win, which makes local overrides easy while
 * CI keeps using secrets exposed as environment variables.
 */
public final class TestConfig {

    /** Root of the GitLab REST API. Points at gitlab.com unless a self-managed instance is configured. */
    public static final String BASE_URL_KEY = "gitlab.base.url";
    /** OAuth2 / personal access token with the {@code api} scope. */
    public static final String TOKEN_KEY = "gitlab.token";
    /** Optional: run against an existing project instead of creating a throw-away one. */
    public static final String PROJECT_ID_KEY = "gitlab.project.id";
    /** Optional: keep the throw-away project after the run (useful when debugging a failure). */
    public static final String KEEP_PROJECT_KEY = "gitlab.project.keep";
    /** Optional: log every request and response, not just the ones that fail. */
    public static final String LOG_ALL_KEY = "gitlab.log.all";

    private static final String DEFAULT_BASE_URL = "https://gitlab.com/api/v4";

    private TestConfig() {
    }

    public static String baseUrl() {
        return trimTrailingSlash(lookup(BASE_URL_KEY).orElse(DEFAULT_BASE_URL));
    }

    /**
     * @throws IllegalStateException with an actionable message when no token is configured, so a
     *         misconfigured run fails immediately and loudly instead of producing a wall of 401s.
     */
    public static String token() {
        return lookup(TOKEN_KEY).orElseThrow(() -> new IllegalStateException(
                "No GitLab token configured. Set the GITLAB_TOKEN environment variable "
                        + "(or -Dgitlab.token=...) to an OAuth2 / personal access token with the 'api' scope."));
    }

    public static Optional<String> existingProjectId() {
        return lookup(PROJECT_ID_KEY);
    }

    public static boolean keepProject() {
        return flag(KEEP_PROJECT_KEY);
    }

    public static boolean logAll() {
        return flag(LOG_ALL_KEY);
    }

    private static boolean flag(String key) {
        return lookup(key).map(Boolean::parseBoolean).orElse(false);
    }

    /** Resolves {@code some.property} from the system properties, then from {@code SOME_PROPERTY}. */
    private static Optional<String> lookup(String key) {
        String fromSystemProperty = System.getProperty(key);
        if (isPresent(fromSystemProperty)) {
            return Optional.of(fromSystemProperty.trim());
        }
        String fromEnvironment = System.getenv(toEnvironmentVariable(key));
        return isPresent(fromEnvironment) ? Optional.of(fromEnvironment.trim()) : Optional.empty();
    }

    private static String toEnvironmentVariable(String key) {
        return key.replace('.', '_').toUpperCase(Locale.ROOT);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
