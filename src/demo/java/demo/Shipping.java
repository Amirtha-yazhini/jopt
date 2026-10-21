package demo;

public class Shipping {
    public static int cost(int grams) {
        int baseCents = 499;
        int perKgCents = 120;
        int freeAboveGrams = 10000;
        boolean expressOnly = false;
        int cost;
        try {
            if (grams > freeAboveGrams) {
                cost = 0;
            } else {
                int kg = (grams + 999) / 1000;
                cost = baseCents + kg * perKgCents;
            }
            if (expressOnly) {
                cost = cost * 2;
            }
        } catch (ArithmeticException e) {
            cost = baseCents;
        }
        return cost;
    }
}
