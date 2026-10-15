package com.vtnexus.notebook.store.service;

import com.vtnexus.notebook.common.auth.AuthProperties;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.ldap.AuthenticationException;
import org.springframework.ldap.CommunicationException;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.DefaultTlsDirContextAuthenticationStrategy;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.ldap.support.LdapEncoder;
import org.springframework.stereotype.Service;

import javax.naming.directory.DirContext;
import javax.naming.ldap.LdapName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class LdapAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(LdapAuthenticator.class);

    private final AuthProperties authProperties;
    private final Environment environment;

    // Singleton pooled LdapContextSource và LdapTemplate
    private volatile LdapContextSource sharedContextSource;
    private volatile LdapTemplate sharedLdapTemplate;

    public LdapAuthenticator(AuthProperties authProperties, Environment environment) {
        this.authProperties = authProperties;
        this.environment = environment;
    }

    public record LdapUser(String canonicalUsername, boolean isAdmin) {}

    private synchronized void initContextSourceIfNeeded() {
        if (sharedContextSource != null) {
            return;
        }

        AuthProperties.LdapProperties ldap = authProperties.getLdap();
        if (ldap.getUrl() == null || ldap.getUrl().isBlank()) {
            return;
        }

        LdapContextSource cs = new LdapContextSource();
        cs.setUrl(ldap.getUrl());
        cs.setBase(ldap.getBaseDn() != null ? ldap.getBaseDn() : "");

        // 7. Timeout & Pool
        Map<String, Object> baseEnv = new HashMap<>();
        long connectTimeoutMs = parseTimeoutMs(ldap.getConnectTimeout(), 5000);
        long readTimeoutMs = parseTimeoutMs(ldap.getReadTimeout(), 10000);
        baseEnv.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(connectTimeoutMs));
        baseEnv.put("com.sun.jndi.ldap.read.timeout", String.valueOf(readTimeoutMs));

        // 6. TLS
        boolean isDev = environment.acceptsProfiles(Profiles.of("dev"));
        if (ldap.getUrl().startsWith("ldaps://")) {
            if (ldap.isTrustAll() && isDev) {
                configureTrustAllSsl(baseEnv);
            } else if (ldap.getTrustStore() != null && !ldap.getTrustStore().isBlank()) {
                configureCustomTrustStore(baseEnv, ldap.getTrustStore());
            }
        } else if (ldap.isStartTls()) {
            cs.setAuthenticationStrategy(new DefaultTlsDirContextAuthenticationStrategy());
        }

        cs.setBaseEnvironmentProperties(baseEnv);

        // Manager DN nếu có
        if (ldap.getManagerDn() != null && !ldap.getManagerDn().isBlank()) {
            cs.setUserDn(ldap.getManagerDn());
            cs.setPassword(ldap.getManagerPassword() != null ? ldap.getManagerPassword() : "");
        }

        cs.setPooled(true);
        cs.afterPropertiesSet();

        this.sharedContextSource = cs;
        this.sharedLdapTemplate = new LdapTemplate(cs);
    }

    private long parseTimeoutMs(Duration duration, long defaultMs) {
        if (duration == null) return defaultMs;
        long ms = duration.toMillis();
        return ms > 0 ? ms : defaultMs;
    }

    private void configureTrustAllSsl(Map<String, Object> baseEnv) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    }
            }, new java.security.SecureRandom());
            baseEnv.put("java.naming.ldap.factory.socket", DummySSLSocketFactory.class.getName());
        } catch (Exception ex) {
            log.warn("Failed to configure trust-all SSL: {}", ex.getMessage());
        }
    }

    private void configureCustomTrustStore(Map<String, Object> baseEnv, String trustStorePath) {
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            try (FileInputStream fis = new FileInputStream(trustStorePath)) {
                ks.load(fis, null);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            // Java JNDI socket factory configuration
        } catch (Exception ex) {
            log.warn("Failed to load trustStore: {}", ex.getMessage());
        }
    }

    public static class DummySSLSocketFactory extends javax.net.ssl.SSLSocketFactory {
        private static javax.net.ssl.SSLSocketFactory defaultFactory;
        static {
            try {
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, new javax.net.ssl.TrustManager[]{
                        new javax.net.ssl.X509TrustManager() {
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                            public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                            public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                        }
                }, new java.security.SecureRandom());
                defaultFactory = sc.getSocketFactory();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        public static javax.net.SocketFactory getDefault() { return new DummySSLSocketFactory(); }
        @Override public String[] getDefaultCipherSuites() { return defaultFactory.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return defaultFactory.getSupportedCipherSuites(); }
        @Override public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            return defaultFactory.createSocket(s, host, port, autoClose);
        }
        @Override public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
            return defaultFactory.createSocket(host, port);
        }
        @Override public java.net.Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws java.io.IOException {
            return defaultFactory.createSocket(host, port, localHost, localPort);
        }
        @Override public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return defaultFactory.createSocket(host, port);
        }
        @Override public java.net.Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort) throws java.io.IOException {
            return defaultFactory.createSocket(address, port, localAddress, localPort);
        }
    }

    public LdapUser authenticate(String username, String password) {
        // 1. Escape & Validate
        if (username == null || username.isBlank() || username.length() > 255) {
            throw new NotebookApiException("INVALID_CREDENTIALS", "Invalid credentials", HttpStatus.UNAUTHORIZED);
        }
        for (char c : username.toCharArray()) {
            if (Character.isISOControl(c)) {
                throw new NotebookApiException("INVALID_CREDENTIALS", "Invalid credentials", HttpStatus.UNAUTHORIZED);
            }
        }

        // 3. Mật khẩu rỗng -> 401 ngay lập tức
        if (password == null || password.isEmpty()) {
            throw new NotebookApiException("INVALID_CREDENTIALS", "Invalid credentials", HttpStatus.UNAUTHORIZED);
        }

        AuthProperties.LdapProperties ldap = authProperties.getLdap();
        if (ldap.getUrl() == null || ldap.getUrl().isBlank()) {
            throw new NotebookApiException("AUTH_BACKEND_UNAVAILABLE", "LDAP unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        }

        initContextSourceIfNeeded();

        try {
            String targetUserDn;
            String canonicalName;

            if (ldap.getUserDnPattern() != null && !ldap.getUserDnPattern().isBlank()) {
                // Nhánh userDnPattern: escape username bằng LdapEncoder.nameEncode
                String encodedUser = LdapEncoder.nameEncode(username);
                targetUserDn = ldap.getUserDnPattern().replace("{0}", encodedUser);
                if (ldap.getBaseDn() != null && !ldap.getBaseDn().isBlank() && !targetUserDn.endsWith(ldap.getBaseDn())) {
                    targetUserDn = targetUserDn + "," + ldap.getBaseDn();
                }

                // Bind với mật khẩu
                bindUser(targetUserDn, password);

                // Đọc lại entry để lấy tên chuẩn usernameAttribute (B3.4)
                canonicalName = readCanonicalUsername(targetUserDn, ldap.getUsernameAttribute(), username);
            } else {
                // Nhánh userSearch: 2. Tìm DN thật qua ContextMapper và getNameInNamespace()
                String searchFilter = ldap.getUserSearchFilter();
                if (searchFilter == null || searchFilter.isBlank()) {
                    searchFilter = "(" + ldap.getUsernameAttribute() + "={0})";
                }

                List<SearchResultEntry> results = sharedLdapTemplate.search(
                        LdapQueryBuilder.query()
                                .base(ldap.getUserSearchBase() != null ? ldap.getUserSearchBase() : "")
                                .filter(searchFilter, username),
                        (ContextMapper<SearchResultEntry>) ctx -> {
                            DirContextAdapter adapter = (DirContextAdapter) ctx;
                            String dn = adapter.getNameInNamespace();
                            String attrVal = adapter.getStringAttribute(ldap.getUsernameAttribute());
                            return new SearchResultEntry(dn, attrVal != null ? attrVal : username);
                        }
                );

                if (results == null || results.size() != 1) {
                    throw new NotebookApiException("INVALID_CREDENTIALS", "Invalid credentials", HttpStatus.UNAUTHORIZED);
                }

                SearchResultEntry entry = results.get(0);
                targetUserDn = entry.dn();
                canonicalName = entry.canonicalUsername();

                // Bind với mật khẩu
                bindUser(targetUserDn, password);
            }

            // 5. Kiểm tra adminGroup nếu có cấu hình
            boolean isAdmin = checkAdminGroup(canonicalName, targetUserDn, ldap);

            return new LdapUser(canonicalName, isAdmin);

        } catch (NotebookApiException ex) {
            throw ex;
        } catch (AuthenticationException ex) {
            log.warn("LDAP authentication failed for user {}: {}", username, ex.getMessage());
            throw new NotebookApiException("INVALID_CREDENTIALS", "Invalid credentials", HttpStatus.UNAUTHORIZED);
        } catch (CommunicationException ex) {
            log.warn("LDAP backend unavailable for user {}: {}", username, ex.getMessage());
            throw new NotebookApiException("AUTH_BACKEND_UNAVAILABLE", "LDAP unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
            if (msg.contains("timeout") || msg.contains("connection") || msg.contains("refused") || msg.contains("unavailable")) {
                log.warn("LDAP backend communication error for user {}: {}", username, ex.getMessage());
                throw new NotebookApiException("AUTH_BACKEND_UNAVAILABLE", "LDAP unavailable", HttpStatus.SERVICE_UNAVAILABLE);
            }
            log.warn("LDAP error for user {}: {}", username, ex.getMessage());
            throw new NotebookApiException("INVALID_CREDENTIALS", "Invalid credentials", HttpStatus.UNAUTHORIZED);
        }
    }

    private void bindUser(String userDn, String password) throws Exception {
        LdapContextSource userContext = new LdapContextSource();
        userContext.setUrl(authProperties.getLdap().getUrl());
        userContext.setUserDn(userDn);
        userContext.setPassword(password);
        userContext.setPooled(false);

        Map<String, Object> baseEnv = new HashMap<>();
        long connectTimeoutMs = parseTimeoutMs(authProperties.getLdap().getConnectTimeout(), 5000);
        long readTimeoutMs = parseTimeoutMs(authProperties.getLdap().getReadTimeout(), 10000);
        baseEnv.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(connectTimeoutMs));
        baseEnv.put("com.sun.jndi.ldap.read.timeout", String.valueOf(readTimeoutMs));
        userContext.setBaseEnvironmentProperties(baseEnv);

        userContext.afterPropertiesSet();
        DirContext ctx = userContext.getReadOnlyContext();
        ctx.close();
    }

    private String readCanonicalUsername(String userDn, String usernameAttr, String defaultName) {
        try {
            DirContextAdapter adapter = (DirContextAdapter) sharedLdapTemplate.lookup(userDn);
            String val = adapter.getStringAttribute(usernameAttr);
            return (val != null && !val.isBlank()) ? val : defaultName;
        } catch (Exception e) {
            return defaultName;
        }
    }

    private boolean checkAdminGroup(String canonicalName, String userDn, AuthProperties.LdapProperties ldap) {
        if (ldap.getAdminGroup() == null || ldap.getAdminGroup().isBlank()) {
            return false;
        }

        try {
            String memberAttr = ldap.getGroupMemberAttribute() != null ? ldap.getGroupMemberAttribute() : "member";
            String searchBase = ldap.getGroupSearchBase() != null ? ldap.getGroupSearchBase() : "";

            // Tham số là DN nếu attribute là member hoặc uniqueMember, là canonicalName nếu là memberUid (B3.5)
            String memberParam = ("memberUid".equalsIgnoreCase(memberAttr)) ? canonicalName : userDn;

            String groupFilter = "(&(objectClass=*)(" + memberAttr + "={0}))";

            List<String> userGroups = sharedLdapTemplate.search(
                    LdapQueryBuilder.query().base(searchBase).filter(groupFilter, memberParam),
                    (ContextMapper<String>) ctx -> ((DirContextAdapter) ctx).getNameInNamespace()
            );

            LdapName configuredAdminGroup = new LdapName(ldap.getAdminGroup());
            for (String groupDn : userGroups) {
                LdapName groupName = new LdapName(groupDn);
                if (groupName.equals(configuredAdminGroup)) {
                    return true;
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to check LDAP admin group for user {}: {}", canonicalName, ex.getMessage());
        }
        return false;
    }

    private record SearchResultEntry(String dn, String canonicalUsername) {}
}
