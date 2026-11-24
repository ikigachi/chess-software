package software.ulpgc.chess.playerservice;

import software.ulpgc.chess.playerservice.application.usecases.ResolvePlayerUseCase;
import software.ulpgc.chess.playerservice.application.usecases.RenamePlayerUseCase;
import software.ulpgc.chess.playerservice.application.ports.PlayerRepository;
import software.ulpgc.chess.playerservice.infrastructure.adapters.SqlitePlayerDatabase;
import software.ulpgc.chess.playerservice.infrastructure.adapters.SqlitePlayerRepository;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerApiHandler;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerHttpServer;
import software.ulpgc.chess.playerservice.infrastructure.adapters.HttpAccountIdentity;
import software.ulpgc.chess.playerservice.application.usecases.AccountProfileUseCases;

import java.nio.file.Path;
import java.net.URI;

public class Main {
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8082;
        SqlitePlayerDatabase database = new SqlitePlayerDatabase(Path.of(args.length > 1 ? args[1] : ".regalia/players.sqlite"));
        database.initializeSchema();
        PlayerRepository repository = new SqlitePlayerRepository(database);
        ResolvePlayerUseCase resolve = new ResolvePlayerUseCase(repository);
        var accounts = new HttpAccountIdentity(URI.create(args.length > 3 ? args[3] : "http://127.0.0.1:8084"));
        PlayerHttpServer server = new PlayerHttpServer(port, new PlayerApiHandler(resolve, new RenamePlayerUseCase(resolve, repository),
                new AccountProfileUseCases(repository, accounts)));
        try {
            server.start(args.length > 2 ? args[2] : "127.0.0.1");
        } catch (RuntimeException exception) {
            server.close();
            accounts.close();
            throw exception;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.close(); accounts.close(); }));
        System.out.println("Player service: " + server.uri());
    }
}
