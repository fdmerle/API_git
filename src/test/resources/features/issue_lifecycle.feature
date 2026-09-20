Feature: Managing the lifecycle of an issue
  As a client of the GitLab Issues API
  I want to raise, find, revise and withdraw issues
  So that a project's backlog reflects what is actually outstanding

  Background:
    Given I am an authorised member of the project

  Scenario: Raising an issue with nothing but a title
    When I raise an issue titled "The login button does nothing"
    Then the request succeeds
    And the issue is titled "The login button does nothing"
    And the issue is open
    And the issue carries no labels
    And the issue has no due date

  Scenario: Raising a fully described issue
    When I raise an issue with:
      | title        | Checkout fails for orders over 1000 EUR |
      | description  | Reproduced on staging, see attached log |
      | labels       | bug,checkout                            |
      | confidential | true                                    |
      | due_date     | 2030-12-31                              |
    Then the request succeeds
    And the issue is titled "Checkout fails for orders over 1000 EUR"
    And the issue carries the labels "bug,checkout"
    And the issue is confidential
    And the issue is due on "2030-12-31"

  Scenario: Finding an issue that was raised earlier
    Given an issue titled "Session expires too quickly" exists
    When I look up that issue
    Then the request succeeds
    And the issue is titled "Session expires too quickly"

  Scenario: Revising the wording of an issue
    Given an issue titled "Vague original wording" exists
    When I revise that issue with:
      | title       | Password reset email never arrives    |
      | description | Happens for addresses on our own domain |
    Then the request succeeds
    And the issue is titled "Password reset email never arrives"
    And looking the issue up again shows the revision

  Scenario: Classifying an issue after it was raised
    Given an issue titled "Needs triage" exists
    When I add the labels "bug,urgent" to that issue
    Then the issue carries the labels "bug,urgent"
    When I remove the label "urgent" from that issue
    Then the issue carries the labels "bug"

  Scenario: Resolving an issue and reopening it when it recurs
    Given an issue titled "Intermittent timeout" exists
    When I close that issue
    Then the issue is closed
    And the issue records when it was closed
    When I reopen that issue
    Then the issue is open
    And the issue no longer records when it was closed

  Scenario: Withdrawing an issue that should never have been raised
    Given an issue titled "Filed against the wrong project" exists
    When I delete that issue
    Then the request succeeds
    And the issue can no longer be found

  Scenario: Finding issues by what they are about
    Given an issue titled "Search finds me by title" exists
    When I search the project for that issue's title
    # "and nothing else": a search parameter that was ignored would return the whole project,
    # which still "contains" the issue.
    Then the listing contains that issue and nothing else

  Scenario Outline: Separating outstanding work from finished work
    Given an issue titled "State filtering fixture" exists
    When I close that issue
    And I list the issues in state "<state>"
    Then the listing <expectation> that issue

    Examples:
      | state  | expectation   |
      | closed | contains      |
      | opened | does not list |
      | all    | contains      |
