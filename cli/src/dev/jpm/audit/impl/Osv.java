package dev.jpm.audit.impl;

import dev.jpm.api.json.Jsonc;
import dev.jpm.util.Http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Osv {
    private static final int BATCH = 500;

    private final String base;
    private final Map<String, Map<String, Object>> details = new LinkedHashMap<>();

    public Osv(String base) {
        this.base = base.replaceAll("/+$", "");
    }

    public record Query(String ecosystem, String name, String version, String commit) {}

    @SuppressWarnings("unchecked")
    public List<List<String>> ids(List<Query> queries) {
        List<List<String>> out = new ArrayList<>();
        for (int from = 0; from < queries.size(); from += BATCH) {
            List<Object> qs = new ArrayList<>();
            for (Query q : queries.subList(from, Math.min(queries.size(), from + BATCH))) {
                Map<String, Object> m = new LinkedHashMap<>();
                if (q.commit() != null) {
                    m.put("commit", q.commit());
                } else {
                    m.put("package", Map.of("ecosystem", q.ecosystem(), "name", q.name()));
                    m.put("version", q.version());
                }
                qs.add(m);
            }
            Map<String, Object> r = (Map<String, Object>) Jsonc.parse(Http.postJson(base + "/v1/querybatch", Jsonc.write(Map.of("queries", qs))));
            for (Object res : (List<Object>) r.getOrDefault("results", List.of())) {
                List<String> ids = new ArrayList<>();
                if (res instanceof Map<?, ?> rm && rm.get("vulns") instanceof List<?> vs) {
                    for (Object v : vs) if (v instanceof Map<?, ?> vm) ids.add(String.valueOf(vm.get("id")));
                }
                out.add(ids);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> vulnerability(String id) {
        return details.computeIfAbsent(id, k -> (Map<String, Object>) Jsonc.parse(Http.getString(base + "/v1/vulns/" + k)));
    }
}
