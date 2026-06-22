package com.ftbqbridge.http;

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
                if (seg.startsWith("{") && seg.endsWith("}")) params.put(seg.substring(1, seg.length()-1), p[i]);
                else if (!seg.equals(p[i])) { ok = false; break; }
            }
            if (ok) return new Match(e.route(), params);
        }
        return null;
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
