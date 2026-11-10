package teacommontea.kawe.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import io.papermc.paper.command.brigadier.argument.resolvers.PlayerProfileListResolver;
import org.bukkit.entity.Player;
import teacommontea.kawe.engine.Preload;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class Brigadier {

    private record Token<N>(ArgumentType<N> nativeType, boolean greedy) implements CustomArgumentType<String, N> {
        @Override
        public String parse(StringReader reader) throws CommandSyntaxException {
            int start = reader.getCursor();
            if (greedy) {
                reader.setCursor(reader.getTotalLength());
                return reader.getString().substring(start);
            }
            if (reader.canRead() && reader.peek() == '"') {
                reader.skip();
                while (reader.canRead()) {
                    char c = reader.read();
                    if (c == '\\' && reader.canRead()) {
                        reader.skip();
                    } else if (c == '"') {
                        break;
                    }
                }
            } else {
                int depth = 0;
                while (reader.canRead()) {
                    char c = reader.peek();
                    if (c == '{' || c == '[') {
                        depth++;
                    } else if ((c == '}' || c == ']') && depth > 0) {
                        depth--;
                    } else if (c == ' ' && depth == 0) {
                        break;
                    }
                    reader.skip();
                }
            }
            if (reader.getCursor() == start) {
                throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().createWithContext(reader);
            }
            return reader.getString().substring(start, reader.getCursor());
        }

        @Override
        public ArgumentType<N> getNativeType() {
            return nativeType;
        }
    }

    private static final Token<PlayerProfileListResolver> SUGGESTED = new Token<>(ArgumentTypes.playerProfiles(), false);
    private static final Token<String> WORD = new Token<>(StringArgumentType.word(), false);
    private static final Token<Integer> INTEGER = new Token<>(IntegerArgumentType.integer(), false);
    private static final Token<Double> DECIMAL = new Token<>(DoubleArgumentType.doubleArg(), false);
    private static final Token<String> REST = new Token<>(StringArgumentType.greedyString(), true);

    private Brigadier() {
    }

    static LiteralCommandNode<CommandSourceStack> build(Cmd root) {
        LiteralArgumentBuilder<CommandSourceStack> node = io.papermc.paper.command.brigadier.Commands.literal(root.name);
        attach(node, root, root);
        return node.build();
    }

    private static void attach(ArgumentBuilder<CommandSourceStack, ?> node, Cmd command, Cmd root) {
        Command<CommandSourceStack> run = context -> run(root, context);
        SuggestionProvider<CommandSourceStack> suggest = (context, builder) -> suggest(root, context, builder);
        if (command.permission != null) {
            String permission = command.permission;
            node.requires(source -> source.getSender().hasPermission(permission));
        }
        for (Cmd sub : command.subs.values()) {
            for (String label : names(sub)) {
                LiteralArgumentBuilder<CommandSourceStack> child = io.papermc.paper.command.brigadier.Commands.literal(label);
                attach(child, sub, root);
                node.then(child);
            }
        }
        List<Cmd.Param> params = command.params;
        int required = 0;
        for (Cmd.Param param : params) {
            if (!param.optional()) {
                required++;
            }
        }
        ArgumentBuilder<CommandSourceStack, ?> tail = null;
        if (!command.flags.isEmpty()) {
            List<String> flags = new ArrayList<>();
            for (Cmd.Flag flag : command.flags.values()) {
                flags.add("-" + flag.key());
            }
            tail = io.papermc.paper.command.brigadier.Commands.argument(String.join("|", flags), REST).executes(run);
        }
        for (int i = params.size() - 1; i >= 0; i--) {
            Cmd.Param param = params.get(i);
            boolean greedy = param.type().greedy();
            boolean suggested = suggests(param.type());
            Token<?> type = greedy ? REST : suggested ? SUGGESTED : param.type() == Types.INT ? INTEGER : param.type() == Types.DOUBLE ? DECIMAL : WORD;
            RequiredArgumentBuilder<CommandSourceStack, String> argument = io.papermc.paper.command.brigadier.Commands.argument(param.name(), type);
            if (suggested) {
                argument.suggests(suggest);
            }
            if (i + 1 >= required) {
                argument.executes(run);
            }
            if (tail != null && !greedy) {
                argument.then(tail);
            }
            tail = argument;
        }
        if (tail != null) {
            node.then(tail);
        }
        if (required == 0 || command.handler == null) {
            node.executes(run);
        }
    }

    private static boolean suggests(ArgType<?> type) {
        try {
            return type.getClass().getMethod("suggest", String.class, CmdContext.class).getDeclaringClass() != ArgType.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static List<String> names(Cmd command) {
        if (command.aliases.isEmpty()) {
            return List.of(command.name);
        }
        List<String> out = new ArrayList<>(command.aliases.size() + 1);
        out.add(command.name);
        out.addAll(command.aliases);
        return out;
    }

    private static String rest(String input) {
        int space = input.indexOf(' ');
        return space < 0 ? "" : input.substring(space + 1);
    }

    private static int run(Cmd root, CommandContext<CommandSourceStack> context) {
        Commands.dispatch(root, root.name, context.getSource().getSender(), rest(context.getInput()));
        return Command.SINGLE_SUCCESS;
    }

    private static CompletableFuture<Suggestions> suggest(Cmd root, CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String input = builder.getInput();
        if (context.getSource().getSender() instanceof Player player) {
            Preload.typing(player);
        }
        String[] args = rest(input).split(" ", -1);
        SuggestionsBuilder out = builder.createOffset(input.lastIndexOf(' ') + 1);
        for (String value : Commands.suggestions(root, context.getSource().getSender(), args)) {
            out.suggest(value);
        }
        return out.buildFuture();
    }
}
