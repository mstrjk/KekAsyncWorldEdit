package teacommontea.kawe.schematic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record ImportReport(int unknownBlocks, int fallbackMappings, int invalidBlockEntities, int invalidEntities, boolean newerVersion, boolean lossy, List<String> details) {

    public static final ImportReport CLEAN = new ImportReport(0, 0, 0, 0, false, false, List.of());

    public static final class Builder {
        private final Map<String, Integer> unknown = new TreeMap<>();
        private final Map<String, Integer> fallback = new TreeMap<>();
        private final List<String> notes = new ArrayList<>();
        private int unknownBlocks;
        private int fallbackMappings;
        private int invalidBlockEntities;
        private int invalidEntities;
        private boolean newerVersion;

        public void unknownBlock(String name) {
            unknownBlocks++;
            unknown.merge(name, 1, Integer::sum);
        }

        public void fallbackMapping(String name) {
            fallbackMappings++;
            fallback.merge(name, 1, Integer::sum);
        }

        public void dataVersion(int version, int current) {
            if (version > current) {
                newerVersion = true;
                note("saved with data version " + version + ", newer than this server (" + current + "); newer content cannot be downgraded");
            }
        }

        public void invalidBlockEntity(String reason) {
            invalidBlockEntities++;
            note("block entity: " + reason);
        }

        public void invalidEntity(String reason) {
            invalidEntities++;
            note("entity: " + reason);
        }

        private void note(String text) {
            if (notes.size() < 50) {
                notes.add(text);
            }
        }

        public ImportReport build() {
            List<String> details = new ArrayList<>();
            if (!unknown.isEmpty()) {
                details.add("unknown blocks placed as air: " + summary(unknown));
            }
            if (!fallback.isEmpty()) {
                details.add("fallback mappings used: " + summary(fallback));
            }
            details.addAll(notes);
            boolean lossy = newerVersion || unknownBlocks + fallbackMappings + invalidBlockEntities + invalidEntities > 0;
            return lossy
                    ? new ImportReport(unknownBlocks, fallbackMappings, invalidBlockEntities, invalidEntities, newerVersion, true, List.copyOf(details))
                    : CLEAN;
        }

        private static String summary(Map<String, Integer> counts) {
            StringBuilder out = new StringBuilder();
            int shown = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (shown++ == 30) {
                    out.append(", ...");
                    break;
                }
                if (!out.isEmpty()) {
                    out.append(", ");
                }
                out.append(entry.getKey()).append(" x").append(entry.getValue());
            }
            return out.toString();
        }
    }
}
