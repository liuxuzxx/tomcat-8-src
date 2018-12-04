package org.apache.tomcat.util.buf;

import java.io.IOException;
import java.io.Serializable;

public final class CharChunk implements Cloneable, Serializable, CharSequence {
    private static final long serialVersionUID = 1L;

    public interface CharInputChannel {

        int realReadChars(char cbuf[], int off, int len) throws IOException;
    }

    public interface CharOutputChannel {

        void realWriteChars(char cbuf[], int off, int len) throws IOException;
    }

    private int hashCode = 0;
    private boolean hasHashCode = false;
    private char buff[];
    private int start;
    private int end;
    private boolean isSet = false;
    private int limit = -1;
    private CharInputChannel in = null;
    private CharOutputChannel out = null;
    private boolean optimizedWrite = true;

    public CharChunk() {
    }

    public CharChunk(int size) {
        allocate(size, -1);
    }

    public boolean isNull() {
        if (end > 0) {
            return false;
        }
        return !isSet;
    }

    public void recycle() {
        isSet = false;
        hasHashCode = false;
        start = 0;
        end = 0;
    }

    public void allocate(int initial, int limit) {
        if (buff == null || buff.length < initial) {
            buff = new char[initial];
        }
        this.limit = limit;
        start = 0;
        end = 0;
        isSet = true;
        hasHashCode = false;
    }

    public void setOptimizedWrite(boolean optimizedWrite) {
        this.optimizedWrite = optimizedWrite;
    }

    public void setChars(char[] c, int off, int len) {
        buff = c;
        start = off;
        end = start + len;
        isSet = true;
        hasHashCode = false;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public int getLimit() {
        return limit;
    }

    public void setCharInputChannel(CharInputChannel in) {
        this.in = in;
    }

    public void setCharOutputChannel(CharOutputChannel out) {
        this.out = out;
    }

    public char[] getChars() {
        return getBuffer();
    }

    public char[] getBuffer() {
        return buff;
    }

    public int getStart() {
        return start;
    }

    public int getOffset() {
        return start;
    }

    public void setOffset(int off) {
        start = off;
    }

    public int getLength() {
        return end - start;
    }

    public int getEnd() {
        return end;
    }

    public void setEnd(int i) {
        end = i;
    }

    public void append(char b) throws IOException {
        makeSpace(1);
        if (limit > 0 && end >= limit) {
            flushBuffer();
        }
        buff[end++] = b;
    }

    public void append(CharChunk src) throws IOException {
        append(src.getBuffer(), src.getOffset(), src.getLength());
    }

    public void append(char src[], int off, int len) throws IOException {
        makeSpace(len);
        if (limit < 0) {
            System.arraycopy(src, off, buff, end, len);
            end += len;
            return;
        }
        if (optimizedWrite && len == limit && end == start && out != null) {
            out.realWriteChars(src, off, len);
            return;
        }
        if (len <= limit - end) {
            System.arraycopy(src, off, buff, end, len);
            end += len;
            return;
        }
        if (len + end < 2 * limit) {
            int avail = limit - end;
            System.arraycopy(src, off, buff, end, avail);
            end += avail;
            flushBuffer();
            System.arraycopy(src, off + avail, buff, end, len - avail);
            end += len - avail;
        } else {
            flushBuffer();
            out.realWriteChars(src, off, len);
        }
    }

    public void append(String s) throws IOException {
        append(s, 0, s.length());
    }

    public void append(String s, int off, int len) throws IOException {
        if (s == null) {
            return;
        }
        makeSpace(len);
        if (limit < 0) {
            s.getChars(off, off + len, buff, end);
            end += len;
            return;
        }

        int sOff = off;
        int sEnd = off + len;
        while (sOff < sEnd) {
            int d = min(limit - end, sEnd - sOff);
            s.getChars(sOff, sOff + d, buff, end);
            sOff += d;
            end += d;
            if (end >= limit) {
                flushBuffer();
            }
        }
    }

    public int substract() throws IOException {
        if ((end - start) == 0) {
            if (in == null) {
                return -1;
            }
            int n = in.realReadChars(buff, end, buff.length - end);
            if (n < 0) {
                return -1;
            }
        }

        return (buff[start++]);

    }

    public int substract(char src[], int off, int len) throws IOException {
        if ((end - start) == 0) {
            if (in == null) {
                return -1;
            }
            int n = in.realReadChars(buff, end, buff.length - end);
            if (n < 0) {
                return -1;
            }
        }

        int n = len;
        if (len > getLength()) {
            n = getLength();
        }
        System.arraycopy(buff, start, src, off, n);
        start += n;
        return n;

    }


    public void flushBuffer() throws IOException {
        if (out == null) {
            throw new IOException("Buffer overflow, no sink " + limit + " " + buff.length);
        }
        out.realWriteChars(buff, start, end - start);
        end = start;
    }

    public void makeSpace(int count) {
        char[] tmp = null;
        int newSize;
        int desiredSize = end + count;
        if (limit > 0 && desiredSize > limit) {
            desiredSize = limit;
        }

        if (buff == null) {
            if (desiredSize < 256) {
                desiredSize = 256;
            }
            buff = new char[desiredSize];
        }

        if (desiredSize <= buff.length) {
            return;
        }
        if (desiredSize < 2 * buff.length) {
            newSize = buff.length * 2;
            if (limit > 0 &&
                    newSize > limit) {
                newSize = limit;
            }
            tmp = new char[newSize];
        } else {
            newSize = buff.length * 2 + count;
            if (limit > 0 &&
                    newSize > limit) {
                newSize = limit;
            }
            tmp = new char[newSize];
        }

        System.arraycopy(buff, 0, tmp, 0, end);
        buff = tmp;
        tmp = null;
    }

    @Override
    public String toString() {
        if (null == buff) {
            return null;
        } else if (end - start == 0) {
            return "";
        }
        return StringCache.toString(this);
    }

    public String toStringInternal() {
        return new String(buff, start, end - start);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof CharChunk) {
            return equals((CharChunk) obj);
        }
        return false;
    }

