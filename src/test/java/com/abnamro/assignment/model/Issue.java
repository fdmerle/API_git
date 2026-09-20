package com.abnamro.assignment.model;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The subset of the GitLab issue representation the tests actually assert on.
 *
 * <p>Unknown fields are ignored during deserialisation (see
 * {@link com.abnamro.assignment.client.GitLabApiClient}), so GitLab can keep adding attributes
 * without breaking this suite.
 *
 * <p>{@code dueDate} stays a {@link String} on purpose: it is a plain {@code YYYY-MM-DD} date and
 * several edge-case tests deliberately post values that are not valid dates at all.
 */
public record Issue(
        Long id,
        Long iid,
        Long projectId,
        String title,
        String description,
        String state,
        List<String> labels,
        Boolean confidential,
        String dueDate,
        String webUrl,
        User author,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime closedAt) {

    public static final String STATE_OPENED = "opened";
    public static final String STATE_CLOSED = "closed";
}
