package shop.core;

import org.example.money.Money;

public final class Cart {
    private long cents;

    public Cart add(long priceInCents) {
        cents += priceInCents;
        return this;
    }

    public String total() {
        return Money.format(cents);
    }
}
