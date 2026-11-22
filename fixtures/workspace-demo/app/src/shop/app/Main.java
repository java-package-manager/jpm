package shop.app;

import shop.core.Cart;

public class Main {
    public static void main(String[] args) {
        System.out.println(Config.GREETING + "! Your total is " + new Cart().add(1999).add(1).total());
    }
}
