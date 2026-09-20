package software.ulpgc.chess.ui;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.geometry.Pos;

public class MainMenuView extends BorderPane {

    public MainMenuView() {
        createMenu();
    }

    private void createMenu() {

        // Titulo
        Label title = new Label("CHESS");
        title.getStyleClass().add("title");

        Label subtitle = new Label("Sistema de salas de ajedrez");
        subtitle.getStyleClass().add("subtitle");


        // Botones
        Button singlePlayerButton = createMenuButton("UN JUGADOR");
        Button multiplayerButton = createMenuButton("MULTIJUGADOR");
        Button extraButton = createMenuButton("EXTRA");


        // Boton ajustes
        Button settingsButton = new Button("⚙ Ajustes");
        settingsButton.getStyleClass().add("settings-button");


        // Acciones temporales
        singlePlayerButton.setOnAction(event -> System.out.println("Un jugador"));
        multiplayerButton.setOnAction(event -> System.out.println("Multijugador"));
        extraButton.setOnAction(event -> System.out.println("Extra"));
        settingsButton.setOnAction(event -> System.out.println("Ajustes"));


        // Menú central
        VBox menu = new VBox(
                20,
                title,
                subtitle,
                singlePlayerButton,
                multiplayerButton,
                extraButton
        );

        menu.setAlignment(Pos.CENTER);


        // Posiciones
        setCenter(menu);
        setTop(settingsButton);
        BorderPane.setAlignment(settingsButton, Pos.TOP_RIGHT);
        getStyleClass().add("main-menu");
    }


    private Button createMenuButton(String text) {

        Button button = new Button(text);
        button.getStyleClass().add("menu-button");
        button.setPrefWidth(300);
        button.setPrefHeight(55);

        return button;
    }
}