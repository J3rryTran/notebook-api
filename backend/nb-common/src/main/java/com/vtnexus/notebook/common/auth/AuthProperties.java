package com.vtnexus.notebook.common.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "notebook.auth")
public class AuthProperties {

    private String mode;
    private String usernameClaim = "preferred_username";
    private Duration cacheTtl = Duration.ofSeconds(60);
    private Duration sessionTtl = Duration.ofHours(8);
    private Duration recheck = Duration.ofMinutes(10);
    private boolean allowUnverified = false;
    private volatile Boolean verifiedOverride = null;

    public boolean isEffectiveVerified() {
        if (verifiedOverride != null) {
            return verifiedOverride;
        }
        return !allowUnverified;
    }

    private OidcProperties oidc = new OidcProperties();
    private LdapProperties ldap = new LdapProperties();

    @Getter
    @Setter
    public static class OidcProperties {
        private String issuer = "";
        private String clientId = "kyuubi-notebook";
        private List<String> scopes = new ArrayList<>(List.of("openid", "profile"));
        private String redirectPath = "/auth/callback";
        private String serviceClientId = "notebook-api";
        private String serviceClientSecret = "";
    }

    @Getter
    @Setter
    public static class LdapProperties {
        private String url = "";
        private boolean startTls = false;
        private String trustStore = "";
        private String trustStorePassword = "";
        private String trustStoreType = "PKCS12";
        private boolean trustAll = false;
        private String baseDn = "";
        private String userDnPattern = "";
        private String userSearchBase = "ou=people";
        private String userSearchFilter = "";
        private String usernameAttribute = "uid";
        private String managerDn = "";
        private String managerPassword = "";
        private String groupSearchBase = "ou=groups";
        private String groupMemberAttribute = "member";
        private String groupObjectClass = "*";
        private String adminGroup = "";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);
    }
}
