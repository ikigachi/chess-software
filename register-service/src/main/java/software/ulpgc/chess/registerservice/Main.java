package software.ulpgc.chess.registerservice;

import software.ulpgc.chess.registerservice.application.ports.AccountRepository;
import software.ulpgc.chess.registerservice.application.usecases.AccountUseCases;
import software.ulpgc.chess.registerservice.infrastructure.adapters.*;

import java.net.URI;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8083;
        var database = new SqliteAccountDatabase(Path.of(args.length > 1 ? args[1] : ".regalia/accounts.sqlite"));
        database.initializeSchema();
        AccountRepository accounts = new SqliteAccountRepository(database);
        var players = new HttpPlayerIdentity(URI.create(args.length > 2 ? args[2] : "http://127.0.0.1:8082"));
        var authentication = new HttpAccountIdentity(URI.create(args.length > 4 ? args[4] : "http://127.0.0.1:8084"));
        try {
            var useCases = new AccountUseCases(accounts, players, new Argon2PasswordHasher(), authentication);
            var server = new RegisterHttpServer(port, new AccountApiHandler(useCases));
            try {
                server.start(args.length > 3 ? args[3] : "127.0.0.1");
            } catch (RuntimeException exception) {
                server.close();
                throw exception;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.close();
                players.close();
                authentication.close();
            }));
            System.out.println("Register service: " + server.uri());
        } catch (RuntimeException exception) {
            players.close();
            authentication.close();
            throw exception;
        }
    }
}
