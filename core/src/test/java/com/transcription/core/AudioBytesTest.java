package com.transcription.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.Test;

public class AudioBytesTest {

    @Test public void readsSmallStreamFully() throws IOException {
        byte[] data = "hello world".getBytes();
        byte[] out = AudioBytes.readAll(new ByteArrayInputStream(data));
        assertArrayEquals(data, out);
    }

    @Test public void emptyStream_returnsEmptyArray() throws IOException {
        byte[] out = AudioBytes.readAll(new ByteArrayInputStream(new byte[0]));
        assertEquals(0, out.length);
    }

    @Test public void readsLargeStreamCrossingBufferBoundary() throws IOException {
        // 20 KB is > the internal 8 KB read buffer, exercising the loop.
        byte[] data = new byte[20 * 1024];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xff);
        byte[] out = AudioBytes.readAll(new ByteArrayInputStream(data));
        assertArrayEquals(data, out);
    }

    @Test public void exceedingMaxBytes_throws() {
        byte[] data = new byte[1024];
        IOException ex = assertThrows(IOException.class,
                () -> AudioBytes.readAll(new ByteArrayInputStream(data), 100));
        assertTrue(ex.getMessage(), ex.getMessage().contains("exceeds maximum size"));
    }

    @Test public void exactlyAtMaxBytes_isAllowed() throws IOException {
        byte[] data = new byte[100];
        byte[] out = AudioBytes.readAll(new ByteArrayInputStream(data), 100);
        assertEquals(100, out.length);
    }

    @Test public void nullStream_throws() {
        assertThrows(NullPointerException.class,
                () -> AudioBytes.readAll(null));
    }

    @Test public void nonPositiveMax_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> AudioBytes.readAll(new ByteArrayInputStream(new byte[0]), 0));
        assertThrows(IllegalArgumentException.class,
                () -> AudioBytes.readAll(new ByteArrayInputStream(new byte[0]), -5));
    }

    @Test public void canReadBundledOpusFixture() throws IOException {
        byte[] sample = Fixtures.read(Fixtures.SAMPLE_OPUS);
        // Ogg/Opus magic = "OggS".
        assertTrue("Fixture should be Ogg-encapsulated",
                sample.length > 4
                        && sample[0] == 'O' && sample[1] == 'g'
                        && sample[2] == 'g' && sample[3] == 'S');
        byte[] out = AudioBytes.readAll(new ByteArrayInputStream(sample));
        assertArrayEquals(sample, out);
    }

    @Test public void privateConstructor_isInvocableForCoverage() throws Exception {
        Constructor<AudioBytes> c = AudioBytes.class.getDeclaredConstructor();
        c.setAccessible(true);
        try {
            c.newInstance();
        } catch (InvocationTargetException e) {
            fail("AudioBytes() should not throw: " + e.getCause());
        }
    }
}
