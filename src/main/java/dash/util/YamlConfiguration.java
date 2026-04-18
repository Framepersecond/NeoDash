package dash.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lightweight YAML config writer supporting dotted-path set operations.
 */
public class YamlConfiguration {
    private final Map<String, Object> root = new LinkedHashMap<>();

    public void set(String path, Object value) {
        if (path == null || path.isBlank()) {
            return;
        }

        String[] parts = path.split("\\.");
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String key = parts[i];
            Object next = cursor.get(key);
            if (!(next instanceof Map<?, ?>)) {
                Map<String, Object> created = new LinkedHashMap<>();
                cursor.put(key, created);
                cursor = created;
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) next;
            cursor = typed;
        }

        String leaf = parts[parts.length - 1];
        if (value == null) {
            cursor.remove(leaf);
            pruneEmptyParents(parts);
            return;
        }
        cursor.put(leaf, value);
    }

    public String saveToString() {
        StringBuilder out = new StringBuilder();
        writeMap(out, root, 0);
        return out.toString();
    }

    private void pruneEmptyParents(String[] parts) {
        List<Map<String, Object>> trail = new ArrayList<>();
        Map<String, Object> cursor = root;
        trail.add(cursor);
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cursor.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) next;
            cursor = typed;
            trail.add(cursor);
        }

        for (int i = parts.length - 2; i >= 0; i--) {
            Map<String, Object> parent = trail.get(i);
            @SuppressWarnings("unchecked")
            Map<String, Object> child = (Map<String, Object>) parent.get(parts[i]);
            if (child != null && child.isEmpty()) {
                parent.remove(parts[i]);
            } else {
                break;
            }
        }
    }

    private void writeMap(StringBuilder out, Map<String, Object> map, int indent) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            indent(out, indent);
            out.append(entry.getKey()).append(":");
            if (entry.getValue() instanceof Map<?, ?> nestedRaw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) nestedRaw;
                if (nested.isEmpty()) {
                    out.append(" {}\n");
                } else {
                    out.append("\n");
                    writeMap(out, nested, indent + 2);
                }
                continue;
            }
            out.append(" ").append(formatScalar(entry.getValue())).append("\n");
        }
    }

    private void indent(StringBuilder out, int count) {
        out.append(" ".repeat(Math.max(0, count)));
    }

    private String formatScalar(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        String text = Objects.toString(value, "");
        String escaped = text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }
}

