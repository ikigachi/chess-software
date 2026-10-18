# Chess Core

## Delivery objective

This delivery contains the domain core for a chess application. Its current scope includes chess colours, pieces, validated board coordinates, an immutable board with the standard initial position, and loading piece placement from FEN text.

The repository currently provides a Java domain core with a deserialization port and a FEN adapter. The application layers required for online play are not implemented yet.

## Requirements and dependencies

- JDK 27
- Apache Maven

The JDK level is defined in `pom.xml` through `maven.compiler.source` and `maven.compiler.target`.

## Build and execution

Compile the project from the repository root:

```bash
mvn compile
```

There is currently no executable frontend, backend server or chess command-line application. `Main` is a placeholder that prints `hola mundo`; it does not start a chess workflow. With JDK 27, its compact `static void main()` entry point can be run after compilation:

```bash
java -cp target/classes software.ulpgc.chess.domain.model.Main
```

## Project structure

```text
chess-core/
├── pom.xml
└── src/main/java/software/ulpgc/chess/
    ├── domain/model/
    │   ├── Board.java
    │   ├── Color.java
    │   ├── File.java
    │   ├── Main.java
    │   ├── Piece.java
    │   ├── Rank.java
    │   ├── Square.java
    │   └── State.java
    ├── ports/
    │   ├── BoardDeserializer.java
    │   ├── PositionDesearizaler.java
    │   └── StateDeserializer.java
    └── adapters/
        └── FenBoardDeserializer.java
```

### Main classes

- `Board`: stores a map of `Square` to `Piece`, exposes an initial-board factory and a board read-only view through `Map.copyOf`.
- `Square`: record representing a file and rank pair. Its factories parse coordinates such as `a1` or accept numeric coordinates from 1 to 8, rejecting invalid input.
- `File`: the eight board files, from `a` to `h`.
- `Rank`: the eight board ranks, from `1` to `8`.
- `Piece`: enum containing the twelve white and black chess pieces, with helpers for colour and piece type.
- `Color`: the `White` and `Black` colours.
- `Main`: placeholder class; no application startup flow has been implemented.
- `State`: empty record reserved for future game-state data.
- `BoardDeserializer`: port defining `Board deserialize(String value)`.
- `FenBoardDeserializer`: adapter implementing the port to load piece placement from FEN text.
- `PositionDesearizaler` and `StateDeserializer`: empty interfaces with no implemented behavior yet.

## Coordinates and FEN loading

`Square.at("a1")` and `Square.at(1, 1)` represent the same square; `Square.at(8, 8)` represents `h8`. Numeric coordinates use 1-based files and ranks, converted internally to 0-based array indices. Text ranks are converted from characters to numeric values before matching a `Rank`. Invalid factory inputs throw `IllegalArgumentException`.

Example usage inside Java code:

```java
BoardDeserializer deserializer = new FenBoardDeserializer();
Board board = deserializer.deserialize(
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR");
boolean isInitialPosition = board.equals(Board.initial()); // true
```

The example uses `Board`, `BoardDeserializer` and `FenBoardDeserializer` from the packages shown above.

The adapter reads ranks from 8 down to 1 and files from `a` to `h`. Uppercase letters identify white pieces and lowercase letters identify black pieces. Digits from 1 to 8 skip empty squares. For example, `8/8/8/3p4/8/8/8/8` places a black pawn on `d5`.

The adapter rejects null or blank input, an incorrect number of ranks, ranks that do not cover exactly eight squares, and unknown piece symbols. It also accepts a complete FEN string, but reads only its first field: turn, castling rights, en passant and move counters are ignored and not validated. This is structural board parsing, not validation of a legal chess position.

## System architecture

The implemented system is currently a single Java module. The complete online architecture described in the assignment is not present yet.

```text
                 Not implemented
       +-------------------------------+
       | Frontend / UI                 |
       +---------------+---------------+
                       | No network contract
                       v
       +-------------------------------+
       | Backend / application server  |
       +---------------+---------------+
                       | No persistence contract
                       v
       +-------------------------------+
       | Database                      |
       +-------------------------------+

       Current implementation:
       [FEN adapter -> deserialization port -> domain model]
```

