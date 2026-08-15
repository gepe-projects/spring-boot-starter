package com.gepe.app.user.api.dto;

/** Jenis kelamin pada profil — boundary-safe enum (AGENTS.md §3.6), dipakai DTO publik & entity internal. */
public enum Gender {
    MALE,
    FEMALE,
    OTHER,
    UNSPECIFIED
}
