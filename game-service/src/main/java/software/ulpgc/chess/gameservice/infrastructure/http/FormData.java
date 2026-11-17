package software.ulpgc.chess.gameservice.infrastructure.http;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public final class FormData {
    private FormData() {}

    public static String encode(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> escape(entry.getKey()) + "=" + escape(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    public static Map<String, String> decode(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return values;
        for (String pair : text.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            if (values.putIfAbsent(key, value) != null)
                throw new IllegalArgumentException("Repeated field: " + key);
        }
        return values;
    }

    private static String escape(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
