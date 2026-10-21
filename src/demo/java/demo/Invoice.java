package demo;

import java.util.ArrayList;
import java.util.List;

public class Invoice {
    private final List<String> names = new ArrayList<>();
    private final List<Integer> prices = new ArrayList<>();
    private final List<Integer> quantities = new ArrayList<>();

    public void add(String name, int priceCents, int quantity) {
        boolean validate = true;
        boolean trace = false;
        if (trace) {
            System.out.println("adding " + name);
        }
        if (validate && quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        names.add(name);
        prices.add(priceCents);
        quantities.add(quantity);
    }

    public int subtotal() {
        int total = 0;
        int count = names.size();
        for (int i = 0; i < count; i = i + 1) {
            int line = prices.get(i) * quantities.get(i);
            total = total + line;
        }
        return total;
    }

    public int tax(int amount) {
        int percent = 18;
        int scale = 100;
        int rounding = scale / 2;
        return (amount * percent + rounding) / scale;
    }

    public int discount(int amount) {
        int threshold = 20000;
        int percent = 5;
        boolean enabled = true;
        int result = 0;
        result = 0;
        if (enabled && amount > threshold) {
            result = amount * percent / 100;
        }
        return result;
    }

    public String render() {
        String currency = "EUR";
        boolean showLines = true;
        StringBuilder sb = new StringBuilder();
        if (showLines) {
            for (int i = 0; i < names.size(); i = i + 1) {
                sb.append(String.format("  %-10s %3d x %8s%n", names.get(i), quantities.get(i), money(prices.get(i), currency)));
            }
        }
        int sub = subtotal();
        int disc = discount(sub);
        int taxed = sub - disc;
        int tax = tax(taxed);
        sb.append(String.format("  subtotal %s, discount %s, tax %s, total %s",
                money(sub, currency), money(disc, currency), money(tax, currency), money(taxed + tax, currency)));
        return sb.toString();
    }

    private static String money(int cents, String currency) {
        int divisor = 100;
        int whole = cents / divisor;
        int frac = cents % divisor;
        String sep = ".";
        return whole + sep + (frac < 10 ? "0" : "") + frac + " " + currency;
    }
}
