package com.vtnexus.notebook.common.auth;

public enum AuthMode {
    OIDC("oidc"),
    NO_OIDC("no-oidc"),
    LDAP("ldap");

    private final String value;

    AuthMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static AuthMode fromString(String text) {
        if (text == null) {
            return null;
        }
        for (AuthMode mode : AuthMode.values()) {
            if (mode.value.equalsIgnoreCase(text.trim())) {
                return mode;
            }
        }
        return null;
    }
}
