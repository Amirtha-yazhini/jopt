import java.util.ArrayList;
import java.util.List;

public class Invoice {
    private final List<Integer> prices = new ArrayList<>();
    private final List<Integer> quantities = new ArrayList<>();

    public void add(int priceCents, int quantity) {
        boolean validate = true;
        boolean trace = false;
        if (trace) {
            System.out.println("adding " + priceCents);
        }
        if (validate && quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        prices.add(priceCents);
        quantities.add(quantity);
    }

    public int subtotal() {
        int total = 0;
        int count = prices.size();
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
        if (enabled && amount > threshold) {
            result = amount * percent / 100;
        }
        return result;
    }
}
