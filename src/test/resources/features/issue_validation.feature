Feature: Refusing input that would corrupt the backlog
  As a client of the GitLab Issues API
  I want invalid input to be refused rather than half-accepted
  So that no issue is ever stored in a state I did not ask for

  Background:
    Given I am an authorised member of the project

  Scenario: An issue must say something
    When I raise an issue with:
      | description | A body, but no title at all |
    Then the request is refused as invalid
    And the refusal mentions "title"

  Scenario: An empty title says nothing
    When I raise an issue titled ""
    Then the request is refused as invalid

  Scenario: A title made only of whitespace says nothing
    When I raise an issue with a title of only whitespace
    Then the request is refused as invalid

  Scenario: A title may be as long as the limit allows
    When I raise an issue with a title of 255 characters
    Then the request succeeds
    And the issue title is 255 characters long

  Scenario: A title beyond the limit is refused rather than quietly shortened
    When I raise an issue with a title of 256 characters
    Then the request is refused as invalid
    And the refusal mentions "title"

  Scenario Outline: Input is stored exactly as it was sent
    When I raise an issue titled "<title>"
    Then the request succeeds
    And the issue is titled "<title>"
    And looking the issue up again shows the same title

    Examples: Escaping is the reader's job, not the API's
      | title                            |
      | Emoji in the title 🐛🚀✅          |
      | 日本語のタイトル                          |
      | <script>alert('xss')</script>    |
      | SQL-ish '; DROP TABLE issues; -- |

  Scenario: Duplicate labels are collapsed once the label exists
    Given the label "already-here" exists in the project
    When I raise an issue with:
      | title  | Duplicate labels are collapsed    |
      | labels | already-here,already-here,another |
    Then the request succeeds
    And the issue carries the labels "already-here,another"

  Scenario: An unknown parameter is ignored rather than rejected
    When I raise an issue with:
      | title                | Tolerates what it does not know |
      | not_a_real_parameter | ignore me                       |
    Then the request succeeds
    And the issue is titled "Tolerates what it does not know"

  Scenario: Revising nothing is refused
    Given an issue titled "Nothing to change here" exists
    When I revise that issue with nothing
    Then the request is refused as invalid

  Scenario: A revision leaves alone what it does not mention
    Given an issue with:
      | title       | Partial revision fixture |
      | description | Keep me                  |
      | labels      | keep-me                  |
    When I revise that issue with:
      | title | Only the title changed |
    Then the issue is titled "Only the title changed"
    And the issue description is "Keep me"
    And the issue carries the labels "keep-me"

  Scenario Outline: A boolean must actually be a boolean
    When I raise an issue with:
      | title        | Boundary of boolean coercion |
      | confidential | <value>                      |
    Then the request is refused as invalid

    Examples:
      | value |
      | maybe |
      | 2     |
      | -1    |
