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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Happy-path coverage of the four CRUD operations on an issue.
 *
 * <p>Each test owns the data it needs and asserts on the complete round trip: what the API returns
 * when the change is made, and what a subsequent read shows. Asserting only on the response of the
 * write would miss the class of bug where a write is acknowledged but never persisted.
 */
@Feature("Issues CRUD")
class IssueCrudTest extends ApiTest {

    @Test
    @DisplayName("CREATE: an issue with only the mandatory title is created with sane defaults")
    void createsIssueWithTitleOnly() {
        // Title is the only required parameter; everything else must fall back to a documented default.
        String title = uniqueTitle("minimal");

        Response response = IssuesApi.create(projectId(), IssuePayload.issue().title(title).build());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.CREATED);
        Issue created = response.as(Issue.class);
        assertThat(created.title()).isEqualTo(title);
        assertThat(created.state()).isEqualTo(Issue.STATE_OPENED);
        assertThat(created.iid()).as("the per-project issue number").isPositive();
        assertThat(created.id()).as("the instance-wide issue id").isPositive();
        assertThat(created.labels()).isEmpty();
        assertThat(created.confidential()).isFalse();
        assertThat(created.dueDate()).isNull();
        assertThat(created.closedAt()).as("a newly opened issue has no closing timestamp").isNull();
        assertThat(created.author().username()).as("the issue is attributed to the calling user").isNotBlank();

