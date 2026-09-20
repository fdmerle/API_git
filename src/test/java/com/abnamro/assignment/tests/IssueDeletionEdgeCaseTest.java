package com.abnamro.assignment.tests;

import com.abnamro.assignment.client.IssuesApi;
import com.abnamro.assignment.model.Issue;
import com.abnamro.assignment.support.ApiTest;
import com.abnamro.assignment.support.HttpStatus;
import io.qameta.allure.Feature;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge cases around deletion — the one operation in this API that cannot be undone.
 */
@Feature("Issue deletion - edge cases")
class IssueDeletionEdgeCaseTest extends ApiTest {

    @Test
    @DisplayName("Deleting the same issue twice returns 404 the second time")
    void secondDeleteReturnsNotFound() {
        // Delete is idempotent in effect but not in response: the caller is told the issue is gone.
        Issue created = givenAnIssue();
        assertThat(IssuesApi.delete(projectId(), created.iid()).statusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Response secondDelete = IssuesApi.delete(projectId(), created.iid());

        assertThat(secondDelete.statusCode()).as(secondDelete.asString()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Deleting an issue that never existed returns 404")
    void deletingUnknownIssueReturnsNotFound() {
        Response response = IssuesApi.delete(projectId(), 999_999_999L);

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("A deleted issue disappears from the project listing as well as from direct reads")
    void deletedIssueLeavesTheListing() {
        // Deletion has to propagate to every read path, not only the one addressed by iid.
        Issue created = givenAnIssue();
        givenADeletedIssue(created.iid());

        List<Issue> matches = List.of(IssuesApi
                .list(projectId(), Map.of("search", created.title()))
                .as(Issue[].class));

        assertThat(matches).isEmpty();
    }

    @Test
    @DisplayName("A closed issue can still be deleted: the two operations are independent")
    void deletesClosedIssue() {
        // Closing first is a pre-condition: without checking it, a failed close would leave this
        // test quietly deleting an open issue and reporting success.
        Issue created = givenAnIssue();
        givenAClosedIssue(created.iid());

        Response response = IssuesApi.delete(projectId(), created.iid());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
