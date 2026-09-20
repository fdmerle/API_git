@defect
Feature: Behaviour that GitLab gets wrong today
  These scenarios assert what the live API actually does, not what it should do. They exist so the
  defects are visible as living documentation, and so the suite fails the day GitLab fixes them -
  which is the signal to delete the scenario and fold the case into the correct-behaviour features.
  All four were reproduced against gitlab.com on 2026-09-20.

  Background:
    Given I am an authorised member of the project

  Scenario: Repeating a brand-new label crashes the request
    # GitLab tries to create the same label twice in one request; the constraint violation is
    # unhandled. The identical request succeeds on a retry, once the label exists.
    When I raise an issue repeating a label that does not exist yet
    Then the request fails with a server error

  Scenario: A fractional issue number quietly resolves to a different issue
    # The request addressed an issue that does not exist and got a different one back, with 200.
    Given an issue titled "Truncation fixture" exists
    When I look that issue up by its number with ".5" appended
    Then the request succeeds
    And the issue returned is the one I created

  Scenario Outline: An unusable due date is dropped instead of refused
    # The caller gets 201 and an issue with no deadline at all.
    When I raise an issue with:
      | title    | Due date fixture |
      | due_date | <due_date>       |
    Then the request succeeds
    And the issue has no due date

    Examples:
      | due_date   |
      | not-a-date |
      | 2030-13-01 |
      | 2030-02-30 |
      | yesterday  |

  Scenario Outline: A non-ISO due date is reinterpreted rather than refused
    # "31-12-2030" is read day-first. A caller sending month-first "03-04-2030" would silently get
    # 3 April instead of 4 March.
    When I raise an issue with:
      | title    | Ambiguous due date fixture |
      | due_date | <submitted>                |
    Then the request succeeds
    And the issue is due on "<stored>"

    Examples:
      | submitted  | stored     |
      | 31-12-2030 | 2030-12-31 |
      | 2030/12/31 | 2030-12-31 |

  Scenario Outline: A flag accepts far more words than true and false
    # The accepted set is much wider than the documentation implies - and the boundary is not
    # obvious, since "on" and "t" are taken while "2" and "maybe" are refused. It matters for any
    # client that passes user input straight through to the API.
    When I raise an issue with:
      | title        | Boolean synonym fixture |
      | confidential | <value>                 |
    Then the request succeeds
    And the issue <expectation> confidential

    Examples:
      | value | expectation |
      | yes   | is          |
      | on    | is          |
      | no    | is not      |
      | off   | is not      |
