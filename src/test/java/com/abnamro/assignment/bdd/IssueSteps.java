package com.abnamro.assignment.bdd;

import com.abnamro.assignment.client.GitLabApiClient;
import com.abnamro.assignment.client.IssuesApi;
import com.abnamro.assignment.model.Issue;
import com.abnamro.assignment.model.IssuePayload;
import com.abnamro.assignment.support.HttpStatus;
import com.abnamro.assignment.support.TestProject;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step definitions for every feature in this suite.
 *
 * <p>Cucumber builds a fresh instance per scenario, so the fields below are naturally
 * scenario-scoped state and no dependency-injection module is needed. Keeping all steps in one
 * class is what makes that work, and at this suite's size it costs nothing in readability.
 *
 * <p>The steps do no HTTP work of their own: they translate a sentence into a call on the same
 * {@code IssuesApi} the JUnit tests use, and translate the response back into an assertion. The BDD
 * layer is therefore a second way to express expectations, not a second implementation.
 */
public class IssueSteps {

    /**
     * Who the current caller is; scenarios swap this to test access control.
     *
     * <p>A supplier rather than a specification, because REST Assured accumulates path parameters
     * on a specification as it is used. Re-using one instance across the several calls a scenario
     * makes would fail with "redundant path parameters" on the second call — each call needs its
     * own.
     */
    private Supplier<RequestSpecification> client = GitLabApiClient::authenticated;

    private Response lastResponse;
    /** The issue under discussion — "that issue" in the Gherkin. */
    private Issue currentIssue;
    /**
     * The number of the issue this scenario created, remembered separately from {@link #currentIssue}.
     *
     * <p>{@code currentIssue} follows whatever the last call returned, which is exactly what makes
     * it useless for "did I get back the issue I asked for?": a read that answered with a different
     * issue would overwrite it, and the comparison would then be the response against itself.
     */
    private Long createdIid;
    private Object requestedIid;

    // ---------------------------------------------------------------- callers

    @Given("I am an authorised member of the project")
    public void authorisedMember() {
        client = GitLabApiClient::authenticated;
    }

    @Given("I am an anonymous caller")
    public void anonymousCaller() {
        client = GitLabApiClient::anonymous;
    }

    @Given("I am a caller with an invalid token")
    public void callerWithInvalidToken() {
        client = () -> GitLabApiClient.withToken("definitely-not-a-valid-token");
    }

    // ---------------------------------------------------------------- raising issues

    @When("I raise an issue titled {string}")
    public void raiseIssueTitled(String title) {
        raise(IssuePayload.issue().title(title).build());
    }

    @When("I raise an issue with:")
    public void raiseIssueWith(Map<String, String> attributes) {
        raise(payloadFrom(attributes));
    }

    @When("I raise an issue with a title of {int} characters")
    public void raiseIssueWithTitleOfLength(int length) {
        raise(IssuePayload.issue().title("a".repeat(length)).build());
    }

    @When("I raise an issue with a title of only whitespace")
    public void raiseIssueWithWhitespaceTitle() {
        raise(IssuePayload.issue().title("   \t  ").build());
    }

    @When("I raise an issue repeating a label that does not exist yet")
    public void raiseIssueRepeatingANewLabel() {
        String brandNewLabel = "never-used-" + UUID.randomUUID();
        raise(IssuePayload.issue()
                .title("Duplicate new label")
                .labels(brandNewLabel, brandNewLabel)
                .build());
    }

    @Given("an issue titled {string} exists")
    public void anIssueTitledExists(String title) {
        raise(IssuePayload.issue().title(title).build());
        requireSuccessfulSetUp();
    }

    @Given("an issue with:")
    public void anIssueWith(Map<String, String> attributes) {
        raise(payloadFrom(attributes));
        requireSuccessfulSetUp();
    }

    @Given("the label {string} exists in the project")
    public void theLabelExists(String label) {
        Response response = IssuesApi.create(GitLabApiClient.authenticated(), TestProject.id(),
                IssuePayload.issue().title("Seeds the label " + label).labels(label).build());
        requireCreated(response, "seeding the label " + label);
    }

    // ---------------------------------------------------------------- reading

    @When("I look up that issue")
    public void lookUpThatIssue() {
        record(IssuesApi.get(client.get(), TestProject.id(), currentIssue.iid()));
    }

    @When("I look that issue up by its number with {string} appended")
    public void lookUpThatIssueWithSuffix(String suffix) {
        requestedIid = currentIssue.iid() + suffix;
        record(IssuesApi.get(client.get(), TestProject.id(), requestedIid));
    }

    @When("I list the issues in the project")
    public void listTheIssues() {
        lastResponse = IssuesApi.list(client.get(), TestProject.id(), Map.of());
    }

