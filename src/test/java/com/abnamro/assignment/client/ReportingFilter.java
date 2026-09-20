package com.abnamro.assignment.client;

import io.qameta.allure.Allure;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

import java.util.Locale;
import java.util.Set;

/**
 * Attaches every request and response to the Allure report, with credentials masked.
 *
 * <p>This exists instead of {@code AllureRestAssured} for one reason: that filter renders the
 * request headers verbatim, including {@code Authorization: Bearer <token>}, and even a
 * copy-pasteable {@code curl} line containing it. The pipeline publishes the Allure results as a
 * build artifact on a public repository, and artifacts — unlike logs — are not scrubbed by GitHub.
 * A report is worth nothing if reading it hands someone the credential that produced it.
 *
 * <p>The attachments are plain text rather than rendered HTML: everything a failure needs (method,
 * URI, headers, body, status) is there, and the format cannot smuggle a value past the mask.
 */
final class ReportingFilter implements Filter {

    /** Headers whose value is a credential and must never reach the report. */
    private static final Set<String> SECRET_HEADERS = Set.of("authorization", "private-token", "job-token");

    private static final String MASK = "***";

    /** Bodies are attached in full up to this size; the 64 KiB description test would drown the rest. */
    private static final int MAX_BODY_CHARS = 8 * 1024;

    @Override
    public Response filter(FilterableRequestSpecification requestSpec,
                           FilterableResponseSpecification responseSpec,
                           FilterContext context) {
        String request = describeRequest(requestSpec);
        Response response = context.next(requestSpec, responseSpec);

        Allure.addAttachment("Request", "text/plain", request);
        Allure.addAttachment("Response", "text/plain", describeResponse(response));
        return response;
    }

    private static String describeRequest(FilterableRequestSpecification requestSpec) {
        StringBuilder description = new StringBuilder()
                .append(requestSpec.getMethod()).append(' ').append(requestSpec.getURI()).append('\n');
        for (Header header : requestSpec.getHeaders()) {
            description.append(header.getName()).append(": ").append(valueOf(header)).append('\n');
        }
        Object body = requestSpec.getBody();
        if (body != null) {
            description.append('\n').append(truncate(String.valueOf(body)));
        }
        return description.toString();
    }

    private static String describeResponse(Response response) {
        StringBuilder description = new StringBuilder()
                .append(response.getStatusLine()).append('\n');
        for (Header header : response.getHeaders()) {
            description.append(header.getName()).append(": ").append(valueOf(header)).append('\n');
        }
        return description.append('\n').append(truncate(response.getBody().asString())).toString();
    }

    private static String valueOf(Header header) {
        return SECRET_HEADERS.contains(header.getName().toLowerCase(Locale.ROOT)) ? MASK : header.getValue();
    }

    private static String truncate(String body) {
        if (body == null || body.length() <= MAX_BODY_CHARS) {
            return body == null ? "" : body;
        }
        return body.substring(0, MAX_BODY_CHARS) + "\n... [" + (body.length() - MAX_BODY_CHARS) + " more characters]";
    }
}
