package com.abnamro.assignment.support;

import com.abnamro.assignment.client.IssuesApi;
import com.abnamro.assignment.model.Issue;
import com.abnamro.assignment.model.IssuePayload;
import io.restassured.response.Response;

import java.util.Map;
import java.util.UUID;

/**
 * Base class for every test in the suite.
 *
 * <p>It offers what nearly every test needs: unique names, and ways to reach a known starting state
 * without repeating the call-and-check dance. Deliberately small — anything an individual test needs
 * to <em>assert</em> stays in that test, so the expectations are always visible where they are
 * exercised.
 *
 * <p>Set-up steps here throw {@link IllegalStateException} rather than failing an assertion. The
 * distinction is not cosmetic: Surefire reports the first as an <em>error</em> and the second as a
 * <em>failure</em>, and a run that could not build its fixtures is a different problem from one
 * where the API misbehaved.
 */
public abstract class ApiTest {

    /** The isolated project all issues in this run belong to. */
    protected static Object projectId() {
        return TestProject.id();
    }

    /**
     * A title that is unique per test run, so {@code search=} based assertions can never collide
     * with an issue left behind by another test.
     */
    protected static String uniqueTitle(String prefix) {
        return prefix + " " + UUID.randomUUID();
    }

    /**
     * A label that is unique per test run.
     *
     * <p>Labels are not per-issue data: the first issue to mention a label creates it for the whole
     * project. Two tests sharing a literal label name therefore share state, and under parallel
     * execution they race to create it — which lands on the very defect this suite documents, where
     * GitLab answers 500 when the same new label is created twice. Unique names keep "every test
     * provisions its own data" true.
     */
    protected static String uniqueLabel(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Creates an issue with a unique title and returns the parsed result. */
    protected static Issue givenAnIssue() {
        return givenAnIssue(IssuePayload.issue().title(uniqueTitle("issue")).build());
    }

    /** Creates an issue from the given payload, failing the run as a set-up error if it cannot. */
    protected static Issue givenAnIssue(Map<String, ?> payload) {
        Response response = IssuesApi.create(projectId(), payload);
        require(response, HttpStatus.CREATED, "create the issue under test");
        return response.as(Issue.class);
    }

    /** Puts an existing issue into the closed state, for tests whose subject starts there. */
    protected static Issue givenAClosedIssue(Object issueIid) {
        Response response = IssuesApi.update(projectId(), issueIid,
                IssuePayload.issue().stateEvent("close").build());
        require(response, HttpStatus.OK, "close issue " + issueIid);
        return response.as(Issue.class);
    }

    /** Removes an issue, for tests whose subject is what happens afterwards. */
    protected static void givenADeletedIssue(Object issueIid) {
        require(IssuesApi.delete(projectId(), issueIid), HttpStatus.NO_CONTENT, "delete issue " + issueIid);
    }

    private static void require(Response response, int expectedStatus, String what) {
        if (response.statusCode() != expectedStatus) {
            throw new IllegalStateException("Pre-condition failed: could not " + what + " (HTTP "
                    + response.statusCode() + " instead of " + expectedStatus + "). Response: "
                    + response.asString());
        }
    }
}
