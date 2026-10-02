package com.expensetracker.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.nio.file.Path;

/** Which database file is open, and at which schema version. */
public final class StatusBar {

    private final HBox root = new HBox(12);

    /** The path is absolute - {@code Database} made it so - or the user cannot tell which file it is. */
    public StatusBar(Path databasePath, int schemaVersion) {
        Label db = new Label(databasePath.toString());
        Label version = new Label("schema v" + schemaVersion);

        // An invisible child that absorbs the leftover width, pushing the labels apart:
        // JavaFX's equivalent of justify-content: space-between.
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        root.getChildren().addAll(db, spacer, version);
        root.getStyleClass().add("status-bar");
        root.setPadding(new Insets(6, 12, 6, 12));
    }

    public Node getRoot() {
        return root;
    }
}
