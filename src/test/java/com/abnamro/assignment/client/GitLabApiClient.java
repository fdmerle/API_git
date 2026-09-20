package com.abnamro.assignment.client;

import com.abnamro.assignment.config.TestConfig;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.LogConfig;
import io.restassured.config.ObjectMapperConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.filter.log.LogDetail;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.http.ContentType;
import io.restassured.path.json.mapper.factory.Jackson2ObjectMapperFactory;
import io.restassured.specification.RequestSpecification;

/**
 * Builds the REST Assured request specifications used by the whole suite.
 *
 * <p>Intentionally thin: it owns transport concerns (base URI, auth header, content type,
 * serialisation, timeouts, logging, reporting) and nothing else. Knowledge about <em>endpoints</em>
 * lives in the {@code *Api} classes, and knowledge about <em>expected behaviour</em> lives in the
 * tests.
 */
public final class GitLabApiClient {

    /**
     * GitLab accepts both OAuth2 tokens and personal access tokens as bearer tokens, so a single
     * scheme covers either kind of credential the assignment may be run with.
     */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * Without these a stalled connection blocks on the JDK default, which is effectively forever:
     * one hung request would burn the pipeline's entire time budget and the run would be killed
     * before the cleanup hook could remove the throw-away project.
     */
    private static final int CONNECTION_TIMEOUT_MS = 10_000;
    private static final int SOCKET_TIMEOUT_MS = 30_000;

    private static final RestAssuredConfig CONFIG = RestAssured.config()
            .objectMapperConfig(jacksonConfig())
            .httpClient(HttpClientConfig.httpClientConfig()
                    .setParam("http.connection.timeout", CONNECTION_TIMEOUT_MS)
                    .setParam("http.socket.timeout", SOCKET_TIMEOUT_MS))
            // Console logging is opt-in, but when it is on it must not print the token either.
            .logConfig(LogConfig.logConfig().blacklistHeader(AUTHORIZATION_HEADER));

    private GitLabApiClient() {
    }

    /** Requests authenticated with the configured token. This is what the vast majority of tests use. */
    public static RequestSpecification authenticated() {
        return withToken(TestConfig.token());
    }

    /** Requests carrying no credentials at all, for the "is this endpoint actually protected?" tests. */
    public static RequestSpecification anonymous() {
        return request(baseSpec());
    }

    /** Requests authenticated with an arbitrary token, for the invalid/revoked-credential tests. */
    public static RequestSpecification withToken(String token) {
        return request(baseSpec().addHeader(AUTHORIZATION_HEADER, "Bearer " + token));
    }

    /**
     * A spec produced by {@link RequestSpecBuilder} is a template, not something that can issue a
     * request on its own — it has to be handed to {@code given()}. Each call gets a fresh one, which
     * is also what makes the suite safe to run in parallel.
     */
    private static RequestSpecification request(RequestSpecBuilder builder) {
        return RestAssured.given().spec(builder.build());
    }

    private static RequestSpecBuilder baseSpec() {
        RequestSpecBuilder builder = new RequestSpecBuilder()
                .setBaseUri(TestConfig.baseUrl())
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .setConfig(CONFIG)
                // Attaches every request and response to the Allure report — with the credential
                // masked, because the pipeline publishes those results as a build artifact.
                .addFilter(new ReportingFilter());

        if (TestConfig.logAll()) {
            builder.addFilter(new RequestLoggingFilter(LogDetail.ALL))
                    .addFilter(new ResponseLoggingFilter(LogDetail.ALL));
        }
        return builder;
    }

    /**
     * GitLab speaks {@code snake_case}; our models are idiomatic Java. Configuring the mapping once
     * here keeps every model free of per-field {@code @JsonProperty} noise, and ignoring unknown
     * fields means a new GitLab response attribute never breaks the suite.
     */
    private static ObjectMapperConfig jacksonConfig() {
        Jackson2ObjectMapperFactory factory = (type, charset) -> new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        return ObjectMapperConfig.objectMapperConfig().jackson2ObjectMapperFactory(factory);
    }
}
