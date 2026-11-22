import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Turns a JaCoCo XML report into a Markdown table: line coverage per package, the classes with the most
 * uncovered lines, and the classes no test reaches at all (candidates for dead code).
 *
 * <p>Usage: {@code java checks/CoverageSummary.java coverage.xml}
 */
public final class CoverageSummary {
    record Counts(String name, int missed, int covered) {
        int total() {
            return missed + covered;
        }

        String percent() {
            return total() == 0 ? "-" : String.format("%.1f%%", 100.0 * covered / total());
        }
    }

    public static void main(String[] args) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Element report = factory.newDocumentBuilder().parse(Files.newInputStream(Path.of(args[0]))).getDocumentElement();
        List<Counts> packages = new ArrayList<>(), classes = new ArrayList<>();
        for (Element pkg : children(report, "package")) {
            packages.add(lines(pkg.getAttribute("name").replace('/', '.'), pkg));
            for (Element cls : children(pkg, "class")) {
                if (cls.getAttribute("name").contains("$")) continue;
                classes.add(lines(cls.getAttribute("name").replace('/', '.'), cls));
            }
        }
        Counts total = lines("total", report);
        StringBuilder md = new StringBuilder("## Coverage\n\nLine coverage: **" + total.percent() + "** (" + total.covered()
                + " of " + total.total() + " lines).\n\n| Package | Lines | Covered |\n|---|---:|---:|\n");
        packages.stream().sorted((a, b) -> a.name().compareTo(b.name()))
                .forEach(p -> md.append("| ").append(p.name()).append(" | ").append(p.total()).append(" | ").append(p.percent()).append(" |\n"));
        md.append("\n### Most uncovered lines\n\n| Class | Uncovered lines | Covered |\n|---|---:|---:|\n");
        classes.stream().sorted((a, b) -> Integer.compare(b.missed(), a.missed())).limit(15)
                .forEach(c -> md.append("| ").append(c.name()).append(" | ").append(c.missed()).append(" | ").append(c.percent()).append(" |\n"));
        List<Counts> unreached = classes.stream().filter(c -> c.covered() == 0 && c.total() > 0).toList();
        md.append("\n### Classes no test reaches (").append(unreached.size()).append(")\n\n");
        if (unreached.isEmpty()) md.append("None.\n");
        unreached.forEach(c -> md.append("- ").append(c.name()).append(" (").append(c.total()).append(" lines)\n"));
        System.out.print(md);
    }

    private static Counts lines(String name, Element e) {
        for (Element c : children(e, "counter")) {
            if (c.getAttribute("type").equals("LINE")) {
                return new Counts(name, Integer.parseInt(c.getAttribute("missed")), Integer.parseInt(c.getAttribute("covered")));
            }
        }
        return new Counts(name, 0, 0);
    }

    private static List<Element> children(Element e, String tag) {
        List<Element> out = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && el.getTagName().equals(tag)) out.add(el);
        }
        return out;
    }
}
