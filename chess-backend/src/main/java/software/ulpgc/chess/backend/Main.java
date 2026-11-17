package software.ulpgc.chess.backend;

import software.ulpgc.chess.backend.infrastructure.adapters.GameServiceProxy;
import software.ulpgc.chess.backend.infrastructure.adapters.RoomHttpServer;
import software.ulpgc.chess.backend.infrastructure.adapters.StaticAssetHandler;

import java.net.URI;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        URI gameService = URI.create(args.length > 1 ? args[1] : "http://127.0.0.1:8081");
        GameServiceProxy proxy = new GameServiceProxy(gameService);
        RoomHttpServer server;
        try {
            server = new RoomHttpServer(port, proxy, new StaticAssetHandler());
        } catch (Exception exception) {
            proxy.close();
            throw exception;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            proxy.close();
        }));
        server.start();
        System.out.println("Regalia: " + server.uri());
    }
}
