package com.ftbqbridge.backend;

public final class ApiException extends RuntimeException {
    public final int status; public final String type;
    public ApiException(int status, String type, String message) { super(message); this.status = status; this.type = type; }
    public static ApiException notFound(String m)   { return new ApiException(404, "not_found", m); }
    public static ApiException badRequest(String m)  { return new ApiException(400, "bad_request", m); }
    public static ApiException notLoaded()           { return new ApiException(503, "quests_not_loaded", "Quest file not loaded"); }
    public static ApiException serverBusy()          { return new ApiException(504, "server_busy", "Server thread timed out"); }
    public static ApiException internal(String m)    { return new ApiException(500, "internal", m); }
}
