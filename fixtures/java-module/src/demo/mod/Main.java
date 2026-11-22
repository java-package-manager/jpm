package demo.mod;

public class Main {
    public static void main(String[] args) {
        java.util.logging.Logger.getLogger("demo").getName();
        System.out.println("module " + Main.class.getModule().getName());
    }
}
