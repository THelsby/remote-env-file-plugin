package io.jenkins.plugins.remoteenvfile;

import hudson.AbortException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

final class DotenvParser {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private DotenvParser() {
    }

    static Map<String, String> parse(String content) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> seenKeys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String normalized = stripBom(lineNumber, line);
                String trimmed = normalized.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("export ") || trimmed.startsWith("export\t")) {
                    throw error(lineNumber, "export prefixes are not supported");
                }

                int separator = normalized.indexOf('=');
                if (separator <= 0) {
                    throw error(lineNumber, "expected KEY=VALUE");
                }

                String key = normalized.substring(0, separator).trim();
                if (!KEY_PATTERN.matcher(key).matches()) {
                    throw error(lineNumber, "invalid variable name '" + key + "'");
                }

                String rawValue = normalized.substring(separator + 1).trim();
                String value = parseValue(lineNumber, rawValue);
                if (!seenKeys.add(key)) {
                    throw error(lineNumber, "duplicate variable '" + key + "'");
                }
                values.put(key, value);
            }
        }
        return Collections.unmodifiableMap(values);
    }

    private static String stripBom(int lineNumber, String line) {
        if (lineNumber == 1 && !line.isEmpty() && line.charAt(0) == '\uFEFF') {
            return line.substring(1);
        }
        return line;
    }

    private static String parseValue(int lineNumber, String rawValue) throws AbortException {
        if (rawValue.isEmpty()) {
            return "";
        }
        if (rawValue.startsWith("\"")) {
            return parseDoubleQuoted(lineNumber, rawValue);
        }
        if (rawValue.startsWith("'")) {
            return parseSingleQuoted(lineNumber, rawValue);
        }
        if (rawValue.indexOf('\n') >= 0 || rawValue.indexOf('\r') >= 0) {
            throw error(lineNumber, "multiline values are not supported");
        }
        if (containsInterpolation(rawValue, false)) {
            throw error(lineNumber, "variable interpolation is not supported");
        }
        return rawValue;
    }

    private static String parseSingleQuoted(int lineNumber, String rawValue) throws AbortException {
        if (rawValue.length() < 2 || !rawValue.endsWith("'")) {
            throw error(lineNumber, "unterminated single-quoted value");
        }
        String inner = rawValue.substring(1, rawValue.length() - 1);
        String trailing = rawValue.substring(rawValue.length() - 1).trim();
        if (!trailing.equals("'")) {
            throw error(lineNumber, "unexpected trailing content after quoted value");
        }
        if (containsInterpolation(inner, true)) {
            throw error(lineNumber, "variable interpolation is not supported");
        }
        return inner;
    }

    private static String parseDoubleQuoted(int lineNumber, String rawValue) throws AbortException {
        if (rawValue.length() < 2 || !rawValue.endsWith("\"")) {
            throw error(lineNumber, "unterminated double-quoted value");
        }
        String inner = rawValue.substring(1, rawValue.length() - 1);
        String trailing = rawValue.substring(rawValue.length() - 1).trim();
        if (!trailing.equals("\"")) {
            throw error(lineNumber, "unexpected trailing content after quoted value");
        }
        if (containsInterpolation(inner, true)) {
            throw error(lineNumber, "variable interpolation is not supported");
        }

        StringBuilder value = new StringBuilder(inner.length());
        boolean escaping = false;
        for (int index = 0; index < inner.length(); index++) {
            char current = inner.charAt(index);
            if (!escaping) {
                if (current == '\\') {
                    escaping = true;
                    continue;
                }
                value.append(current);
                continue;
            }
            switch (current) {
            case '\\':
                value.append('\\');
                break;
            case '"':
                value.append('"');
                break;
            case 'n':
                value.append('\n');
                break;
            case 'r':
                value.append('\r');
                break;
            case 't':
                value.append('\t');
                break;
            case '$':
                value.append('$');
                break;
            default:
                throw error(lineNumber, "unsupported escape sequence \\" + current + "'");
            }
            escaping = false;
        }
        if (escaping) {
            throw error(lineNumber, "trailing escape in double-quoted value");
        }
        return value.toString();
    }

    private static boolean containsInterpolation(String value, boolean quoted) {
        boolean escaping = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quoted && escaping) {
                escaping = false;
                continue;
            }
            if (quoted && current == '\\') {
                escaping = true;
                continue;
            }
            if (current != '$' || index + 1 >= value.length()) {
                continue;
            }
            char next = value.charAt(index + 1);
            if (next == '{' || next == '_' || Character.isLetter(next)) {
                return true;
            }
        }
        return false;
    }

    private static AbortException error(int lineNumber, String message) {
        return new AbortException("Invalid dotenv content at line " + lineNumber + ": " + message);
    }
}
