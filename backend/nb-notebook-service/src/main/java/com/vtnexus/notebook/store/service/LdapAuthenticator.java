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

import org.springframework.ldap.support.LdapUtils;

import javax.naming.Name;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.ldap.LdapName;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
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

        // 6. TLS: một SSLSocketFactory dùng cho cả ldaps:// lẫn StartTLS (B9)
        boolean isDev = environment.acceptsProfiles(Profiles.of("dev"));
        SSLSocketFactory sslFactory = buildSslSocketFactory(ldap, isDev);
        boolean ldaps = ldap.getUrl().startsWith("ldaps://");
        if (ldaps) {
            ConfiguredSslSocketFactory.delegate = sslFactory;
            baseEnv.put("java.naming.ldap.factory.socket", ConfiguredSslSocketFactory.class.getName());
        } else if (ldap.isStartTls()) {
            DefaultTlsDirContextAuthenticationStrategy tls = new DefaultTlsDirContextAuthenticationStrategy();
            tls.setSslSocketFactory(sslFactory);
            if (ldap.isTrustAll() && isDev) {
                tls.setHostnameVerifier((host, session) -> true);
            }
            cs.setAuthenticationStrategy(tls);
        }

        cs.setBaseEnvironmentProperties(baseEnv);

        // Manager DN nếu có
        if (ldap.getManagerDn() != null && !ldap.getManagerDn().isBlank()) {
            cs.setUserDn(ldap.getManagerDn());
            cs.setPassword(ldap.getManagerPassword() != null ? ldap.getManagerPassword() : "");
        }

        // JNDI connection pooling không dùng được với StartTLS (kết nối nâng cấp TLS không trả về pool an toàn)
        cs.setPooled(!ldap.isStartTls() || ldaps);
        cs.afterPropertiesSet();

        this.sharedContextSource = cs;
        this.sharedLdapTemplate = new LdapTemplate(cs);
    }

    private long parseTimeoutMs(Duration duration, long defaultMs) {
        if (duration == null) return defaultMs;
        long ms = duration.toMillis();
        return ms > 0 ? ms : defaultMs;
    }

    private SSLSocketFactory buildSslSocketFactory(AuthProperties.LdapProperties ldap, boolean isDev) {
        try {
            TrustManager[] trustManagers;
            if (ldap.isTrustAll() && isDev) {
                log.warn("LDAP trustAll is enabled (dev profile): server certificates are NOT verified");
                trustManagers = new TrustManager[]{new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }};
            } else if (ldap.getTrustStore() != null && !ldap.getTrustStore().isBlank()) {
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(loadTrustStore(ldap));
                trustManagers = tmf.getTrustManagers();
            } else {
                return (SSLSocketFactory) SSLSocketFactory.getDefault();
            }
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception ex) {
            // Không lặng lẽ rơi về truststore mặc định: cấu hình TLS sai phải lộ ra ngay.
            throw new IllegalStateException("Cannot initialise LDAP TLS trust store: " + ex.getClass().getSimpleName(), ex);
        }
    }

    private KeyStore loadTrustStore(AuthProperties.LdapProperties ldap) throws Exception {
        String type = ldap.getTrustStoreType() != null ? ldap.getTrustStoreType().toUpperCase() : "PKCS12";
        char[] password = ldap.getTrustStorePassword() != null && !ldap.getTrustStorePassword().isEmpty()
                ? ldap.getTrustStorePassword().toCharArray() : null;
        try (InputStream in = new FileInputStream(ldap.getTrustStore())) {
            if ("PEM".equals(type)) {
                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                int i = 0;
                for (Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    ks.setCertificateEntry("ldap-ca-" + i++, cert);
                }
                if (i == 0) {
                    throw new IllegalStateException("PEM trust store contains no certificate");
                }
                return ks;
            }
            KeyStore ks = KeyStore.getInstance(type);
            ks.load(in, password);
            return ks;
        }
    }

    /**
     * JNDI chỉ nhận socket factory dưới dạng tên class có {@code static getDefault()}; class này trả về
     * factory đã dựng từ cấu hình truststore (dùng cho ldaps://).
     */
    public static class ConfiguredSslSocketFactory extends SSLSocketFactory {
        static volatile SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();

        public static SocketFactory getDefault() { return new ConfiguredSslSocketFactory(); }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return delegate.createSocket(s, host, port, autoClose);
        }
        @Override public Socket createSocket() throws IOException { return delegate.createSocket(); }
        @Override public Socket createSocket(String host, int port) throws IOException { return delegate.createSocket(host, port); }
        @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return delegate.createSocket(host, port, localHost, localPort);
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { return delegate.createSocket(host, port); }
        @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return delegate.createSocket(address, port, localAddress, localPort);
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

                // Bind với mật khẩu, đọc tên chuẩn bằng chính kết nối của người dùng (B3.4, B9)
                canonicalName = bindAndReadCanonicalName(targetUserDn, password, ldap.getUsernameAttribute());
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

                // Bind với mật khẩu qua cùng ContextSource (cùng TLS) với bước tìm kiếm
                bindAndReadCanonicalName(targetUserDn, password, null);
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

    /**
     * Bind bằng DN + mật khẩu người dùng qua {@code sharedContextSource} (cùng URL, TLS, timeout).
     * {@code getContext(principal, credentials)} của Spring LDAP luôn tắt pooling nên mật khẩu không nằm lại
     * trong pool. Khi {@code usernameAttribute} khác null: đọc thuộc tính đó từ entry của chính người dùng;
     * entry không có thuộc tính → từ chối, không rơi về chuỗi người dùng gõ.
     */
    private String bindAndReadCanonicalName(String userDn, String password, String usernameAttribute) throws Exception {
        DirContext ctx = sharedContextSource.getContext(userDn, password);
        try {
            if (usernameAttribute == null) {
                return null;
            }
            Name relative = LdapUtils.newLdapName(userDn);
            Name base = sharedContextSource.getBaseLdapName();
            if (base != null && !base.isEmpty() && relative.startsWith(base)) {
                relative = LdapUtils.removeFirst(relative, base);
            }
            Attributes attrs = ctx.getAttributes(relative, new String[]{usernameAttribute});
            Attribute attr = attrs.get(usernameAttribute);
            Object value = attr != null ? attr.get() : null;
            if (value == null || value.toString().isBlank()) {
                log.warn("LDAP entry {} has no attribute {}", userDn, usernameAttribute);
                throw new NotebookApiException("INVALID_CREDENTIALS", "Invalid credentials", HttpStatus.UNAUTHORIZED);
            }
            return value.toString();
        } finally {
            LdapUtils.closeContext(ctx);
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
