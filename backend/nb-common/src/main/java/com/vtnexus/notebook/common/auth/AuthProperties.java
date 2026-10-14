package com.vtnexus.notebook.common.auth;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "notebook.auth")
public class AuthProperties {

    private String mode; // oidc | no-oidc | ldap
    private String usernameClaim = "preferred_username";
    private String cacheTtl = "60s";
    private String sessionTtl = "8h";
    private String recheck = "10m";
    private boolean allowUnverified = false;

    private OidcProperties oidc = new OidcProperties();
    private LdapProperties ldap = new LdapProperties();

    @Getter
    @Setter
    public static class OidcProperties {
        private String issuer;
        private String clientId;
        private List<String> scopes = new ArrayList<>(List.of("openid", "profile"));
        private String serviceClientId;
        private String serviceClientSecret;
    }

    @Getter
    @Setter
    public static class LdapProperties {
        private String url = "";
        private boolean startTls = false;
        private String trustStore = "";
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
        private String adminGroup = "";
        private String connectTimeout = "5s";
        private String readTimeout = "10s";
    }
}
