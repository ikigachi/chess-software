# Chess Core

## Delivery objective

This delivery contains the first domain model for a chess application. Its current scope is the representation of chess colours, pieces, board coordinates and a board containing an initial-position definition.

The repository currently provides a Java domain core only. The application layers required for online play are not implemented yet.

## Requirements and dependencies

- JDK 27
- Apache Maven

The JDK level is defined in `pom.xml` through `maven.compiler.source` and `maven.compiler.target`.

## Build and execution

Compile the project from the repository root:

```bash
mvn compile
```

There is currently no executable frontend, backend server or command-line application. `Main` is only a placeholder method and is not a `public static void main(String[] args)` entry point. Therefore, the verified operation at this stage is compilation of the core module.

## Project structure

```text
chess-core/
├── pom.xml
└── src/main/java/software/ulpgc/chess/
    ├── Board.java
    ├── Color.java
    ├── File.java
    ├── Main.java
    ├── Piece.java
    ├── Rank.java
    └── Square.java
```

### Main classes

- `Board`: stores a map of `Square` to `Piece`, exposes an initial-board factory and a board read-only view through `Map.copyOf`.
- `Square`: record representing a file and rank pair.
- `File`: the eight board files, from `a` to `h`.
- `Rank`: the eight board ranks, from `1` to `8`.
- `Piece`: enum containing the twelve white and black chess pieces, with helpers for colour and piece type.
- `Color`: the `White` and `Black` colours.
- `Main`: placeholder class; no application startup flow has been implemented.

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
       [software.ulpgc.chess Java domain core]
```

There is no HTTP, REST, WebSocket or other frontend-backend communication contract. The only current contract is the in-process Java API exposed by the domain classes, for example `Board.initial()`, `Board.pieces()` and `Square.at(String)`.

## Backend hexagonal architecture

There is no backend server yet, so a complete hexagonal backend cannot be documented as implemented. The classes currently present form only a domain-model layer:

```text
software.ulpgc.chess
├── Board       domain state
├── Piece       domain value/type
├── Square      domain value
├── File        domain value
├── Rank        domain value
└── Color       domain value
```

Use cases, inbound ports, outbound ports and infrastructure adapters have not been created. Consequently, there are no backend ports, server endpoints or backend adapters to configure.

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

## Implemented design decision

The `Board` stores a defensive immutable copy of its pieces using `Map.copyOf`. This fits the domain because board states can be treated as values and cannot be modified through the map returned by `pieces()`. It also provides a suitable basis for future move operations that return a new board state instead of mutating an existing one.

## Screenshots

No screenshots are included because this delivery does not contain a graphical board, room creation or online-game history. The mandatory screenshots will become applicable once a frontend and the corresponding workflows exist.

## Verification

The current verification is limited to the Maven compilation check:

```bash
mvn compile
```

There are no automated tests in the repository yet, and no manual UI or multiplayer checks can be performed because those layers have not been implemented.

## Known limitations

- `Square.at(String)` is still a placeholder and currently returns `a1` for every input.
- The initial-position map is declared with all standard starting squares, but coordinate parsing must be completed before those keys can represent different squares correctly.
- `Board.move(Square, Square)` currently returns the same board and does not move or validate a piece.
- No legal chess rules are implemented: turns, captures, check, checkmate, castling, promotion and en passant are absent.
- There is no user, room, game or move-history model.
- There is no backend, frontend, database, network communication or concurrency handling.
- There are no automated tests or runtime screenshots yet.