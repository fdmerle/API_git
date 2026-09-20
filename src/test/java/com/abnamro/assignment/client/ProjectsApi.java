package com.abnamro.assignment.client;

import io.qameta.allure.Step;
import io.restassured.response.Response;

import java.util.Map;

/**
 * Projects endpoints — used purely as test <em>infrastructure</em>.
 *
 * <p>The system under test is the Issues API; projects are only created and torn down so that every
 * run starts from a known-empty container and leaves nothing behind.
 *
 * @see <a href="https://docs.gitlab.com/ee/api/projects.html">GitLab Projects API</a>
 */
public final class ProjectsApi {

    private ProjectsApi() {
    }

    @Step("Create a throw-away project named {name}")
    public static Response create(String name) {
        return GitLabApiClient.authenticated()
                .body(Map.of(
                        "name", name,
                        "path", name,
                        // Private keeps generated test data out of public search results.
                        "visibility", "private",
                        "issues_enabled", true,
                        "initialize_with_readme", false))
                .post("/projects");
    }

    @Step("Delete project {projectId}")
    public static Response delete(Object projectId) {
        return GitLabApiClient.authenticated().delete("/projects/{projectId}", projectId);
    }

    public static Response get(Object projectId) {
        return GitLabApiClient.authenticated().get("/projects/{projectId}", projectId);
    }

    /**
     * Removes a project for good.
     *
     * <p>On gitlab.com a plain {@code DELETE} only <em>schedules</em> deletion: the project is
     * renamed to {@code <path>-deletion_scheduled-<id>} and sits there for the retention period. A
     * throw-away project per run would therefore pile up in the namespace for weeks. Passing
     * {@code permanently_remove} together with the project's current full path finishes the job.
     *
     * <p>Instances that delete immediately simply return 404 on the follow-up read, in which case
     * there is nothing left to do.
     *
     * <p>Every step is checked and the outcome is reported back rather than assumed: a cleanup that
     * fails silently is how a namespace fills up with abandoned projects that nobody is watching
     * for. Deciding what to do about it belongs to the caller, not here.
     *
     * @return {@code true} when the project is confirmed gone.
     */
    @Step("Permanently delete project {projectId}")
    public static boolean deletePermanently(Object projectId) {
        delete(projectId);

        Response afterScheduling = get(projectId);
        if (afterScheduling.statusCode() == 404) {
            return true;
        }
        if (afterScheduling.statusCode() != 200) {
            return false;
        }

        String fullPath = afterScheduling.jsonPath().getString("path_with_namespace");
        Response removal = GitLabApiClient.authenticated()
                .queryParam("permanently_remove", true)
                .queryParam("full_path", fullPath)
                .delete("/projects/{projectId}", projectId);
        if (removal.statusCode() >= 300) {
            return false;
        }

        return get(projectId).statusCode() == 404;
    }
}