        Issue stored = IssuesApi.get(projectId(), created.iid()).as(Issue.class);
        assertThat(stored.state()).isEqualTo(Issue.STATE_OPENED);
        assertThat(stored.labels()).isEmpty();
        assertThat(stored.confidential()).isFalse();
        assertThat(stored.dueDate()).isNull();
    }

    @Test
    @DisplayName("CREATE: every supported optional attribute is persisted as sent")
    void createsIssueWithAllOptionalAttributes() {
        // Guards against attributes being silently dropped — a classic regression in write endpoints.
        String title = uniqueTitle("full");
        String description = "Line one\n\n- bullet\n- **bold**";
        String dueDate = LocalDate.now().plusDays(7).toString();
        String defect = uniqueLabel("bug");
        String area = uniqueLabel("automation");

        Issue created = givenAnIssue(IssuePayload.issue()
                .title(title)
                .description(description)
                .labels(defect, area)
                .confidential(true)
                .dueDate(dueDate)
                .build());

        assertThat(created.title()).isEqualTo(title);
        assertThat(created.description()).as("markdown must round-trip byte for byte").isEqualTo(description);
        assertThat(created.labels()).containsExactlyInAnyOrder(defect, area);
        assertThat(created.confidential()).isTrue();
        assertThat(created.dueDate()).isEqualTo(dueDate);

        // The response echoing an attribute is not proof that it was stored; only a read is.
        Issue stored = IssuesApi.get(projectId(), created.iid()).as(Issue.class);
        assertThat(stored.description()).isEqualTo(description);
        assertThat(stored.labels()).containsExactlyInAnyOrder(defect, area);
        assertThat(stored.confidential()).isTrue();
        assertThat(stored.dueDate()).isEqualTo(dueDate);
    }

    @Test
    @DisplayName("READ: a created issue can be fetched by its iid and matches the documented schema")
    void readsIssueByIid() {
        Issue created = givenAnIssue();

        Response response = IssuesApi.get(projectId(), created.iid());

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
        // Schema validation catches contract drift (a field disappearing, a type changing) that
        // field-by-field assertions would not notice.
        response.then().assertThat().body(matchesJsonSchemaInClasspath("schemas/issue-schema.json"));

        Issue fetched = response.as(Issue.class);
        assertThat(fetched.id()).isEqualTo(created.id());
        assertThat(fetched.iid()).isEqualTo(created.iid());
        assertThat(fetched.title()).isEqualTo(created.title());
        // Observed on 2026-09-20: GitLab has moved issue permalinks onto the work-items scheme, so
        // web_url reads .../-/work_items/44 rather than .../-/issues/44. Worth knowing for any
        // client that string-matches the path. Asserting the trailing identifier keeps what the
        // check is for - the link addresses this issue rather than some other one - without
        // pinning a URL shape that is mid-migration.
        assertThat(fetched.webUrl())
                .as("the browsable URL must address this very issue")
                .startsWith("http")
                .endsWith("/" + created.iid());
    }

    @Test
    @DisplayName("READ: the project issue list contains the newly created issue")
    void listsCreatedIssue() {
        Issue created = givenAnIssue();

        // Searching by the unique title keeps the assertion stable no matter how many issues the
        // rest of the suite has already created in the shared project.
        Response response = IssuesApi.list(projectId(), Map.of("search", created.title()));

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
        List<Issue> issues = List.of(response.as(Issue[].class));
        assertThat(issues).extracting(Issue::iid).containsExactly(created.iid());
    }

    @Test
    @DisplayName("UPDATE: title and description can be changed and the change is persisted")
    void updatesTitleAndDescription() {
        Issue created = givenAnIssue();
        String newTitle = uniqueTitle("updated");
        String newDescription = "Rewritten by the update test.";

        Response response = IssuesApi.update(projectId(), created.iid(),
                IssuePayload.issue().title(newTitle).description(newDescription).build());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.OK);
        Issue updated = response.as(Issue.class);
        assertThat(updated.title()).isEqualTo(newTitle);
        assertThat(updated.description()).isEqualTo(newDescription);
        assertThat(updated.iid()).as("updating must not change the issue's identity").isEqualTo(created.iid());

        // Re-read, because "the response said so" is not the same as "the server stored it".
        Issue reFetched = IssuesApi.get(projectId(), created.iid()).as(Issue.class);
        assertThat(reFetched.title()).isEqualTo(newTitle);
        assertThat(reFetched.description()).isEqualTo(newDescription);
    }

    @Test
    @DisplayName("UPDATE: labels can be added and removed incrementally")
    void addsAndRemovesLabels() {
        String keep = uniqueLabel("keep");
        String drop = uniqueLabel("drop");
        String extra = uniqueLabel("extra");
        Issue created = givenAnIssue(IssuePayload.issue()
                .title(uniqueTitle("labels"))
                .labels(keep, drop)
                .build());

        Issue afterAdd = IssuesApi.update(projectId(), created.iid(),
                        IssuePayload.issue().addLabels(extra).build())
                .as(Issue.class);
        assertThat(afterAdd.labels()).containsExactlyInAnyOrder(keep, drop, extra);

        Issue afterRemove = IssuesApi.update(projectId(), created.iid(),
                        IssuePayload.issue().removeLabels(drop).build())
                .as(Issue.class);
        assertThat(afterRemove.labels()).containsExactlyInAnyOrder(keep, extra);

        assertThat(IssuesApi.get(projectId(), created.iid()).as(Issue.class).labels())
                .as("the label set the next reader sees")
                .containsExactlyInAnyOrder(keep, extra);
    }

    @Test
    @DisplayName("UPDATE: an issue can be closed and reopened, and its timestamps reflect that")
    void closesAndReopensIssue() {
        Issue created = givenAnIssue();

        Issue closed = IssuesApi.update(projectId(), created.iid(),
                        IssuePayload.issue().stateEvent("close").build())
                .as(Issue.class);
        assertThat(closed.state()).isEqualTo(Issue.STATE_CLOSED);
        assertThat(closed.closedAt()).as("closing must record when it happened").isNotNull();

        Issue reopened = IssuesApi.update(projectId(), created.iid(),
                        IssuePayload.issue().stateEvent("reopen").build())
                .as(Issue.class);
        assertThat(reopened.state()).isEqualTo(Issue.STATE_OPENED);
        assertThat(reopened.closedAt()).as("reopening must clear the closing timestamp").isNull();

        Issue stored = IssuesApi.get(projectId(), created.iid()).as(Issue.class);
        assertThat(stored.state()).isEqualTo(Issue.STATE_OPENED);
        assertThat(stored.closedAt()).isNull();
    }

    @Test
    @DisplayName("DELETE: a deleted issue returns 204 and is no longer retrievable")
    void deletesIssue() {
        Issue created = givenAnIssue();

        Response response = IssuesApi.delete(projectId(), created.iid());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(IssuesApi.get(projectId(), created.iid()).statusCode())
                .as("a deleted issue must be gone, not merely hidden")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
