package software.ulpgc.chess.gameservice.application.ports;

import software.ulpgc.chess.application.domain.model.User;

public interface PlayerIdentity {
    User resolve(String credential);
}
