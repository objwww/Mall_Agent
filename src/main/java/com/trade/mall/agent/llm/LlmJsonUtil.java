package com.trade.mall.agent.llm;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一个刻意最小化的 JSON 解析器——**不是**要重新发明 Jackson。
 *
 * <p>为什么手写而不是引入一个 JSON 库：Maven Central 被出口策略拦截（D1 起反复确认的
 * 沙箱约束），本项目至今所有第三方依赖都是"文档里写了、代码里没法引入"。D6 `M-CAP-01`
 * 要求"结构化输出：schema 约束 + 解析失败→确定失败"，MCP 响应又需要嵌套对象和数组，
 * 两者仍完全在一个小型
 * 递归下降解析器的合理范围内，比为了避免"重新发明轮子"这条一般性顾虑而在这里引入
 * 一个假的/简化的"JSON 库"更诚实——生产环境接入真实 LLM SDK 时，这个类应该被替换成
 * Jackson/Gson 之类的真实实现，见 `D6-REPORT.md` §4。</p>
 *
 * <p><b>D7 变更</b>：D6 里这是 {@code understanding} 包下的包内私有类，只服务
 * {@code TicketUnderstandingService} 一个调用方。D7 的 {@code reasoning.ReasoningService}
 * 需要解析结构完全同构的另一种扁平 JSON（`Finding` 的结构化输出），没有理由维护第二份
 * 一模一样的递归下降解析器——那样两份实现迟早会在某个转义字符或错误信息上悄悄分叉，
 * 变成两个只有一个人记得住区别的"同一个东西"。挪到 `llm` 包、改为包外可见，是因为
 * "解析 LLM 的结构化文本输出"本来就是 `agent.llm` 这个隔离区该管的横切能力，
 * 不专属于任何一个具体的 `M-CAP-*` 能力——`understanding`/`reasoning` 两个包各自只关心
 * "解析出来的 {@code Map<String,Object>} 该怎么翻译成本域的结果类型"，这才是它们各自的
 * 业务逻辑，解析本身不是。</p>
 *
 * <p>能解析标准 JSON 对象、嵌套对象与数组；字符串支持标准 JSON 转义。任何语法不对的地方都抛
 * {@link IllegalArgumentException}——调用方把它当作"LLM 输出不满足 schema"的信号，
 * 触发"追加修复提示、重试"。</p>
 */
public final class LlmJsonUtil {
    private LlmJsonUtil() {}

    public static Map<String, Object> parseFlatObject(String json) {
        if (json == null) throw new IllegalArgumentException("null JSON input");
        Cursor c = new Cursor(json);
        c.skipWs();
        Map<String, Object> result = parseObject(c);
        c.skipWs();
        c.expectEnd();
        return result;
    }

    private static Map<String,Object> parseObject(Cursor c) {
        c.expect('{');
        Map<String,Object> result = new LinkedHashMap<>();
        c.skipWs();
        if (c.pos < c.len && c.peek() == '}') { c.next(); return result; }
        while (true) {
            c.skipWs();
            String key = parseString(c);
            c.skipWs();
            c.expect(':');
            c.skipWs();
            Object value = parseValue(c);
            result.put(key, value);
            c.skipWs();
            char sep = c.next();
            if (sep == ',') continue;
            if (sep == '}') break;
            throw new IllegalArgumentException("expected ',' or '}' at position " + (c.pos - 1));
        }
        return result;
    }

    private static Object parseValue(Cursor c) {
        char ch = c.peek();
        if (ch == '"') return parseString(c);
        if (ch == '{') return parseObject(c);
        if (ch == '[') return parseArray(c);
        if (ch == 't' || ch == 'f') return parseBoolean(c);
        if (ch == 'n') { c.expectLiteral("null"); return null; }
        if (ch == '-' || Character.isDigit(ch)) return parseNumber(c);
        throw new IllegalArgumentException("unexpected character '" + ch + "' at position " + c.pos);
    }

    private static String parseString(Cursor c) {
        c.expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (c.pos >= c.len) throw new IllegalArgumentException("unterminated string");
            char ch = c.next();
            if (ch == '"') break;
            if (ch == '\\') {
                if (c.pos >= c.len) throw new IllegalArgumentException("unterminated escape");
                char esc = c.next();
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (c.pos + 4 > c.len) throw new IllegalArgumentException("unterminated unicode escape");
                        try { sb.append((char) Integer.parseInt(c.src.substring(c.pos, c.pos + 4), 16)); }
                        catch (NumberFormatException bad) { throw new IllegalArgumentException("invalid unicode escape"); }
                        c.pos += 4;
                    }
                    default -> throw new IllegalArgumentException("unsupported escape \\" + esc);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static List<Object> parseArray(Cursor c) {
        c.expect('[');
        List<Object> out = new ArrayList<>();
        c.skipWs();
        if (c.peek() == ']') { c.next(); return out; }
        while (true) {
            c.skipWs();
            out.add(parseValue(c));
            c.skipWs();
            char sep = c.next();
            if (sep == ',') continue;
            if (sep == ']') break;
            throw new IllegalArgumentException("expected ',' or ']' in array at position " + (c.pos - 1));
        }
        return out;
    }

    private static Boolean parseBoolean(Cursor c) {
        if (c.peek() == 't') { c.expectLiteral("true"); return Boolean.TRUE; }
        c.expectLiteral("false");
        return Boolean.FALSE;
    }

    private static Double parseNumber(Cursor c) {
        int start = c.pos;
        if (c.peek() == '-') c.next();
        while (c.pos < c.len && (Character.isDigit(c.peek()) || c.peek() == '.' || c.peek() == 'e' || c.peek() == 'E' || c.peek() == '+' || c.peek() == '-')) {
            c.next();
        }
        String num = c.src.substring(start, c.pos);
        try {
            return Double.parseDouble(num);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid number literal '" + num + "'");
        }
    }

    private static final class Cursor {
        final String src;
        final int len;
        int pos = 0;
        Cursor(String src) { this.src = src; this.len = src.length(); }

        char peek() {
            if (pos >= len) throw new IllegalArgumentException("unexpected end of input");
            return src.charAt(pos);
        }
        char next() {
            if (pos >= len) throw new IllegalArgumentException("unexpected end of input");
            return src.charAt(pos++);
        }
        void skipWs() { while (pos < len && Character.isWhitespace(src.charAt(pos))) pos++; }
        void expect(char expected) {
            char actual = next();
            if (actual != expected) throw new IllegalArgumentException("expected '" + expected + "' but got '" + actual + "' at position " + (pos - 1));
        }
        void expectLiteral(String literal) {
            if (pos + literal.length() > len || !src.startsWith(literal, pos)) {
                throw new IllegalArgumentException("expected literal '" + literal + "' at position " + pos);
            }
            pos += literal.length();
        }
        void expectEnd() {
            if (pos != len) throw new IllegalArgumentException("trailing content after JSON object at position " + pos);
        }
    }
}
