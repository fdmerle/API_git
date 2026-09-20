package com.abnamro.assignment.support;

import com.abnamro.assignment.client.ProjectsApi;
import com.abnamro.assignment.config.TestConfig;
import io.restassured.response.Response;

import java.util.Optional;
import java.util.UUID;

/**
 * The isolated project every test in this run works in.
 *
 * <p>Isolation matters: listing, filtering and pagination assertions are only meaningful when the
 * test knows exactly which issues exist. The project is created on first use and removed when the
 * JVM exits.
 *
 * <p>It is a process-wide fixture rather than a JUnit extension because two runners share it — the
 * JUnit tests and the Cucumber scenarios run in the same forked JVM, and a JUnit
 * {@code Extension} would be invisible to the latter. One project per run, whichever runner asks
 * for it first.
 *
 * <p>Set {@code gitlab.project.id} to run against an existing project instead (nothing is then
 * deleted), or {@code gitlab.project.keep=true} to keep a generated project for debugging.
 */
public final class TestProject {

    private static Object id;
    private static boolean ephemeral;

    private TestProject() {
    }

    /** The id of the project under which all issues in this run are created. */
    public static synchronized Object id() {
        if (id == null) {
            provision();
        }
        return id;
    }

    private static void provision() {
        Optional<String> configured = TestConfig.existingProjectId();
        if (configured.isPresent()) {
            id = configured.get();
            ephemeral = false;
            return;
        }

        id = create();
        ephemeral = !TestConfig.keepProject();
        if (ephemeral) {
            Runtime.getRuntime().addShutdownHook(new Thread(TestProject::release, "test-project-cleanup"));
        }
    }

    /** @return the id of a freshly created, empty, private project. */
    private static long create() {
        String name = "api-issues-tests-" + UUID.randomUUID().toString().substring(0, 8);
        Response response = ProjectsApi.create(name);
        if (response.statusCode() != HttpStatus.CREATED) {
            throw new IllegalStateException(
                    "Could not create the test project (HTTP " + response.statusCode() + "). "
                            + "Check that the token has the 'api' scope and may create projects. Response: "
                            + response.asString());
        }
        return response.jsonPath().getLong("id");
    }

    /**
     * Best-effort teardown. A shutdown hook does not run when the JVM is killed outright — a
     * cancelled or timed-out pipeline run does exactly that — so a leftover project is always
     * possible. What must not happen is a leftover project nobody hears about: whatever goes wrong
     * here is printed with the id needed to clean it up by hand.
     */
    private static void release() {
        try {
            if (!ProjectsApi.deletePermanently(id)) {
                warn("project " + id + " could not be confirmed deleted - remove it manually");
            }
        } catch (RuntimeException failure) {
            warn("project " + id + " could not be deleted (" + failure + ") - remove it manually");
        }
    }

    private static void warn(String message) {
        System.err.println("[test-project-cleanup] " + message);
    }
}
