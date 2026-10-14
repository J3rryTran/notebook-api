package com.vtnexus.notebook.common.auth;

public final class KyuubiCredentialHolder {

    private static final ThreadLocal<String> AUTH_HEADER_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME_HOLDER = new ThreadLocal<>();

    private KyuubiCredentialHolder() {}

    public static void set(String authHeader, String username) {
        AUTH_HEADER_HOLDER.set(authHeader);
        USERNAME_HOLDER.set(username);
    }

    public static String getAuthHeader() {
        return AUTH_HEADER_HOLDER.get();
    }

    public static String getUsername() {
        return USERNAME_HOLDER.get();
    }

    public static void clear() {
        AUTH_HEADER_HOLDER.remove();
        USERNAME_HOLDER.remove();
    }
}