    @When("I search the project for that issue's title")
    public void searchForThatIssuesTitle() {
        lastResponse = IssuesApi.list(client.get(), TestProject.id(), Map.of("search", currentIssue.title()));
    }

    @When("I list the issues in state {string}")
    public void listTheIssuesInState(String state) {
        lastResponse = IssuesApi.list(client.get(), TestProject.id(), Map.of("state", state));
    }

    // ---------------------------------------------------------------- revising

    @When("I revise that issue with:")
    public void reviseThatIssueWith(Map<String, String> attributes) {
        record(IssuesApi.update(client.get(), TestProject.id(), currentIssue.iid(), payloadFrom(attributes)));
    }

    @When("I revise that issue with nothing")
    public void reviseThatIssueWithNothing() {
        record(IssuesApi.update(client.get(), TestProject.id(), currentIssue.iid(), IssuePayload.empty()));
    }

    @When("I add the labels {string} to that issue")
    public void addLabels(String labels) {
        record(IssuesApi.update(client.get(), TestProject.id(), currentIssue.iid(),
                IssuePayload.issue().addLabels(split(labels)).build()));
    }

    @When("I remove the label {string} from that issue")
    public void removeLabel(String labels) {
        record(IssuesApi.update(client.get(), TestProject.id(), currentIssue.iid(),
                IssuePayload.issue().removeLabels(split(labels)).build()));
    }

    @When("I close that issue")
    public void closeThatIssue() {
        record(IssuesApi.update(client.get(), TestProject.id(), currentIssue.iid(),
                IssuePayload.issue().stateEvent("close").build()));
    }

    @When("I reopen that issue")
    public void reopenThatIssue() {
        record(IssuesApi.update(client.get(), TestProject.id(), currentIssue.iid(),
                IssuePayload.issue().stateEvent("reopen").build()));
    }

    @When("I delete that issue")
    public void deleteThatIssue() {
        lastResponse = IssuesApi.delete(client.get(), TestProject.id(), currentIssue.iid());
    }

    // ---------------------------------------------------------------- outcomes

    @Then("the request succeeds")
    public void theRequestSucceeds() {
        assertThat(lastResponse.statusCode())
                .as("expected success, body was %s", lastResponse.asString())
                .isBetween(HttpStatus.OK, 299);
    }

