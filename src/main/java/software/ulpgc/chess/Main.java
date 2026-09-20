package software.ulpgc.chess;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import software.ulpgc.chess.ui.MainMenuView;

public class Main extends Application {

    @Override
    public void start(Stage stage) {
        MainMenuView mainMenu = new MainMenuView();
        Scene scene = new Scene(mainMenu, 1000, 700);



        stage.setTitle("Chess");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}