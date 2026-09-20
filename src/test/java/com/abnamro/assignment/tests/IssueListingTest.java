package com.abnamro.assignment.tests;

import com.abnamro.assignment.client.IssuesApi;
import com.abnamro.assignment.model.Issue;
import com.abnamro.assignment.model.IssuePayload;
import com.abnamro.assignment.support.ApiTest;
import com.abnamro.assignment.support.HttpStatus;
import io.qameta.allure.Feature;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Listing, filtering, sorting and pagination.
 *
 * <p>All assertions are scoped by a label that is unique to this class's run, so they hold no
 * matter how many issues the rest of the suite has created in the shared project. Without that
 * scoping, every assertion here would be a race against the other test classes.
 */
@Feature("Issue listing, filtering and pagination")
class IssueListingTest extends ApiTest {

    /** Unique per run: the handle by which this class finds exactly its own fixtures. */
    private static final String SCOPE_LABEL = uniqueLabel("listing");
    private static final int FIXTURE_COUNT = 3;

    private static List<Issue> fixtures;

    @BeforeAll
    static void createFixtures() {
        fixtures = IntStream.rangeClosed(1, FIXTURE_COUNT)
                .mapToObj(index -> givenAnIssue(IssuePayload.issue()
                        .title(SCOPE_LABEL + " fixture " + index)
                        .labels(SCOPE_LABEL)
                        .build()))
                .toList();
    }

    @Test
    @DisplayName("The listing returns every issue carrying the filtered label, and nothing else")
    void filtersByLabel() {
        List<Issue> listed = listScoped(Map.of("labels", SCOPE_LABEL));

        assertThat(listed)
                .hasSize(FIXTURE_COUNT)
                .extracting(Issue::iid)
                .containsExactlyInAnyOrderElementsOf(fixtures.stream().map(Issue::iid).toList());
    }

