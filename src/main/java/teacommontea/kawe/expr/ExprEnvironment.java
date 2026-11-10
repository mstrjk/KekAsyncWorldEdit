package teacommontea.kawe.expr;

public interface ExprEnvironment {

    int blockType(double x, double y, double z);

    int blockData(double x, double y, double z);

    int blockTypeAbs(double x, double y, double z);

    int blockDataAbs(double x, double y, double z);

    int blockTypeRel(double x, double y, double z);

    int blockDataRel(double x, double y, double z);
}
