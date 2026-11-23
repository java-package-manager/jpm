package dev.jpm.maven;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilesTest {
    private static final Profiles.Context LINUX_21 = new Profiles.Context("21.0.5", "Linux", "amd64", "6.1", Map.of("ci", "true"));

    private static Element project(String profiles) throws Exception {
        String xml = "<project><profiles>" + profiles + "</profiles></project>";
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }

    private static List<String> active(String profiles, Profiles.Context ctx) throws Exception {
        return Profiles.active(project(profiles), null, ctx).stream().map(p -> Pom.text(p, "id")).toList();
    }

    @Test
    void jdkPrefixesNegationsAndRanges() {
        assertTrue(Profiles.jdk("21", "21.0.5"));
        assertFalse(Profiles.jdk("1.8", "21.0.5"));
        assertTrue(Profiles.jdk("1.8", "1.8.0_402"));
        assertTrue(Profiles.jdk("!1.8", "21"));
        assertTrue(Profiles.jdk("[17,)", "21.0.5"));
        assertFalse(Profiles.jdk("[1.8,17)", "21"));
        assertTrue(Profiles.jdk("[1.8,17)", "11.0.2"));
        assertTrue(Profiles.jdk("(,1.8],[17,)", "21"));
    }

    @Test
    void osPropertyAndAllConditionsTogether() throws Exception {
        String profiles = "<profile><id>unix</id><activation><os><family>unix</family></os></activation></profile>"
                + "<profile><id>win</id><activation><os><family>windows</family></os></activation></profile>"
                + "<profile><id>ci</id><activation><property><name>ci</name><value>true</value></property></activation></profile>"
                + "<profile><id>not-skip</id><activation><property><name>!skip</name></property></activation></profile>"
                + "<profile><id>both</id><activation><jdk>[17,)</jdk><os><arch>!aarch64</arch></os></activation></profile>"
                + "<profile><id>arm-only</id><activation><jdk>21</jdk><os><arch>aarch64</arch></os></activation></profile>";
        assertEquals(List.of("unix", "ci", "not-skip", "both"), active(profiles, LINUX_21));
    }

    @Test
    void activeByDefaultOnlyWhenNothingElseIs() throws Exception {
        String byDefault = "<profile><id>default</id><activation><activeByDefault>true</activeByDefault></activation></profile>";
        assertEquals(List.of("default"), active(byDefault + "<profile><id>old</id><activation><jdk>1.8</jdk></activation></profile>", LINUX_21));
        assertEquals(List.of("new"), active(byDefault + "<profile><id>new</id><activation><jdk>21</jdk></activation></profile>", LINUX_21));
    }

    @Test
    void fileActivationNeedsAPomOnDisk() throws Exception {
        assertEquals(List.of(), active("<profile><id>f</id><activation><file><missing>x.txt</missing></file></activation></profile>", LINUX_21));
    }

    @Test
    void theManifestsJavaDecides() {
        assertEquals("17", Profiles.Context.forJava("temurin-17").jdk());
        assertEquals("21.0.2", Profiles.Context.forJava("21.0.2").jdk());
    }
}
