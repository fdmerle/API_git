package com.abnamro.assignment.tests;

import com.abnamro.assignment.client.IssuesApi;
import com.abnamro.assignment.model.Issue;
import com.abnamro.assignment.support.ApiTest;
import com.abnamro.assignment.support.HttpStatus;
import io.qameta.allure.Feature;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge cases around reading a single issue: identifiers that do not exist, identifiers that are not
 * valid identifiers at all, and the difference between an issue's two ids.
 */
@Feature("Issue retrieval - edge cases")
class IssueRetrievalEdgeCaseTest extends ApiTest {

    @Test
    @DisplayName("Fetching an issue number that was never used returns 404")
    void returnsNotFoundForUnusedIssueNumber() {
        long neverUsedIid = 999_999_999L;

        Response response = IssuesApi.get(projectId(), neverUsedIid);

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Fetching an issue from a project that does not exist returns 404")
    void returnsNotFoundForUnknownProject() {
        Response response = IssuesApi.get(999_999_999L, 1);

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest(name = "issue_iid = [{0}]")
    @ValueSource(strings = {"0", "-1", "abc", "1abc", "%20", "null"})
    @DisplayName("An issue identifier that is not a positive integer is refused, never guessed at")
    void refusesMalformedIssueIdentifier(String malformedIid) {
        // The exact code is not part of GitLab's documented contract: a value that fails type
        // coercion yields 400, one that coerces but matches nothing yields 404. What matters —
        // and what this test guards — is that the API never falls back to some "nearest" issue.
        Response response = IssuesApi.get(projectId(), malformedIid);

        assertThat(response.statusCode())
                .as("expected a client error for iid '%s', body was %s", malformedIid, response.asString())
                .isIn(HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("DEFECT: a decimal identifier is truncated, returning a different issue than addressed")
    void truncatesDecimalIssueIdentifier() {
        // Reproduced against gitlab.com on 2026-09-20: GET .../issues/1.5 returns issue 1, and
        // .../issues/2.5 returns issue 2. The request addressed a resource that does not exist, and
        // the API answered 200 with a different one - the worst possible outcome for a client that
        // computed an identifier wrongly, because nothing signals the mistake.
        //
        // "1abc" is refused with 400, so the parser is not simply lenient: it specifically drops a
        // fractional part. Asserting the defect keeps it visible until GitLab rejects these instead.
        Issue first = givenAnIssue();

        Response response = IssuesApi.get(projectId(), first.iid() + ".5");

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.as(Issue.class).iid())
                .as("known defect; a fractional iid resolves to its truncated integer")
                .isEqualTo(first.iid());
    }

    @Test
    @DisplayName("The project-scoped endpoint takes the iid, not the global id")
    void distinguishesIidFromGlobalId() {
        // Confusing the two is the single most common mistake against this API. The global id is
        // orders of magnitude larger than the per-project iid on gitlab.com, so addressing an issue
        // by its global id inside a project must not resolve.
        Issue created = givenAnIssue();
        assertThat(created.id()).as("pre-condition: the two identifiers differ").isNotEqualTo(created.iid());

        Response byGlobalId = IssuesApi.get(projectId(), created.id());

        assertThat(byGlobalId.statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Issue numbers increase within a project and are never reused after a delete")
    void doesNotReuseIssueNumbers() {
        // Reusing a number after a delete would make every stored reference ambiguous. The delete
        // is checked: if it did not happen, this test would degrade into "iids increase".
        Issue first = givenAnIssue();
        givenADeletedIssue(first.iid());

        Issue second = givenAnIssue();

        assertThat(second.iid()).isGreaterThan(first.iid());
    }
}
