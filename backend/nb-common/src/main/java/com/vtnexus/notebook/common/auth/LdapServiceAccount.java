package com.vtnexus.notebook.common.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class LdapServiceAccount {

    private LdapServiceAccount() {
    }

    public static String basicHeader(AuthProperties authProperties) {
        AuthProperties.ServiceAccount sa = authProperties.getLdap().getServiceAccount();
        if (sa == null || sa.getUsername() == null || sa.getUsername().isBlank()
                || sa.getPassword() == null || sa.getPassword().isEmpty()) {
            throw new IllegalStateException("notebook.auth.ldap.service-account.username/password is not configured");
        }
        String raw = sa.getUsername().trim() + ":" + sa.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
