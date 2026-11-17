package software.ulpgc.chess.playerservice;

import software.ulpgc.chess.playerservice.application.usecases.ResolvePlayerUseCase;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerApiHandler;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerHttpServer;

public class Main {
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8082;
        PlayerHttpServer server = new PlayerHttpServer(port, new PlayerApiHandler(new ResolvePlayerUseCase()));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
        System.out.println("Player service: " + server.uri());
    }
}
