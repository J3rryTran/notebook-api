package com.vtnexus.notebook.store.service;

import com.vtnexus.notebook.common.auth.AuthProperties;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.ldap.support.LdapEncoder;
import org.springframework.stereotype.Service;

import javax.naming.AuthenticationException;
import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.ServiceUnavailableException;
import javax.naming.SizeLimitExceededException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.StartTlsRequest;
import javax.naming.ldap.StartTlsResponse;
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
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;

@Service
public class LdapAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(LdapAuthenticator.class);
    private static final String[] USER_OBJECT_CLASSES = {"person", "user", "inetOrgPerson"};

    private final AuthProperties authProperties;
    private final Environment environment;
    private volatile SSLSocketFactory sslSocketFactory;

    public LdapAuthenticator(AuthProperties authProperties, Environment environment) {
        this.authProperties = authProperties;
        this.environment = environment;
    }

    public record LdapUser(String canonicalUsername, boolean isAdmin) {}

    public LdapUser authenticate(String user, String password) {
        validateUser(user);
        if (password == null || password.isEmpty() || password.charAt(0) == 0) {
            throw invalid();
        }
        AuthProperties.LdapProperties ldap = config();
        boolean usedBind = notBlank(ldap.getBindDn()) && notBlank(ldap.getBindPassword());

        LdapContext ctx = usedBind
                ? openCandidates(ldap.getBindDn(), ldap.getBindPassword())
                : openCandidates(user, password);
        try {
            applyFilter(ctx, user);
            String userName = getUserName(user);
            String userDn = findUserDnQuietly(ctx, userName);
            if (usedBind) {
                if (userDn == null) {
                    throw invalid();
                }
                close(openCandidates(userDn, password));
            }
            return new LdapUser(canonicalName(ctx, userDn, userName), false);
        } finally {
            close(ctx);
        }
    }

    public LdapUser lookupUser(String user) {
        validateUser(user);
        AuthProperties.LdapProperties ldap = config();
        if (!notBlank(ldap.getBindDn()) || !notBlank(ldap.getBindPassword())) {
            return new LdapUser(getUserName(user), false);
        }
        LdapContext ctx = openCandidates(ldap.getBindDn(), ldap.getBindPassword());
        try {
            applyFilter(ctx, user);
            String userDn = findUserDnQuietly(ctx, getUserName(user));
            if (userDn == null) {
                throw invalid();
            }
            return new LdapUser(canonicalName(ctx, userDn, getUserName(user)), false);
        } finally {
            close(ctx);
        }
    }

    private AuthProperties.LdapProperties config() {
        AuthProperties.LdapProperties ldap = authProperties.getLdap();
        if (!notBlank(ldap.getUrl())) {
            throw unavailable();
        }
        return ldap;
    }

    private static void validateUser(String user) {
        if (user == null || user.isBlank() || user.length() > 255) {
            throw invalid();
        }
        for (char c : user.toCharArray()) {
            if (Character.isISOControl(c)) {
                throw invalid();
            }
        }
    }

    private void applyFilter(LdapContext ctx, String user) {
        AuthProperties.LdapProperties ldap = authProperties.getLdap();
        String name = getUserName(user);
        if (notBlank(ldap.getCustomLdapQuery())) {
            customQueryFilter(ctx, ldap.getCustomLdapQuery(), user);
            return;
        }
        List<String> groupFilter = nonBlank(ldap.getGroupFilter());
        List<String> userFilter = nonBlank(ldap.getUserFilter());
        if (!groupFilter.isEmpty() || !userFilter.isEmpty()) {
            if (findUserDnQuietly(ctx, name) == null) {
                throw invalid();
            }
        }
        if (!userFilter.isEmpty()) {
            String userName = extractUserName(user).toLowerCase(Locale.ROOT);
            if (userFilter.stream().noneMatch(u -> u.toLowerCase(Locale.ROOT).equals(userName))) {
                throw invalid();
            }
        }
        if (!groupFilter.isEmpty()) {
            if (notBlank(ldap.getUserMembershipKey())) {
                userMembershipKeyFilter(ctx, groupFilter, name);
            } else {
                groupMembershipKeyFilter(ctx, groupFilter, name);
            }
        }
    }

    private void groupMembershipKeyFilter(LdapContext ctx, List<String> groupFilter, String user) {
        String userDn = findUserDnQuietly(ctx, user);
        if (userDn == null) {
            throw invalid();
        }
        for (String groupDn : findGroupsForUser(ctx, userDn)) {
            String shortName = getShortName(groupDn);
            if (groupFilter.stream().anyMatch(shortName::equalsIgnoreCase)) {
                return;
            }
        }
        throw invalid();
    }

    private void userMembershipKeyFilter(LdapContext ctx, List<String> groupFilter, String user) {
        List<String> groupDns = new ArrayList<>();
        for (String groupId : groupFilter) {
            List<String> found = search(ctx, groupBases(), findGroupDnById(groupId), 2);
            if (found.size() == 1) {
                groupDns.add(found.get(0));
            }
        }
        if (groupDns.isEmpty()) {
            throw invalid();
        }
        String userId = extractUserName(user);
        for (String groupDn : groupDns) {
            if (search(ctx, userBases(), isUserMemberOfGroup(userId, groupDn), 2).size() == 1) {
                return;
            }
        }
        throw invalid();
    }

    private void customQueryFilter(LdapContext ctx, String query, String user) {
        String baseDn = authProperties.getLdap().getBaseDn();
        List<String> results = searchNamesAndAttribute(ctx, baseDn, query, authProperties.getLdap().getGroupMembershipKey());
        for (String matchedDn : results) {
            if (getShortNameSafe(matchedDn).equalsIgnoreCase(user) || matchedDn.equalsIgnoreCase(user)) {
                return;
            }
        }
        if (query.contains("%s")) {
            String userQuery = query.replace("%s", LdapEncoder.filterEncode(user));
            if (search(ctx, new String[]{baseDn}, userQuery, 0).size() == 1) {
                return;
            }
        }
        throw invalid();
    }

    private String findUserDnQuietly(LdapContext ctx, String user) {
        List<String> names;
        if (isDn(user)) {
            String rdn = extractFirstRdn(user);
            int eq = rdn.indexOf('=');
            String safeRdn = rdn.substring(0, eq + 1) + LdapEncoder.filterEncode(rdn.substring(eq + 1));
            names = search(ctx, new String[]{extractBaseDn(user)}, "(&" + userClasses() + "(" + safeRdn + "))", 2);
        } else {
            names = new ArrayList<>();
            for (String pattern : userPatterns()) {
                String rdn = extractFirstRdn(pattern).replace("%s", LdapEncoder.filterEncode(user));
                names = search(ctx, new String[]{extractBaseDn(pattern)}, "(" + rdn + ")", 2);
                if (!names.isEmpty()) {
                    break;
                }
            }
            if (names.isEmpty()) {
                String u = LdapEncoder.filterEncode(user);
                names = search(ctx, userBases(), "(&" + userClasses() + "(|(uid=" + u + ")(sAMAccountName=" + u + ")))", 2);
            }
        }
        return names.size() == 1 ? names.get(0) : null;
    }

    private List<String> findGroupsForUser(LdapContext ctx, String userDn) {
        AuthProperties.LdapProperties ldap = authProperties.getLdap();
        String userName = LdapEncoder.filterEncode(extractUserName(userDn));
        String gm = ldap.getGroupMembershipKey();
        String filter = "(&(objectClass=" + ldap.getGroupClassKey() + ")(|(" + gm + "=" + LdapEncoder.filterEncode(userDn)
                + ")(" + gm + "=" + userName + ")))";
        return search(ctx, groupBases(), filter, 0);
    }

    private String findGroupDnById(String groupId) {
        AuthProperties.LdapProperties ldap = authProperties.getLdap();
        return "(&(objectClass=" + ldap.getGroupClassKey() + ")(" + ldap.getGuidKey() + "=" + LdapEncoder.filterEncode(groupId) + "))";
    }

    private String isUserMemberOfGroup(String userId, String groupDn) {
        AuthProperties.LdapProperties ldap = authProperties.getLdap();
        return "(&" + userClasses() + "(" + ldap.getUserMembershipKey() + "=" + LdapEncoder.filterEncode(groupDn) + ")("
                + ldap.getGuidKey() + "=" + LdapEncoder.filterEncode(userId) + "))";
    }

    private static String userClasses() {
        StringBuilder sb = new StringBuilder("(|");
        for (String c : USER_OBJECT_CLASSES) {
            sb.append("(objectClass=").append(c).append(')');
        }
        return sb.append(')').toString();
    }

    private String canonicalName(LdapContext ctx, String userDn, String fallback) {
        if (userDn == null) {
            return fallback;
        }
        try {
            Attributes attrs = ctx.getAttributes(new javax.naming.ldap.LdapName(userDn), new String[]{authProperties.getLdap().getGuidKey()});
            Attribute attr = attrs.get(authProperties.getLdap().getGuidKey());
            Object value = attr != null ? attr.get() : null;
            return value != null && !value.toString().isBlank() ? value.toString() : fallback;
        } catch (NamingException ex) {
            return fallback;
        }
    }

    private List<String> search(LdapContext ctx, String[] baseDns, String filter, int limit) {
        List<String> names = new ArrayList<>();
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setCountLimit(limit);
        controls.setReturningAttributes(new String[0]);
        for (String baseDn : baseDns) {
            if (baseDn == null) {
                continue;
            }
            try {
                NamingEnumeration<SearchResult> results = ctx.search(baseDn, filter, controls);
                try {
                    while (results.hasMore()) {
                        names.add(results.next().getNameInNamespace());
                    }
                } catch (SizeLimitExceededException ex) {
                    names.add("<more>");
                }
            } catch (CommunicationException | ServiceUnavailableException ex) {
                throw unavailable();
            } catch (NamingException ex) {
                log.debug("LDAP search failed base={} filter={}: {}", baseDn, filter, ex.getMessage());
            }
        }
        return names;
    }

    private List<String> searchNamesAndAttribute(LdapContext ctx, String baseDn, String filter, String attribute) {
        List<String> out = new ArrayList<>();
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(new String[]{attribute});
        try {
            NamingEnumeration<SearchResult> results = ctx.search(baseDn, filter, controls);
            while (results.hasMore()) {
                SearchResult r = results.next();
                out.add(r.getNameInNamespace());
                Attribute attr = r.getAttributes().get(attribute);
                if (attr != null) {
                    NamingEnumeration<?> values = attr.getAll();
                    while (values.hasMore()) {
                        out.add(String.valueOf(values.next()));
                    }
                }
            }
        } catch (CommunicationException | ServiceUnavailableException ex) {
            throw unavailable();
        } catch (NamingException ex) {
            log.debug("LDAP custom query failed: {}", ex.getMessage());
        }
        return out;
    }

    private LdapContext openCandidates(String user, String password) {
        List<String> principals = createCandidatePrincipals(user);
        if (principals.isEmpty()) {
            throw invalid();
        }
        for (int i = 0; i < principals.size(); i++) {
            try {
                return connect(principals.get(i), password);
            } catch (AuthenticationException ex) {
                if (i == principals.size() - 1) {
                    throw invalid();
                }
            } catch (CommunicationException | ServiceUnavailableException ex) {
                log.warn("LDAP unavailable: {}", ex.getMessage());
                throw unavailable();
            } catch (NamingException | IOException ex) {
                log.warn("LDAP connect error for {}: {}", principals.get(i), ex.getMessage());
                if (i == principals.size() - 1) {
                    throw invalid();
                }
            }
        }
        throw invalid();
    }

    private LdapContext connect(String principal, String password) throws NamingException, IOException {
        AuthProperties.LdapProperties ldap = authProperties.getLdap();
        Hashtable<String, Object> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, ldap.getUrl());
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(ldap.getConnectTimeout().toMillis()));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(ldap.getReadTimeout().toMillis()));
        boolean ldaps = ldap.getUrl().trim().toLowerCase(Locale.ROOT).startsWith("ldaps://");
        if (ldaps) {
            ConfiguredSslSocketFactory.delegate = sslFactory(ldap);
            env.put("java.naming.ldap.factory.socket", ConfiguredSslSocketFactory.class.getName());
        }
        if (!ldaps && ldap.isStartTls()) {
            LdapContext ctx = new InitialLdapContext(env, null);
            StartTlsResponse tls = (StartTlsResponse) ctx.extendedOperation(new StartTlsRequest());
            tls.negotiate(sslFactory(ldap));
            ctx.addToEnvironment(Context.SECURITY_AUTHENTICATION, "simple");
            ctx.addToEnvironment(Context.SECURITY_PRINCIPAL, principal);
            ctx.addToEnvironment(Context.SECURITY_CREDENTIALS, password);
            ctx.reconnect(null);
            return ctx;
        }
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, password);
        return new InitialLdapContext(env, null);
    }

    private SSLSocketFactory sslFactory(AuthProperties.LdapProperties ldap) {
        if (sslSocketFactory == null) {
            synchronized (this) {
                if (sslSocketFactory == null) {
                    sslSocketFactory = buildSslSocketFactory(ldap, environment.acceptsProfiles(Profiles.of("dev")));
                }
            }
        }
        return sslSocketFactory;
    }

    List<String> createCandidatePrincipals(String user) {
        AuthProperties.LdapProperties ldap = authProperties.getLdap();
        List<String> out = new ArrayList<>();
        if (hasDomain(user) || isDn(user)) {
            out.add(user);
            return out;
        }
        if (notBlank(ldap.getDomain())) {
            out.add(user + "@" + ldap.getDomain().trim());
            return out;
        }
        List<String> patterns = userPatterns();
        if (patterns.isEmpty()) {
            out.add(user);
            return out;
        }
        for (String pattern : patterns) {
            out.add(pattern.replace("%s", LdapEncoder.nameEncode(user)));
        }
        return out;
    }

    private List<String> userPatterns() {
        return parseDnPatterns(authProperties.getLdap().getUserDnPattern());
    }

    private String[] userBases() {
        return userPatterns().stream().map(LdapAuthenticator::patternToBaseDn).toArray(String[]::new);
    }

    private String[] groupBases() {
        return parseDnPatterns(authProperties.getLdap().getGroupDnPattern()).stream()
                .map(LdapAuthenticator::patternToBaseDn).toArray(String[]::new);
    }

    List<String> parseDnPatterns(String patterns) {
        List<String> out = new ArrayList<>();
        AuthProperties.LdapProperties ldap = authProperties.getLdap();
        if (notBlank(patterns)) {
            for (String p : patterns.split(":")) {
                if (p.contains(",") && p.contains("=")) {
                    out.add(p.trim());
                } else {
                    log.warn("Unexpected LDAP DN pattern, ignoring {}", p);
                }
            }
        } else if (notBlank(ldap.getBaseDn())) {
            out.add(ldap.getGuidKey() + "=%s," + ldap.getBaseDn().trim());
        }
        return out;
    }

    static String patternToBaseDn(String pattern) {
        return pattern.contains("=%s") ? pattern.split(",", 2)[1] : pattern;
    }

    static boolean isDn(String name) {
        return name.contains("=");
    }

    static int indexOfDomainMatch(String userName) {
        if (userName == null) {
            return -1;
        }
        int idx = userName.indexOf('/');
        int idx2 = userName.indexOf('@');
        int endIdx = Math.min(idx, idx2);
        if (endIdx == -1) {
            endIdx = Math.max(idx, idx2);
        }
        return endIdx;
    }

    static boolean hasDomain(String userName) {
        return indexOfDomainMatch(userName) > 0;
    }

    static String getUserName(String user) {
        return hasDomain(user) ? extractUserName(user) : user;
    }

    static String extractUserName(String userDn) {
        if (!isDn(userDn) && !hasDomain(userDn)) {
            return userDn;
        }
        int domainIdx = indexOfDomainMatch(userDn);
        if (domainIdx > 0) {
            return userDn.substring(0, domainIdx);
        }
        if (userDn.contains("=")) {
            int comma = userDn.indexOf(',');
            return comma > 0 ? userDn.substring(userDn.indexOf('=') + 1, comma) : userDn.substring(userDn.indexOf('=') + 1);
        }
        return userDn;
    }

    static String extractBaseDn(String dn) {
        int i = dn.indexOf(',');
        return i > -1 ? dn.substring(i + 1) : null;
    }

    static String extractFirstRdn(String dn) {
        int i = dn.indexOf(',');
        return i > -1 ? dn.substring(0, i) : dn;
    }

    static String getShortName(String rdn) {
        return rdn.split(",")[0].split("=")[1];
    }

    private static String getShortNameSafe(String dn) {
        try {
            return getShortName(dn);
        } catch (RuntimeException ex) {
            return dn;
        }
    }

    private static List<String> nonBlank(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank()) {
                    out.add(v.trim());
                }
            }
        }
        return out;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static void close(LdapContext ctx) {
        if (ctx != null) {
            try {
                ctx.close();
            } catch (NamingException ignored) {
            }
        }
    }

    private static NotebookApiException invalid() {
        return new NotebookApiException("INVALID_CREDENTIALS", "Invalid credentials", HttpStatus.UNAUTHORIZED);
    }

    private static NotebookApiException unavailable() {
        return new NotebookApiException("AUTH_BACKEND_UNAVAILABLE", "LDAP unavailable", HttpStatus.SERVICE_UNAVAILABLE);
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
}
