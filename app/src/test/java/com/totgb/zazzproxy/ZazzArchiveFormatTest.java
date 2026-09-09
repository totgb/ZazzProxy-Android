package com.totgb.zazzproxy;

import com.totgb.zazzproxy.archive.ZazzArchive;
import com.totgb.zazzproxy.model.FileInfo;
import com.totgb.zazzproxy.network.protocol.BinaryProtocol;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertEquals;

import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Arrays;

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

    @Test
    public void writesTheBinaryManifestHeader() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ZazzArchive.exportManifest(Collections.emptyList(), "Test node", output);
        byte[] bytes = output.toByteArray();
        assertEquals(bytes[0], (byte) 'z');
        assertEquals(bytes[1], (byte) 'a');
        assertEquals(bytes[2], (byte) 'Z');
        assertEquals(bytes[3], (byte) 'z');
        assertEquals(bytes[4], (byte) 'P');
    }

    @Test
    public void encodesProtocolControlMessagesAsBinaryRecords() throws Exception {
        byte[] payload = BinaryProtocol.manifest("Server", Collections.singletonList(
                new FileInfo("id", "file.bin", 4, "hash")));
        assertEquals(payload[0], 0);
        BinaryProtocol.Manifest manifest = BinaryProtocol.readManifest(payload);
        assertEquals(manifest.name, "Server");
        assertEquals(manifest.files.get(0).name, "file.bin");
        assertEquals(manifest.files.get(0).bytes, 4L);
    }
}
