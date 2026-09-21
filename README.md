# Chess Software

Sistema de salas para jugar al ajedrez desarrollado para la asignatura
Ingeniería de Software 2.

## Versión

**Versión actual: 1.1**

Esta versión corresponde a las primeras etapas del desarrollo del proyecto.
Actualmente se ha implementado la estructura inicial del frontend y se ha
comenzado el modelado del dominio del ajedrez.

---

## Objetivo del proyecto

El objetivo del proyecto es desarrollar un sistema de ajedrez formado por
un frontend y un backend.

La aplicación permitirá crear o unirse a salas de ajedrez, jugar partidas,
registrar movimientos y consultar partidas almacenadas.

El sistema estará separado en frontend y backend y seguirá una arquitectura
hexagonal adaptada al alcance del proyecto.

---

## Estado actual

Actualmente están implementados:

- Ventana principal de la aplicación.
- Menú principal.
- Botón `Un jugador`.
- Botón `Multijugador`.
- Botón `Extra`.
- Botón `⚙ Ajustes`.
- Estructura inicial del proyecto con Maven.
- Integración de JavaFX.

Los botones todavía no tienen implementada su funcionalidad definitiva.

### Dominio

Se ha comenzado a implementar el modelo básico del ajedrez.

Actualmente existen:

- `Color`: representa el color de las piezas (`WHITE` o `BLACK`).
- `Position`: representa una posición mediante fila y columna.
- `Tile`: representa una casilla del tablero y permite almacenar una pieza.
- `Piece`: clase abstracta base para representar las piezas de ajedrez.

---

## Tecnologías utilizadas

- Java 21
- JavaFX
- Maven
- IntelliJ IDEA
- Git

---

## Ejecución

### Requisitos

Es necesario disponer de:

- JDK 21
- Maven

Para comprobar las versiones instaladas:

```bash
java --version
mvn --version
```


