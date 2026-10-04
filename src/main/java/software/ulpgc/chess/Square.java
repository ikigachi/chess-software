package software.ulpgc.chess;

public record Square(File file, Rank rank) {
    public static Square at(String square) {
        return new Square(File.A, Rank.R1);
    }

    private static class ParseException extends Exception {
        public ParseException(String s) {

        }
    }
}
