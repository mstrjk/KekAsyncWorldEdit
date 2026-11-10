package teacommontea.kawe.region.selector;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.region.IncompleteRegionException;
import teacommontea.kawe.region.Region;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

public interface Selector {

    String typeName();

    boolean selectPrimary(BlockVec pos);

    boolean selectSecondary(BlockVec pos);

    void explainPrimary(Audience to, BlockVec pos);

    void explainSecondary(Audience to, BlockVec pos);

    boolean isDefined();

    Region region() throws IncompleteRegionException;

    Optional<Region> partialRegion();

    BlockVec primary() throws IncompleteRegionException;

    void learn(Region changed);

    void clear();

    List<Component> info();


    default boolean needsWorld() {
        return false;
    }

    default void resolve(Edit reader) {
    }

    default long volume() {
        return partialRegion().map(Region::volume).orElse(0L);
    }

    static String number(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString();
    }
}
