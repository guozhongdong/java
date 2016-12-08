package com.github.jsoniter;

import sun.misc.FloatingDecimal;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

public class Jsoniter implements Closeable {

    final static char rune1Max = 1 << 7 - 1;
    final static char rune2Max = 1 << 11 - 1;
    final static char rune3Max = 1 << 16 - 1;
    final static char tx = 0x80; // 1000 0000
    final static char t2 = 0xC0; // 1100 0000
    final static char t3 = 0xE0; // 1110 0000
    final static char t4 = 0xF0; // 1111 0000
    final static char maskx = 0x3F; // 0011 1111
    final static int[] digits = new int[(int) 'f' + 1];
    final InputStream in;
    final byte[] buf;
    int head;
    int tail;
    boolean eof;

    public Jsoniter(InputStream in, byte[] buf) {
        this.in = in;
        this.buf = buf;
        if (this.in == null) {
            tail = buf.length;
        }
    }

    public static Jsoniter parse(InputStream in, int bufSize) {
        return new Jsoniter(in, new byte[bufSize]);
    }

    public static Jsoniter parseBytes(byte[] buf) {
        return new Jsoniter(null, buf);
    }

    public static Jsoniter parseString(String str) {
        return parseBytes(str.getBytes());
    }

    static {
        for (int i = 0; i < digits.length; i++) {
            digits[i] = -1;
        }
        for (int i = '0'; i <= '9'; ++i) {
            digits[i] = (i - '0');
        }
        for (int i = 'a'; i <= 'f'; ++i) {
            digits[i] = ((i - 'a') + 10);
        }
        for (int i = 'A'; i <= 'F'; ++i) {
            digits[i] = ((i - 'A') + 10);
        }
    }


    public void close() throws IOException {
        if (in != null) {
            in.close();
        }
    }

    byte readByte() throws IOException {
        if (head == tail) {
            if (!loadMore()) {
                return 0;
            }
        }
        return buf[head++];
    }

    private boolean loadMore() throws IOException {
        if (in == null) {
            eof = true;
            return false;
        }
        int n = in.read(buf);
        if (n < 1) {
            if (n == -1) {
                eof = true;
                return false;
            } else {
                throw new IOException("read returned " + n);
            }
        } else {
            head = 0;
            tail = n;
        }
        return true;
    }

    void unreadByte() throws IOException {
        if (head == 0) {
            throw new IOException("unread too many bytes");
        }
        head--;
    }

    public short readShort() throws IOException {
        int v = readInt();
        if (Short.MIN_VALUE <= v && v <= Short.MAX_VALUE) {
            return (short) v;
        } else {
            throw new RuntimeException("short overflow: " + v);
        }
    }

    public int readInt() throws IOException {
        byte c = readByte();
        if (c == '-') {
            return -readUnsignedInt();
        } else {
            unreadByte();
            return readUnsignedInt();
        }
    }

    public int readUnsignedInt() throws IOException {
        // TODO: throw overflow
        byte c = readByte();
        int v = digits[c];
        if (v == 0) {
            return 0;
        }
        if (v == -1) {
            throw err("readUnsignedInt", "expect 0~9");
        }
        int result = 0;
        for (; ; ) {
            result = result * 10 + v;
            c = readByte();
            v = digits[c];
            if (v == -1) {
                unreadByte();
                break;
            }
        }
        return result;
    }

    public long readLong() throws IOException {
        byte c = readByte();
        if (c == '-') {
            return -readUnsignedLong();
        } else {
            unreadByte();
            return readUnsignedLong();
        }
    }

    public long readUnsignedLong() throws IOException {
        // TODO: throw overflow
        byte c = readByte();
        int v = digits[c];
        if (v == 0) {
            return 0;
        }
        if (v == -1) {
            throw err("readUnsignedLong", "expect 0~9");
        }
        long result = 0;
        for (; ; ) {
            result = result * 10 + v;
            c = readByte();
            v = digits[c];
            if (v == -1) {
                unreadByte();
                break;
            }
        }
        return result;
    }

    public RuntimeException err(String op, String msg) {
        return new RuntimeException(op + ": " + msg + ", head: " + head + ", buf: " + new String(buf));
    }

    public boolean readArray() throws IOException {
        byte c = nextToken();
        switch (c) {
            case '[':
                c = nextToken();
                if (c == ']') {
                    return false;
                } else {
                    unreadByte();
                    return true;
                }
            case ']':
                return false;
            case ',':
                skipWhitespaces();
                return true;
            case 'n':
                skipUntilBreak();
                return false;
            default:
                throw err("readArray", "expect [ or , or n or ]");
        }
    }

