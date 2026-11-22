package plain;

import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        List<String> words = new ArrayList<>();
        words.add("plain");
        words.add("ok");
        System.out.println(String.join(" ", words));
    }
}
