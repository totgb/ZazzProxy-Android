package com.totgb.zazzproxy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertEquals;

import java.io.OutputStream;
import java.util.Collections;

import org.testng.annotations.Test;

/** Guards the externally visible, case-sensitive formats from accidental renaming. */
public class ZazzArchiveFormatTest {
    @Test
    public void keepsThePublishedFormatExtensionsExactly() {
        assertEquals(ZazzArchive.MANIFEST_EXTENSION, ".zaZzProxy");
        assertEquals(ZazzArchive.SETTINGS_EXTENSION, ".zaZzSettings");
    }

    @Test
    public void writesAManifestToTheProvidedStream() throws Exception {
        OutputStream output = mock(OutputStream.class);
        ZazzArchive.exportManifest(Collections.emptyList(), "Test node", output);
        verify(output).write(org.mockito.ArgumentMatchers.any(byte[].class), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }
}
