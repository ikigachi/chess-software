package software.ulpgc.chess.domain.model;

import java.util.Arrays;

public record Square(File file, Rank rank) {

    public static Square at(String value) {
        if (value == null || value.length() != 2)
            throw new IllegalArgumentException("Invalid square: " + value);
        return new Square(fileIn(value), rankIn(value));
    }

    public static Square at(int i, int j) {
        if (i < 1 || i > 8 || j < 1 || j > 8)
            throw new IllegalArgumentException("Square coordinates must be between 1 and 8");
        return new Square(File.values()[i - 1], Rank.values()[j - 1]);
    }

    private static File fileIn(String value) {
        return fileIn(value.charAt(0));
    }

    private static File fileIn(char c) {
        return Arrays.stream(File.values())
                .filter(file -> file.symbol() == c)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid file: " + c));
    }

    private static Rank rankIn(String value) {
        return rankIn(value.charAt(1));
    }

    private static Rank rankIn(char c) {
        return Arrays.stream(Rank.values())
                .filter(rank -> rank.symbol() == c - '0')
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid rank: " + c));
    }
}

