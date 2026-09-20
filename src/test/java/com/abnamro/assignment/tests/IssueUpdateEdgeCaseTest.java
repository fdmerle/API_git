package com.abnamro.assignment.tests;

import com.abnamro.assignment.client.IssuesApi;
import com.abnamro.assignment.model.Issue;
import com.abnamro.assignment.model.IssuePayload;
import com.abnamro.assignment.support.ApiTest;
import com.abnamro.assignment.support.HttpStatus;
import io.qameta.allure.Feature;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge cases around updating an issue: no-op requests, invalid transitions, and the rule that an
 * update must never touch a field the caller did not mention.
 */
@Feature("Issue update - edge cases")
class IssueUpdateEdgeCaseTest extends ApiTest {

    @Test
    @DisplayName("An update with no parameters is rejected instead of silently doing nothing")
    void rejectsUpdateWithoutParameters() {
        // A no-op that answers 200 lets a client with a bug believe its change was applied.
        Issue created = givenAnIssue();

        Response response = IssuesApi.update(projectId(), created.iid(), IssuePayload.empty());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("An update touches only the fields it names: other fields keep their values")
    void leavesUnmentionedFieldsUntouched() {
        // This is the difference between PUT-as-replace and PUT-as-patch. GitLab implements the
        // latter, and a regression to the former would blank out data.
        String label = uniqueLabel("keep-me");
        Issue created = givenAnIssue(IssuePayload.issue()
                .title(uniqueTitle("partial"))
                .description("Keep me.")
                .labels(label)
                .build());

        Issue updated = IssuesApi.update(projectId(), created.iid(),
                        IssuePayload.issue().title(uniqueTitle("partial-updated")).build())
                .as(Issue.class);

        assertThat(updated.description()).isEqualTo("Keep me.");
        assertThat(updated.labels()).containsExactly(label);
    }

    @Test
    @DisplayName("A description can be explicitly cleared by sending an empty string")
    void clearsDescriptionWithEmptyString() {
        // The counterpart to the test above: omitting a field keeps it, sending an empty value
        // clears it. Both behaviours must hold for the API to be usable.
        Issue created = givenAnIssue(IssuePayload.issue()
                .title(uniqueTitle("clear-description"))
                .description("Temporary.")
                .build());

        Issue updated = IssuesApi.update(projectId(), created.iid(),
                        IssuePayload.issue().description("").build())
                .as(Issue.class);

        assertThat(updated.description()).isNullOrEmpty();
    }

    @Test
    @DisplayName("An unknown state_event is rejected: only close and reopen exist")
    void rejectsUnknownStateEvent() {
        Issue created = givenAnIssue();

        Response response = IssuesApi.update(projectId(), created.iid(),
                IssuePayload.issue().stateEvent("archive").build());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(IssuesApi.get(projectId(), created.iid()).as(Issue.class).state())
                .as("a rejected transition must leave the issue as it was")
                .isEqualTo(Issue.STATE_OPENED);
    }

    @Test
    @DisplayName("Closing an already closed issue is idempotent rather than an error")
    void closingTwiceIsIdempotent() {
        // Retries are a fact of life for API clients; the second close must not fail.
        // The first close is a pre-condition: if it silently failed, the "second" close below would
        // be a first close, and this test would pass without ever exercising idempotency.
        Issue created = givenAnIssue();
        givenAClosedIssue(created.iid());

        Response secondClose = IssuesApi.update(projectId(), created.iid(),
                IssuePayload.issue().stateEvent("close").build());

        assertThat(secondClose.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondClose.as(Issue.class).state()).isEqualTo(Issue.STATE_CLOSED);
    }

    @Test
    @DisplayName("A closed issue can still be edited: closing is not archiving")
    void allowsEditingClosedIssue() {
        Issue created = givenAnIssue();
        givenAClosedIssue(created.iid());
        String newTitle = uniqueTitle("edited-while-closed");

        Response response = IssuesApi.update(projectId(), created.iid(),
                IssuePayload.issue().title(newTitle).build());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
        Issue updated = response.as(Issue.class);
        assertThat(updated.title()).isEqualTo(newTitle);
        assertThat(updated.state()).as("editing must not reopen the issue").isEqualTo(Issue.STATE_CLOSED);
    }

    @Test
    @DisplayName("The same length limit applies to updates as to creation")
    void appliesTitleLengthLimitOnUpdate() {
        // Validation that only guards the create path is a classic way for bad data to get in.
        Issue created = givenAnIssue();

        Response response = IssuesApi.update(projectId(), created.iid(),
                IssuePayload.issue().title("a".repeat(256)).build());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(IssuesApi.get(projectId(), created.iid()).as(Issue.class).title())
                .as("the original title must survive a rejected update")
                .isEqualTo(created.title());
    }

    @Test
    @DisplayName("Updating an issue that does not exist returns 404")
    void returnsNotFoundWhenUpdatingUnknownIssue() {
        Response response = IssuesApi.update(projectId(), 999_999_999L,
                IssuePayload.issue().title(uniqueTitle("ghost")).build());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
