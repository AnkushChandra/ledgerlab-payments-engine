package com.ledgerlab.shared.security;

/** SpEL expressions for {@code @PreAuthorize}, kept in one place so policies are easy to audit. */
public final class Roles {

    public static final String VIEWER = "hasRole('VIEWER')";
    public static final String OPERATIONS = "hasRole('OPERATIONS')";
    public static final String ADMIN = "hasRole('ADMIN')";

    private Roles() {}
}
