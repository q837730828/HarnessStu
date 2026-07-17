package com.ahi.harness.session;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertTrue;

public class ObservationArchiveStoreTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void archivesLargeObservationsAndReturnsReadablePointer() throws Exception {
        File workspace = temp.newFolder("workspace");
        ObservationArchiveStore store = new ObservationArchiveStore(workspace, 1000);
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            large.append('x');
        }

        String observation = store.archiveIfLarge("external__demo__large", large.toString());

        assertTrue(observation.contains("Archive path: .harness/observations/"));
        assertTrue(new File(workspace, ".harness/observations").isDirectory());
    }
}
