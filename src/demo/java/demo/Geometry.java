package demo;

public class Geometry {
    public static int gridArea(int width, int height) {
        int cellSize = 4;
        int border = 0;
        int w = width * cellSize + border;
        int h = height * cellSize + border;
        int unused = w - h;
        return w * h;
    }

    public static int perimeter(int width, int height) {
        int sides = 2;
        int scale = 1;
        int p = sides * width + sides * height;
        return p * scale;
    }
}
