package software.ulpgc.chess.authenticationservice.application.ports;

public interface SessionTokens {
    String generate();

    String digest(String token);
}