    @Then("the request is refused as invalid")
    public void theRequestIsRefusedAsInvalid() {
        assertThat(lastResponse.statusCode())
                .as("body was %s", lastResponse.asString())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Then("the request is refused as unauthenticated")
    public void theRequestIsRefusedAsUnauthenticated() {
        assertThat(lastResponse.statusCode())
                .as("body was %s", lastResponse.asString())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Then("the request is refused as not found")
    public void theRequestIsRefusedAsNotFound() {
        assertThat(lastResponse.statusCode())
                .as("body was %s", lastResponse.asString())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Then("the request fails with a server error")
    public void theRequestFailsWithAServerError() {
        assertThat(lastResponse.statusCode())
                .as("known defect; body was %s", lastResponse.asString())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Then("the refusal mentions {string}")
    public void theRefusalMentions(String text) {
        assertThat(lastResponse.asString()).containsIgnoringCase(text);
    }

    @Then("the refusal reveals no issue content")
    public void theRefusalRevealsNoIssueContent() {
        assertThat(lastResponse.asString()).doesNotContain("web_url");
    }

    // ---------------------------------------------------------------- the issue itself

    @Then("the issue is titled {string}")
    public void theIssueIsTitled(String title) {
        assertThat(currentIssue.title()).isEqualTo(title);
    }

    @Then("the issue title is {int} characters long")
    public void theIssueTitleIsLong(int length) {
        assertThat(currentIssue.title()).hasSize(length);
    }

    @Then("the issue description is {string}")
    public void theIssueDescriptionIs(String description) {
        assertThat(currentIssue.description()).isEqualTo(description);
    }

    @Then("the issue is open")
    public void theIssueIsOpen() {
        assertThat(currentIssue.state()).isEqualTo(Issue.STATE_OPENED);
    }

    @Then("the issue is closed")
    public void theIssueIsClosed() {
        assertThat(currentIssue.state()).isEqualTo(Issue.STATE_CLOSED);
    }

    @Then("the issue records when it was closed")
    public void theIssueRecordsWhenItWasClosed() {
        assertThat(currentIssue.closedAt()).isNotNull();
    }

    @Then("the issue no longer records when it was closed")
    public void theIssueNoLongerRecordsWhenItWasClosed() {
        assertThat(currentIssue.closedAt()).isNull();
    }

    @Then("the issue is confidential")
    public void theIssueIsConfidential() {
        assertThat(currentIssue.confidential()).isTrue();
    }

    @Then("the issue is not confidential")
    public void theIssueIsNotConfidential() {
        assertThat(currentIssue.confidential()).isFalse();
    }

    @Then("the issue carries no labels")
    public void theIssueCarriesNoLabels() {
        assertThat(currentIssue.labels()).isEmpty();
    }

    @Then("the issue carries the labels {string}")
    public void theIssueCarriesTheLabels(String labels) {
        assertThat(currentIssue.labels()).containsExactlyInAnyOrder(split(labels));
    }

    @Then("the issue has no due date")
    public void theIssueHasNoDueDate() {
        assertThat(currentIssue.dueDate()).isNull();
    }

    @Then("the issue is due on {string}")
    public void theIssueIsDueOn(String dueDate) {
        assertThat(currentIssue.dueDate()).isEqualTo(dueDate);
    }

    @Then("the issue returned is the one I created")
    public void theIssueReturnedIsTheOneICreated() {
        Issue returned = lastResponse.as(Issue.class);
        assertThat(returned.iid())
                .as("asked for issue %s and got %s back", requestedIid, returned.iid())
                .isEqualTo(createdIid);
    }

    // ---------------------------------------------------------------- re-reading

    @Then("looking the issue up again shows the revision")
    public void lookingUpAgainShowsTheRevision() {
        // "The response said so" is not the same as "the server stored it".
        Issue stored = freshCopy();
        assertThat(stored.title()).isEqualTo(currentIssue.title());
        assertThat(stored.description()).isEqualTo(currentIssue.description());
    }

    @Then("looking the issue up again shows the same title")
    public void lookingUpAgainShowsTheSameTitle() {
        assertThat(freshCopy().title()).isEqualTo(currentIssue.title());
    }

    @Then("the issue can no longer be found")
    public void theIssueCanNoLongerBeFound() {
        assertThat(IssuesApi.get(GitLabApiClient.authenticated(), TestProject.id(), currentIssue.iid())
                .statusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Then("the issue survives, still visible to an authorised member")
    public void theIssueSurvives() {
        assertThat(IssuesApi.get(GitLabApiClient.authenticated(), TestProject.id(), currentIssue.iid())
                .statusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------- listings

    @Then("the listing contains that issue")
    public void theListingContainsThatIssue() {
        assertThat(listedIids()).contains(currentIssue.iid());
    }

    /**
     * The strict form, for scenarios about a query that is supposed to narrow the result down: a
     * filter that was ignored altogether would still satisfy "contains".
     */
    @Then("the listing contains that issue and nothing else")
    public void theListingContainsOnlyThatIssue() {
        assertThat(listedIids()).containsExactly(currentIssue.iid());
    }

    @Then("the listing does not list that issue")
    public void theListingDoesNotListThatIssue() {
        assertThat(listedIids()).doesNotContain(currentIssue.iid());
    }

    // ---------------------------------------------------------------- plumbing

    private void raise(Map<String, ?> payload) {
        Response response = IssuesApi.create(client.get(), TestProject.id(), payload);
        record(response);
        if (response.statusCode() == HttpStatus.CREATED) {
            createdIid = currentIssue.iid();
        }
    }

    /** Keeps {@link #currentIssue} in step with the last call that returned an issue body. */
    private void record(Response response) {
        lastResponse = response;
        if (response.statusCode() < 300 && response.asString().contains("\"iid\"")) {
            currentIssue = response.as(Issue.class);
        }
    }

    /**
     * Set-up steps fail as errors rather than as assertion failures: Surefire reports the two
     * differently, and "the fixtures could not be built" is a different problem from "the API
     * misbehaved".
     */
    private void requireSuccessfulSetUp() {
        requireCreated(lastResponse, "creating the issue under discussion");
    }

    private static void requireCreated(Response response, String what) {
        if (response.statusCode() != HttpStatus.CREATED) {
            throw new IllegalStateException("Pre-condition failed: " + what + " returned HTTP "
                    + response.statusCode() + ". Response: " + response.asString());
        }
    }

    private Issue freshCopy() {
        return IssuesApi.get(GitLabApiClient.authenticated(), TestProject.id(), currentIssue.iid())
                .as(Issue.class);
    }

    private List<Long> listedIids() {
        return Arrays.stream(lastResponse.as(Issue[].class)).map(Issue::iid).toList();
    }

    /**
     * Every table cell is passed through verbatim — no coercion, no defaulting — so a scenario can
     * send a deliberately invalid value and see what the API makes of it.
     */
    private static Map<String, Object> payloadFrom(Map<String, String> attributes) {
        return new LinkedHashMap<>(attributes);
    }

    private static String[] split(String commaSeparated) {
        return commaSeparated.split(",");
    }
}