    private void skipWhitespaces() throws IOException {
        for(;;) {
            for (int i = head; i < tail; i++) {
                byte c = buf[i];
                switch (c) {
                    case ' ':
                    case '\n':
                    case '\t':
                    case '\r':
                        continue;
                }
                head = i;
                return;
            }
            if (!loadMore()) {
                return;
            }
        }
    }

    private byte nextToken() throws IOException {
        for(;;) {
            for (int i = head; i < tail; i++) {
                byte c = buf[i];
                switch (c) {
                    case ' ':
                    case '\n':
                    case '\t':
                    case '\r':
                        continue;
                }
                head = i+1;
                return c;
            }
            if (!loadMore()) {
                return 0;
            }
        }
    }

    public Slice readStringAsSlice() throws IOException {
        byte c = readByte();
        switch (c) {
            case 'n':
                skipUntilBreak();
                return Slice.make(0, 0);
            case '"':
                break;
            default:
                throw err("readStringAsSlice", "expect n or \"");
        }
        Slice result = Slice.make(0, 10);
        for (; ; ) {
            c = readByte();
            switch (c) {
                case '"':
                    return result;
                case '\\':
                    c = readByte();
                    switch (c) {
                        case '"':
                            result.append((byte) '"');
                            break;
                        case '\\':
                            result.append((byte) '\\');
                            break;
                        case '/':
                            result.append((byte) '/');
                            break;
                        case 'b':
                            result.append((byte) '\b');
                            break;
                        case 'f':
                            result.append((byte) '\f');
                            break;
                        case 'n':
                            result.append((byte) '\n');
                            break;
                        case 'r':
                            result.append((byte) '\r');
                            break;
                        case 't':
                            result.append((byte) '\t');
                            break;
                        case 'u':
                            int v = digits[readByte()];
                            if (v == -1) {
                                throw err("readStringAsSlice", "expect 0~9 or a~f");
                            }
                            char b = (char) v;
                            v = digits[readByte()];
                            if (v == -1) {
                                throw err("readStringAsSlice", "expect 0~9 or a~f");
                            }
                            b = (char) (b << 4);
                            b += v;
                            v = digits[readByte()];
                            if (v == -1) {
                                throw err("readStringAsSlice", "expect 0~9 or a~f");
                            }
                            b = (char) (b << 4);
                            b += v;
                            v = digits[readByte()];
                            if (v == -1) {
                                throw err("readStringAsSlice", "expect 0~9 or a~f");
                            }
                            b = (char) (b << 4);
                            b += v;
                            if (b <= rune1Max) {
                                result.append((byte) b);
                            } else if (b <= rune2Max) {
                                result.append((byte) (t2 | ((byte) (b >> 6))));
                                result.append((byte) (tx | ((byte) b) & maskx));
                            } else if (b <= rune3Max) {
                                result.append((byte) (t3 | ((byte) (b >> 12))));
                                result.append((byte) (tx | ((byte) (b >> 6)) & maskx));
                                result.append((byte) (tx | ((byte) b) & maskx));
                            } else {
                                result.append((byte) (t4 | ((byte) (b >> 18))));
                                result.append((byte) (tx | ((byte) (b >> 12)) & maskx));
                                result.append((byte) (tx | ((byte) (b >> 6)) & maskx));
                                result.append((byte) (tx | ((byte) b) & maskx));
                            }
                            break;
                        default:
                            throw err("readStringAsSlice", "invalid escape char after \\");
                    }
                    break;
                default:
                    result.append(c);
            }
        }
    }

    public Slice readObject() throws IOException {
        byte c = nextToken();
        switch (c) {
            case 'n':
                skipUntilBreak();
                return Slice.make(0, 0);
            case '{':
                c = nextToken();
                switch (c) {
                    case '}':
                        return null; // end of object
                    case '"':
                        unreadByte();
                        return readObjectField();
                    default:
                        throw err("readObject", "expect \" after {");
                }
            case ',':
                skipWhitespaces();
                return readObjectField();
            case '}':
                return null; // end of object
            default:
                throw err("readObject", "expect { or , or } or n");
        }
    }

    private Slice readObjectField() throws IOException {
        Slice field = readStringAsSlice();
        byte c = nextToken();
        if (c != ':') {
            throw err("readObjectField", "expect : after object field");
        }
        skipWhitespaces();
        return field;
    }

    public <T> T read(Class<T> clazz) throws IOException {
        return (T) Codegen.gen(clazz).decode(clazz, this);
    }

