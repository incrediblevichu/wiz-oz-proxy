package com.wizoz.proxy.model;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * One side of an exchange as shown in the request log: a text preview of the body, cut to {@link #PREVIEW_BYTES}.
 *
 * @param contentType the Content-Type header, if any
 * @param body        the body as UTF-8 text, or a bracketed note when it can't be shown as text
 * @param truncated   true when the body is longer than the preview
 * @param bytes       the size of the body as sent, before any decompression
 */
public record Payload(String contentType, String body, boolean truncated, long bytes) {

	public static final int PREVIEW_BYTES = 10_240;

	/**
	 * @param contentEncoding the Content-Encoding header: gzip is decompressed for the preview, other encodings are
	 *                        only noted
	 */
	public static Payload of(byte[] body, String contentType, String contentEncoding) {
		if (body == null || body.length == 0) {
			return new Payload(contentType, "", false, 0);
		}
		byte[] shown = body;
		String encoding = contentEncoding == null ? "" : contentEncoding.trim().toLowerCase();
		if (encoding.contains("gzip")) {
			try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
				shown = in.readNBytes(PREVIEW_BYTES + 1);
			} catch (IOException e) {
				return new Payload(contentType, "[gzip, could not be decoded: " + body.length + " bytes]", false, body.length);
			}
		} else if (!encoding.isEmpty() && !encoding.equals("identity")) {
			return new Payload(contentType, "[" + encoding + ", " + body.length + " bytes]", false, body.length);
		}
		boolean truncated = shown.length > PREVIEW_BYTES;
		String text = new String(shown, 0, Math.min(shown.length, PREVIEW_BYTES), StandardCharsets.UTF_8);
		if (looksBinary(text)) {
			return new Payload(contentType, "[binary, " + body.length + " bytes]", false, body.length);
		}
		return new Payload(contentType, text, truncated, body.length);
	}

	public static Payload ofText(String text, String contentType) {
		return of(text.getBytes(StandardCharsets.UTF_8), contentType, null);
	}

	/** More than one character in twenty being a replacement or control character means this is not text. */
	private static boolean looksBinary(String text) {
		int bad = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\uFFFD' || (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
				bad++;
			}
		}
		return bad * 20 > text.length();
	}

}
