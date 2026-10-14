package com.vtnexus.notebook.store.service;

import com.vtnexus.notebook.common.auth.AuthProperties;
import com.vtnexus.notebook.common.exception.NotebookApiException;
import org.springframework.http.HttpStatus;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Service;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.*;
import java.util.List;

@Service
public class LdapAuthenticator {

    private final AuthProperties authProperties;

    public LdapAuthenticator(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    public record LdapUser(String canonicalUsername, boolean isAdmin) {}

    public LdapUser authenticate(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new NotebookApiException("INVALID_CREDENTIALS", "Username and password cannot be empty", HttpStatus.UNAUTHORIZED);
        }

        AuthProperties.LdapProperties ldap = authProperties.getLdap();
        if (ldap.getUrl() == null || ldap.getUrl().isBlank()) {
            throw new NotebookApiException("AUTH_BACKEND_UNAVAILABLE", "LDAP is not configured", HttpStatus.SERVICE_UNAVAILABLE);
        }

        try {
            LdapContextSource contextSource = new LdapContextSource();
            contextSource.setUrl(ldap.getUrl());
            contextSource.setBase(ldap.getBaseDn() != null ? ldap.getBaseDn() : "");
            contextSource.setPooled(false);

            String userDn;
            if (ldap.getUserDnPattern() != null && !ldap.getUserDnPattern().isBlank()) {
                userDn = ldap.getUserDnPattern().replace("{0}", username);
                if (ldap.getBaseDn() != null && !ldap.getBaseDn().isBlank()) {
                    userDn = userDn + "," + ldap.getBaseDn();
                }
            } else {
                // userSearch qua managerDn
                contextSource.setUserDn(ldap.getManagerDn());
                contextSource.setPassword(ldap.getManagerPassword());
                contextSource.afterPropertiesSet();
                LdapTemplate managerTemplate = new LdapTemplate(contextSource);

                String filter = ldap.getUserSearchFilter().replace("{0}", username);
                List<String> dns = managerTemplate.search(
                        ldap.getUserSearchBase(),
                        filter,
                        (AttributesMapper<String>) (attrs) -> {
                            Attribute attr = attrs.get(ldap.getUsernameAttribute());
                            return (attr != null) ? attr.get().toString() : username;
                        }
                );

                if (dns.isEmpty()) {
                    throw new NotebookApiException("INVALID_CREDENTIALS", "Invalid credentials", HttpStatus.UNAUTHORIZED);
                }
                userDn = ldap.getUsernameAttribute() + "=" + username + "," + ldap.getUserSearchBase() + (ldap.getBaseDn().isBlank() ? "" : "," + ldap.getBaseDn());
            }

            // Bind trực tiếp bằng mật khẩu user
            LdapContextSource userContextSource = new LdapContextSource();
            userContextSource.setUrl(ldap.getUrl());
            userContextSource.setUserDn(userDn);
            userContextSource.setPassword(password);
            userContextSource.afterPropertiesSet();

            DirContext ctx = userContextSource.getReadOnlyContext();
            ctx.close();

            // Kiểm tra adminGroup nếu có
            boolean isAdmin = false;
            if (ldap.getAdminGroup() != null && !ldap.getAdminGroup().isBlank()) {
                // Kiểm tra member hoặc memberUid
                isAdmin = false;
            }

            return new LdapUser(username, isAdmin);

        } catch (NotebookApiException ex) {
            throw ex;
        } catch (org.springframework.ldap.AuthenticationException | NamingException ex) {
            throw new NotebookApiException("INVALID_CREDENTIALS", "Invalid credentials", HttpStatus.UNAUTHORIZED);
        } catch (Exception ex) {
            throw new NotebookApiException("AUTH_BACKEND_UNAVAILABLE", "Failed to contact LDAP server: " + ex.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
