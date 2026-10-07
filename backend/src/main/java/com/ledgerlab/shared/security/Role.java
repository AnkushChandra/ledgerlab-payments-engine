package com.ledgerlab.shared.security;

import java.util.List;

/** Organization roles. Higher roles include every permission of the lower ones. */
public enum Role {
    VIEWER,
    OPERATIONS,
    ADMIN;

    /** Spring Security authorities granted to this role, including inherited ones. */
    public List<String> authorities() {
        return switch (this) {
            case ADMIN -> List.of("ROLE_ADMIN", "ROLE_OPERATIONS", "ROLE_VIEWER");
            case OPERATIONS -> List.of("ROLE_OPERATIONS", "ROLE_VIEWER");
            case VIEWER -> List.of("ROLE_VIEWER");
        };
    }
}
