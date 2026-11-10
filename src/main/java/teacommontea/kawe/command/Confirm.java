package teacommontea.kawe.command;

import teacommontea.kawe.Settings;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.region.IncompleteRegionException;
import teacommontea.kawe.region.Region;

public enum Confirm {
    REGION, RADIUS, LIMIT, ALWAYS, WILDCARD, WORLD;

    private static final int LIMIT_MAX = 50;

    boolean passes(CmdContext c, String argument) {
        String line = c.commandLine();
        return switch (this) {
            case ALWAYS -> {
                c.print("kawe.cancel.reason.confirm", line);
                yield false;
            }
            case WORLD -> {
                String raw = c.raw(argument);
                boolean whole = raw == null ? Settings.get().butcherDefaultRadius() == -1 && Settings.get().maxButcherRadius() == -1 : raw.trim().equals("-1");
                if (!whole) {
                    yield true;
                }
                c.print("kawe.cancel.reason.confirm", line);
                yield false;
            }
            case WILDCARD -> {
                if (!"*".equals(c.raw(argument))) {
                    yield true;
                }
                c.print("kawe.cancel.reason.confirm", line);
                yield false;
            }
            case REGION -> {
                Region region;
                try {
                    region = c.selection();
                } catch (IncompleteRegionException | CommandError e) {
                    yield true;
                }
                BlockVec min = region.min();
                BlockVec max = region.max();
                long area = ((long) max.x() - min.x() + 1) * ((long) max.z() - min.z() + 1);
                long limit = Settings.get().confirmArea();
                if (limit < 0 || area <= limit) {
                    yield true;
                }
                c.print("kawe.cancel.reason.confirm.region", min.toString(), max.toString(), line, region.volume());
                yield false;
            }
            case RADIUS -> {
                double value = number(c, argument);
                int max = Settings.get().maxRadius();
                if (max < 0 || value <= max) {
                    yield true;
                }
                c.print("kawe.cancel.reason.confirm.radius", value, max, line);
                yield false;
            }
            case LIMIT -> {
                double value = number(c, argument);
                if (value <= LIMIT_MAX) {
                    yield true;
                }
                c.print("kawe.cancel.reason.confirm.limit", value, LIMIT_MAX, line);
                yield false;
            }
        };
    }

    private static double number(CmdContext c, String argument) {
        String raw = c.raw(argument);
        if (raw == null) {
            return 0;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
