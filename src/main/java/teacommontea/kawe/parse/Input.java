package teacommontea.kawe.parse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Input {

    private Input() {
    }

    public record Entry(String full, String command, List<String> args, boolean and) {
    }

    public static List<Entry> split(String text) throws InputParseException {
        List<String> parts = new ArrayList<>();
        List<Boolean> joins = new ArrayList<>();
        String input = text;
        int last = 0;
        boolean expression = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case ',', '&', ' ' -> {
                    if (expression) {
                        continue;
                    }
                    String part = input.substring(last, i);
                    if (part.isEmpty()) {
                        throw InputParseException.of("kawe.error.parse.invalid-dangling-character", String.valueOf(c));
                    }
                    parts.add(part);
                    joins.add(c == '&' || c == ' ');
                    last = i + 1;
                }
                case '=' -> expression = true;
                case '[' -> {
                    int close = matchingBracket(input, i);
                    if (close == -1) {
                        input = input + "]";
                        i = input.length();
                    } else {
                        i = close;
                    }
                    expression = false;
                }
                default -> {
                }
            }
        }
        parts.add(input.substring(last));
        List<Entry> out = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            String full = parts.get(i);
            String command = full;
            List<String> args = new ArrayList<>();
            while (!command.isEmpty() && command.charAt(command.length() - 1) == ']') {
                int open = matchingOpen(command, command.length() - 1);
                if (open == -1) {
                    break;
                }
                args.add(command.substring(open + 1, command.length() - 1));
                command = command.substring(0, open);
            }
            Collections.reverse(args);
            out.add(new Entry(full, command, args, i > 0 && joins.get(i - 1)));
        }
        return out;
    }

    public static int matchingBracket(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    public static int matchingOpen(String s, int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ']') {
                depth++;
            } else if (c == '[' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    public static List<String> splitTopLevel(String s, char separator) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int last = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[' || c == '{' || c == '(') {
                depth++;
            } else if (c == ']' || c == '}' || c == ')') {
                depth--;
            } else if (c == separator && depth == 0) {
                out.add(s.substring(last, i));
                last = i + 1;
            }
        }
        out.add(s.substring(last));
        return out;
    }
}
