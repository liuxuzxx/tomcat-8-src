package org.apache.tomcat.util.buf;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.apache.tomcat.util.res.StringManager;

public class B2CConverter {

    private static final StringManager sm = StringManager.getManager(Constants.Package);
    private static final Map<String, Charset> encodingToCharsetCache = new HashMap<>();
    private static final int LEFTOVER_SIZE = 9;

    static {
        for (Charset charset: Charset.availableCharsets().values()) {
            encodingToCharsetCache.put(charset.name().toLowerCase(Locale.ENGLISH), charset);
            for (String alias : charset.aliases()) {
                encodingToCharsetCache.put(alias.toLowerCase(Locale.ENGLISH), charset);
            }
        }
    }

    public static Charset getCharset(String enc) throws UnsupportedEncodingException {
        String lowerCaseEnc = enc.toLowerCase(Locale.ENGLISH);
        return getCharsetLower(lowerCaseEnc);
    }

    public static Charset getCharsetLower(String lowerCaseEnc) throws UnsupportedEncodingException {
        Charset charset = encodingToCharsetCache.get(lowerCaseEnc);
        if (charset == null) {
            throw new UnsupportedEncodingException(sm.getString("b2cConverter.unknownEncoding", lowerCaseEnc));
        }
        return charset;
    }

    private final CharsetDecoder decoder;
    private ByteBuffer bb = null;
    private CharBuffer cb = null;
    private final ByteBuffer leftovers;

    public B2CConverter(String encoding) throws IOException {
        this(encoding, false);
    }

    public B2CConverter(String encoding, boolean replaceOnError) throws IOException {
        byte[] left = new byte[LEFTOVER_SIZE];
        leftovers = ByteBuffer.wrap(left);
        CodingErrorAction action;
        if (replaceOnError) {
            action = CodingErrorAction.REPLACE;
        } else {
            action = CodingErrorAction.REPORT;
        }
        Charset charset = getCharset(encoding);
        if (charset.equals(StandardCharsets.UTF_8)) {
            decoder = new Utf8Decoder();
        } else {
            decoder = charset.newDecoder();
        }
        decoder.onMalformedInput(action);
        decoder.onUnmappableCharacter(action);
    }

    public void recycle() {
        decoder.reset();
        leftovers.position(0);
    }

    public void convert(ByteChunk bc, CharChunk cc, boolean endOfInput) throws IOException {
        if ((bb == null) || (bb.array() != bc.getBuffer())) {
            // Create a new byte buffer if anything changed
            bb = ByteBuffer.wrap(bc.getBuffer(), bc.getStart(), bc.getLength());
        } else {
            // Initialize the byte buffer
            bb.limit(bc.getEnd());
            bb.position(bc.getStart());
        }
        if ((cb == null) || (cb.array() != cc.getBuffer())) {
            // Create a new char buffer if anything changed
            cb = CharBuffer.wrap(cc.getBuffer(), cc.getEnd(), cc.getBuffer().length - cc.getEnd());
        } else {
            // Initialize the char buffer
            cb.limit(cc.getBuffer().length);
            cb.position(cc.getEnd());
        }
        CoderResult result = null;
        // Parse leftover if any are present
        if (leftovers.position() > 0) {
            int pos = cb.position();
            // Loop until one char is decoded or there is a decoder error
            do {
                leftovers.put(bc.substractB());
                leftovers.flip();
                result = decoder.decode(leftovers, cb, endOfInput);
                leftovers.position(leftovers.limit());
                leftovers.limit(leftovers.array().length);
            } while (result.isUnderflow() && (cb.position() == pos));
            if (result.isError() || result.isMalformed()) {
                result.throwException();
            }
            bb.position(bc.getStart());
            leftovers.position(0);
        }
        // Do the decoding and get the results into the byte chunk and the char
        // chunk
        result = decoder.decode(bb, cb, endOfInput);
        if (result.isError() || result.isMalformed()) {
            result.throwException();
        } else if (result.isOverflow()) {
            // Propagate current positions to the byte chunk and char chunk, if
            // this continues the char buffer will get resized
            bc.setOffset(bb.position());
            cc.setEnd(cb.position());
        } else if (result.isUnderflow()) {
            // Propagate current positions to the byte chunk and char chunk
            bc.setOffset(bb.position());
            cc.setEnd(cb.position());
            // Put leftovers in the leftovers byte buffer
            if (bc.getLength() > 0) {
                leftovers.limit(leftovers.array().length);
                leftovers.position(bc.getLength());
                bc.substract(leftovers.array(), 0, bc.getLength());
            }
        }
    }
}