There is no HTTP, REST, WebSocket or other frontend-backend communication contract. Current contracts are in-process Java APIs, including `Board.initial()`, `Board.pieces()`, `Square.at(...)` and `BoardDeserializer.deserialize(String)`.

## Backend hexagonal architecture

There is no backend server yet. The current structure begins the separation of domain, ports and adapters:

```text
software.ulpgc.chess
├── domain.model   Board, Piece, Square, File, Rank, Color, State
├── ports          BoardDeserializer and placeholder interfaces
└── adapters       FenBoardDeserializer implements BoardDeserializer
```

The FEN adapter depends on the deserialization contract and the domain model; the domain model does not depend on FEN parsing. Application use cases, network endpoints and persistence adapters have not been implemented. There are no server ports or database settings to configure yet.

## Frontend hexagonal architecture

No frontend module is included in this delivery. There are currently no UI components, frontend use cases, service ports or communication adapters.

## Users, rooms, games and moves

The requested online-game data model is not implemented yet:

| Concept | Current status |
| --- | --- |
| User | Not implemented |
| Room | Not implemented |
| Game | Not implemented as a separate entity |
| Move history | Not implemented |
| Database persistence | Not implemented |

The only implemented state is a `Board` containing a `Map<Square, Piece>`.

## Connecting users

No user connection mechanism is available yet. Rooms, invitations, matchmaking and shared room codes are outside the current implementation. As a result, there is no port, database table or endpoint for joining another player.

## Design decisions and principles

The `Board` stores a defensive immutable copy of its pieces using `Map.copyOf`. This fits the domain because board states can be treated as values and cannot be modified through the map returned by `pieces()`. It also provides a suitable basis for future move operations that return a new board state instead of mutating an existing one.

Coordinate parsing belongs to `Square`, while FEN interpretation belongs to `FenBoardDeserializer`, keeping related responsibilities together. The `BoardDeserializer` interface allows callers to depend on a small contract and substitute another implementation. The domain remains independent of the input format.

Development follows high cohesion, low coupling, SOLID, composition over inheritance, DRY, convention over configuration and YAGNI. Patterns are introduced when a concrete need justifies them, keeping the design proportional to the current scope.

## Screenshots

No screenshots are included because this delivery does not contain a graphical board, room creation or online-game history. The mandatory screenshots will become applicable once a frontend and the corresponding workflows exist.

## Verification

To compile with Maven:

```bash
mvn compile
```

The coordinate and FEN changes were verified with JDK 27 compilation using `javac` and a temporary Java harness that passed 110 checks. The harness covered all 64 coordinate pairs, invalid coordinate inputs, initial-position equivalence with `Board.initial()`, all twelve piece symbols, empty and sparse boards, complete FEN input and malformed board fields.

The temporary harness is not included in the repository, so those checks are not yet a reproducible committed test suite. Maven compilation is the documented build command, not the command used for that verification. UI and multiplayer checks are pending implementation of those layers.

## Known limitations

- Only FEN piece placement is loaded; the remaining fields and legal-position rules are not validated.
- Coordinate validation is performed by `Square.at(...)`; the record constructor still permits null components.
- `State`, `PositionDesearizaler` and `StateDeserializer` are placeholders.
- `Board.move(Square, Square)` currently returns the same board and does not move or validate a piece.
- No legal chess rules are implemented: turns, captures, check, checkmate, castling, promotion and en passant are absent.
- There is no user, room, game or move-history model.
- There is no backend, frontend, database, network communication or concurrency handling.
- There are no automated tests or runtime screenshots yet.

## Next steps

1. Implement basic movement in `Board.move()` and define the subset of rules to validate.
2. Model users, rooms, games, moves and game state.
3. Add file input/output for positions and move history, keeping file access in an adapter.
4. Build a console demonstration for loading a board, making moves and saving the history.
5. Add frontend/backend communication, shared rooms, persistent history and reproducible verification for the final project.
