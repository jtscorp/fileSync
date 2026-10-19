package uz.jtscorp.filesync.ui;

import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import uz.jtscorp.filesync.Texts;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** A drag edge: dragging changes a width, letting go keeps it, a double click resets it. */
final class PaneGrip {

    private PaneGrip() {}

    /**
     * @param sign  1 when dragging right widens the part (the sidebar), -1 when dragging left does (the panel)
     * @param width the part's width as shown now
     * @param drag  takes the wanted width; the caller keeps it within its range
     * @param done  runs once a drag or a reset is over, to save the width
     */
    static void attach(Region grip, int sign, DoubleSupplier width, DoubleConsumer drag, Runnable reset,
                       Runnable done) {
        Tooltip.install(grip, new Tooltip(Texts.t("pane.resize")));
        double[] start = new double[2];
        grip.setOnMousePressed(e -> {
            start[0] = e.getScreenX();
            start[1] = width.getAsDouble();
        });
        grip.setOnMouseDragged(e -> drag.accept(start[1] + sign * (e.getScreenX() - start[0])));
        grip.setOnMouseReleased(e -> done.run());
        grip.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                reset.run();
                done.run();
            }
        });
    }
}
