package software.ulpgc.chess.application.ports;

import software.ulpgc.chess.application.domain.model.Board;

public interface BoardDeserializer {
    Board deserialize(String value);
}
