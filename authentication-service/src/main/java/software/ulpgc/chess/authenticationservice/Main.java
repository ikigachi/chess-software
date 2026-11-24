package software.ulpgc.chess.authenticationservice;

import software.ulpgc.chess.authenticationservice.application.usecases.AuthenticationUseCases;
import software.ulpgc.chess.authenticationservice.infrastructure.adapters.*;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

public class Main {
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8084;
        var database = new SqliteAuthenticationDatabase(Path.of(args.length > 1 ? args[1] : ".regalia/accounts.sqlite"));
        database.initializeSchema();
        var authentication = new AuthenticationUseCases(new SqliteAccountCredentialsReader(database), new Argon2PasswordVerifier(),
                new SqliteSessionRepository(database), new SecureSessionTokens(), Clock.systemUTC(), Duration.ofHours(8), Duration.ofMinutes(30));
        var server = new AuthenticationHttpServer(port, new AuthenticationApiHandler(authentication));
        try {
            server.start(args.length > 2 ? args[2] : "127.0.0.1");
        } catch (RuntimeException exception) {
            server.close();
            throw exception;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("Authentication service: " + server.uri());
    }
}
