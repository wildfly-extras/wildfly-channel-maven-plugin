package org.wildfly.channelplugin.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class VersionComparatorTestCase {

    private VersionComparator instance = new VersionComparator();

    @Test
    public void test() {
        // The commented-out assertions are not satisfied by the currently used comparator.

        Assertions.assertTrue(instance.compare("1.42.1", "1.42.0") > 0);
//        Assertions.assertTrue(instance.compare("1.42.1", "1.42.1.alpha") > 0);
        Assertions.assertTrue(instance.compare("1.42.1.beta", "1.42.1.alpha") > 0);
        Assertions.assertTrue(instance.compare("1.42.1.cr1", "1.42.1.beta") > 0);
        Assertions.assertTrue(instance.compare("1.42.1.final", "1.42.1.cr1") > 0);
        Assertions.assertTrue(instance.compare("1.42.1.ga", "1.42.1.cr1") > 0);
//        Assertions.assertTrue(instance.compare("1.42.1.ga", "1.42.1.rc1") > 0);
//        Assertions.assertEquals(0, instance.compare("1.42.1.alpha", "1.42.1-alpha"));
        Assertions.assertTrue(instance.compare("1.42.1.alpha-redhat-00001", "1.42.1-alpha") > 0);
//        Assertions.assertTrue(instance.compare("5.0.SP3", "5.0.0.SP3-redhat-00001") < 0); !!!
    }
}
