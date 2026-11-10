package teacommontea.kawe.expr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Parser {

    private enum Kind { NUMBER, ID, OP, END }

    private record Token(Kind kind, String text, int position) {
    }

    private static final String[] OPERATORS = {
            "**", "<<", ">>", "+=", "-=", "*=", "/=", "%=", "^=", "==", "!=", "~=", "<=", ">=", "&&", "||", "++", "--",
            "+", "-", "*", "/", "%", "^", "=", "~", "<", ">", ",", "(", ")", "{", "}", ";", "?", ":", "!"
    };
    private static final Set<String> KEYWORDS = Set.of("if", "else", "while", "do", "for", "break", "continue", "return", "switch", "case", "default");
    private static final Map<String, Double> CONSTANTS = Map.of("e", Math.E, "pi", Math.PI, "true", 1.0, "false", 0.0);

    private final List<Token> tokens;
    private final Map<String, Integer> slots;
    private int index;

    private Parser(String source, Map<String, Integer> slots) {
        this.tokens = lex(source);
        this.slots = slots;
    }

    static Node.Statement parse(String source, Map<String, Integer> slots) {
        Parser parser = new Parser(source, slots);
        Node.Statement root = parser.statements(true);
        if (parser.peek().kind != Kind.END) {
            throw new ExpressionException(parser.peek().position, "Unexpected '" + parser.peek().text + "'");
        }
        return root;
    }

    private static List<Token> lex(String source) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (digit(c) || (c == '.' && i + 1 < length && digit(source.charAt(i + 1)))) {
                int start = i;
                while (i < length && digit(source.charAt(i))) {
                    i++;
                }
                if (i < length && source.charAt(i) == '.') {
                    i++;
                    if (i >= length || !digit(source.charAt(i))) {
                        throw new ExpressionException(i, "Expected digits after '.'");
                    }
                    while (i < length && digit(source.charAt(i))) {
                        i++;
                    }
                }
                if (i < length && (source.charAt(i) == 'e' || source.charAt(i) == 'E')) {
                    int mark = i;
                    i++;
                    if (i < length && (source.charAt(i) == '+' || source.charAt(i) == '-')) {
                        i++;
                    }
                    if (i < length && digit(source.charAt(i))) {
                        while (i < length && digit(source.charAt(i))) {
                            i++;
                        }
                    } else {
                        i = mark;
                    }
                }
                out.add(new Token(Kind.NUMBER, source.substring(start, i), start));
                continue;
            }
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                int start = i;
                while (i < length) {
                    char d = source.charAt(i);
                    if ((d >= 'A' && d <= 'Z') || (d >= 'a' && d <= 'z') || (d >= '0' && d <= '9') || d == '_') {
                        i++;
                    } else {
                        break;
                    }
                }
                out.add(new Token(Kind.ID, source.substring(start, i), start));
                continue;
            }
            String matched = null;
            for (String op : OPERATORS) {
                if (source.startsWith(op, i)) {
                    matched = op;
                    break;
                }
            }
            if (matched == null) {
                throw new ExpressionException(i, "Unexpected character '" + c + "'");
            }
            out.add(new Token(Kind.OP, matched, i));
            i += matched.length();
        }
        out.add(new Token(Kind.END, "<EOF>", length));
        return out;
    }

    private Token peek() {
        return tokens.get(index);
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(index + ahead, tokens.size() - 1));
    }

    private Token next() {
        return tokens.get(index++);
    }

    private boolean at(String text) {
        Token token = peek();
        return (token.kind == Kind.OP || token.kind == Kind.ID) && token.text.equals(text);
    }

    private boolean accept(String text) {
        if (at(text)) {
            index++;
            return true;
        }
        return false;
    }

    private Token expect(String text) {
        if (!at(text)) {
            throw new ExpressionException(peek().position, "Expected '" + text + "' but found '" + peek().text + "'");
        }
        return next();
    }

    private int slot(String name, int position) {
        if (CONSTANTS.containsKey(name)) {
            throw new ExpressionException(position, "'" + name + "' is not a variable");
        }
        if (KEYWORDS.contains(name)) {
            throw new ExpressionException(position, "'" + name + "' is a keyword");
        }
        return slots.computeIfAbsent(name, k -> slots.size());
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private Node.Statement statements(boolean top) {
        return statements(top, false);
    }

    private Node.Statement statements(boolean top, boolean caseBody) {
        int position = peek().position;
        List<Node.Statement> list = new ArrayList<>();
        while (peek().kind != Kind.END && !at("}") && !(caseBody && (at("case") || at("default")))) {
            list.add(statement());
        }
        if (list.isEmpty() && top) {
            throw new ExpressionException(position, "Expected a statement");
        }
        return list.size() == 1 ? list.getFirst() : new Node.Sequence(position, list);
    }

    private Node.Statement statement() {
        Node.Statement statement = statementBody();
        accept(";");
        return statement;
    }

    private Node.Statement statementBody() {
        Token token = peek();
        int position = token.position;
        if (accept("{")) {
            Node.Statement body = statements(false);
            expect("}");
            return body;
        }
        if (token.kind == Kind.ID) {
            switch (token.text) {
                case "if" -> {
                    next();
                    expect("(");
                    Node condition = expression();
                    expect(")");
                    Node.Statement yes = statement();
                    Node.Statement no = accept("else") ? statement() : null;
                    return new Node.If(position, condition, yes, no);
                }
                case "while" -> {
                    next();
                    expect("(");
                    Node condition = expression();
                    expect(")");
                    return new Node.While(position, null, condition, null, statement(), true);
                }
                case "do" -> {
                    next();
                    Node.Statement body = statement();
                    expect("while");
                    expect("(");
                    Node condition = expression();
                    expect(")");
                    return new Node.While(position, null, condition, null, body, false);
                }
                case "for" -> {
                    return forStatement();
                }
                case "break" -> {
                    next();
                    return new Node.Jump(position, Node.Signal.BREAK);
                }
                case "continue" -> {
                    next();
                    return new Node.Jump(position, Node.Signal.CONTINUE);
                }
                case "return" -> {
                    next();
                    return new Node.ReturnStatement(position, expression());
                }
                case "switch" -> {
                    return switchStatement();
                }
                case "case", "default" -> throw new ExpressionException(position, "'" + token.text + "' outside a switch");
                default -> {
                }
            }
        }
        if (at(";")) {
            return new Node.Empty(position);
        }
        return new Node.ExpressionStatement(position, expression());
    }

    private Node.Statement forStatement() {
        int position = next().position;
        expect("(");
        if (peek().kind == Kind.ID && peek(1).text.equals("=") && !KEYWORDS.contains(peek().text)) {
            int mark = index;
            Token counter = next();
            next();
            Node first = expression();
            if (accept(",")) {
                Node last = expression();
                expect(")");
                return new Node.SimpleFor(position, slot(counter.text, counter.position), first, last, statement());
            }
            index = mark;
        }
        Node init = at(";") ? null : expression();
        expect(";");
        Node condition = expression();
        expect(";");
        Node update = at(")") ? null : expression();
        expect(")");
        return new Node.While(position, init, condition, update, statement(), true);
    }

    private Node.Statement switchStatement() {
        int position = next().position;
        expect("(");
        Node target = expression();
        expect(")");
        expect("{");
        List<Double> keys = new ArrayList<>();
        List<Node.Statement> bodies = new ArrayList<>();
        int fallback = -1;
        Set<Double> seen = new HashSet<>();
        while (!at("}")) {
            Token label = peek();
            if (accept("case")) {
                boolean negative = accept("-");
                Token constant = next();
                if (constant.kind != Kind.NUMBER) {
                    throw new ExpressionException(constant.position, "Case label must be a number");
                }
                double key = number(constant);
                if (negative) {
                    key = -key;
                }
                expect(":");
                if (!seen.add(key)) {
                    throw new ExpressionException(label.position, "Duplicate cases detected.");
                }
                keys.add(key);
            } else if (accept("default")) {
                expect(":");
                if (fallback >= 0) {
                    throw new ExpressionException(label.position, "Duplicate default cases detected.");
                }
                fallback = keys.size();
                keys.add(Double.NaN);
            } else {
                throw new ExpressionException(label.position, "Expected 'case' or 'default'");
            }
            bodies.add(statements(false, true));
        }
        expect("}");
        if (keys.isEmpty()) {
            throw new ExpressionException(position, "Switch needs at least one case");
        }
        double[] table = new double[keys.size()];
        for (int i = 0; i < table.length; i++) {
            table[i] = keys.get(i);
        }
        return new Node.Switch(position, target, table, bodies.toArray(Node.Statement[]::new), fallback);
    }

    private static double number(Token token) {
        double value = Double.parseDouble(token.text);
        if (Double.isInfinite(value)) {
            throw new ExpressionException(token.position, "Number is too large: " + token.text);
        }
        return value;
    }

    private Node expression() {
        Token token = peek();
        if (token.kind == Kind.ID && !KEYWORDS.contains(token.text) && peek(1).kind == Kind.OP) {
            String op = peek(1).text;
            if (op.equals("=") || op.equals("+=") || op.equals("-=") || op.equals("*=") || op.equals("/=") || op.equals("%=") || op.equals("^=")) {
                next();
                next();
                Node source = expression();
                return new Node.Assign(token.position, slot(token.text, token.position), token.text, op, source);
            }
        }
        return conditional();
    }

    private Node conditional() {
        Node condition = or();
        if (at("?")) {
            int position = next().position;
            Node yes = expression();
            expect(":");
            Node no = conditional();
            return new Node.Ternary(position, condition, yes, no);
        }
        return condition;
    }

    private Node or() {
        Node left = and();
        while (at("||")) {
            int position = next().position;
            left = new Node.Or(position, left, and());
        }
        return left;
    }

    private Node and() {
        Node left = equality();
        while (at("&&")) {
            int position = next().position;
            left = new Node.And(position, left, equality());
        }
        return left;
    }

    private Node equality() {
        Node left = relational();
        while (at("==") || at("!=") || at("~=")) {
            Token op = next();
            left = new Node.Binary(op.position, op.text, left, relational());
        }
        return left;
    }

    private Node relational() {
        Node left = shift();
        while (at("<") || at(">") || at("<=") || at(">=")) {
            Token op = next();
            left = new Node.Binary(op.position, op.text, left, shift());
        }
        return left;
    }

    private Node shift() {
        Node left = additive();
        while (at("<<") || at(">>")) {
            Token op = next();
            left = new Node.Binary(op.position, op.text, left, additive());
        }
        return left;
    }

    private Node additive() {
        Node left = multiplicative();
        while (at("+") || at("-")) {
            Token op = next();
            left = new Node.Binary(op.position, op.text, left, multiplicative());
        }
        return left;
    }

    private Node multiplicative() {
        Node left = power();
        while (at("*") || at("/") || at("%")) {
            Token op = next();
            left = new Node.Binary(op.position, op.text, left, power());
        }
        return left;
    }

    private Node power() {
        Node left = unary();
        while (at("^") || at("**")) {
            Token op = next();
            left = new Node.Binary(op.position, "^", left, unary());
        }
        return left;
    }

    private Node unary() {
        Token token = peek();
        if (at("++") || at("--")) {
            next();
            Token target = next();
            if (target.kind != Kind.ID) {
                throw new ExpressionException(target.position, "Expected a variable after '" + token.text + "'");
            }
            return new Node.Step(token.position, slot(target.text, target.position), target.text, token.text.equals("++") ? 1 : -1, true);
        }
        if (at("+") || at("-")) {
            next();
            return new Node.Unary(token.position, token.text.charAt(0), unary());
        }
        if (at("~")) {
            next();
            return new Node.Unary(token.position, '~', unary());
        }
        if (at("!")) {
            next();
            return new Node.Unary(token.position, '!', unary());
        }
        return postfix();
    }

    private Node postfix() {
        Token token = peek();
        if (token.kind == Kind.ID && (peek(1).text.equals("++") || peek(1).text.equals("--")) && peek(1).kind == Kind.OP) {
            next();
            Token op = next();
            return new Node.Step(token.position, slot(token.text, token.position), token.text, op.text.equals("++") ? 1 : -1, false);
        }
        Node node = primary();
        while (at("!")) {
            int position = next().position;
            node = new Node.Factorial(position, node);
        }
        return node;
    }

    private Node primary() {
        Token token = next();
        switch (token.kind) {
            case NUMBER -> {
                return new Node.Num(token.position, number(token));
            }
            case ID -> {
                if (KEYWORDS.contains(token.text)) {
                    throw new ExpressionException(token.position, "Unexpected keyword '" + token.text + "'");
                }
                if (at("(")) {
                    next();
                    List<Node> args = new ArrayList<>();
                    if (!at(")")) {
                        do {
                            args.add(expression());
                        } while (accept(","));
                    }
                    expect(")");
                    Functions.Signature signature = Functions.resolve(token.text, args.size(), token.position);
                    return new Node.Call(token.position, signature.function(), args.toArray(Node[]::new));
                }
                Double constant = CONSTANTS.get(token.text);
                if (constant != null) {
                    return new Node.Num(token.position, constant);
                }
                return new Node.Read(token.position, slot(token.text, token.position), token.text);
            }
            case OP -> {
                if (token.text.equals("(")) {
                    Node inner = expression();
                    expect(")");
                    return inner;
                }
                throw new ExpressionException(token.position, "Unexpected '" + token.text + "'");
            }
            default -> throw new ExpressionException(token.position, "Unexpected end of expression");
        }
    }
}
