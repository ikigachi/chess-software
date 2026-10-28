# Chess Software

## Delivery objective

This project is the foundation of a room-based chess system. It currently implements an in-memory Java domain model, basic moves and turn checks, immutable game history, users, two-player rooms and loading board placement from FEN text.

The repository is organized into three Maven modules. The backend and frontend have their own application structure, but the actual server, graphical UI, network communication and persistence are not implemented yet.

## Requirements and dependencies

- JDK 27
- Apache Maven

The root `pom.xml` centralizes the Java version and compiler plugin. The modules inherit these settings and introduce no JSON, HTTP or database libraries.

## Build and execution

Run from the repository root:

```bash
mvn compile
```

Maven builds `chess-core` first, followed by `chess-backend` and `chess-frontend`. To build one application together with its dependencies:

```bash
mvn -pl chess-backend -am compile
mvn -pl chess-frontend -am compile
```

The frontend currently contains only the previous placeholder `Main`, which prints `hola mundo`. After compilation it can be run with JDK 27:

```bash
java -cp chess-frontend/target/classes software.ulpgc.chess.frontend.Main
```

This placeholder does not use core classes. A future launcher using the core will need its runtime dependencies on the classpath or appropriate packaging. The backend `Main` is an empty composition-root placeholder and does not start a server yet.

In IntelliJ IDEA, open or link the root `pom.xml`, select JDK 27 as the project SDK and Maven runner JDK, then reload all Maven projects. Maven will supply the three modules and their source roots; there is no need to mark folders manually. The local checkout directory may still be named `chess-core`; the root Maven artifact is now `chess-software`.

## Modules and project structure

The organization follows the supplied templates: separate application modules, `application/domain/model`, `application/ports` and `infrastructure/adapters` where code already exists. A root aggregator POM builds them together. Additional packages will be created when they have classes.

```text
project-root/
├── pom.xml                          parent and Maven aggregator: chess-software
├── chess-core/
│   ├── pom.xml                      shared library
│   └── src/main/java/software/ulpgc/chess/
│       ├── application/
│       │   ├── domain/model/        Board, Color, File, Game, Move, Piece,
│       │   │                       Rank, Room, Square, State, User
│       │   └── ports/               BoardDeserializer, PositionDesearizaler,
│       │                           StateDeserializer
│       └── infrastructure/adapters/
│                                   FenBoardDeserializer
├── chess-backend/
│   ├── pom.xml                      depends on chess-core
│   └── src/main/java/software/ulpgc/chess/backend/
│       └── Main.java                empty placeholder
└── chess-frontend/
    ├── pom.xml                      depends on chess-core
    └── src/main/java/software/ulpgc/chess/frontend/
        └── Main.java                existing greeting placeholder
```

Backend and frontend contain only their POM and `Main` source. Empty package folders and `.gitkeep` files are not included; packages will be added when needed. The shared domain is kept in `chess-core` rather than copied into each application.

The dependency direction is:

```text
chess-backend  ──> chess-core <── chess-frontend
```

Neither application depends on the other. This diagram shows Java build dependencies, not network communication. Future frontend/backend calls must use a documented service contract through adapters.

### Main classes

- `Board`: immutable map of squares to pieces, with a standard initial position and basic movement/capture operations that return new boards.
- `Square`: coordinate record. `at("a1")` and `at(1, 1)` represent the same square; factory methods reject invalid coordinates.
- `File`, `Rank`, `Color` and `Piece`: board files, ranks, player colours and the twelve piece variants.
- `Move`: immutable origin and destination pair; null endpoints are rejected.
- `State`: board and current turn. `initial()` starts with White; `move(Move)` checks the moving piece's colour and returns the next state.
- `Game`: identifier, state and ordered move history. `initial(id)` creates a game and `move(Move)` returns an updated game. History is copied with `List.copyOf`; `turn()` exposes the current turn.
- `User`: nonblank identifier and name. Room membership uses the identifier.
- `Room`: code, White player, optional Black player and game. Creation, joining and moving return immutable room values.
- `BoardDeserializer`: port defining `Board deserialize(String value)`.
- `FenBoardDeserializer`: adapter converting FEN piece placement into a board.
- `PositionDesearizaler` and `StateDeserializer`: empty interfaces reserved for future work.

## Coordinates and FEN loading

All domain classes are now in `software.ulpgc.chess.application.domain.model`. The deserialization port is in `software.ulpgc.chess.application.ports` and its FEN adapter is in `software.ulpgc.chess.infrastructure.adapters`.

```java
import software.ulpgc.chess.application.domain.model.Board;
import software.ulpgc.chess.application.ports.BoardDeserializer;
import software.ulpgc.chess.infrastructure.adapters.FenBoardDeserializer;

// Inside a method:
BoardDeserializer deserializer = new FenBoardDeserializer();
Board board = deserializer.deserialize(
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR");
boolean isInitialPosition = board.equals(Board.initial()); // true
```

Numeric coordinates use files and ranks from 1 to 8, converted internally to array indices from 0 to 7. Text ranks are converted from characters to numbers before matching `Rank`.