    @Test
    @DisplayName("A filter that matches nothing returns an empty array, not an error")
    void returnsEmptyArrayWhenNothingMatches() {
        // An empty result is a valid answer to a valid question; only a malformed query is an error.
        Response response = IssuesApi.list(projectId(), Map.of("labels", "label-that-cannot-exist-" + UUID.randomUUID()));

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.as(Issue[].class)).isEmpty();
    }

    @Test
    @DisplayName("The state filter separates open from closed issues")
    void filtersByState() {
        // This test needs to change an issue's state, so it works on a pair of its own under a
        // separate label rather than mutating the shared fixtures. Borrowing one of those and
        // putting it back afterwards would leave the class's other tests looking at a half-modified
        // set whenever an assertion here failed before the restore.
        String stateLabel = uniqueLabel("state");
        Issue staysOpen = givenAnIssue(IssuePayload.issue()
                .title(uniqueTitle("state-open")).labels(stateLabel).build());
        Issue getsClosed = givenAnIssue(IssuePayload.issue()
                .title(uniqueTitle("state-closed")).labels(stateLabel).build());
        givenAClosedIssue(getsClosed.iid());

        List<Issue> closed = listScoped(Map.of("labels", stateLabel, "state", "closed"));
        List<Issue> open = listScoped(Map.of("labels", stateLabel, "state", "opened"));
        List<Issue> all = listScoped(Map.of("labels", stateLabel, "state", "all"));

        assertThat(closed).extracting(Issue::iid).containsExactly(getsClosed.iid());
        assertThat(open).extracting(Issue::iid).containsExactly(staysOpen.iid());
        assertThat(all).extracting(Issue::iid)
                .containsExactlyInAnyOrder(staysOpen.iid(), getsClosed.iid());
    }

    @Test
    @DisplayName("A free-text search returns the matching issue and only that one")
    void searchesByTitle() {
        // The search term carries a UUID, so exactly one issue in the project can match it. That is
        // what makes containsExactly meaningful here: asserting only that the target is somewhere in
        // the result would also pass if the API ignored the search parameter and returned
        // everything. The shared fixtures are unusable for this - their titles differ only by a
        // trailing index, which GitLab drops as a search term for being shorter than three
        // characters, so searching for one of them legitimately returns all three.
        Issue target = givenAnIssue(IssuePayload.issue().title(uniqueTitle("searchable")).build());

        List<Issue> found = listScoped(Map.of("search", target.title(), "in", "title"));

        assertThat(found).extracting(Issue::iid).containsExactly(target.iid());
    }

    @Test
    @DisplayName("Ordering by creation date is honoured and the two directions are exact mirrors")
    void sortsByCreationDate() {
        // Asserting that descending is the reverse of ascending — rather than pinning a specific
        // issue to position zero — keeps the test meaningful without depending on how the API
        // breaks ties between issues created within the same instant.
        List<Long> ascending = listScoped(Map.of(
                "labels", SCOPE_LABEL, "order_by", "created_at", "sort", "asc"))
                .stream().map(Issue::iid).toList();
        List<Long> descending = listScoped(Map.of(
                "labels", SCOPE_LABEL, "order_by", "created_at", "sort", "desc"))
                .stream().map(Issue::iid).toList();

        List<Long> ascendingReversed = new ArrayList<>(ascending);
        Collections.reverse(ascendingReversed);

        assertThat(ascending).hasSize(FIXTURE_COUNT);
        assertThat(descending).containsExactlyElementsOf(ascendingReversed);
    }

    @Test
    @DisplayName("Pagination splits the result set and advertises the totals in response headers")
    void paginatesResults() {
        Response firstPage = IssuesApi.list(projectId(), Map.of(
                "labels", SCOPE_LABEL, "per_page", 2, "page", 1, "order_by", "created_at", "sort", "asc"));

        assertThat(firstPage.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstPage.as(Issue[].class)).hasSize(2);
        assertThat(firstPage.header("x-total")).isEqualTo(String.valueOf(FIXTURE_COUNT));
        assertThat(firstPage.header("x-total-pages")).isEqualTo("2");
        assertThat(firstPage.header("x-per-page")).isEqualTo("2");
        assertThat(firstPage.header("x-next-page")).isEqualTo("2");

        Response secondPage = IssuesApi.list(projectId(), Map.of(
                "labels", SCOPE_LABEL, "per_page", 2, "page", 2, "order_by", "created_at", "sort", "asc"));

        assertThat(secondPage.as(Issue[].class)).as("the remainder lands on the last page").hasSize(1);
        assertThat(secondPage.header("x-next-page"))
                .as("there is no page after the last one")
                .isNullOrEmpty();
    }

    @Test
    @DisplayName("A page beyond the last one is empty rather than an error")
    void returnsEmptyPageBeyondTheEnd() {
        Response response = IssuesApi.list(projectId(), Map.of("labels", SCOPE_LABEL, "per_page", 2, "page", 99));

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.as(Issue[].class)).isEmpty();
    }

    @Test
    @DisplayName("A per_page above the documented maximum is capped at 100 instead of being honoured")
    void capsPageSizeAtTheDocumentedMaximum() {
        // The cap is what protects the API from a caller asking for the entire project in one go.
        Response response = IssuesApi.list(projectId(), Map.of("per_page", 1000));

        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Integer.parseInt(response.header("x-per-page"))).isLessThanOrEqualTo(100);
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({"state, archived", "order_by, nonsense", "sort, sideways", "scope, everything"})
    @DisplayName("A filter value outside the allowed set is rejected, not ignored")
    void rejectsInvalidFilterValues(String parameter, String invalidValue) {
        // Silently ignoring an unrecognised filter would return a superset of what was asked for —
        // for a confidentiality-sensitive resource, that is the dangerous failure mode.
        Response response = IssuesApi.list(projectId(), Map.of(parameter, invalidValue));

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Lists issues and returns them parsed; keeps the assertions above free of plumbing. */
    private static List<Issue> listScoped(Map<String, ?> queryParams) {
        Response response = IssuesApi.list(projectId(), queryParams);
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.OK);
        return List.of(response.as(Issue[].class));
    }
}
