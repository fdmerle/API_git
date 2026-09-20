package com.abnamro.assignment.tests;

import com.abnamro.assignment.client.GitLabApiClient;
import com.abnamro.assignment.client.IssuesApi;
import com.abnamro.assignment.model.Issue;
import com.abnamro.assignment.model.IssuePayload;
import com.abnamro.assignment.support.ApiTest;
import com.abnamro.assignment.support.HttpStatus;
import io.qameta.allure.Feature;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authentication and information-disclosure behaviour.
 *
 * <p>The project used by this suite is private, which makes it the right subject for two separate
 * questions: does the API refuse callers without valid credentials, and does it avoid confirming
 * that the resource exists at all while refusing them?
 */
@Feature("Issue authentication and confidentiality")
class IssueAuthorizationTest extends ApiTest {

    private static final String INVALID_TOKEN = "definitely-not-a-valid-token";

    @Test
    @DisplayName("A request with an invalid token is rejected as unauthenticated")
    void rejectsInvalidToken() {
        // A malformed credential is unambiguous: the API can safely say "your token is no good"
        // without revealing anything about the resource.
        Response response = IssuesApi.list(GitLabApiClient.withToken(INVALID_TOKEN), projectId(), Map.of());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("An anonymous read of a private project is refused without confirming the project exists")
    void hidesPrivateProjectFromAnonymousReads() {
        // 404 rather than 403 is deliberate: a 403 would confirm the project exists, which is
        // itself a disclosure. Note the asymmetry with the write paths below, which answer 401 —
        // reads hide the resource, writes demand credentials before looking at all.
        Response response = IssuesApi.list(GitLabApiClient.anonymous(), projectId(), Map.of());

        assertThat(response.statusCode())
                .as("anonymous reads must not confirm the project exists, body was %s", response.asString())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.asString())
                .as("the refusal must not leak issue content")
                .doesNotContain("web_url");
    }

    @Test
    @DisplayName("An anonymous caller cannot create an issue in a private project")
    void rejectsAnonymousCreate() {
        RequestSpecification anonymous = GitLabApiClient.anonymous();

        Response response = IssuesApi.create(anonymous, projectId(),
                IssuePayload.issue().title(uniqueTitle("anonymous")).build());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("An anonymous caller cannot delete an existing issue")
    void rejectsAnonymousDelete() {
        // The destructive operation gets its own test: authorisation gaps tend to appear on the
        // paths that are exercised least.
        Issue created = givenAnIssue();

        Response response = IssuesApi.delete(GitLabApiClient.anonymous(), projectId(), created.iid());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(IssuesApi.get(projectId(), created.iid()).statusCode())
                .as("the issue must survive the refused deletion")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("A confidential issue is returned to its authorised author")
    void returnsConfidentialIssueToAuthorisedCaller() {
        // Confidentiality must restrict who can read an issue without hiding it from the owner.
        Issue confidential = givenAnIssue(IssuePayload.issue()
                .title(uniqueTitle("confidential"))
                .confidential(true)
                .build());

        Response response = IssuesApi.get(projectId(), confidential.iid());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.as(Issue.class).confidential()).isTrue();
    }
}
