package com.ftbqbridge.backend;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiExceptionTest {
    @Test void factoriesCarryStatusAndType() {
        assertEquals(404, ApiException.notFound("x").status);
        assertEquals("not_found", ApiException.notFound("x").type);
        assertEquals(400, ApiException.badRequest("x").status);
        assertEquals("bad_request", ApiException.badRequest("x").type);
        assertEquals(503, ApiException.notLoaded().status);
        assertEquals("quests_not_loaded", ApiException.notLoaded().type);
        assertEquals(504, ApiException.serverBusy().status);
        assertEquals(500, ApiException.internal("x").status);
    }
}
