package com.abnamro.assignment.model;

/** Minimal user projection, as embedded in issue and project payloads. */
public record User(Long id, String username, String name, String state) {
}
