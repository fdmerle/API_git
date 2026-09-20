package com.abnamro.assignment.client;

import io.qameta.allure.Step;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import java.util.Map;

/**
 * Thin, un-opinionated wrapper around the GitLab Issues endpoints.
 *
 * <p>Two deliberate design choices:
 * <ul>
 *   <li>Every method returns the raw {@link Response} rather than a parsed model. Negative tests
 *       need to assert on status codes and error bodies, and a wrapper that throws on a non-2xx
 *       response would make half of this assignment impossible to write.</li>
 *   <li>Request bodies are passed as maps, not typed DTOs, because edge-case tests must be able to
 *       send payloads that a typed DTO would refuse to represent (wrong types, unknown fields,
 *       missing required fields). {@link com.abnamro.assignment.model.IssuePayload} gives that a
 *       readable, fluent shape.</li>
 * </ul>
 *
 * @see <a href="https://docs.gitlab.com/ee/api/issues.html">GitLab Issues API</a>
 */
public final class IssuesApi {

    private static final String PROJECT_ISSUES = "/projects/{projectId}/issues";
    private static final String PROJECT_ISSUE = "/projects/{projectId}/issues/{issueIid}";

    private IssuesApi() {
    }

    @Step("Create an issue in project {projectId}")
    public static Response create(Object projectId, Map<String, ?> body) {
        return create(GitLabApiClient.authenticated(), projectId, body);
    }

    public static Response create(RequestSpecification spec, Object projectId, Map<String, ?> body) {
        return spec.body(body).post(PROJECT_ISSUES, projectId);
    }

    @Step("Get issue {issueIid} from project {projectId}")
    public static Response get(Object projectId, Object issueIid) {
        return get(GitLabApiClient.authenticated(), projectId, issueIid);
    }

    public static Response get(RequestSpecification spec, Object projectId, Object issueIid) {
        return spec.get(PROJECT_ISSUE, projectId, issueIid);
    }

    @Step("List issues in project {projectId}")
    public static Response list(Object projectId, Map<String, ?> queryParams) {
        return list(GitLabApiClient.authenticated(), projectId, queryParams);
    }

    public static Response list(RequestSpecification spec, Object projectId, Map<String, ?> queryParams) {
        return spec.queryParams(queryParams).get(PROJECT_ISSUES, projectId);
    }

    @Step("Update issue {issueIid} in project {projectId}")
    public static Response update(Object projectId, Object issueIid, Map<String, ?> body) {
        return update(GitLabApiClient.authenticated(), projectId, issueIid, body);
    }

    public static Response update(RequestSpecification spec, Object projectId, Object issueIid,
                                  Map<String, ?> body) {
        return spec.body(body).put(PROJECT_ISSUE, projectId, issueIid);
    }

    @Step("Delete issue {issueIid} from project {projectId}")
    public static Response delete(Object projectId, Object issueIid) {
        return delete(GitLabApiClient.authenticated(), projectId, issueIid);
    }

    public static Response delete(RequestSpecification spec, Object projectId, Object issueIid) {
        return spec.delete(PROJECT_ISSUE, projectId, issueIid);
    }
}
