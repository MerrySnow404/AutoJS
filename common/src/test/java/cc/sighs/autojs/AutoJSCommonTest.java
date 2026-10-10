package cc.sighs.autojs;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Sample test for the shared source tree. Every target compiles this same file
 * into its own test source set, and `gradlew -p common test` runs it against the
 * standalone common classpath.
 */
class AutoJSCommonTest {
    @Test
    void sharedModuleIsReachable() {
        assertNotNull(AutoJSCommon.class.getName());
    }
}
