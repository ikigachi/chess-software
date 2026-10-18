package software.ulpgc.chess.adapters;

import software.ulpgc.chess.domain.model.Board;
import software.ulpgc.chess.domain.model.Piece;
import software.ulpgc.chess.domain.model.Square;
import software.ulpgc.chess.ports.BoardDeserializer;

import java.util.HashMap;
import java.util.Map;

public class FenBoardDeserializer implements BoardDeserializer {

    @Override
    public Board deserialize(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("FEN board cannot be empty");
        return deserialize(value.strip().split("\\s+")[0].split("/", -1));
    }

    private Board deserialize(String[] ranks) {
        if (ranks.length != 8)
            throw new IllegalArgumentException("FEN board must have 8 ranks");
        return new Board(piecesIn(ranks));
    }

    private Map<Square, Piece> piecesIn(String[] ranks) {
        Map<Square, Piece> map = new HashMap<>();
        int j = 8;
        for (String rank : ranks)
            map.putAll(piecesIn(j--, rank.toCharArray()));
        return map;
    }

    private Map<Square, Piece> piecesIn(int j, char[] rank) {
        Map<Square, Piece> map = new HashMap<>();
        int i = 1;
        for (char order : rank) {
            if (order >= '1' && order <= '8')
                i += order - '0';
            else {
                Square square = Square.at(i++, j);
                map.put(square, pieceIn(order));
            }
            if (i > 9)
                throw new IllegalArgumentException("FEN rank must contain 8 squares");
        }
        if (i != 9)
            throw new IllegalArgumentException("FEN rank must contain 8 squares");
        return map;
    }

    private Piece pieceIn(char symbol) {
        return switch (symbol) {
            case 'P' -> Piece.WhitePawn;
            case 'R' -> Piece.WhiteRook;
            case 'N' -> Piece.WhiteKnight;
            case 'B' -> Piece.WhiteBishop;
            case 'Q' -> Piece.WhiteQueen;
            case 'K' -> Piece.WhiteKing;
            case 'p' -> Piece.BlackPawn;
            case 'r' -> Piece.BlackRook;
            case 'n' -> Piece.BlackKnight;
            case 'b' -> Piece.BlackBishop;
            case 'q' -> Piece.BlackQueen;
            case 'k' -> Piece.BlackKing;
            default -> throw new IllegalArgumentException("Invalid FEN piece: " + symbol);
        };
    }
}
