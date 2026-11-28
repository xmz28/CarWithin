package com.example.carwithin;

import org.junit.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class EasyConnProtocolTest {

    @Test
    public void easyConnProfilePersistsAndUpdatesNegotiatedSize() {
        StreamProfile def = StreamProfile.easyConnDefault();
        assertEquals(800, def.width());
        assertEquals(362, def.height());
        assertEquals(25, def.fps());
        assertEquals(StreamProfile.Transport.EASYCONN_TCP, def.transport());
        assertEquals(def, StreamProfile.decode(def.encode()));

        StreamProfile updated = def.withNegotiated(1024, 516, 30);
        assertEquals(1024, updated.width());
        assertEquals(516, updated.height());
        assertEquals(30, updated.fps());
        assertEquals(StreamProfile.Transport.EASYCONN_TCP, updated.transport());
    }

    @Test
    public void parseSai600CaptureConfigAndReply() throws Exception {
        byte[] body = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 800)
                .putShort((short) 362)
                .putInt(0)   // unspecified fps -> defaults to 25 for 800x362
                .putInt(2)   // H.264 encoder id
                .array();
        body[29] = 1;

        EasyConnPxcConnection.CaptureConfig cfg = EasyConnPxcConnection.parseCaptureConfig(body);
        assertEquals(800, cfg.width());
        assertEquals(362, cfg.height());
        assertEquals(25, cfg.fps());
        assertEquals(2, cfg.encoder());
        assertEquals(1, cfg.extFlag());

        byte[] reply = EasyConnPxcConnection.buildCaptureReply(cfg);
        assertEquals(9, reply.length);
        ByteBuffer rb = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, rb.getInt(0));
        assertEquals(800, rb.getShort(4) & 0xFFFF);
        assertEquals(362, rb.getShort(6) & 0xFFFF);
        assertEquals(1, reply[8]);
    }

    @Test
    public void parseOddHeightRoundsUpToEven() throws Exception {
        byte[] body = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 1024)
                .putShort((short) 515)
                .putInt(30)
                .putInt(0)
                .array();
        EasyConnPxcConnection.CaptureConfig cfg = EasyConnPxcConnection.parseCaptureConfig(body);
        assertEquals(1024, cfg.width());
        assertEquals(516, cfg.height());
        assertEquals(30, cfg.fps());
        assertEquals(2, cfg.encoder());
    }

    @Test(expected = IOException.class)
    public void parseOutOfRangeDimensionsRejected() throws Exception {
        byte[] body = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 100)
                .putShort((short) 100)
                .array();
        EasyConnPxcConnection.parseCaptureConfig(body);
    }

    @Test
    public void relaxedOwnerMatchesSai600DirectGroup() {
        Target t = new Target("594200", "50:23:4a:00:42:59");
        assertTrue(t.matchesEasyConnOwner("594200", "DIRECT-uh-594200", "52:23:4a:00:42:59"));
        assertTrue(t.matchesEasyConnOwner("", "DIRECT-uh", "52:23:4a:00:42:59"));
        assertTrue(DeviceCatalog.easyConnCandidate("594200"));
        assertFalse(DeviceCatalog.easyConnCandidate("   "));
    }
}
