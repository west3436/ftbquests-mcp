package com.ftbqbridge.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Auth {
    public enum Result { OK, UNAUTHORIZED, REMOTE_DISABLED }
    private Auth() {}

    public static Result evaluate(boolean loopback, String authHeader, String expectedToken, boolean allowRemote) {
        if (!loopback && !allowRemote) return Result.REMOTE_DISABLED;
        String presented = null;
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            presented = authHeader.substring(7).trim();
        }
        if (presented == null || !constantTimeEquals(presented, expectedToken)) return Result.UNAUTHORIZED;
        return Result.OK;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
