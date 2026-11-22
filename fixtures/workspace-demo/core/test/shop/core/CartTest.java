package shop.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CartTest {
    @Test
    void addsPrices() {
        assertEquals("EUR 3.50", new Cart().add(150).add(200).total());
    }
}