    public boolean equals(String s) {
        char[] c = buff;
        int len = end - start;
        if (c == null || len != s.length()) {
            return false;
        }
        int off = start;
        for (int i = 0; i < len; i++) {
            if (c[off++] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    public boolean equalsIgnoreCase(String s) {
        char[] c = buff;
        int len = end - start;
        if (c == null || len != s.length()) {
            return false;
        }
        int off = start;
        for (int i = 0; i < len; i++) {
            if (Ascii.toLower(c[off++]) != Ascii.toLower(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public boolean equals(CharChunk cc) {
        return equals(cc.getChars(), cc.getOffset(), cc.getLength());
    }

    public boolean equals(char b2[], int off2, int len2) {
        char b1[] = buff;
        if (b1 == null && b2 == null) {
            return true;
        }

        if (b1 == null || b2 == null || end - start != len2) {
            return false;
        }
        int off1 = start;
        int len = end - start;
        while (len-- > 0) {
            if (b1[off1++] != b2[off2++]) {
                return false;
            }
        }
        return true;
    }

    public boolean startsWith(String s) {
        char[] c = buff;
        int len = s.length();
        if (c == null || len > end - start) {
            return false;
        }
        int off = start;
        for (int i = 0; i < len; i++) {
            if (c[off++] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    public boolean startsWithIgnoreCase(String s, int pos) {
        char[] c = buff;
        int len = s.length();
        if (c == null || len + pos > end - start) {
            return false;
        }
        int off = start + pos;
        for (int i = 0; i < len; i++) {
            if (Ascii.toLower(c[off++]) != Ascii.toLower(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public boolean endsWith(String s) {
        char[] c = buff;
        int len = s.length();
        if (c == null || len > end - start) {
            return false;
        }
        int off = end - len;
        for (int i = 0; i < len; i++) {
            if (c[off++] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        if (hasHashCode) {
            return hashCode;
        }
        int code = 0;

        code = hash();
        hashCode = code;
        hasHashCode = true;
        return code;
    }

    public int hash() {
        int code = 0;
        for (int i = start; i < start + end - start; i++) {
            code = code * 37 + buff[i];
        }
        return code;
    }

    public int indexOf(char c) {
        return indexOf(c, start);
    }

    public int indexOf(char c, int starting) {
        int ret = indexOf(buff, start + starting, end, c);
        return (ret >= start) ? ret - start : -1;
    }

    public static int indexOf(char chars[], int off, int cend, char qq) {
        while (off < cend) {
            char b = chars[off];
            if (b == qq) {
                return off;
            }
            off++;
        }
        return -1;
    }


    public int indexOf(String src, int srcOff, int srcLen, int myOff) {
        char first = src.charAt(srcOff);
        int srcEnd = srcOff + srcLen;

        for (int i = myOff + start; i <= (end - srcLen); i++) {
            if (buff[i] != first) {
                continue;
            }
            int myPos = i + 1;
            for (int srcPos = srcOff + 1; srcPos < srcEnd; ) {
                if (buff[myPos++] != src.charAt(srcPos++)) {
                    break;
                }
                if (srcPos == srcEnd) {
                    return i - start; // found it
                }
            }
        }
        return -1;
    }

    private int min(int a, int b) {
        if (a < b) {
            return a;
        }
        return b;
    }

    @Override
    public char charAt(int index) {
        return buff[index + start];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        try {
            CharChunk result = (CharChunk) this.clone();
            result.setOffset(this.start + start);
            result.setEnd(this.start + end);
            return result;
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }

    @Override
    public int length() {
        return end - start;
    }

}
