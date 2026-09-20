package com.abnamro.assignment.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Fluent builder for issue request bodies.
 *
 * <p>It produces a plain {@code Map}, which is what makes it usable for both happy-path and
 * edge-case tests: {@link #field(String, Object)} can put a value of any type — or an intentionally
 * malformed one — under any key, including keys the API does not know.
 *
 * <pre>{@code
 * IssuePayload.issue().title("Login fails").labels("bug", "ui").confidential(true).build()
 * }</pre>
 */
public final class IssuePayload {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    private IssuePayload() {
    }

    public static IssuePayload issue() {
        return new IssuePayload();
    }

    /** An empty body — used to assert that "update nothing" is rejected. */
    public static Map<String, Object> empty() {
        return Map.of();
    }

    public IssuePayload title(Object title) {
        return field("title", title);
    }

    public IssuePayload description(Object description) {
        return field("description", description);
    }

    /** GitLab expects labels as one comma-separated string. */
    public IssuePayload labels(String... labels) {
        return field("labels", join(labels));
    }

    public IssuePayload addLabels(String... labels) {
        return field("add_labels", join(labels));
    }

    public IssuePayload removeLabels(String... labels) {
        return field("remove_labels", join(labels));
    }

    public IssuePayload confidential(Object confidential) {
        return field("confidential", confidential);
    }

    /** Expected format is {@code YYYY-MM-DD}; deliberately typed loosely so tests can break it. */
    public IssuePayload dueDate(Object dueDate) {
        return field("due_date", dueDate);
    }

    /** {@code close} or {@code reopen}. */
    public IssuePayload stateEvent(Object stateEvent) {
        return field("state_event", stateEvent);
    }

    public IssuePayload issueType(Object issueType) {
        return field("issue_type", issueType);
    }

    /** Escape hatch for any parameter the builder does not model explicitly. */
    public IssuePayload field(String name, Object value) {
        fields.put(name, value);
        return this;
    }

    public Map<String, Object> build() {
        return new LinkedHashMap<>(fields);
    }

    private static String join(String... values) {
        StringJoiner joiner = new StringJoiner(",");
        for (String value : values) {
            joiner.add(value);
        }
        return joiner.toString();
    }
}
