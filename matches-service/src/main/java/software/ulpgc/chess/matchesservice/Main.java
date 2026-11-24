package software.ulpgc.chess.matchesservice;

import software.ulpgc.chess.matchesservice.application.usecases.GetMatchUseCase;
import software.ulpgc.chess.matchesservice.infrastructure.adapters.HttpGameReader;
import software.ulpgc.chess.matchesservice.infrastructure.adapters.MatchesHttpServer;

import java.net.URI;

public final class Main {
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8085;
        var games = new HttpGameReader(URI.create(args.length > 1 ? args[1] : "http://127.0.0.1:8081"));
        try {
            var server = new MatchesHttpServer(port, new GetMatchUseCase(games));
            try {
                server.start(args.length > 2 ? args[2] : "127.0.0.1");
            } catch (RuntimeException exception) {
                server.close();
                throw exception;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.close(); games.close(); }));
            System.out.println("Matches service: " + server.uri());
        } catch (RuntimeException exception) {
            games.close();
            throw exception;
        }
    }
}