The FEN adapter reads ranks from 8 down to 1 and files from `a` to `h`. Uppercase letters represent white pieces, lowercase letters black pieces, and digits skip empty squares. For example, `8/8/8/3p4/8/8/8/8` places a black pawn on `d5`.

It rejects null or blank input, an incorrect rank count, rows that do not cover eight squares and unknown piece symbols. A complete FEN string is accepted, but only its first field is read. Turn, castling rights, en passant and move counters are ignored and not validated. Board parsing does not establish that a chess position is legal.

## Users, rooms, games and moves

| Concept | Current status |
| --- | --- |
| User | Identifier and name; no authentication |
| Room | Code, two player slots, joining and player turn checks |
| Game | Identifier, current state and immutable move history |
| Move history | Ordered origin/destination moves stored in memory |
| Persistence | Not implemented |

Example usage inside a method, with domain classes imported from `software.ulpgc.chess.application.domain.model`:

```java
User white = new User("user-1", "Ana");
User black = new User("user-2", "Luis");
Room waiting = Room.create("room-1", white);
Room ready = waiting.join(black);
Room afterWhite = ready.move(white, new Move(Square.at("e2"), Square.at("e4")));
Room afterBlack = afterWhite.move(black, new Move(Square.at("e7"), Square.at("e5")));
int moveCount = afterBlack.game().moves().size(); // 2
```

The creator plays White and the joining user plays Black. A null Black player denotes a waiting room. The caller supplies the room code, also used as the initial game identifier. Uniqueness across rooms is not enforced yet.

Rooms reject repeated user identifiers, a third player, moves before a second player joins, outsiders and moves submitted by the wrong player. `Game` delegates move execution to `State`, which checks piece ownership and delegates board updates to `Board`. A successful move returns a new room, game, state and board. Rejected moves do not alter the original objects or append history.

## System and hexagonal architecture

The system currently consists of a shared core and two application skeletons. An independently running backend, a graphical frontend and a database are still pending. There are no server ports, endpoints, network messages or database settings to configure yet.

Inside the core, the model is independent of FEN parsing. The adapter implements the deserialization port and constructs domain objects. In each application, future use cases belong in `application/usecases`, their contracts in `application/ports`, and technical implementations in `infrastructure/adapters`. Each application's `Main` is the place for wiring dependencies.

The future frontend will put UI code in adapters and delegate communication through a service port. Backend use cases must remain independent of the transport and storage frameworks. There is no need to duplicate shared classes or copy the login and store services from the templates, since those features have not been requested.

## Design decisions and principles

- High cohesion and SRP: `Board` manages piece placement, `State` handles turn ownership, `Game` maintains history, and `Room` handles membership and player authorization.
- Low coupling and DIP: parsing depends on a small deserialization contract; domain objects do not depend on UI, network or persistence libraries.
- Composition over inheritance: rooms contain games, games contain states, and states contain boards.
- Immutability: `Map.copyOf` and `List.copyOf` protect collections; operations return new values.
- Law of Demeter: `Room` asks `Game.turn()` instead of navigating its internal state.
- DRY and YAGNI: shared classes stay in the core; application packages are created only when there are classes to put in them.

## Verification

The three-module Maven reactor was compiled with JDK 27 using `mvn -o compile` through IntelliJ's bundled Maven. Offline mode used dependencies already available locally; a fresh environment should use `mvn compile` to resolve the required plugins.

Before reorganizing packages, temporary Java harnesses passed 110 coordinate/FEN checks, 26 board movement checks, 34 move/state checks, 35 game/history checks and 59 user/room checks. These temporary harnesses are not a committed test suite. UI, network and persistence verification remain pending.

After the module and package reorganization, a new temporary harness passed 134 regression checks covering coordinates, FEN parsing, board movement, state turns, game history and room membership. The relocated frontend placeholder was also run successfully.

## Screenshots and documentation for final delivery

There is no graphical board or network room workflow yet, so the required UI screenshots are pending. The final README must include real frontend/backend startup commands, communication contracts, storage configuration, screenshots and reproducible verification once those features exist.

## Known limitations

- `Square.at(...)` validates coordinates, but the record constructor still permits null components; move application rejects incomplete squares.
- Board moves validate origin/destination, the presence of a piece and same-colour capture restrictions. Piece movement patterns and blocked paths are not implemented.
- Turn ownership is enforced by `State`; room membership is enforced by `Room`. Direct calls to lower layers bypass room checks, so future player requests must go through the room workflow.
- Check, checkmate, castling, promotion, en passant, game results and completion status are not implemented.
- There is no authentication or shared room registry; knowing a user identifier is not an authentication mechanism.
- All data lives in memory. There is no JSON, file storage, database, network communication or concurrency handling.
- Backend and frontend folders are scaffolding; they do not yet provide a working server or graphical chess application.

## Next steps

1. Add backend use cases and a shared room repository port with an in-memory adapter.
2. Add a frontend interaction flow and a service port, initially backed by a test double or local adapter.
3. Extend and document the chosen chess rules and game completion status.
4. Add storage and serialization adapters when that work is explicitly undertaken.
5. Connect frontend/backend through a documented protocol and verify two-player use, history and errors.
