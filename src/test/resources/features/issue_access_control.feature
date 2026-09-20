Feature: Keeping a private project private
  As the owner of a private project
  I want unauthorised callers turned away without learning anything
  So that neither the issues nor the project's existence leaks

  Scenario: A caller with a bad token is told the token is bad
    Given I am a caller with an invalid token
    When I list the issues in the project
    Then the request is refused as unauthenticated

  Scenario: An anonymous reader is not even told the project exists
    Given I am an anonymous caller
    When I list the issues in the project
    # 404 rather than 403: a 403 would confirm the project is there.
    Then the request is refused as not found
    And the refusal reveals no issue content

  Scenario: An anonymous caller cannot raise an issue
    Given I am an anonymous caller
    When I raise an issue titled "Should never be created"
    Then the request is refused as unauthenticated

  Scenario: An anonymous caller cannot withdraw someone else's issue
    Given I am an authorised member of the project
    And an issue titled "Protected from anonymous deletion" exists
    Given I am an anonymous caller
    When I delete that issue
    Then the request is refused as unauthenticated
    And the issue survives, still visible to an authorised member
