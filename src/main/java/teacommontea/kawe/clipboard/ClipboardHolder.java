package teacommontea.kawe.clipboard;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class ClipboardHolder {

    private final List<Clipboard> clipboards;
    private final List<String> names;
    private final Supplier<Clipboard> lazy;
    private volatile Transform transform = Transform.IDENTITY;
    private volatile boolean randomRotation;

    public ClipboardHolder(Clipboard clipboard) {
        this(List.of(clipboard), List.of("0"));
    }

    public ClipboardHolder(List<Clipboard> clipboards, List<String> names) {
        this.clipboards = List.copyOf(clipboards);
        this.names = List.copyOf(names);
        this.lazy = null;
    }

    public ClipboardHolder(Supplier<Clipboard> lazy) {
        this.clipboards = List.of();
        this.names = List.of("lazy");
        this.lazy = lazy;
    }

    public Clipboard clipboard() {
        if (lazy != null) {
            return lazy.get();
        }
        if (clipboards.size() == 1) {
            return clipboards.getFirst();
        }
        return clipboards.get(ThreadLocalRandom.current().nextInt(clipboards.size()));
    }

    public List<Clipboard> clipboards() {
        return lazy != null ? List.of(lazy.get()) : clipboards;
    }

    public List<String> names() {
        return names;
    }

    public Transform transform() {
        if (randomRotation) {
            return Transform.IDENTITY.rotateY(ThreadLocalRandom.current().nextInt(4) * 90).combine(transform);
        }
        return transform;
    }

    public Transform base() {
        return transform;
    }

    public boolean randomRotation() {
        return randomRotation;
    }

    public ClipboardHolder renamed(List<String> next) {
        ClipboardHolder holder = lazy != null ? new ClipboardHolder(lazy) : new ClipboardHolder(clipboards, next);
        holder.transform = transform;
        holder.randomRotation = randomRotation;
        return holder;
    }

    public void randomRotation(boolean value) {
        this.randomRotation = value;
    }

    public void transform(Transform value) {
        this.transform = value;
    }
}