    private String readNumber() throws IOException {
        StringBuilder str = new StringBuilder(8);
        for (byte c = readByte(); !eof; c = readByte()) {
            switch (c) {
                case '-':
                case '+':
                case '.':
                case 'e':
                case 'E':
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    str.append(((char) c));
                    break;
                default:
                    unreadByte();
                    return str.toString();
            }
        }
        return str.toString();
    }

    public float readFloat() throws IOException {
        // TODO: remove dependency on sun.misc
        return FloatingDecimal.readJavaFormatString(readNumber()).floatValue();
    }

    public double readDouble() throws IOException {
        // TODO: remove dependency on sun.misc
        return FloatingDecimal.readJavaFormatString(readNumber()).doubleValue();
    }

    public <T> T read(TypeLiteral<T> typeLiteral) throws IOException {
        System.out.println(typeLiteral.getType());
        return null;
    }

    public void skip() throws IOException {
        byte c = readByte();
        switch (c) {
            case '"':
                skipString();
                return;
            case '-':
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
            case 't':
            case 'f':
            case 'n':
                skipUntilBreak();
                return;
            case '[':
                skipArray();
                return;
            case '{':
                skipObject();
                return;
            default:
                throw err("Skip", "do not know how to skip: " + c);
        }
    }

    private void skipObject() throws IOException {
        int level = 1;
        for(;;) {
            for (int i = head; i < tail; i++) {
                switch (buf[i]) {
                    case '"': // If inside string, skip it
                        head = i + 1;
                        skipString();
                        i = head - 1; // it will be i++ soon
                        break;
                    case '{': // If open symbol, increase level
                        level++;
                        break;
                    case '}': // If close symbol, increase level
                        level--;

                        // If we have returned to the original level, we're done
                        if (level == 0) {
                            head = i + 1;
                            return;
                        }
                        break;
                }
            }
            if (!loadMore()) {
                return;
            }
        }
    }

    private void skipArray() throws IOException {
        int level = 1;
        for(;;) {
            for (int i = head; i < tail; i++) {
                switch (buf[i]) {
                    case '"': // If inside string, skip it
                        head = i + 1;
                        skipString();
                        i = head - 1; // it will be i++ soon
                        break;
                    case '[': // If open symbol, increase level
                        level++;
                        break;
                    case ']': // If close symbol, increase level
                        level--;

                        // If we have returned to the original level, we're done
                        if (level == 0) {
                            head = i + 1;
                            return;
                        }
                        break;
                }
            }
            if (!loadMore()) {
                return;
            }
        }
    }

    private void skipUntilBreak() throws IOException {
        // true, false, null, number
        for (; ; ) {
            for (int i = head; i < tail; i++) {
                byte c = buf[i];
                switch (c) {
                    case ' ':
                    case '\n':
                    case '\r':
                    case '\t':
                    case ',':
                    case '}':
                    case ']':
                        head = i;
                        return;
                }
            }
            if (!loadMore()) {
                return;
            }
        }
    }

    private void skipString() throws IOException {
        for(;;) {
            int end = findStringEnd();
            if (end == -1) {
                int j = tail - 1;
                boolean escaped = true;
                for(;;) {
                    if (j < head || buf[j] != '\\') {
                        // even number of backslashes
                        // either end of buffer, or " found
                        escaped = false;
                        break;
                    }
                    j--;
                    if (j < head || buf[j] != '\\') {
                        // odd number of backslashes
                        // it is \" or \\\"
                        break;
                    }
                    j--;

                }
                if (!loadMore()) {
                    return;
                }
                if (escaped) {
                    head = 1; // skip the first char as last char read is \
                }
            } else {
                head = end;
                return;
            }
        }
    }

    // adapted from: https://github.com/buger/jsonparser/blob/master/parser.go
    // Tries to find the end of string
    // Support if string contains escaped quote symbols.
    int findStringEnd() {
        boolean escaped = false;
        for (int i = head; i < tail; i++) {
            byte c = buf[i];
            if (c == '"') {
                if (!escaped) {
                    return i + 1;
                } else {
                    int j = i - 1;
                    for (; ; ) {
                        if (j < head || buf[j] != '\\') {
                            // even number of backslashes
                            // either end of buffer, or " found
                            return i + 1;
                        }
                        j--;
                        if (j < head || buf[j] != '\\') {
                            // odd number of backslashes
                            // it is \" or \\\"
                            break;
                        }
                        j--;
                    }
                }
            } else if (c == '\\') {
                escaped = true;
            }
        }
        return -1;
    }
}