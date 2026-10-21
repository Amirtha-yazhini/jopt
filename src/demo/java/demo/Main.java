package demo;

/**
 * A small app written the way code often looks in practice: configuration flags held in locals,
 * debug code that is switched off, values copied through temporaries, {@code i = i + 1}. javac
 * translates all of it literally; jopt cleans it up.
 */
public class Main {
    public static void main(String[] args) {
        Invoice invoice = new Invoice();
        invoice.add("Keyboard", 4999, 2);
        invoice.add("Monitor", 18999, 1);
        invoice.add("Cable", 799, 5);
        System.out.println(invoice.render());

        System.out.println("checksum(\"hello world\") = " + Checksum.of("hello world"));
        System.out.println("checksum(invoice) = " + Checksum.of(invoice.render()));

        int[] data = {5, 3, 9, 1, 7, 3, 8, 2, 6, 4};
        System.out.println("stats: " + Stats.describe(data));
        System.out.println("grid area 7x5 = " + Geometry.gridArea(7, 5) + ", perimeter = " + Geometry.perimeter(7, 5));
        System.out.println("shipping for 2.5 kg = " + Shipping.cost(2500));
        System.out.println("shipping for 12 kg = " + Shipping.cost(12000));
    }
}
