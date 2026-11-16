package waymoreitems.core;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Point d'entrée JUnit 5 : chaque vérification du noyau devient un cas, nommé d'après elle. */
class CoreTests {

    @TestFactory
    List<DynamicTest> core() {
        List<DynamicTest> tests = new ArrayList<>();
        WmiTests.runAll((name, ok) -> tests.add(DynamicTest.dynamicTest(name, () -> assertTrue(ok, name))));
        return tests;
    }
}
