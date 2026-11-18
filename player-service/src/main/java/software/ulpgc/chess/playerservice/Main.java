package software.ulpgc.chess.playerservice;

import software.ulpgc.chess.playerservice.application.usecases.ResolvePlayerUseCase;
import software.ulpgc.chess.playerservice.application.usecases.RenamePlayerUseCase;
import software.ulpgc.chess.playerservice.application.ports.PlayerRepository;
import software.ulpgc.chess.playerservice.infrastructure.adapters.SqlitePlayerDatabase;
import software.ulpgc.chess.playerservice.infrastructure.adapters.SqlitePlayerRepository;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerApiHandler;
import software.ulpgc.chess.playerservice.infrastructure.adapters.PlayerHttpServer;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8082;
        SqlitePlayerDatabase database = new SqlitePlayerDatabase(Path.of(args.length > 1 ? args[1] : ".regalia/players.sqlite"));
        database.initializeSchema();
        PlayerRepository repository = new SqlitePlayerRepository(database);
        ResolvePlayerUseCase resolve = new ResolvePlayerUseCase(repository);
        PlayerHttpServer server = new PlayerHttpServer(port, new PlayerApiHandler(resolve, new RenamePlayerUseCase(resolve, repository)));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start(args.length > 2 ? args[2] : "127.0.0.1");
        System.out.println("Player service: " + server.uri());
    }
}
