package com.ftbqbridge.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Router {
    @FunctionalInterface public interface Route { JsonResponse handle(RequestContext ctx); }
    public record Match(Route route, Map<String,String> params) {}

    private record Entry(String method, String[] segs, Route route) {}
    private final List<Entry> entries = new ArrayList<>();

    public Router add(String method, String pattern, Route route) {
        entries.add(new Entry(method, split(pattern), route));
        return this;
    }

    public Match match(String method, String path) {
        String[] p = split(path);
        for (Entry e : entries) {
            if (!e.method().equalsIgnoreCase(method) || e.segs().length != p.length) continue;
            Map<String,String> params = new HashMap<>();
            boolean ok = true;
            for (int i = 0; i < p.length; i++) {
                String seg = e.segs()[i];
                if (seg.startsWith("{") && seg.endsWith("}")) params.put(seg.substring(1, seg.length()-1), decode(p[i]));
                else if (!seg.equals(p[i])) { ok = false; break; }
            }
            if (ok) return new Match(e.route(), params);
        }
        return null;
    }

    /**
     * Percent-decode a captured path segment (e.g. {@code ftbquests%3Aitem} -> {@code ftbquests:item}).
     * Path segments use percent-encoding only — unlike query strings, {@code +} is a literal plus, not
     * a space — so we decode {@code %XX} escapes and leave every other byte as-is. The request line is
     * ASCII, so non-escaped chars map straight to their byte; escaped bytes are reassembled and decoded
     * as UTF-8.
     */
    private static String decode(String s) {
        if (s.indexOf('%') < 0) return s; // fast path: nothing escaped (e.g. hex ids)
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) { out.write((hi << 4) + lo); i += 2; continue; }
            }
            out.write(c); // ASCII path char
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String[] split(String path) {
        String s = path;
        int q = s.indexOf('?'); if (q >= 0) s = s.substring(0, q);
        if (s.startsWith("/")) s = s.substring(1);
        if (s.endsWith("/")) s = s.substring(0, s.length()-1);
        if (s.isEmpty()) return new String[0];
        return s.split("/");
    }
}
