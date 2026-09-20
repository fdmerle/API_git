# GitLab Issues API — Test Automation

Automated API tests for the [GitLab Issues API](https://docs.gitlab.com/ee/api/issues.html),
covering the full CRUD lifecycle plus validation, boundary and authorisation edge cases.

The API is exercised over plain HTTP — no GitLab client library is used, because the API itself is
the system under test.

**138 tests, all green against gitlab.com (verified 2026-09-20)** — 95 JUnit tests and 43 Cucumber
scenarios, run together by one `mvn test`. Every expectation was calibrated against the live API
rather than taken from the documentation, which turned out to matter: in four places the two
disagree. See [What the tests found](#what-the-tests-found).

---

## Quick start

```bash
export GITLAB_TOKEN=<oauth2-or-personal-access-token-with-the-api-scope>
mvn test
```

That is the whole setup. The suite creates its own private, throw-away GitLab project, runs against
it, and deletes it again when the JVM exits.

---

## Two layers, one API client

The suite is written at two altitudes, and they share everything below the assertions — the same
`IssuesApi`, the same models, the same project fixture. Neither re-implements the other's plumbing.

**The JUnit layer** (`tests/`) is where coverage lives: 95 tests, heavy on parameterised boundary
cases, written for an engineer changing this code.

**The Cucumber layer** (`features/` + `bdd/`) is where the contract is stated in language a product
owner or a tester can read and challenge:

```gherkin
Scenario: Resolving an issue and reopening it when it recurs
  Given an issue titled "Intermittent timeout" exists
  When I close that issue
  Then the issue is closed
  And the issue records when it was closed
  When I reopen that issue
  Then the issue is open
  And the issue no longer records when it was closed
```

It deliberately does not mirror all 95 JUnit tests. It covers the lifecycle, the validation rules
worth agreeing on out loud, the access-control expectations, and — under a `@defect` tag — the four
behaviours where GitLab gets it wrong, so those are readable as living documentation rather than
buried in a Java comment.

## What is covered

| Suite | Focus |
| --- | --- |
| `IssueCrudTest` | The happy path for create, read, list, update and delete, including label edits and close/reopen. Every write is verified with a follow-up read, and the single-issue response is validated against a JSON schema. |
| `IssueCreationValidationTest` | Mandatory and blank titles, the 255-character title boundary (255 accepted, 256 refused), whitespace stripping, unicode/emoji/script/SQL-shaped titles, a 64 KiB description, duplicate titles, label de-duplication, due-date handling, the accepted set of boolean synonyms, unknown enums and unknown parameters. |
| `IssueRetrievalEdgeCaseTest` | Unknown issue numbers and projects, malformed identifiers (`0`, `-1`, `abc`, `1abc`, `%20`), decimal-identifier truncation, the `iid`-versus-`id` trap, and non-reuse of issue numbers after a delete. |
| `IssueUpdateEdgeCaseTest` | Empty updates, partial updates leaving other fields untouched, explicitly clearing a field, invalid state transitions, idempotent closes, editing a closed issue, and validation parity between create and update. |
| `IssueDeletionEdgeCaseTest` | Double deletes, deleting what never existed, deletion propagating to the listing, and deleting a closed issue. |
| `IssueListingTest` | Label/state/search filters, sorting in both directions, pagination with `x-total` / `x-total-pages` / `x-next-page` headers, pages past the end, the `per_page` cap, and rejection of out-of-range filter values. |
| `IssueAuthorizationTest` | Invalid tokens, anonymous reads/writes/deletes against a private project, the 404-versus-401 asymmetry between read and write paths, and confidential issues remaining visible to their author. |
| `issue_lifecycle.feature` | The CRUD lifecycle told as a story: raising, finding, revising, classifying, resolving, reopening and withdrawing an issue. |
| `issue_validation.feature` | The input rules stated in business language — an issue must say something, a title has a limit, input is stored verbatim (emoji and Japanese included), duplicate labels collapse, a revision leaves alone what it does not mention. |
| `issue_access_control.feature` | What an anonymous or badly-credentialled caller is allowed to learn about a private project. |
| `issue_known_defects.feature` | The four defects below, tagged `@defect`. |

Every test carries a `@DisplayName` stating the contract it pins down, and a comment explaining
*why* that behaviour is worth testing rather than restating what the code does. Every write is
verified by a follow-up read: a response that echoes an attribute is not proof that the server
stored it.

---

## What the tests found

Four behaviours where the live API departs from what its documentation implies. Two are defects
serious enough to report; two are traps worth knowing about. Each has a test that pins it down.

### 1. Repeating a not-yet-existing label returns HTTP 500

`POST /projects/:id/issues` with `labels=foo,foo` — where `foo` does not exist in the project yet —
fails with `500 Internal Server Error`. GitLab tries to create the label twice in one request and
the resulting constraint violation is never handled.

The failure is conditional on the label being new, which is what makes it easy to miss: the very
same request succeeds on a second attempt, because by then the label exists and de-duplication works
normally. Sending a label twice is at worst redundant input, and a 500 leaves the caller unable to
tell whether the issue was created.

Covered by `IssueCreationValidationTest.repeatingANewLabelCausesServerError`.

### 2. A decimal issue number silently resolves to a different issue

`GET /projects/:id/issues/1.5` returns **issue 1**, with HTTP 200. `…/issues/2.5` returns issue 2.
The request addressed a resource that does not exist and the API answered with a different one —
the worst available outcome, because nothing signals the mistake to a client that computed an
identifier wrongly.

This is not general leniency: `…/issues/1abc` is properly refused with `400 issue_iid is invalid`.
The parser specifically discards a fractional part.

Covered by `IssueRetrievalEdgeCaseTest.truncatesDecimalIssueIdentifier`.

### 3. An invalid due date is discarded instead of rejected

`due_date=2030-02-30`, `2030-13-01`, `not-a-date` and `yesterday` all return **201 Created** with
`due_date: null`. The caller gets a success response and an issue with no deadline.

Worse, some non-ISO formats are *reinterpreted* rather than dropped: `31-12-2030` and `2030/12/31`
are both stored as `2030-12-31`. A client sending the US month-first `03-04-2030` would have it
silently stored as 3 April rather than 4 March.

Covered by `silentlyIgnoresUnparseableDueDate` and `reinterpretsAmbiguousDueDateFormats`.

### 4. Boolean parameters accept far more than `true` and `false`

`confidential` accepts `true`, `false`, `1`, `0`, `yes`, `no`, `on`, `off`, `t`, `f` and mixed case
(`TRUE`, `Yes`). It rejects `maybe`, `2`, `-1` and the empty string with `400`. The boundary is not
obvious, and it matters for any client passing user input straight through.

Covered by `coercesBooleanSynonyms` and `rejectsNonBooleanConfidentialFlag`.

All four are asserted at both altitudes: as JUnit tests, and as `@defect`-tagged scenarios in
[`issue_known_defects.feature`](src/test/resources/features/issue_known_defects.feature), where they
read as plain statements of what the API does today.

These tests assert the *current, defective* behaviour on purpose, and say so in their names and
comments. That way the suite documents the bug and fails the moment GitLab fixes it — which is the
signal to delete the test and extend the correct-behaviour one. Run `mvn test -Dtest=RunCucumberTest
-Dcucumber.filter.tags='not @defect'` to see the suite as it would look once they are fixed.

---

## Requirements

- **Java 17** (JDK)
- **Maven 3.6+**
- A **GitLab account** and an **OAuth2 or personal access token** with the `api` scope

---

## Configuration

Every setting can be given as an environment variable or as a `-D` system property; the system
property wins, which keeps local overrides easy while CI uses secrets.

| Environment variable | System property | Default | Purpose |
| --- | --- | --- | --- |
| `GITLAB_TOKEN` | `gitlab.token` | *(required)* | OAuth2 / personal access token with the `api` scope. |
| `GITLAB_BASE_URL` | `gitlab.base.url` | `https://gitlab.com/api/v4` | Point the suite at a self-managed instance. |
| `GITLAB_PROJECT_ID` | `gitlab.project.id` | *(unset)* | Run against an existing project instead of creating one. Nothing is deleted in this mode. |
| `GITLAB_PROJECT_KEEP` | `gitlab.project.keep` | `false` | Keep the generated project after the run, for debugging. |
| `GITLAB_LOG_ALL` | `gitlab.log.all` | `false` | Log every request and response to the console. Independently of this, every request and response is attached to the Allure report, and failing assertions carry the response body in their message. The `Authorization` header is masked everywhere — console, report and all. |

If no token is configured the run fails immediately with an actionable message, rather than
producing a screen full of `401`s.

---

## Running the tests

```bash
# Everything
mvn test

# One suite
mvn test -Dtest=IssueCrudTest

# One test
mvn test -Dtest=IssueCrudTest#deletesIssue

# Only the BDD scenarios, or only the JUnit tests
mvn test -Dtest=RunCucumberTest
mvn test -Dtest='Issue*Test'

# Only the scenarios covering known defects, or everything except them
mvn test -Dtest=RunCucumberTest -Dcucumber.filter.tags='@defect'
mvn test -Dtest=RunCucumberTest -Dcucumber.filter.tags='not @defect'

# Against a self-managed instance and an existing project, with full HTTP logging
mvn test -Dgitlab.base.url=https://gitlab.example.com/api/v4 \
         -Dgitlab.project.id=42 \
         -Dgitlab.log.all=true

# Faster, at the cost of a heavier load on the API (4 workers, see junit-platform.properties)
mvn test -Djunit.jupiter.execution.parallel.enabled=true
```

### Reports

Surefire output lands in `target/surefire-reports`. Allure results are written on every run:

```bash
mvn allure:report     # renders target/site/allure-maven-plugin
mvn allure:serve      # renders and opens it in a browser
```

---

## Continuous integration

[`.github/workflows/api-tests.yml`](.github/workflows/api-tests.yml) runs the suite on every push
and pull request, nightly at 06:00 UTC, and on demand. The nightly run is deliberate: this suite
tests a third-party API, so a failure can originate on GitLab's side without anything in this
repository having changed.

The workflow needs one repository secret, `GITLAB_TOKEN`, and optionally a `GITLAB_BASE_URL`
repository variable. Each run publishes a per-suite results table to the job summary — naming every
test that failed, and why, so a red build is diagnosable without downloading anything — and uploads
the Surefire reports, the raw Allure results and a rendered Allure HTML report as artifacts.
Pull requests opened from a fork cannot read the secret, so the run stops early with a clear
message instead of failing on authentication.

Those artifacts are published from a public repository, and GitHub scrubs secrets from logs but not
from artifacts. The suite therefore masks the `Authorization` header before anything is attached to
a report; see **Reporting** below.

---

## Design notes

**Layering.** `client` owns transport (base URI, auth, serialisation, logging, reporting) and
endpoints; `model` owns request and response shapes; `support` owns the fixture lifecycle; `tests`
and `bdd` own expectations. No test or step definition builds a URL or sets a header, and no client
class knows what "correct" looks like. A step definition translates a sentence into an `IssuesApi`
call and the response back into an assertion — nothing more, which is what keeps the BDD layer from
becoming a second implementation.

**One fixture for both runners.** The project fixture is a process-wide singleton
(`support/TestProject`) rather than a JUnit `Extension`, because a JUnit extension is invisible to
Cucumber and the two run in the same forked JVM. Whichever runner asks first creates the project;
a shutdown hook removes it.

**Reporting never sees the credential.** `client/ReportingFilter` attaches every request and
response to the Allure report with `Authorization` replaced by `***`. It exists instead of
`AllureRestAssured`, which renders request headers verbatim — including a copy-pasteable `curl`
line carrying the token — into results the pipeline then publishes as a downloadable artifact. The
console logging filters are configured with the same header blacklisted.

**Endpoint methods return the raw `Response`.** A wrapper that threw on a non-2xx status would make
most of this assignment unwritable — half the value is in what the API does with input it should
refuse.

**Specifications are built per call, never shared.** REST Assured accumulates path parameters on a
specification as it is used, so re-using one across the several calls a scenario makes fails with
"redundant path parameters". The step definitions therefore hold a `Supplier<RequestSpecification>`
— the *kind* of caller — not an instance.

**Request bodies are maps, built by `IssuePayload`.** A typed DTO cannot express the payloads the
edge-case tests need to send: wrong types, unknown fields, missing required fields. The builder
keeps those readable while `field(name, value)` stays available as an escape hatch.

**Test isolation.** The project is created once per run and deleted on exit, so the suite never
depends on — or pollutes — pre-existing data. Where assertions depend on *which* issues exist
(listing, pagination, sorting), the fixtures carry a run-unique label and the query is scoped to it,
so those tests stay correct even when the suite is parallelised.

Labels get the same treatment (`ApiTest.uniqueLabel`), and for a less obvious reason: a label is not
per-issue data. The first issue to mention one creates it for the whole project, so two tests
sharing a literal label name share state — and in parallel they race to create it, which lands
straight on defect 1 above. Unique names are what make "every test provisions its own data" true
rather than nearly true.

**Timeouts.** Connection and socket timeouts are set explicitly (10 s / 30 s). Without them a
stalled connection blocks on the JDK default, which is effectively forever: one hung request would
consume the pipeline's whole time budget, and the run would then be killed before the cleanup hook
could remove the throw-away project.

**Expectations come from observed behaviour, not documentation.** Every assertion was run against
gitlab.com and corrected where the live API disagreed with the docs. Four such disagreements are
written up under [What the tests found](#what-the-tests-found); a suite that had only been reasoned
about would have shipped those as false expectations.

**Response validation.** The single-issue read is checked against
[`issue-schema.json`](src/test/resources/schemas/issue-schema.json) in addition to field-level
assertions. Field assertions catch wrong values; the schema catches contract drift — a field
disappearing or changing type.

---

## Assumptions and trade-offs

- **Tokens.** GitLab accepts OAuth2 tokens and personal access tokens interchangeably as bearer
  tokens, so the suite uses a single `Authorization: Bearer` scheme for either.
- **The token may create and delete projects.** This buys real isolation. If that is not acceptable
  in your environment, set `GITLAB_PROJECT_ID` to an existing project and nothing will be created or
  deleted. Note that issue deletion requires Owner rights on the project.
- **Cleanup is a two-step delete.** On gitlab.com a plain `DELETE /projects/:id` only *schedules*
  deletion: the project is renamed `<path>-deletion_scheduled-<id>` and lingers for the retention
  period, so a per-run project would pile up in the namespace for weeks. The fixture follows up with
  `permanently_remove=true` and the project's current full path, then reads the project back to
  confirm it is gone. On instances that delete immediately, the follow-up read returns 404 and the
  second step is skipped.
- **Cleanup is best-effort, and says so when it fails.** It runs in a shutdown hook, which does not
  run when the JVM is killed outright — a cancelled or timed-out pipeline run does exactly that. A
  leftover project is therefore always possible; what the fixture guarantees is that you hear about
  it, by printing the project id to stderr instead of failing silently. Sweeping up older strays
  automatically was deliberately not added: deleting projects by name pattern on someone's account
  is not something a test suite should decide to do on its own.
- **Live API, no mocks.** Testing a third-party contract against a mock of that contract proves only
  that the mock is self-consistent. The cost is that runs need network access and a valid token, and
  that a GitLab outage fails the build — which, for a suite whose job is to detect changes in that
  API, is the correct outcome.
- **Sequential by default.** Every test provisions its own data — its own issues *and* its own
  labels — so the suite is safe to parallelise
  (`-Djunit.jupiter.execution.parallel.enabled=true`), but it runs single-threaded by default to
  stay comfortably inside GitLab's rate limits. The switch lives in
  [`junit-platform.properties`](src/test/resources/junit-platform.properties) only: setting the same
  key through Surefire's `systemPropertyVariables` as well would override the `-D` and quietly
  ignore it.
- **One test asserts a *set* of acceptable status codes.** `refusesMalformedIssueIdentifier` accepts
  either `400` or `404`, because the code depends on whether the value fails type coercion (`abc`
  → 400) or coerces but matches nothing (`0` → 404). What the test actually guards is the property
  that matters — the API never falls back to some "nearest" issue. Everywhere else, including the
  authorisation tests, the expected code is asserted exactly, because a live run established what it
  is: anonymous *reads* of a private project return `404` (hiding its existence) while anonymous
  *writes* return `401`.
- **Not covered, deliberately:** attachments, notes/comments, time-tracking, milestones, assignees
  and epics. They are separate endpoints with their own semantics; within the assignment's time
  budget, depth on the Issues CRUD surface is worth more than breadth across adjacent APIs.
- **Not covered, for want of a second account:** the `403` path — a valid token belonging to someone
  with no access to the project — and the other half of confidentiality, where a confidential issue
  must be invisible to a non-member. Both need a second credential, which the assignment's setup
  does not provide. The suite covers what one token can prove: anonymous and invalid-token callers.
- **Also not covered:** keyset pagination, the `created_after` / `updated_after` date filters, and
  `in=description`. These are real gaps rather than deliberate exclusions; they are the next things
  worth adding.
