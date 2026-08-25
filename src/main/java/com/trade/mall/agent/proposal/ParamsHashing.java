package com.trade.mall.agent.proposal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Proposal 参数的稳定编码与哈希。
 *
 * <p>先生成按 key 排序、完整 JSON 转义的 canonical JSON（规范 JSON），再做 SHA-256。
 * 这样既保持“相同参数稳定得到相同哈希”，也避免旧的 {@code key=value&...} 拼接把
 * {@code {a:"x&b=y"}} 与 {@code {a:"x", b:"y"}} 编成同一串文本。</p>
 */
public final class ParamsHashing {
    private ParamsHashing() {}

    public static String sha256(Map<String, String> params) {
        String canonical = canonicalJson(params);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
    }

    /** 只编码当前 Proposal 真正支持的 {@code Map<String,String>}，不提前造通用 JSON 框架。 */
    public static String canonicalJson(Map<String, String> params) {
        if (params == null) throw new IllegalArgumentException("params must not be null");
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) out.append(',');
            out.append('\"').append(escapeJson(e.getKey())).append("\":\"")
                .append(escapeJson(e.getValue())).append('\"');
            first = false;
        }
        return out.append('}').toString();
    }

    private static String escapeJson(String value) {
        if (value == null) throw new IllegalArgumentException("param value must not be null");
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}

