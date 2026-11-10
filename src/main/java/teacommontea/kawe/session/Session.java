package teacommontea.kawe.session;

import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Material;
import org.bukkit.World;
import teacommontea.kawe.Settings;
import teacommontea.kawe.clipboard.ClipboardHolder;
import teacommontea.kawe.color.BlockColors;
import teacommontea.kawe.command.Cmd;
import teacommontea.kawe.engine.ChangeSet;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.engine.EditCancelledException;
import teacommontea.kawe.engine.KWorld;
import teacommontea.kawe.engine.Preload;
import teacommontea.kawe.engine.SideEffect;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.region.selector.CuboidSelector;
import teacommontea.kawe.region.selector.Selector;
import teacommontea.kawe.region.selector.Selectors;
import teacommontea.kawe.tool.NavigationWand;
import teacommontea.kawe.tool.SelectionWand;
import teacommontea.kawe.tool.Tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class Session {

    private final UUID owner;
    private final List<ChangeSet> history = new ArrayList<>();
    private int historyPointer;
    private Selector selector = new CuboidSelector();
    private World selectorWorld;
    private String defaultSelector;
    private UUID worldOverride;
    private boolean tips = true;
    private ClipboardHolder clipboard;
    private String maskInput;
    private String sourceMaskInput;
    private String transformInput;
    private final Map<Material, Tool> tools = new HashMap<>(Map.of(Material.WOODEN_AXE, SelectionWand.INSTANCE, Material.COMPASS, NavigationWand.INSTANCE));
    private Tool superPickaxeTool;
    private BlockColors.Palette palette;
    private List<Count> lastDistribution;
    private boolean lastDistributionStates;

    public record Count(BlockState state, long amount) {
    }
    private boolean placeAtPos1;
    private boolean fast;
    private Boolean bypass;
    private boolean superPickaxe;
    private long limit = Settings.get().defaultChangeLimit();
    private int timeout = Settings.get().timeout();
    private final Set<SideEffect> sideEffects = SideEffect.defaults();
    private final List<ChangeSet> unsaved = new ArrayList<>();
    private int maxHistory;
    private CompletableFuture<?> chain = CompletableFuture.completedFuture(null);
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicInteger waiting = new AtomicInteger();
    private final Set<Edit> running = ConcurrentHashMap.newKeySet();
    private Pending pending;

    public record Pending(Cmd command, String label, String input, long deadline, String selection) {
    }

    public synchronized void pending(Pending value) {
        this.pending = value;
    }

    public synchronized Pending takePending() {
        Pending value = pending;
        pending = null;
        return value;
    }

    public Session(UUID owner, int maxHistory) {
        this.owner = owner;
        this.maxHistory = maxHistory;
    }

    public UUID owner() {
        return owner;
    }

    public synchronized Selector selector(World world) {
        if (selectorWorld == null || !selectorWorld.equals(world)) {
            selectorWorld = world;
            Selector fresh = defaultSelector == null ? null : Selectors.create(defaultSelector, world.getMinHeight(), world.getMaxHeight() - 1);
            if (fresh == null) {
                selector.clear();
            } else {
                selector = fresh;
            }
        }
        return selector;
    }

    public synchronized void defaultSelector(String type) {
        this.defaultSelector = type;
    }

    public synchronized Selector selectorRaw() {
        return selector;
    }

    public synchronized World selectorWorld() {
        return selectorWorld;
    }

    public void selector(World world, Selector value) {
        synchronized (this) {
            this.selectorWorld = world;
            this.selector = value;
        }
        Preload.selected(owner);
    }

    public synchronized List<ChangeSet> drainUnsaved() {
        if (unsaved.isEmpty()) {
            return List.of();
        }
        List<ChangeSet> out = List.copyOf(unsaved);
        unsaved.clear();
        return out;
    }

    public synchronized void remember(ChangeSet changes, boolean keepEmpty) {
        if (changes == null || (!keepEmpty && changes.isEmpty())) {
            return;
        }
        unsaved.add(changes);
        while (history.size() > historyPointer) {
            history.removeLast();
        }
        history.add(changes);
        while (maxHistory > 0 && history.size() > maxHistory) {
            history.removeFirst();
        }
        historyPointer = history.size();
    }

    public synchronized ChangeSet peekUndo() {
        return historyPointer <= 0 ? null : history.get(historyPointer - 1);
    }

    public synchronized ChangeSet peekRedo() {
        return historyPointer >= history.size() ? null : history.get(historyPointer);
    }

    public synchronized void restore(List<ChangeSet> steps, int done) {
        if (!history.isEmpty()) {
            return;
        }
        history.addAll(steps);
        historyPointer = done;
    }

    public synchronized boolean advance(ChangeSet expected, boolean undo) {
        if (undo) {
            if (historyPointer > 0 && history.get(historyPointer - 1) == expected) {
                historyPointer--;
                return true;
            }
            return false;
        }
        if (historyPointer < history.size() && history.get(historyPointer) == expected) {
            historyPointer++;
            return true;
        }
        return false;
    }

    public synchronized void clearHistory() {
        history.clear();
        historyPointer = 0;
    }

    public synchronized int historySize() {
        return history.size();
    }

    public synchronized List<ChangeSet> historyView() {
        return List.copyOf(history);
    }

    public synchronized ClipboardHolder clipboard() {
        return clipboard;
    }

    public synchronized void clipboard(ClipboardHolder value) {
        this.clipboard = value;
    }

    public synchronized UUID worldOverride() {
        return worldOverride;
    }

    public synchronized void worldOverride(World value) {
        this.worldOverride = value == null ? null : value.getUID();
    }

    public synchronized String transformInput() {
        return transformInput;
    }

    public synchronized void transformInput(String value) {
        this.transformInput = value;
    }

    public synchronized String maskInput() {
        return maskInput;
    }

    public synchronized void maskInput(String value) {
        this.maskInput = value;
    }

    public synchronized String sourceMaskInput() {
        return sourceMaskInput;
    }

    public synchronized void sourceMaskInput(String value) {
        this.sourceMaskInput = value;
    }

    public synchronized Tool tool(Material item) {
        return tools.get(item);
    }

    public synchronized void tool(Material item, Tool value) {
        if (value == null) {
            tools.remove(item);
        } else {
            tools.put(item, value);
        }
    }

    public synchronized void lastDistribution(List<Count> value, boolean states) {
        this.lastDistribution = List.copyOf(value);
        this.lastDistributionStates = states;
    }

    public synchronized List<Count> lastDistribution() {
        return lastDistribution;
    }

    public synchronized boolean lastDistributionStates() {
        return lastDistributionStates;
    }

    public synchronized BlockColors.Palette palette() {
        return palette == null ? BlockColors.all() : palette;
    }

    public synchronized void palette(BlockColors.Palette value) {
        this.palette = value;
    }

    public synchronized Tool superPickaxeTool() {
        return superPickaxeTool;
    }

    public synchronized void superPickaxeTool(Tool value) {
        this.superPickaxeTool = value;
    }

    public synchronized boolean placeAtPos1() {
        return placeAtPos1;
    }

    public synchronized void placeAtPos1(boolean value) {
        this.placeAtPos1 = value;
    }

    public synchronized Boolean bypass() {
        return bypass;
    }

    public synchronized void bypass(Boolean value) {
        this.bypass = value;
    }

    public synchronized boolean fast() {
        return fast;
    }

    public synchronized void fast(boolean value) {
        this.fast = value;
    }

    public synchronized boolean superPickaxe() {
        return superPickaxe;
    }

    public synchronized void superPickaxe(boolean value) {
        this.superPickaxe = value;
    }

    public synchronized long limit() {
        return limit;
    }

    public synchronized void limit(long value) {
        this.limit = value;
    }

    public synchronized Set<SideEffect> sideEffects() {
        return Set.copyOf(sideEffects);
    }

    public synchronized void sideEffect(SideEffect effect, boolean enabled) {
        if (enabled) {
            sideEffects.add(effect);
        } else {
            sideEffects.remove(effect);
        }
    }

    public synchronized boolean tips() {
        return tips;
    }

    public synchronized void tips(boolean value) {
        this.tips = value;
    }

    public synchronized int timeout() {
        return timeout;
    }

    public synchronized void timeout(int value) {
        this.timeout = value;
    }

    public synchronized void maxHistory(int value) {
        this.maxHistory = value;
    }

    public boolean sameWorld(KWorld world) {
        World current = selectorWorld();
        return current != null && current.equals(world.bukkit());
    }

    public synchronized <T> CompletableFuture<T> queue(Supplier<CompletableFuture<T>> job) {
        int queuedAt = generation.get();
        waiting.incrementAndGet();
        CompletableFuture<T> next = chain.handle((ignored, error) -> null).thenCompose(v -> {
            waiting.decrementAndGet();
            if (generation.get() != queuedAt) {
                return CompletableFuture.failedFuture(new EditCancelledException());
            }
            return job.get();
        });
        chain = next;
        return next;
    }

    public void track(Edit edit) {
        running.add(edit);
    }

    public void untrack(Collection<Edit> edits) {
        running.removeAll(edits);
    }

    public int cancel() {
        int count = waiting.get();
        generation.incrementAndGet();
        for (Edit edit : running) {
            if (edit.cancel()) {
                count++;
            }
        }
        return count;
    }
}
