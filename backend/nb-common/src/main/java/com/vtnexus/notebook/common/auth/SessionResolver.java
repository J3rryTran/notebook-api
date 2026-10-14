package com.vtnexus.notebook.common.auth;

@FunctionalInterface
public interface SessionResolver {
    SessionInfo resolve(String sessionId);
}
