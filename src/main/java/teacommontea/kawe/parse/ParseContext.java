package teacommontea.kawe.parse;

import teacommontea.kawe.engine.Extent;
import teacommontea.kawe.engine.KWorld;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.session.Actor;
import teacommontea.kawe.session.Session;

public record ParseContext(Actor actor, Session session, KWorld world, Extent extent, BlockVec anchor) {

    public ParseContext(Actor actor, Session session, KWorld world, Extent extent) {
        this(actor, session, world, extent, actor == null ? null : actor.block());
    }
}
