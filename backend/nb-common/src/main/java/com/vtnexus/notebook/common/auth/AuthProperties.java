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
        private Jwt jwt = new Jwt();
        private Ui ui = new Ui();
        private String authorizationEndpoint = "";
        private String tokenEndpoint = "";
        private String endSessionEndpoint = "";
        private String redirectUri = "";
        private Duration sessionTimeout = Duration.ofHours(4);
        private String serviceClientId = "notebook-api";
        private String serviceClientSecret = "";
    }

    @Getter
    @Setter
    public static class Jwt {
        private String issuer = "";
        private List<String> audience = new ArrayList<>();
        private String jwksUrl = "";
        private String usernameClaim = "preferred_username";
        private List<String> allowedAlgorithms = new ArrayList<>(List.of("RS256"));
        private Duration clockSkew = Duration.ofSeconds(30);
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(5);
    }

    @Getter
    @Setter
    public static class Ui {
        private String clientId = "kyuubi-notebook";
        private String clientSecret = "";
        private String scope = "openid profile email";
    }

    @Getter
    @Setter
    public static class LdapProperties {
        private String url = "";
        private String baseDn = "";
        private String domain = "";
        private String bindDn = "";
        private String bindPassword = "";
        private String guidKey = "uid";
        private String userDnPattern = "";
        private String groupDnPattern = "";
        private String groupClassKey = "groupOfNames";
        private String groupMembershipKey = "member";
        private String userMembershipKey = "";
        private List<String> groupFilter = new ArrayList<>();
        private List<String> userFilter = new ArrayList<>();
        private String customLdapQuery = "";
        private boolean startTls = false;
        private String trustStore = "";
        private String trustStorePassword = "";
        private String trustStoreType = "PKCS12";
        private boolean trustAll = false;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);
        private ServiceAccount serviceAccount = new ServiceAccount();
    }

    @Getter
    @Setter
    public static class ServiceAccount {
        private String username = "";
        private String password = "";
    }
}
