package com.wizoz.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import com.wizoz.proxy.model.Payload;

class PayloadTest {

	private static byte[] gzip(String text) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
			gz.write(text.getBytes(StandardCharsets.UTF_8));
		}
		return out.toByteArray();
	}

	@Test
	void emptyAndMissingBodiesAreEmpty() {
		assertEquals("", Payload.of(null, "application/json", null).body());
		assertEquals(0, Payload.of(new byte[0], null, null).bytes());
	}

	@Test
	void textIsShownAsIs() {
		Payload payload = Payload.of("{\"a\":1}".getBytes(StandardCharsets.UTF_8), "application/json", null);
		assertEquals("{\"a\":1}", payload.body());
		assertEquals("application/json", payload.contentType());
		assertEquals(7, payload.bytes());
		assertFalse(payload.truncated());
	}

	@Test
	void longBodiesAreCutAndMarked() {
		byte[] big = "x".repeat(Payload.PREVIEW_BYTES + 500).getBytes(StandardCharsets.UTF_8);
		Payload payload = Payload.of(big, "text/plain", null);
		assertEquals(Payload.PREVIEW_BYTES, payload.body().length());
		assertTrue(payload.truncated());
		assertEquals(big.length, payload.bytes());
	}

	@Test
	void aBodyExactlyAtTheLimitIsNotMarkedCut() {
		Payload payload = Payload.of("x".repeat(Payload.PREVIEW_BYTES).getBytes(StandardCharsets.UTF_8), "text/plain", null);
		assertFalse(payload.truncated());
	}

	@Test
	void gzipBodiesAreDecompressedForThePreviewButSizedAsSent() throws IOException {
		byte[] compressed = gzip("{\"hello\":\"world\"}");
		Payload payload = Payload.of(compressed, "application/json", "gzip");
		assertEquals("{\"hello\":\"world\"}", payload.body());
		assertEquals(compressed.length, payload.bytes());
	}

	@Test
	void aHugeGzipBombIsCutWithoutReadingItAll() throws IOException {
		Payload payload = Payload.of(gzip("a".repeat(5_000_000)), "text/plain", "gzip");
		assertEquals(Payload.PREVIEW_BYTES, payload.body().length());
		assertTrue(payload.truncated());
	}

	@Test
	void brokenGzipIsReportedNotThrown() {
		Payload payload = Payload.of("not gzip".getBytes(StandardCharsets.UTF_8), null, "gzip");
		assertTrue(payload.body().startsWith("[gzip"));
	}

	@Test
	void otherEncodingsAndBinaryAreNotShownAsText() {
		assertTrue(Payload.of(new byte[] { 1, 2, 3 }, null, "br").body().startsWith("[br"));
		byte[] binary = new byte[200];
		for (int i = 0; i < binary.length; i++) {
			binary[i] = (byte) (i % 2 == 0 ? 0 : 0xFF);
		}
		assertTrue(Payload.of(binary, "application/octet-stream", null).body().startsWith("[binary"));
	}

	@Test
	void multiByteTextSurvives() {
		assertEquals("caf\u00e9 \u2713", Payload.of("caf\u00e9 \u2713".getBytes(StandardCharsets.UTF_8), "text/plain", null).body());
	}

}
