package com.ftbqbridge.http;

import org.junit.jupiter.api.Test;
import static com.ftbqbridge.http.Auth.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthTest {
    @Test void okOnLoopbackWithGoodToken() {
        assertEquals(OK, Auth.evaluate(true, "Bearer secret", "secret", false));
    }
    @Test void unauthorizedOnBadToken() {
        assertEquals(UNAUTHORIZED, Auth.evaluate(true, "Bearer nope", "secret", false));
    }
    @Test void unauthorizedOnMissingHeader() {
        assertEquals(UNAUTHORIZED, Auth.evaluate(true, null, "secret", false));
    }
    @Test void remoteDisabledBlocksNonLoopbackEvenWithGoodToken() {
        assertEquals(REMOTE_DISABLED, Auth.evaluate(false, "Bearer secret", "secret", false));
    }
    @Test void remoteAllowedWithGoodToken() {
        assertEquals(OK, Auth.evaluate(false, "Bearer secret", "secret", true));
    }
}
