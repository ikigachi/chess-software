FROM maven:3.10.0-eclipse-temurin-27@sha256:3175c44b207d6698aecb9bbb5bf285b5b4efcf7f567d6f65d88cd218276a6ab2 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -ntp -pl player-service,game-service -am -DskipTests package org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency

FROM eclipse-temurin:27-jre@sha256:acfb350b50e707addf3dcbb47f7052d1882d1e5f1670ce838a9c7838d82d4fe5 AS runtime
WORKDIR /app
RUN mkdir /data && chown 10001:10001 /app /data
USER 10001:10001

FROM runtime AS player-service
COPY --from=build /workspace/player-service/target/classes /app/classes
COPY --from=build /workspace/player-service/target/dependency /app/lib
ENTRYPOINT ["java", "-cp", "/app/classes:/app/lib/*", "software.ulpgc.chess.playerservice.Main"]
CMD ["8082", "/data/players.sqlite", "0.0.0.0"]

FROM runtime AS game-service
COPY --from=build /workspace/game-service/target/classes /app/classes
COPY --from=build /workspace/game-service/target/dependency /app/lib
ENTRYPOINT ["java", "-cp", "/app/classes:/app/lib/*", "software.ulpgc.chess.gameservice.Main"]
CMD ["8081", "/data/rooms.sqlite", "http://player-service:8082", "0.0.0.0"]
