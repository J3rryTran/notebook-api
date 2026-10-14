package com.vtnexus.notebook.common.auth;

public record SessionInfo(String username, String authorizationHeader, boolean isAdmin) {
}
