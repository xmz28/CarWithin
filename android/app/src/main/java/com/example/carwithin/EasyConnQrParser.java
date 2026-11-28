package com.example.carwithin;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parses Carbit / EasyConn motorcycle dashboard pairing QR codes (e.g. QJMotor _ylqx, CFMOTO,
 * Benelli, Voge, standard WIFI: QR, and MotoFun Wifi=ssid#pwd) into structured connection fields.
 */
public final class EasyConnQrParser {

    public enum LinkMode {
        WIFI_DIRECT,
        WIFI_AP
    }

    public record ParsedQr(
            String ssid,
            String password,
            String mac,
            String name,
            String modelId,
            String auth,
            int action,
            LinkMode recommendedMode,
            String raw
    ) {}

    private EasyConnQrParser() {}

    public static ParsedQr parse(String rawInput) {
        if (rawInput == null || rawInput.trim().isEmpty()) {
            throw new IllegalArgumentException("二维码内容为空");
        }
        String raw = rawInput.trim();

        // 1. Standard WIFI:T:WPA;S:ssid;P:pwd;;
        if (raw.regionMatches(true, 0, "WIFI:", 0, 5)) {
            return parseWifiScheme(raw);
        }

        // 2. Query-string URL (Carbit / EasyConn / _ylqx / _sdk / generic OEM URL)
        int qIdx = raw.indexOf('?');
        String queryPart = qIdx >= 0 ? raw.substring(qIdx + 1) : raw;
        int hashIdx = queryPart.indexOf('#');
        if (hashIdx >= 0) queryPart = queryPart.substring(0, hashIdx);

        // 2a. MotoFun style: Wifi=SSID#PASSWORD
        if (raw.contains("Wifi=") || raw.contains("wifi=")) {
            ParsedQr motoFun = tryParseMotoFun(raw);
            if (motoFun != null) return motoFun;
        }

        Map<String, String> params = parseQueryParams(queryPart);
        String ssid = firstNonEmpty(params.get("ssid"), params.get("name"), params.get("device"));
        if (ssid.isEmpty()) {
            throw new IllegalArgumentException("未在二维码中找到有效的 Wi-Fi 名称（ssid / name）");
        }
        String password = firstNonEmpty(params.get("pwd"), params.get("password"), params.get("pass"));
        String name = firstNonEmpty(params.get("name"), ssid);
        String mac = normalizeMac(firstNonEmpty(params.get("mac"), params.get("bm")));
        String modelId = firstNonEmpty(params.get("modelid"), params.get("productid"));
        String auth = firstNonEmpty(params.get("auth"), password.isEmpty() ? "OPEN" : "WPA2");
        int action = parseIntSafe(params.get("action"), 0);

        LinkMode mode = inferLinkMode(raw, ssid, action);
        return new ParsedQr(ssid, password, mac, name, modelId, auth, action, mode, raw);
    }

    private static ParsedQr parseWifiScheme(String raw) {
        String body = raw.substring(5);
        String ssid = "", pwd = "", auth = "WPA2";
        for (String part : body.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            String k = part.substring(0, colon).trim().toUpperCase(Locale.ROOT);
            String v = unescapeWifi(part.substring(colon + 1)).trim();
            if ("S".equals(k)) ssid = v;
            else if ("P".equals(k)) pwd = v;
            else if ("T".equals(k)) auth = v;
        }
        if (ssid.isEmpty()) throw new IllegalArgumentException("WIFI 二维码缺少名称 (S:)");
        LinkMode mode = ssid.toUpperCase(Locale.ROOT).startsWith("DIRECT-") ? LinkMode.WIFI_DIRECT : LinkMode.WIFI_AP;
        return new ParsedQr(ssid, pwd, "", ssid, "", auth, 0, mode, raw);
    }

    private static ParsedQr tryParseMotoFun(String raw) {
        int idx = raw.toLowerCase(Locale.ROOT).indexOf("wifi=");
        if (idx < 0) return null;
        String tail = raw.substring(idx + 5);
        int amp = tail.indexOf('&');
        if (amp >= 0) tail = tail.substring(0, amp);
        int sharp = tail.indexOf('#');
        if (sharp <= 0) return null;
        String ssid = decode(tail.substring(0, sharp)).trim();
        String pwd = decode(tail.substring(sharp + 1)).trim();
        if (ssid.isEmpty()) return null;
        LinkMode mode = ssid.toUpperCase(Locale.ROOT).startsWith("DIRECT-") ? LinkMode.WIFI_DIRECT : LinkMode.WIFI_AP;
        return new ParsedQr(ssid, pwd, "", ssid, "", pwd.isEmpty() ? "OPEN" : "WPA2", 0, mode, raw);
    }

    private static LinkMode inferLinkMode(String raw, String ssid, int action) {
        if (ssid.toUpperCase(Locale.ROOT).startsWith("DIRECT-")) return LinkMode.WIFI_DIRECT;
        boolean hasP2p = (action & 8) != 0;
        boolean hasAp = (action & 3) != 0;
        if (hasP2p && !hasAp) return LinkMode.WIFI_DIRECT;
        if (hasAp && !hasP2p) return LinkMode.WIFI_AP;
        // QJMotor / EasyRide (_ylqx) with action=0 uses Wi-Fi Direct peer name in ssid=
        if (raw.toLowerCase(Locale.ROOT).contains("_ylqx") && action == 0) {
            return LinkMode.WIFI_DIRECT;
        }
        return LinkMode.WIFI_AP;
    }

    private static Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = decode(pair.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
            String val = decode(pair.substring(eq + 1)).trim();
            if (!key.isEmpty()) map.put(key, val);
        }
        return map;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    private static String unescapeWifi(String s) {
        return s.replace("\\;", ";").replace("\\:", ":").replace("\\\\", "\\");
    }

    static String normalizeMac(String rawMac) {
        if (rawMac == null) return "";
        String cleaned = rawMac.trim().replace("-", ":").toLowerCase(Locale.ROOT);
        if (cleaned.matches("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")) {
            return cleaned;
        }
        String hex = cleaned.replaceAll("[^0-9a-f]", "");
        if (hex.length() == 12) {
            StringBuilder sb = new StringBuilder(17);
            for (int i = 0; i < 12; i += 2) {
                if (i > 0) sb.append(':');
                sb.append(hex, i, i + 2);
            }
            return sb.toString();
        }
        return "";
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static int parseIntSafe(String v, int def) {
        if (v == null || v.isEmpty()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
