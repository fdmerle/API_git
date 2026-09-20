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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Input validation and boundary conditions on issue creation.
 *
 * <p>The interesting question for a write endpoint is not "does the happy path work" but "what does
 * it do with input it should refuse". Each test below states the expected contract in its name, so
 * a failure immediately tells you which rule the API stopped honouring.
 */
@Feature("Issue creation - validation and boundaries")
class IssueCreationValidationTest extends ApiTest {

    /** GitLab's issuable title column is bounded at 255 characters. */
    private static final int MAX_TITLE_LENGTH = 255;

    @Test
    @DisplayName("A request without a title is rejected: title is the only mandatory parameter")
    void rejectsMissingTitle() {
        Response response = IssuesApi.create(projectId(),
                IssuePayload.issue().description("A body, but no title.").build());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.asString()).containsIgnoringCase("title");
    }

    @Test
    @DisplayName("An entirely empty request body is rejected rather than creating a blank issue")
    void rejectsEmptyBody() {
        Response response = IssuesApi.create(projectId(), IssuePayload.empty());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest(name = "title = [{0}]")
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    @DisplayName("A title that is empty or only whitespace is rejected: it carries no information")
    void rejectsBlankTitle(String blankTitle) {
        // Whitespace-only titles are the boundary case between "present" and "meaningful". GitLab
        // strips surrounding whitespace before validating, so these all collapse to an empty title.
        Response response = IssuesApi.create(projectId(), IssuePayload.issue().title(blankTitle).build());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("A title of exactly 255 characters is accepted: the upper boundary is inclusive")
    void acceptsTitleAtMaximumLength() {
        String maximumTitle = "a".repeat(MAX_TITLE_LENGTH);

        Issue created = givenAnIssue(IssuePayload.issue().title(maximumTitle).build());

        assertThat(created.title()).hasSize(MAX_TITLE_LENGTH).isEqualTo(maximumTitle);
    }

    @Test
    @DisplayName("A title of 256 characters is rejected rather than silently truncated")
    void rejectsTitleBeyondMaximumLength() {
        // Silent truncation would be worse than a refusal: the caller would believe it stored
        // something it did not. This test pins the API to the safer behaviour.
        String tooLongTitle = "a".repeat(MAX_TITLE_LENGTH + 1);

        Response response = IssuesApi.create(projectId(), IssuePayload.issue().title(tooLongTitle).build());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.asString()).containsIgnoringCase("title");
    }

    @Test
    @DisplayName("Surrounding whitespace is stripped from the title before it is stored")
    void stripsSurroundingWhitespaceFromTitle() {
        String padded = "   " + uniqueTitle("padded") + "   ";

        Issue created = givenAnIssue(IssuePayload.issue().title(padded).build());

        assertThat(created.title()).isEqualTo(padded.strip());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "Ünïcödé àccénts and ümlauts",
            "Emoji in the title 🐛🚀✅",
            "日本語のタイトル",
            "<script>alert('xss')</script>",
            "Quotes \"double\" and 'single' and `backtick`",
            "Backslashes \\ and slashes / and pipes |",
            "JSON-ish {\"key\": [1, 2]}",
            "SQL-ish '; DROP TABLE issues; --"
    })
    @DisplayName("Unusual but legal titles round-trip unchanged: the API stores data, it does not sanitise it")
    void storesUnusualTitlesVerbatim(String unusualTitle) {
        // Storing input verbatim is the correct behaviour for an API; escaping is the renderer's job.
        // These cases also prove no injection path mangles the payload in transit.
        Issue created = givenAnIssue(IssuePayload.issue().title(unusualTitle).build());

        assertThat(created.title()).isEqualTo(unusualTitle);
        assertThat(IssuesApi.get(projectId(), created.iid()).as(Issue.class).title()).isEqualTo(unusualTitle);
    }

    @Test
    @DisplayName("A large description is accepted: issue bodies are expected to hold real content")
    void acceptsLargeDescription() {
        // 64 KiB is well within GitLab's 1 MiB limit, and large enough to catch a mid-size cap or a
        // truncating proxy in front of the API.
        String largeDescription = "x".repeat(64 * 1024);

        Issue created = givenAnIssue(IssuePayload.issue()
                .title(uniqueTitle("large-body"))
                .description(largeDescription)
                .build());

        assertThat(created.description()).hasSameSizeAs(largeDescription);
    }

    @Test
    @DisplayName("Two issues may share a title: titles are descriptive, not identifying")
    void allowsDuplicateTitles() {
        String sharedTitle = uniqueTitle("duplicate");

        Issue first = givenAnIssue(IssuePayload.issue().title(sharedTitle).build());
        Issue second = givenAnIssue(IssuePayload.issue().title(sharedTitle).build());

        assertThat(second.iid()).as("each issue gets its own number").isNotEqualTo(first.iid());
        assertThat(second.title()).isEqualTo(first.title());
    }

    @Test
    @DisplayName("Duplicate labels in one request are de-duplicated, once the label exists")
    void deduplicatesRepeatedLabels() {
        // The label has to exist in the project before this holds - see the defect test below for
        // what happens when it does not.
        String label = uniqueLabel("dedup");
        String other = uniqueLabel("other");
        givenAnIssue(IssuePayload.issue().title(uniqueTitle("seed-label")).labels(label).build());

        Issue created = givenAnIssue(IssuePayload.issue()
                .title(uniqueTitle("dup-labels"))
                .labels(label, label, other)
                .build());

        assertThat(created.labels()).containsExactlyInAnyOrder(label, other);
    }

    @Test
    @DisplayName("DEFECT: repeating a label that does not exist yet makes the API return 500")
    void repeatingANewLabelCausesServerError() {
        // Reproduced consistently against gitlab.com on 2026-09-20. Passing the same not-yet-existing
        // label twice in one request makes GitLab try to create it twice, and the resulting
        // constraint violation surfaces as an unhandled 500 rather than a validation error.
        //
        // Sending the same label twice is at worst redundant input; the API de-duplicates it happily
        // once the label exists, so the only difference here is a race inside label creation. A 500
        // also leaves the caller unable to tell whether the issue was created.
        //
        // This test asserts the *defective* behaviour deliberately, so the suite both documents the
        // bug and tells us the moment GitLab fixes it - at which point this test should be deleted
        // and the de-duplication test above extended to cover new labels too.
        String brandNewLabel = uniqueLabel("never-used");

        Response response = IssuesApi.create(projectId(), IssuePayload.issue()
                .title(uniqueTitle("duplicate-new-label"))
                .labels(brandNewLabel, brandNewLabel)
                .build());

        assertThat(response.statusCode())
                .as("known defect; if this is no longer 500, GitLab has fixed it and this test must go")
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ParameterizedTest(name = "due_date = [{0}]")
    @ValueSource(strings = {"not-a-date", "2030-13-01", "2030-02-30", "yesterday"})
    @DisplayName("An unparseable due date is silently dropped instead of being rejected")
    void silentlyIgnoresUnparseableDueDate(String invalidDueDate) {
        // Observed behaviour, and a trap worth pinning down: the issue is created with HTTP 201 and
        // no due date at all. A client that sent "2030-02-30" gets a success response and quietly
        // ends up with an issue that has no deadline. A 400 would be the safer contract; this test
        // documents what the API actually does so the difference is visible rather than assumed.
        Response response = IssuesApi.create(projectId(), IssuePayload.issue()
                .title(uniqueTitle("bad-due-date"))
                .dueDate(invalidDueDate)
                .build());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.as(Issue.class).dueDate())
                .as("the rejected value must not be stored in some mangled form either")
                .isNull();
    }

    @ParameterizedTest(name = "due_date [{0}] is reinterpreted as {1}")
    @CsvSource({"31-12-2030, 2030-12-31", "2030/12/31, 2030-12-31"})
    @DisplayName("A due date in a non-ISO but parseable format is reinterpreted, not refused")
    void reinterpretsAmbiguousDueDateFormats(String submitted, String stored) {
        // The day-first form is the risky one: a caller sending the US month-first "03-04-2030"
        // would have it stored as 3 April, not 4 March, with no indication anything was assumed.
        Issue created = givenAnIssue(IssuePayload.issue()
                .title(uniqueTitle("ambiguous-due-date"))
                .dueDate(submitted)
                .build());

        assertThat(created.dueDate()).isEqualTo(stored);
    }

    @Test
    @DisplayName("A due date in the past is accepted: back-dating is a legitimate use case")
    void acceptsPastDueDate() {
        String pastDate = LocalDate.now().minusYears(1).toString();

        Issue created = givenAnIssue(IssuePayload.issue()
                .title(uniqueTitle("past-due"))
                .dueDate(pastDate)
                .build());

        assertThat(created.dueDate()).isEqualTo(pastDate);
    }

    @ParameterizedTest(name = "confidential = [{0}]")
    @ValueSource(strings = {"maybe", "2", "-1", ""})
    @DisplayName("A value that is not a recognised boolean is rejected, not guessed at")
    void rejectsNonBooleanConfidentialFlag(String invalidFlag) {
        // Loose coercion here would mean "confidential=maybe" quietly creating a public issue.
        Response response = IssuesApi.create(projectId(), IssuePayload.issue()
                .title(uniqueTitle("bad-boolean"))
                .confidential(invalidFlag)
                .build());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest(name = "confidential = [{0}] means {1}")
    @CsvSource({
            "true, true", "TRUE, true", "1, true", "yes, true", "Yes, true", "on, true", "t, true",
            "false, false", "0, false", "no, false", "off, false", "f, false"})
    @DisplayName("Boolean parameters accept a wide set of synonyms beyond JSON true and false")
    void coercesBooleanSynonyms(String submitted, boolean expected) {
        // Worth pinning down because the accepted set is much wider than the documentation implies,
        // and the boundary is not obvious: "on" and "t" are accepted, "2" and "maybe" are refused.
        // A client relying on strict JSON booleans is fine; one passing through user input is not.
        Issue created = givenAnIssue(IssuePayload.issue()
                .title(uniqueTitle("boolean-synonym"))
                .confidential(submitted)
                .build());

        assertThat(created.confidential()).isEqualTo(expected);
    }

    @Test
    @DisplayName("An unsupported issue_type is rejected: the enum must be closed")
    void rejectsUnknownIssueType() {
        Response response = IssuesApi.create(projectId(), IssuePayload.issue()
                .title(uniqueTitle("bad-type"))
                .issueType("not_a_real_type")
                .build());

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Unknown parameters are ignored rather than causing a failure")
    void ignoresUnknownParameters() {
        // Tolerating unknown input keeps older clients working; this test documents that choice and
        // — just as importantly — proves the unknown field is not echoed back as if it were stored.
        String title = uniqueTitle("unknown-param");

        Issue created = givenAnIssue(IssuePayload.issue()
                .title(title)
                .field("not_a_real_parameter", "ignore me")
                .build());

        assertThat(created.title()).isEqualTo(title);
        assertThat(IssuesApi.get(projectId(), created.iid()).asString()).doesNotContain("not_a_real_parameter");
    }
}
