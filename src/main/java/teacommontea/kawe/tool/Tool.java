package teacommontea.kawe.tool;

public interface Tool {

    String permission();

    boolean rightClick(ToolClick click);

    boolean leftClick(ToolClick click);

    default boolean scroll(ToolClick click, int amount) {
        return false;
    }
}
