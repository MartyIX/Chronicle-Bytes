/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.bytes;

import net.openhft.chronicle.bytes.pool.BytesPool;
import net.openhft.chronicle.bytes.util.StringInternerBytes;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.Memory;
import net.openhft.chronicle.core.annotation.ForceInline;
import net.openhft.chronicle.core.annotation.NotNull;
import net.openhft.chronicle.core.annotation.Nullable;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.core.pool.EnumInterner;
import net.openhft.chronicle.core.pool.StringBuilderPool;
import net.openhft.chronicle.core.util.ByteBuffers;
import net.openhft.chronicle.core.util.Histogram;
import net.openhft.chronicle.core.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UTFDataFormatException;
import java.lang.reflect.Field;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static net.openhft.chronicle.core.util.StringUtils.extractChars;
import static net.openhft.chronicle.core.util.StringUtils.setCount;

/**
 * Utility methods to support common functionality in this package. This is not intended to be
 * accessed directly.
 */
enum BytesInternal {
    ;
    static final char[] HEXI_DECIMAL = "0123456789ABCDEF".toCharArray();
    static final ThreadLocal<ByteBuffer> BYTE_BUFFER_TL = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(0));
    static final ThreadLocal<ByteBuffer> BYTE_BUFFER2_TL = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(0));
    private static final byte[] MIN_VALUE_TEXT = ("" + Long.MIN_VALUE).getBytes(ISO_8859_1);
    private static final StringBuilderPool SBP = new StringBuilderPool();
    private static final BytesPool BP = new BytesPool();
    private static final StringInternerBytes SI = new StringInternerBytes(4096);
    private static final byte[] Infinity = "Infinity".getBytes(ISO_8859_1);
    private static final byte[] NaN = "NaN".getBytes(ISO_8859_1);
    private static final long MAX_VALUE_DIVIDE_5 = Long.MAX_VALUE / 5;
    private static final ThreadLocal<byte[]> NUMBER_BUFFER = ThreadLocal.withInitial(() -> new byte[20]);
    private static final long MAX_VALUE_DIVIDE_10 = Long.MAX_VALUE / 10;
    private static final ThreadLocal<DateCache> dateCacheTL = new ThreadLocal<>();
    private static final int MAX_STRING_LEN = Integer.getInteger("bytes.max-string-len", 128 * 1024);

    static {
        try {
            ClassAliasPool.CLASS_ALIASES.addAlias(BytesStore.class, "!binary");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static boolean contentEqual(@org.jetbrains.annotations.Nullable @Nullable BytesStore a, @org.jetbrains.annotations.Nullable @Nullable BytesStore b) {
        if (a == null) return b == null;
        if (b == null) return false;
        if (a.readRemaining() != b.readRemaining())
            return false;
        long aPos = a.readPosition();
        long bPos = b.readPosition();
        long length = a.readRemaining();
        long i;
        for (i = 0; i < length - 7; i += 8) {
            if (a.readLong(aPos + i) != b.readLong(bPos + i))
                return false;
        }
        for (; i < length; i++) {
            if (a.readByte(aPos + i) != b.readByte(bPos + i))
                return false;
        }
        return true;
    }

    static boolean startsWith(@org.jetbrains.annotations.NotNull @NotNull BytesStore a, @org.jetbrains.annotations.NotNull @NotNull BytesStore b) {
        if (a.readRemaining() < b.readRemaining())
            return false;
        long aPos = a.readPosition();
        long bPos = b.readPosition();
        long length = b.readRemaining();
        long i;
        for (i = 0; i < length - 7; i += 8) {
            if (a.readLong(aPos + i) != b.readLong(bPos + i))
                return false;
        }
        if (i < length - 3) {
            if (a.readInt(aPos + i) != b.readInt(bPos + i))
                return false;
            i += 4;
        }
        if (i < length - 1) {
            if (a.readShort(aPos + i) != b.readShort(bPos + i))
                return false;
            i += 2;
        }
        if (i < length) {
            if (a.readByte(aPos + i) != b.readByte(bPos + i))
                return false;
//            i ++;
        }
        return true;
    }

    public static void parseUtf8(
            @org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, Appendable appendable, int utflen)
            throws UTFDataFormatRuntimeException, BufferUnderflowException {
        System.out.println("BytesInternal.parseUtf8[StreamingDataInput](utflen:" + utflen + ")");
        if (appendable instanceof StringBuilder
                && bytes.isDirectMemory()
                && utflen < 1 << 20) {
            // todo fix, a problem with very long sequences. #35
            System.out.println("BytesInternal.parseUtf8(): about to call parseUtf8_SB1()");
            parseUtf8_SB1((Bytes) bytes, (StringBuilder) appendable, utflen);
        } else {
            System.out.println("BytesInternal.parseUtf8(): about to call parseUtf81()");
            parseUtf81(bytes, appendable, utflen);
        }
        System.out.println("BytesInternal.parseUtf8[StreamingDataInput](-)");
    }

    public static void parseUtf8(
            @NotNull RandomDataInput input, long offset, Appendable appendable, int utflen)
            throws UTFDataFormatRuntimeException, BufferUnderflowException {
        System.out.println("BytesInternal.parseUtf8[RandomDataInput](offset:" + offset + ", utflen:" + utflen + ")");
        if (appendable instanceof StringBuilder) {
            if (input instanceof NativeBytesStore) {
                parseUtf8_SB1((NativeBytesStore) input, offset, (StringBuilder) appendable, utflen);
                System.out.println("BytesInternal.parseUtf8[RandomDataInput][1](-)");
                return;
            } else if (input instanceof Bytes
                    && ((Bytes) input).bytesStore() instanceof NativeBytesStore) {
                @org.jetbrains.annotations.Nullable NativeBytesStore bs = (NativeBytesStore) ((Bytes) input).bytesStore();
                parseUtf8_SB1(bs, offset, (StringBuilder) appendable, utflen);

                System.out.println("BytesInternal.parseUtf8[RandomDataInput][2](-)");
                return;
            }
        }
        parseUtf81(input, offset, appendable, utflen);
        System.out.println("BytesInternal.parseUtf8[RandomDataInput][3](-)");
    }

    public static boolean compareUtf8(@org.jetbrains.annotations.NotNull RandomDataInput input, long offset, @org.jetbrains.annotations.Nullable CharSequence other)
            throws IORuntimeException {
        long utfLen;
        if ((utfLen = input.readByte(offset++)) < 0) {
            utfLen &= 0x7FL;
            long b;
            int count = 7;
            while ((b = input.readByte(offset++)) < 0) {
                utfLen |= (b & 0x7FL) << count;
                count += 7;
            }
            if (b != 0) {
                if (count > 56)
                    throw new IORuntimeException(
                            "Cannot read more than 9 stop bits of positive value");
                utfLen |= (b << count);
            } else {
                if (count > 63)
                    throw new IORuntimeException(
                            "Cannot read more than 10 stop bits of negative value");
                utfLen = ~utfLen;
            }
        }
        if (utfLen == -1)
            return other == null;
        if (other == null)
            return false;
        return compareUtf8(input, offset, utfLen, other);
    }

    private static boolean compareUtf8(
            @org.jetbrains.annotations.NotNull RandomDataInput input, long offset, long utfLen, @org.jetbrains.annotations.NotNull CharSequence other)
            throws UTFDataFormatRuntimeException {
        if (offset + utfLen > input.realCapacity())
            throw new BufferUnderflowException();
        int i = 0;
        while (i < utfLen && i < other.length()) {
            int c = input.readByte(offset + i);
            if (c < 0)
                break;
            if ((char) c != other.charAt(i))
                return false;
            i++;
        }
        if (i < utfLen && i < other.length())
            return compareUtf82(input, offset + i, i, utfLen, other);
        return utfLen == other.length();
    }

    private static boolean compareUtf82(
            @org.jetbrains.annotations.NotNull RandomDataInput input, long offset, int charI, long utfLen, @org.jetbrains.annotations.NotNull CharSequence other)
            throws UTFDataFormatRuntimeException {
        long limit = offset + utfLen;
        while (offset < limit && charI < other.length()) {
            int c = input.readUnsignedByte(offset++);
            switch (c >> 4) {
                case 0:
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                /* 0xxxxxxx */
                    if ((char) c != other.charAt(charI))
                        return false;
                    break;

                case 12:
                case 13: {
                /* 110x xxxx 10xx xxxx */
                    if (offset == limit)
                        throw new UTFDataFormatRuntimeException(
                                "malformed input: partial character at end");
                    int char2 = input.readUnsignedByte(offset++);
                    if ((char2 & 0xC0) != 0x80)
                        throw new UTFDataFormatRuntimeException(
                                "malformed input around byte " + (offset - limit + utfLen) +
                                        " was " + char2);
                    int c2 = (char) (((c & 0x1F) << 6) |
                            (char2 & 0x3F));
                    if ((char) c2 != other.charAt(charI))
                        return false;
                    break;
                }

                case 14: {
                /* 1110 xxxx 10xx xxxx 10xx xxxx */
                    if (offset + 2 > limit)
                        throw new UTFDataFormatRuntimeException(
                                "malformed input: partial character at end");
                    int char2 = input.readUnsignedByte(offset++);
                    int char3 = input.readUnsignedByte(offset++);

                    if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80))
                        throw new UTFDataFormatRuntimeException(
                                "malformed input around byte " + (offset - limit + utfLen - 1) +
                                        " was " + char2 + " " + char3);
                    int c3 = (char) (((c & 0x0F) << 12) |
                            ((char2 & 0x3F) << 6) |
                            (char3 & 0x3F));
                    if ((char) c3 != other.charAt(charI))
                        return false;
                    break;
                }
                // TODO add code point of characters > 0xFFFF support.
                default:
                /* 10xx xxxx, 1111 xxxx */
                    throw new UTFDataFormatRuntimeException(
                            "malformed input around byte " + (offset - limit + utfLen));
            }
            charI++;
        }
        return offset == limit && charI == other.length();
    }

    public static void parse8bit(long offset, @NotNull RandomDataInput bytesStore, Appendable appendable, int utflen)
            throws BufferUnderflowException {
        if (bytesStore instanceof NativeBytesStore
                && appendable instanceof StringBuilder) {
            parse8bit_SB1(offset, (NativeBytesStore) bytesStore, (StringBuilder) appendable, utflen);
        } else {
            parse8bit1(offset, bytesStore, appendable, utflen);
        }
    }

    public static void parseUtf81(
            @org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull Appendable appendable, int utflen)
            throws UTFDataFormatRuntimeException, BufferUnderflowException {
        //System.out.println("BytesInternal.parseUtf81(utflen:" + utflen + ")");
        try {
            int count = 0;
            assert bytes.readRemaining() >= utflen;

            while (count < utflen) {
                int c = bytes.readUnsignedByte();
                if (c >= 128) {
                    bytes.readSkip(-1);
                    break;

                } else if (c < 0) {
                    break;
                }
                count++;
                appendable.append((char) c);
            }

            if (utflen > count) {
                //System.out.println("BytesInternal.parseUtf81(): about to call parseUtf82()");
                parseUtf82(bytes, appendable, utflen, count);
            }
        } catch (IOException e) {
            e.printStackTrace();
            throw Jvm.rethrow(e);
        }

        //System.out.println("BytesInternal.parseUtf81()");
    }

    public static void parseUtf81(@org.jetbrains.annotations.NotNull @NotNull RandomDataInput input, long offset,
                                  @org.jetbrains.annotations.NotNull @NotNull Appendable appendable, int utflen)
            throws UTFDataFormatRuntimeException, BufferUnderflowException {
        try {
            assert input.realCapacity() >= offset + utflen;
            long limit = offset + utflen;
            while (offset < limit) {
                int c = input.readUnsignedByte(offset++);
                if (c >= 128) {
                    offset--;
                    break;

                } else if (c < 0) {
                    break;
                }
                appendable.append((char) c);
            }

            if (limit > offset)
                parseUtf82(input, offset, limit, appendable, utflen);
        } catch (IOException e) {
            throw Jvm.rethrow(e);
        }
    }

    public static void parse8bit1(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull StringBuilder sb, int utflen) {
        assert bytes.readRemaining() >= utflen;
        sb.ensureCapacity(utflen);
        byte[] chars = StringUtils.extractChars(sb);
        for (int count = 0; count < utflen; count++) {
            int c = bytes.readUnsignedByte();
            chars[count] = (byte) c;
        }
        StringUtils.setLength(sb, utflen);
    }

    public static void parse8bit1(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull Appendable appendable, int utflen) {
        try {
            assert bytes.readRemaining() >= utflen;
            for (int count = 0; count < utflen; count++) {
                int c = bytes.readUnsignedByte();
                appendable.append((char) c);
            }
        } catch (IOException e) {
            throw Jvm.rethrow(e);
        }
    }

    public static void parse8bit1(long offset, @org.jetbrains.annotations.NotNull @NotNull RandomDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull Appendable appendable, int utflen)
            throws BufferUnderflowException {
        try {
            assert bytes.realCapacity() >= utflen + offset;
            for (int count = 0; count < utflen; count++) {
                int c = bytes.readUnsignedByte(offset + count);
                appendable.append((char) c);
            }
        } catch (IOException e) {
            throw Jvm.rethrow(e);
        }
    }

    public static void parseUtf8_SB1(@org.jetbrains.annotations.NotNull @NotNull Bytes bytes, @org.jetbrains.annotations.NotNull @NotNull StringBuilder sb, int utflen)
            throws UTFDataFormatRuntimeException, BufferUnderflowException {
        System.out.println("BytesInternal.parseUtf8_SB1[Bytes](utflen:"+utflen+")");
        try {
            int count = 0;
            if (utflen > bytes.readRemaining()) {
                @org.jetbrains.annotations.NotNull final BufferUnderflowException bue = new BufferUnderflowException();
                bue.initCause(new IllegalStateException("utflen: " + utflen + ", readRemaining: " + bytes.readRemaining()));
                throw bue;
            }
            @org.jetbrains.annotations.NotNull NativeBytesStore nbs = (NativeBytesStore) bytes.bytesStore();
            long address = nbs.address + nbs.translate(bytes.readPosition());
            @org.jetbrains.annotations.Nullable Memory memory = nbs.memory;
            //byte[] chars = extractChars(sb);
            sb.ensureCapacity(utflen);
            sb.setLength(utflen);

            while (count < utflen) {
                byte c = memory.readByte(address + count);
                if (c < 0)
                    break;
                // chars[count++] = c;
                System.out.println("BytesInternal.parseUtf8_SB1(): Write char ["+count+"] '" +(char)c+ "'");
                sb.setCharAt(count++, (char)c);
            }
            bytes.readSkip(count);
            setCount(sb, count);
            if (count < utflen)
                parseUtf82(bytes, sb, utflen, count);
        } catch (IOException e) {
            throw Jvm.rethrow(e);
        }
        System.out.println("BytesInternal.parseUtf8_SB1[Bytes](-)");
    }

    public static void parseUtf8_SB1(@org.jetbrains.annotations.NotNull @NotNull NativeBytesStore bytes, long offset,
                                     @org.jetbrains.annotations.NotNull @NotNull StringBuilder sb, int utflen)
            throws UTFDataFormatRuntimeException, BufferUnderflowException {
        System.out.println("BytesInternal.parseUtf8_SB1[NativeBytesStore](offset: "+offset+", sb:"+sb.toString()+", utflen:"+utflen+")");
        try {
            if (offset + utflen > bytes.realCapacity())
                throw new BufferUnderflowException();
            long address = bytes.address + bytes.translate(offset);
            @org.jetbrains.annotations.Nullable Memory memory = bytes.memory;
            sb.ensureCapacity(utflen);
            sb.setLength(utflen);
            int count = 0;
            while (count < utflen) {
                byte c = memory.readByte(address + count);
                if (c < 0) {
                    System.out.println("BytesInternal.parseUtf8_SB1[NativeBytesStore](): Break: c < 0: " +c);
                    break;
                }

                System.out.println("BytesInternal.parseUtf8_SB1(): Write char ["+count+"] '" +(char)c+ "'");
                sb.setCharAt(count++, (char)c);
            }
            setCount(sb, count);
            if (count < utflen) {
                System.out.println("BytesInternal.parseUtf8_SB1(): offset = " + offset + ", utflen = " + utflen + ", sb=" + sb.toString());
                parseUtf82(bytes, offset + count, offset + utflen, sb, utflen);
            }
        } catch (IOException e) {
            System.out.println("BytesInternal.parseUtf8_SB1(): IOException: " + e.getMessage());
            throw Jvm.rethrow(e);
        }

        System.out.println("BytesInternal.parseUtf8_SB1[NativeBytesStore](-)");
    }

    public static int parse8bit_SB1(long offset, @org.jetbrains.annotations.NotNull NativeBytesStore nbs, @org.jetbrains.annotations.NotNull @NotNull StringBuilder sb, int utflen) {
        long address = nbs.address + nbs.translate(offset);
        @org.jetbrains.annotations.Nullable Memory memory = nbs.memory;
        sb.ensureCapacity(utflen);
        byte[] chars = extractChars(sb);
        int count = 0;
        while (count < utflen) {
            byte b = memory.readByte(address + count);
            chars[count++] = b;
        }
        setCount(sb, count);
        return count;
    }

    static void parseUtf82(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull Appendable appendable, int utflen, int count)
            throws IOException, UTFDataFormatRuntimeException {
        System.out.println("BytesInternal.parseUtf82(utflen:"+utflen+", count:"+count+")");
        while (count < utflen) {
            int c = bytes.readUnsignedByte();
            System.out.println("BytesInternal.parseUtf82(): [utflen="+utflen+", count="+count+"] bytes.readUnsignedByte -> "
                    + c
                    + "; "
                    + String.format("%02X ", c)
                    + "; "
                    + Integer.toBinaryString(c));
            if (c < 0)
                break;
            switch (c >> 4) {
                case 0:
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                /* 0xxxxxxx */
                    count++;
                    appendable.append((char) c);
                    break;

                case 12:
                case 13: {
                /* 110x xxxx 10xx xxxx */
                    System.out.println("BytesInternal.parseUtf82(): 12/13 case");
                    count += 2;
                    System.out.println("BytesInternal.parseUtf82(): count " + count + " > " + utflen);
                    if (count > utflen)
                        throw new UTFDataFormatRuntimeException(
                                "malformed input: partial character at end");
                    int char2 = bytes.readUnsignedByte();
                    System.out.println("BytesInternal.parseUtf82(): Read another char: bytes.readUnsignedByte -> "
                            + char2 + "; "
                            + String.format("%02X ", char2)
                            + "; " + Integer.toBinaryString(char2));

                    if ((char2 & 0xC0) != 0x80)
                        throw new UTFDataFormatRuntimeException(
                                "malformed input around byte " + count + " was " + char2);

                    int c2 = (char) (((c & 0x1F) << 6) | (char2 & 0x3F));

                    System.out.println("BytesInternal.parseUtf82(): [2 bytes]: "
                            + String.format("%02X ", (byte) ((c & 0x1F) << 6))
                            + "; "
                            + String.format("%02X ", (byte) (char2 & 0x3F))
                            + "; c2="
                            + String.format("%02X ", (char) c2)
                    );

                    System.out.println("BytesInternal.parseUtf82(): final char: " + (char)c2);
                    appendable.append((char) c2);
                    break;
                }

                case 14: {
                /* 1110 xxxx 10xx xxxx 10xx xxxx */
                    count += 3;
                    if (count > utflen)
                        throw new UTFDataFormatRuntimeException(
                                "malformed input: partial character at end");
                    int char2 = bytes.readUnsignedByte();
                    int char3 = bytes.readUnsignedByte();

                    if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80))
                        throw new UTFDataFormatRuntimeException(
                                "malformed input around byte " + (count - 1) + " was " + char2 + " " + char3);
                    int c3 = (char) (((c & 0x0F) << 12) |
                            ((char2 & 0x3F) << 6) |
                            (char3 & 0x3F));
                    appendable.append((char) c3);
                    break;
                }
                // TODO add code point of characters > 0xFFFF support.
                default:
                /* 10xx xxxx, 1111 xxxx */
                    System.out.println("BytesInternal.parseUtf82(): default case: " + (c >> 4));
                    throw new UTFDataFormatRuntimeException(
                            "malformed input around byte " + count);
            }
        }
        System.out.println("BytesInternal.parseUtf82(-)");
    }

    static void parseUtf82(@org.jetbrains.annotations.NotNull @NotNull RandomDataInput input, long offset, long limit,
                           @org.jetbrains.annotations.NotNull @NotNull Appendable appendable, int utflen)
            throws IOException, UTFDataFormatRuntimeException {
        System.out.println("BytesInternal.parseUtf82[x](offset:"+offset+", limit:"+limit+", utflen: "+utflen+")");
        while (offset < limit) {
            int c = input.readUnsignedByte(offset);
            System.out.println("BytesInternal.parseUtf82[x](): [offset="+offset+", limit="+limit+"] bytes.readUnsignedByte -> "
                    + c
                    + "; "
                    + String.format("%02X ", c)
                    + "; "
                    + Integer.toBinaryString(c));
            offset++;
            switch (c >> 4) {
                case 0:
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                /* 0xxxxxxx */
                    appendable.append((char) c);
                    break;

                case 12:
                case 13: {
                /* 110x xxxx 10xx xxxx */
                    System.out.println("BytesInternal.parseUtf82[x](): 12/13 case");
                    if (offset == limit)
                        throw new UTFDataFormatRuntimeException(
                                "malformed input: partial character at end");
                    int char2 = input.readUnsignedByte(offset++);
                    System.out.println("BytesInternal.parseUtf82[x](): Read another char: bytes.readUnsignedByte -> "
                            + char2
                            + "; "
                            + String.format("%02X ", char2)
                            + "; " + Integer.toBinaryString(char2));

                    if ((char2 & 0xC0) != 0x80)
                        throw new UTFDataFormatRuntimeException(
                                "malformed input around byte " + (offset - limit + utflen) +
                                        " was " + char2);
                    int c2 = (char) (((c & 0x1F) << 6) |
                            (char2 & 0x3F));

                    // http://www.fileformat.info/info/unicode/char/0080/index.htm
                    System.out.println("BytesInternal.parseUtf82[x](): [2 bytes]: "
                            + String.format("%02X", (byte) ((c & 0x1F) << 6))
                            + "; "
                            + String.format("%02X ", (byte) (char2 & 0x3F))
                    );

                    System.out.println("BytesInternal.parseUtf82[x](): final char: "
                            + (char)c2
                            + "; c2="
                            + String.format("%04X ", c2)
                    );
                    appendable.append((char) c2);
                    break;
                }

                case 14: {
                /* 1110 xxxx 10xx xxxx 10xx xxxx */
                    System.out.println("BytesInternal.parseUtf82[x](): 14 case");
                    if (offset + 1 > limit)
                        throw new UTFDataFormatRuntimeException(
                                "malformed input: partial character at end");
                    int char2 = input.readUnsignedByte(offset++);
                    int char3 = input.readUnsignedByte(offset++);

                    if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80))
                        throw new UTFDataFormatRuntimeException(
                                "malformed input around byte " + (offset - limit + utflen - 1) +
                                        " was " + char2 + " " + char3);
                    int c3 = (char) (((c & 0x0F) << 12) |
                            ((char2 & 0x3F) << 6) |
                            (char3 & 0x3F));
                    appendable.append((char) c3);
                    System.out.println("BytesInternal.parseUtf82[x](): final char: " + (char)c3);
                    break;
                }
                // TODO add code point of characters > 0xFFFF support.
                default:
                /* 10xx xxxx, 1111 xxxx */
                    System.out.println("BytesInternal.parseUtf82[x](): default case: " + (c >> 4));
                    throw new UTFDataFormatRuntimeException(
                            "malformed input around byte " + (offset - limit + utflen));
            }
        }
    }

    public static void writeUtf8(@org.jetbrains.annotations.NotNull @NotNull StreamingDataOutput bytes, @org.jetbrains.annotations.Nullable @Nullable String str)
            throws IllegalArgumentException, BufferOverflowException {
        System.out.println("BytesInternal.writeUtf8(" + str + ")");

        if (str == null) {
            bytes.writeStopBit(-1);
            // System.out.println("writeUtf8(-) [null case]");
            return;
        }

        byte[] chars = extractChars(str);
        byte coder = StringUtils.getStringCoder(str);
        long utfLength = AppendableUtil.findUtf8Length(chars, coder);

        System.out.println("BytesInternal.writeUtf8(): str: " + str);
        System.out.println("BytesInternal.writeUtf8(): length: " + str.length());
        System.out.println("BytesInternal.writeUtf8(): bytes.length: " + utfLength);
        bytes.writeStopBit(utfLength);
        bytes.appendUtf8(chars, 0, str.length(), coder);

        System.out.println("BytesInternal.writeUtf8(-)");
    }

    @ForceInline
    public static void writeUtf8(@org.jetbrains.annotations.NotNull @NotNull StreamingDataOutput bytes, @Nullable CharSequence str)
            throws BufferOverflowException, IllegalArgumentException,
            IndexOutOfBoundsException {
        if (str instanceof String) {
            writeUtf8(bytes, (String) str);
            return;
        }
        if (str == null) {
            bytes.writeStopBit(-1);

        } else {
            long utfLength = AppendableUtil.findUtf8Length(str);
            bytes.writeStopBit(utfLength);
            appendUtf8(bytes, str, 0, str.length());
        }
    }

    @ForceInline
    public static long writeUtf8(@org.jetbrains.annotations.NotNull @NotNull RandomDataOutput out, long offset,
                                 @org.jetbrains.annotations.Nullable @Nullable CharSequence str)
            throws BufferOverflowException, IllegalArgumentException,
            IndexOutOfBoundsException {
        if (str == null) {
            offset = writeStopBit(out, offset, -1);

        } else {
            int strLength = str.length();
            if (strLength < 32) {
                long lenOffset = offset;
                offset = appendUtf8(out, offset + 1, str, 0, strLength);
                long utfLength = offset - lenOffset - 1;
                assert utfLength <= 127;
                writeStopBit(out, lenOffset, utfLength);
            } else {
                long utfLength = AppendableUtil.findUtf8Length(str);
                offset = writeStopBit(out, offset, utfLength);
                if (utfLength == strLength) {
                    append8bit(offset, out, str, 0, strLength);
                    offset += utfLength;
                } else {
                    offset = appendUtf8(out, offset, str, 0, strLength);
                }
            }
        }
        return offset;
    }

    @ForceInline
    public static long writeUtf8(@org.jetbrains.annotations.NotNull @NotNull RandomDataOutput out, long offset,
                                 @org.jetbrains.annotations.Nullable @Nullable CharSequence str, int maxUtf8Len)
            throws BufferOverflowException, IllegalArgumentException, IndexOutOfBoundsException {
        if (str == null) {
            offset = writeStopBit(out, offset, -1);

        } else {
            int strLength = str.length();
            long utfLength = AppendableUtil.findUtf8Length(str);
            if (utfLength > maxUtf8Len) {
                throw new IllegalArgumentException("Attempted to write a char sequence of " +
                        "utf8 size " + utfLength + ": \"" + str +
                        "\", when only " + maxUtf8Len + " allowed");
            }
            offset = writeStopBit(out, offset, utfLength);
            if (utfLength == strLength) {
                append8bit(offset, out, str, 0, strLength);
                offset += utfLength;
            } else {
                offset = appendUtf8(out, offset, str, 0, strLength);
            }
        }
        return offset;
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    public static Bytes asBytes(@org.jetbrains.annotations.NotNull @NotNull RandomDataOutput bytes, long position, long limit)
            throws IllegalStateException, BufferOverflowException, BufferUnderflowException {
        Bytes sbytes = bytes.bytesForWrite();
        sbytes.writeLimit(limit);
        sbytes.readLimit(limit);
        sbytes.readPosition(position);
        return sbytes;
    }

    public static void appendUtf8(@org.jetbrains.annotations.NotNull @NotNull StreamingDataOutput bytes,
                                  @org.jetbrains.annotations.NotNull @NotNull CharSequence str, int offset, int length)
            throws IndexOutOfBoundsException, BufferOverflowException {
        // System.out.println("BytesInternal.appendUtf8(str:"+str+", offset:"+offset+", length:"+length+")");

        int i;
        for (i = 0; i < length; i++) {
            char c = str.charAt(offset + i);
            if (c > 0x007F)
                break;

            // System.out.println("BytesInternal.appendUtf8(): write ascii: " + c);
            bytes.writeByte((byte) c);
        }
        appendUtf82(bytes, str, offset, length, i);

        // System.out.println("BytesInternal.appendUtf8(-)");
    }

    private static void appendUtf82(@org.jetbrains.annotations.NotNull @NotNull StreamingDataOutput bytes,
                                    @org.jetbrains.annotations.NotNull @NotNull CharSequence str, int offset, int length, int i)
            throws IndexOutOfBoundsException, BufferOverflowException {
        System.out.println("BytesInternal.appendUtf82(str:"+str+", offset:"+offset+", length:"+length+", i:"+i+")");

        for (; i < length; i++) {
            char c = str.charAt(offset + i);
            System.out.println("BytesInternal.appendUtf82(): c=" + c);
            appendUtf8Char(bytes, c);
        }

        System.out.println("BytesInternal.appendUtf82(-)");
    }

    public static long appendUtf8(@org.jetbrains.annotations.NotNull @NotNull RandomDataOutput out, long outOffset,
                                  @org.jetbrains.annotations.NotNull @NotNull CharSequence str, int strOffset, int length)
            throws IndexOutOfBoundsException, BufferOverflowException {
        System.out.println("BytesInternal.appendUtf8(str:"+str+", strOffset:"+strOffset+", length:"+length+")");

        int i;
        for (i = 0; i < length; i++) {
            char c = str.charAt(strOffset + i);
            if (c > 0x007F)
                break;
            out.writeByte(outOffset++, (byte) c);
        }
        long l = appendUtf82(out, outOffset, str, strOffset, length, i);

        System.out.println("BytesInternal.appendUtf8(-):" + l);
        return l;
    }

    private static long appendUtf82(@org.jetbrains.annotations.NotNull @NotNull RandomDataOutput out, long outOffset,
                                    @org.jetbrains.annotations.NotNull @NotNull CharSequence str, int strOffset, int length, int i)
            throws IndexOutOfBoundsException, BufferOverflowException {
        for (; i < length; i++) {
            char c = str.charAt(strOffset + i);
            outOffset = appendUtf8Char(out, outOffset, c);
        }
        return outOffset;
    }

    public static void append8bit(long offsetInRDO, RandomDataOutput bytes, @NotNull CharSequence str, int offset, int length)
            throws IllegalArgumentException, BufferOverflowException, BufferUnderflowException,
            IndexOutOfBoundsException {
        if (bytes instanceof VanillaBytes) {
            @org.jetbrains.annotations.NotNull VanillaBytes vb = (VanillaBytes) bytes;
            if (str instanceof RandomDataInput) {
                vb.write(offsetInRDO, (RandomDataInput) str, offset, length);
                return;
            }
            if (str instanceof String) {
                vb.write(offsetInRDO, str, offset, length);
                return;
            }
        }
        for (int i = 0; i < length; i++) {
            char c = str.charAt(offset + i);
            if (c > 255) c = '?';
            bytes.writeUnsignedByte(offsetInRDO + i, c);
        }
    }

    public static void appendUtf8Char(@org.jetbrains.annotations.NotNull @NotNull StreamingDataOutput bytes, int c)
            throws BufferOverflowException {
         System.out.println("BytesInternal.appendUtf8Char(c: " + c + "; " + Integer.toBinaryString(c) + ")");

        if (c <= 0x007F) {
            bytes.writeByte((byte) c);
            System.out.println("BytesInternal.appendUtf8Char(): (1): " + Integer.toBinaryString((byte) c) + " | " + (char)c);
        } else if (c <= 0x07FF) {
            bytes.writeByte((byte) (0xC0 | ((c >> 6) & 0x1F)));
            bytes.writeByte((byte) (0x80 | c & 0x3F));

            System.out.println("BytesInternal.appendUtf8Char(): (1): "
                    + String.format("%02X ", (byte) (0xC0 | ((c >> 6) & 0x1F)))
                    + "; "
                    + Integer.toBinaryString((byte) (0xC0 | ((c >> 6) & 0x1F)))
            );
            
            System.out.println("BytesInternal.appendUtf8Char(): (2): "
                    + String.format("%02X ", (byte) (0x80 | c & 0x3F))
                    + "; "
                    + Integer.toBinaryString((byte) (0x80 | c & 0x3F))
            );

        } else if (c <= 0xFFFF) {
            bytes.writeByte((byte) (0xE0 | ((c >> 12) & 0x0F)));
            bytes.writeByte((byte) (0x80 | ((c >> 6) & 0x3F)));
            bytes.writeByte((byte) (0x80 | (c & 0x3F)));

            System.out.println("BytesInternal.appendUtf8Char(): (1): " + Integer.toBinaryString((byte) (0xE0 | ((c >> 12) & 0x0F))));
            System.out.println("BytesInternal.appendUtf8Char(): (2): " + Integer.toBinaryString((byte) (0x80 | ((c >> 6) & 0x3F))));
            System.out.println("BytesInternal.appendUtf8Char(): (3): " + Integer.toBinaryString((byte) (0x80 | (c & 0x3F))));

        } else {
            bytes.writeByte((byte) (0xF0 | ((c >> 18) & 0x07)));
            bytes.writeByte((byte) (0x80 | ((c >> 12) & 0x3F)));
            bytes.writeByte((byte) (0x80 | ((c >> 6) & 0x3F)));
            bytes.writeByte((byte) (0x80 | (c & 0x3F)));

            System.out.println("BytesInternal.appendUtf8Char(): (TODO): ");
        }

        System.out.println("BytesInternal.appendUtf8Char(-)");
    }

    public static long appendUtf8Char(@org.jetbrains.annotations.NotNull @NotNull RandomDataOutput out, long offset, int c)
            throws BufferOverflowException {
        if (c <= 0x007F) {
            out.writeByte(offset++, (byte) c);

        } else if (c <= 0x07FF) {
            out.writeByte(offset++, (byte) (0xC0 | ((c >> 6) & 0x1F)));
            out.writeByte(offset++, (byte) (0x80 | c & 0x3F));

        } else if (c <= 0xFFFF) {
            out.writeByte(offset++, (byte) (0xE0 | ((c >> 12) & 0x0F)));
            out.writeByte(offset++, (byte) (0x80 | ((c >> 6) & 0x3F)));
            out.writeByte(offset++, (byte) (0x80 | (c & 0x3F)));

        } else {
            out.writeByte(offset++, (byte) (0xF0 | ((c >> 18) & 0x07)));
            out.writeByte(offset++, (byte) (0x80 | ((c >> 12) & 0x3F)));
            out.writeByte(offset++, (byte) (0x80 | ((c >> 6) & 0x3F)));
            out.writeByte(offset++, (byte) (0x80 | (c & 0x3F)));
        }
        return offset;
    }

    public static void writeStopBit(@org.jetbrains.annotations.NotNull @NotNull StreamingDataOutput out, long n)
            throws BufferOverflowException {
        if ((n & ~0x7F) == 0) {
            out.writeByte((byte) (n & 0x7f));
            return;
        }
        if ((n & ~0x3FFF) == 0) {
            out.writeByte((byte) ((n & 0x7f) | 0x80));
            out.writeByte((byte) (n >> 7));
            return;
        }
        writeStopBit0(out, n);
    }

    public static long writeStopBit(@org.jetbrains.annotations.NotNull @NotNull RandomDataOutput out, long offset, long n)
            throws BufferOverflowException {
        if ((n & ~0x7F) == 0) {
            out.writeByte(offset++, (byte) (n & 0x7f));
            return offset;
        }
        if ((n & ~0x3FFF) == 0) {
            out.writeByte(offset++, (byte) ((n & 0x7f) | 0x80));
            out.writeByte(offset++, (byte) (n >> 7));
            return offset;
        }
        return writeStopBit0(out, offset, n);
    }

    @SuppressWarnings("ShiftOutOfRange")
    public static void writeStopBit(@org.jetbrains.annotations.NotNull @NotNull StreamingDataOutput out, double d)
            throws BufferOverflowException {
        long n = Double.doubleToRawLongBits(d);
        while ((n & (~0L >>> 7)) != 0) {
            out.writeByte((byte) (((n >>> -7) & 0x7F) | 0x80));
            n <<= 7;
        }
        out.writeByte((byte) ((n >>> -7) & 0x7F));
    }

    public static double readStopBitDouble(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput in) {
        long n = 0;
        int shift = 64 - 7;
        int b;
        do {
            b = in.readByte();
            n |= shift > 0 ? (long) (b & 0x7F) << shift : b >> -shift;
            shift -= 7;
        } while (b < 0);
        return Double.longBitsToDouble(n);
    }

    static void writeStopBit0(@org.jetbrains.annotations.NotNull @NotNull StreamingDataOutput out, long n)
            throws BufferOverflowException {
        boolean neg = false;
        if (n < 0) {
            neg = true;
            n = ~n;
        }

        long n2;
        while ((n2 = n >>> 7) != 0) {
            out.writeByte((byte) (0x80L | n));
            n = n2;
        }
        // final byte
        if (!neg) {
            out.writeByte((byte) n);

        } else {
            out.writeByte((byte) (0x80L | n));
            out.writeByte((byte) 0);
        }
    }

    static long writeStopBit0(@org.jetbrains.annotations.NotNull @NotNull RandomDataOutput out, long offset, long n)
            throws BufferOverflowException {
        boolean neg = false;
        if (n < 0) {
            neg = true;
            n = ~n;
        }

        long n2;
        while ((n2 = n >>> 7) != 0) {
            out.writeByte(offset++, (byte) (0x80L | n));
            n = n2;
        }
        // final byte
        if (!neg) {
            out.writeByte(offset++, (byte) n);

        } else {
            out.writeByte(offset++, (byte) (0x80L | n));
            out.writeByte(offset++, (byte) 0);
        }
        return offset;
    }

    static int stopBitLength0(long n) {
        int len = 0;
        if (n < 0) {
            len = 1;
            n = ~n;
        }

        while ((n >>>= 7) != 0) len++;
        return len + 1;
    }

    public static String toDebugString(@org.jetbrains.annotations.NotNull @NotNull RandomDataInput bytes, long maxLength) {
        if (bytes.refCount() < 1)
            // added because something is crashing the JVM
            return "<unknown>";

        bytes.reserve();
        try {
            int len = Maths.toUInt31(maxLength + 40);
            @org.jetbrains.annotations.NotNull StringBuilder sb = new StringBuilder(len);
            long readPosition = bytes.readPosition();
            long readLimit = bytes.readLimit();
            sb.append("[")
                    .append("pos: ").append(readPosition)
                    .append(", rlim: ").append(readLimit)
                    .append(", wlim: ").append(asSize(bytes.writeLimit()))
                    .append(", cap: ").append(asSize(bytes.capacity()))
                    .append(" ] ");
            try {
                long start = Math.max(bytes.start(), readPosition - 64);
                long end = Math.min(readLimit + 64, start + maxLength);
                try {
                    for (; end >= start + 16 && end >= readLimit + 16; end -= 8) {
                        if (bytes.readLong(end - 8) != 0)
                            break;
                    }
                } catch (@org.jetbrains.annotations.NotNull UnsupportedOperationException | BufferUnderflowException ignored) {
                    // ignore
                }
                toString(bytes, sb, start, readPosition, bytes.writePosition(), end);
                if (end < bytes.readLimit())
                    sb.append("...");
            } catch (Exception e) {
                sb.append(' ').append(e);
            }
            return sb.toString();

        } finally {
            bytes.release();
        }
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    public static Object asSize(long size) {
        return size == Bytes.MAX_CAPACITY ? "8EiB" : size;
    }

    public static String to8bitString(@org.jetbrains.annotations.NotNull @NotNull BytesStore bytes)
            throws IllegalArgumentException {
        long pos = bytes.readPosition();
        int len = Maths.toInt32(bytes.readRemaining());
        @org.jetbrains.annotations.NotNull char[] chars = new char[len];
        if (bytes instanceof VanillaBytes) {
            ((VanillaBytes) bytes).read8Bit(chars, len);
        } else {
            for (int i = 0; i < len; i++)
                try {
                    chars[i] = (char) bytes.readUnsignedByte(pos + i);
                } catch (Exception e) {
                    return new String(chars, 0, len) + ' ' + e;
                }
        }
        return StringUtils.newString(chars);
    }

    @org.jetbrains.annotations.NotNull
    public static String toString(@org.jetbrains.annotations.NotNull @NotNull RandomDataInput bytes)
            throws IllegalStateException {

        // the output will be no larger than this
        final int size = MAX_STRING_LEN;
        @org.jetbrains.annotations.NotNull final StringBuilder sb = new StringBuilder(size);

        if (bytes.readRemaining() > size) {
            final Bytes bytes1 = bytes.bytesForRead();
            try {
                bytes1.readLimit(bytes1.readPosition() + size);
                toString(bytes1, sb);
                return sb.toString() + "...";
            } finally {
                bytes1.release();
            }
        } else {
            toString(bytes, sb);
            return sb.toString();
        }
    }

    private static void toString(@org.jetbrains.annotations.NotNull @NotNull RandomDataInput bytes,
                                 @org.jetbrains.annotations.NotNull @NotNull Appendable sb,
                                 long start,
                                 long readPosition,
                                 long writePosition,
                                 long end) throws BufferUnderflowException {
        try {
            // before
            if (start < bytes.start()) start = bytes.start();
            long realCapacity = bytes.realCapacity();
            if (end > realCapacity) end = realCapacity;
            if (readPosition >= start && bytes instanceof Bytes) {
                long last = Math.min(readPosition, end);
                toString(bytes, sb, start, last);
                sb.append('\u2016');
            }
            toString(bytes, sb, Math.max(readPosition, start), Math.min(writePosition, end));
            if (writePosition <= end) {
                if (bytes instanceof Bytes)
                    sb.append('\u2021');
                toString(bytes, sb, writePosition, end);
            }
        } catch (Exception e) {
            try {
                sb.append(' ').append(e.toString());
            } catch (IOException e1) {
                throw new AssertionError(e);
            }
        }
    }

    private static void toString(@org.jetbrains.annotations.NotNull @NotNull RandomDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull Appendable sb, long start, long last)
            throws IOException {
        for (long i = start; i < last; i++) {
            sb.append(bytes.printable(i));
        }
    }

    private static void toString(@org.jetbrains.annotations.NotNull @NotNull RandomDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull StringBuilder sb)
            throws IllegalStateException {
        bytes.reserve();
        assert bytes.start() <= bytes.readPosition();
        assert bytes.readPosition() <= bytes.readLimit();
        assert bytes.readLimit() <= bytes.realCapacity();

        try {
            for (long i = bytes.readPosition(); i < bytes.readLimit(); i++) {
                sb.append((char) bytes.readUnsignedByte(i));
            }
        } catch (BufferUnderflowException e) {
            sb.append(' ').append(e);
        }

        bytes.release();
    }

    @ForceInline
    public static long readStopBit(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput in)
            throws IORuntimeException {
        //System.out.println("BytesInternal.readStopBit()");

        long l = in.readByte();
        //System.out.println("BytesInternal.readStopBit():" + l);

        if (l >= 0) {
            //System.out.println("BytesInternal.readStopBit(-):" + l);
            return l;
        }

        //System.out.println("BytesInternal.readStopBit(-): readStopBit0");
        long l1 = readStopBit0(in, l);
        //System.out.println("BytesInternal.readStopBit(-):" + l1);
        return l1;
    }

    static long readStopBit0(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput in, long l)
            throws IORuntimeException {
        //System.out.println("BytesInternal.readStopBit0(l:" + l + ")");

        l &= 0x7FL;
        long b;
        int count = 7;
        while ((b = in.readByte()) < 0) {
            l |= (b & 0x7FL) << count;
            count += 7;
        }
        if (b != 0) {
            if (count > 56)
                throw new IORuntimeException(
                        "Cannot read more than 9 stop bits of positive value");

            //System.out.println("BytesInternal.readStopBit0(-):" + (l | (b << count)));
            return l | (b << count);

        } else {
            if (count > 63)
                throw new IORuntimeException(
                        "Cannot read more than 10 stop bits of negative value");

            //System.out.println("BytesInternal.readStopBit0(-):" + ~l);
            return ~l;
        }
    }

    public static void append(@org.jetbrains.annotations.NotNull @NotNull ByteStringAppender out, long num, int base)
            throws IllegalArgumentException, BufferOverflowException {
        if (num < 0) {
            if (num == Long.MIN_VALUE) {
                if (base == 10)
                    out.write(MIN_VALUE_TEXT);
                else out.write(Long.toString(Long.MIN_VALUE, base));
                return;
            }
            out.writeByte((byte) '-');
            num = -num;
        }
        if (num == 0) {
            out.writeByte((byte) '0');

        } else {
            if (base == 10)
                appendLong0(out, num);
            else
                out.write(Long.toString(num, base));
        }
    }

    public static void appendDecimal(@org.jetbrains.annotations.NotNull @NotNull ByteStringAppender out, long num, int decimalPlaces)
            throws IllegalArgumentException, BufferOverflowException {
        if (decimalPlaces == 0) {
            append(out, num, 10);
            return;
        }

        byte[] numberBuffer = NUMBER_BUFFER.get();
        int endIndex;
        if (num < 0) {
            if (num == Long.MIN_VALUE) {
                numberBuffer = MIN_VALUE_TEXT;
                endIndex = MIN_VALUE_TEXT.length;
            } else {
                out.writeByte((byte) '-');
                num = -num;
                endIndex = appendLong1(numberBuffer, num);
            }
        } else {
            endIndex = appendLong1(numberBuffer, num);
        }
        int digits = numberBuffer.length - endIndex;
        if (decimalPlaces >= digits) {
            out.writeUnsignedByte('0');
            out.writeUnsignedByte('.');
            while (decimalPlaces-- > digits)
                out.writeUnsignedByte('0');
            out.write(numberBuffer, endIndex, digits);
            return;
        }

        int decimalLength = numberBuffer.length - endIndex - decimalPlaces;
        out.write(numberBuffer, endIndex, decimalLength);
        out.writeUnsignedByte('.');
        out.write(numberBuffer, endIndex + decimalLength, digits - decimalLength);
    }

    public static void prepend(@org.jetbrains.annotations.NotNull @NotNull BytesPrepender out, long num)
            throws IllegalArgumentException, BufferOverflowException {
        boolean neg = false;
        if (num < 0) {
            if (num == Long.MIN_VALUE) {
                out.prewrite(MIN_VALUE_TEXT);
                return;
            }
            neg = true;
            num = -num;
        }
        do {
            out.prewriteByte((byte) (num % 10 + '0'));
            num /= 10;
        } while (num > 0);
        if (neg)
            out.prewriteByte((byte) '-');
    }

    /**
     * The length of the number must be fixed otherwise short numbers will not overwrite longer
     * numbers
     */
    public static void append(@org.jetbrains.annotations.NotNull @NotNull RandomDataOutput out, long offset, long num, int digits)
            throws IllegalArgumentException, BufferOverflowException {
        boolean negative = num < 0;
        num = Math.abs(num);

        for (int i = digits - 1; i > 0; i--) {
            out.writeByte(offset + i, (byte) (num % 10 + '0'));
            num /= 10;
        }
        if (negative) {
            if (num != 0)
                numberTooLarge(digits);
            out.writeByte(offset, '-');

        } else {
            if (num > 9)
                numberTooLarge(digits);
            out.writeByte(offset, (byte) (num % 10 + '0'));
        }
    }

    /**
     * Appends given long value with given decimalPlaces to RandomDataOutput out
     *
     * @param out
     * @param num
     * @param offset
     * @param decimalPlaces
     * @param width         Maximum width. I will be padded with zeros to the left if necessary
     * @throws IORuntimeException
     * @throws IllegalArgumentException
     * @throws BufferOverflowException
     */
    public static void appendDecimal(@org.jetbrains.annotations.NotNull @NotNull RandomDataOutput out, long num, long offset, int decimalPlaces, int width)
            throws IORuntimeException, IllegalArgumentException, BufferOverflowException {
        if (decimalPlaces == 0) {
            append(out, offset, num, width);
            return;
        }

        byte[] numberBuffer = NUMBER_BUFFER.get();
        int endIndex;
        if (num < 0) {
            if (num == Long.MIN_VALUE) {
                numberBuffer = MIN_VALUE_TEXT;
                endIndex = MIN_VALUE_TEXT.length;
            } else {
                out.writeByte(offset++, (byte) '-');
                num = -num;
                endIndex = appendLong1(numberBuffer, num);
            }
        } else {
            endIndex = appendLong1(numberBuffer, num);
        }
        int digits = numberBuffer.length - endIndex;

        if (decimalPlaces >= digits) {
            int numDigitsRequired = 2 + decimalPlaces;
            if (numDigitsRequired > width)
                throw new IllegalArgumentException("Value do not fit in " + width + " digits");
            out.writeUnsignedByte(offset++, '0');
            out.writeUnsignedByte(offset++, '.');
            while (decimalPlaces-- > digits)
                out.writeUnsignedByte(offset++, '0');
            out.write(offset++, numberBuffer, endIndex, digits);
            return;
        } else {
            int numDigitsRequired = digits + 1;
            if (numDigitsRequired > width)
                throw new IllegalArgumentException("Value do not fit in " + width + " digits");
        }

        while (width-- > (digits + 1)) {
            out.writeUnsignedByte(offset++, '0');
        }

        int decimalLength = numberBuffer.length - endIndex - decimalPlaces;
        out.write(offset, numberBuffer, endIndex, decimalLength);
        offset += decimalLength;
        out.writeUnsignedByte(offset++, '.');
        out.write(offset++, numberBuffer, endIndex + decimalLength, digits - decimalLength);
    }

    private static void numberTooLarge(int digits) throws IllegalArgumentException {
        throw new IllegalArgumentException("Number too large for " + digits + "digits");
    }

    private static void appendLong0(@org.jetbrains.annotations.NotNull @NotNull StreamingDataOutput out, long num)
            throws IllegalArgumentException, BufferOverflowException {
        byte[] numberBuffer = NUMBER_BUFFER.get();
        // Extract digits into the end of the numberBuffer
        int endIndex = appendLong1(numberBuffer, num);

        // Bulk copy the digits into the front of the buffer
        out.write(numberBuffer, endIndex, numberBuffer.length - endIndex);
    }

    private static int appendLong1(byte[] numberBuffer, long num) {
        numberBuffer[19] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 19;
        numberBuffer[18] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 18;
        numberBuffer[17] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 17;
        numberBuffer[16] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 16;
        numberBuffer[15] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 15;
        numberBuffer[14] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 14;
        numberBuffer[13] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 13;
        numberBuffer[12] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 12;
        numberBuffer[11] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 11;
        numberBuffer[10] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 10;
        numberBuffer[9] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 9;
        numberBuffer[8] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 8;
        numberBuffer[7] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 7;
        numberBuffer[6] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 6;
        numberBuffer[5] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 5;
        numberBuffer[4] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 4;
        numberBuffer[3] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 3;
        numberBuffer[2] = (byte) (num % 10L + '0');
        num /= 10;
        if (num <= 0)
            return 2;
        numberBuffer[1] = (byte) (num % 10L + '0');
        return 1;
    }

    public static void append(@org.jetbrains.annotations.NotNull @NotNull StreamingDataOutput out, double d)
            throws BufferOverflowException, IllegalArgumentException {
        long val = Double.doubleToRawLongBits(d);
        int sign = (int) (val >>> 63);
        int exp = (int) ((val >>> 52) & 2047);
        long mantissa = val & ((1L << 52) - 1);
        if (sign != 0) {
            out.writeByte((byte) '-');
        }
        if (exp == 0 && mantissa == 0) {
            out.writeByte((byte) '0');
            out.writeByte((byte) '.');
            out.writeByte((byte) '0');
            return;

        } else if (exp == 2047) {
            if (mantissa == 0) {
                out.write(Infinity);

            } else {
                out.write(NaN);
            }
            return;

        } else if (exp > 0) {
            mantissa += 1L << 52;
        }
        final int shift = (1023 + 52) - exp;
        if (shift > 0) {
            // integer and faction
            if (shift < 53) {
                long intValue = mantissa >> shift;
                appendLong0(out, intValue);
                mantissa -= intValue << shift;
                if (mantissa > 0) {
                    out.writeByte((byte) '.');
                    mantissa <<= 1;
                    mantissa++;
                    int precision = shift + 1;
                    long error = 1;

                    long value = intValue;
                    int decimalPlaces = 0;
                    while (mantissa > error) {
                        // times 5*2 = 10
                        mantissa *= 5;
                        error *= 5;
                        precision--;
                        long num = (mantissa >> precision);
                        value = value * 10 + num;
                        out.writeByte((byte) ('0' + num));
                        mantissa -= num << precision;

                        final double parsedValue = asDouble(value, 0, sign != 0, ++decimalPlaces);
                        if (parsedValue == d)
                            break;
                    }
                } else {
                    out.writeByte((byte) '.');
                    out.writeByte((byte) '0');
                }
                return;

            } else {
                // faction.
                out.writeByte((byte) '0');
                out.writeByte((byte) '.');
                mantissa <<= 6;
                mantissa += (1 << 5);
                int precision = shift + 6;

                long error = (1 << 5);

                long value = 0;
                int decimalPlaces = 0;
                while (mantissa > error) {
                    while (mantissa > MAX_VALUE_DIVIDE_5) {
                        mantissa >>>= 1;
                        error = (error + 1) >>> 1;
                        precision--;
                    }
                    // times 5*2 = 10
                    mantissa *= 5;
                    error *= 5;
                    precision--;
                    if (precision >= 64) {
                        decimalPlaces++;
                        out.writeByte((byte) '0');
                        continue;
                    }
                    long num = (mantissa >>> precision);
                    value = value * 10 + num;
                    final char c = (char) ('0' + num);
                    assert !(c < '0' || c > '9');
                    out.writeByte((byte) c);
                    mantissa -= num << precision;
                    final double parsedValue = asDouble(value, 0, sign != 0, ++decimalPlaces);
                    if (parsedValue == d)
                        break;
                }
                return;
            }
        }
        // large number
        mantissa <<= 10;
        int precision = -10 - shift;
        int digits = 0;
        while ((precision > 53 || mantissa > Long.MAX_VALUE >> precision) && precision > 0) {
            digits++;
            precision--;
            long mod = mantissa % 5;
            mantissa /= 5;
            int modDiv = 1;
            while (mantissa < MAX_VALUE_DIVIDE_5 && precision > 1) {
                precision -= 1;
                mantissa <<= 1;
                modDiv <<= 1;
            }
            mantissa += modDiv * mod / 5;
        }
        long val2 = precision > 0 ? mantissa << precision : mantissa >>> -precision;

        appendLong0(out, val2);
        for (int i = 0; i < digits; i++)
            out.writeByte((byte) '0');
    }

    private static double asDouble(long value, int exp, boolean negative, int decimalPlaces) {
        if (decimalPlaces > 0 && value < Long.MAX_VALUE / 2) {
            if (value < Long.MAX_VALUE / (1L << 32)) {
                exp -= 32;
                value <<= 32;
            }
            if (value < Long.MAX_VALUE / (1L << 16)) {
                exp -= 16;
                value <<= 16;
            }
            if (value < Long.MAX_VALUE / (1L << 8)) {
                exp -= 8;
                value <<= 8;
            }
            if (value < Long.MAX_VALUE / (1L << 4)) {
                exp -= 4;
                value <<= 4;
            }
            if (value < Long.MAX_VALUE / (1L << 2)) {
                exp -= 2;
                value <<= 2;
            }
            if (value < Long.MAX_VALUE / (1L << 1)) {
                exp -= 1;
                value <<= 1;
            }
        }
        if (decimalPlaces < 0) {
            for (; decimalPlaces < 0; decimalPlaces++) {
                exp++;
                int mod = 0;
                if (value > Long.MAX_VALUE / 5 * 4) {
                    mod = (int) (((value & 0x7) * 5 + 4) >> 3);
                    value >>= 3;
                    exp += 3;
                } else if (value > Long.MAX_VALUE / 5 * 2) {
                    mod = (int) (((value & 0x3) * 5 + 2) >> 2);
                    value >>= 2;
                    exp += 2;
                } else if (value > Long.MAX_VALUE / 5) {
                    mod = (int) (((value & 0x1) * 5 + 1) >> 1);
                    value >>= 1;
                    exp++;
                }
                value *= 5;
                value += mod;
            }

        } else {
            for (; decimalPlaces > 0; decimalPlaces--) {
                exp--;
                long mod = value % 5;
                value /= 5;
                int modDiv = 1;
                if (value < Long.MAX_VALUE / (1L << 4)) {
                    exp -= 4;
                    value <<= 4;
                    modDiv <<= 4;
                }
                if (value < Long.MAX_VALUE / (1L << 2)) {
                    exp -= 2;
                    value <<= 2;
                    modDiv <<= 2;
                }
                if (value < Long.MAX_VALUE / (1L << 1)) {
                    exp -= 1;
                    value <<= 1;
                    modDiv <<= 1;
                }
                if (decimalPlaces > 1)
                    value += modDiv * mod / 5;
                else
                    value += (modDiv * mod + 4) / 5;
            }
        }
        final double d = Math.scalb((double) value, exp);
        return negative ? -d : d;
    }

    @Nullable
    @ForceInline
    public static String readUtf8(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput in)
            throws BufferUnderflowException, IllegalArgumentException, IORuntimeException {
        System.out.println("BytesInternal.readUtf8()");

        StringBuilder sb = acquireStringBuilder();
        System.out.println("BytesInternal.readUtf8(): acquired stringBuilder: " + sb.toString());

        String s = in.readUtf8(sb) ? SI.intern(sb) : null;

        System.out.println("BytesInternal.readUtf8(-):" + s);
        return s;
    }

    @Nullable
    @ForceInline
    public static String readUtf8(@org.jetbrains.annotations.NotNull @NotNull RandomDataInput in, long offset, int maxUtf8Len)
            throws BufferUnderflowException, IllegalArgumentException,
            IllegalStateException, IORuntimeException {
        StringBuilder sb = acquireStringBuilder();
        return in.readUtf8Limited(offset, sb, maxUtf8Len) > 0 ? SI.intern(sb) : null;
    }

    public static StringBuilder acquireStringBuilder() {
        return SBP.acquireStringBuilder();
    }

    public static Bytes acquireBytes() {
        return BP.acquireBytes();
    }

    @Nullable
    @ForceInline
    public static String read8bit(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput in)
            throws BufferUnderflowException, IORuntimeException {
        Bytes bytes = acquireBytes();
        return in.read8bit(bytes) ? SI.intern(bytes) : null;
    }

    @org.jetbrains.annotations.Nullable
    @NotNull
    @ForceInline
    public static String parseUtf8(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull StopCharTester tester) {
        System.out.println("BytesInternal.parseUtf8()");

        StringBuilder utfReader = acquireStringBuilder();
        parseUtf8(bytes, utfReader, tester);

        System.out.println("BytesInternal.parseUtf8(-)");
        return SI.intern(utfReader);
    }

    @ForceInline
    public static void parseUtf8(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @NotNull Appendable builder,
                                 @org.jetbrains.annotations.NotNull @NotNull StopCharTester tester)
            throws BufferUnderflowException, IllegalStateException {
        System.out.println("BytesInternal.parseUtf8[2]()");

        try {
            if (builder instanceof StringBuilder && bytes.isDirectMemory()) {
                System.out.println("BytesInternal.parseUtf8[2](): StringBuilder - start");

                @org.jetbrains.annotations.NotNull Bytes vb = (Bytes) bytes;
                @org.jetbrains.annotations.NotNull StringBuilder sb = (StringBuilder) builder;
                sb.setLength(0);
                readUtf8_SB1(vb, sb, tester);

                System.out.println("BytesInternal.parseUtf8[2](): StringBuilder - finish");
            } else {
                System.out.println("BytesInternal.parseUtf8[2](): Not StringBuilder");

                AppendableUtil.setLength(builder, 0);
                System.out.println("BytesInternal.parseUtf8[2](): AppendableUtil.setLength was set.");
                readUtf81(bytes, builder, tester);
            }
        } catch (UTFDataFormatException e) {
            e.printStackTrace();
            @org.jetbrains.annotations.NotNull UTFDataFormatRuntimeException e2 = new UTFDataFormatRuntimeException("Unable to parse invalid UTF-8 code");
            e2.initCause(e);
            System.out.println("BytesInternal.parseUtf8[2](): UTFDataFormatException: " + e.getMessage());
            throw e2;

        } catch (IOException e) {
            System.out.println("BytesInternal.parseUtf8[2](): IOException: " + e.getMessage());
            throw Jvm.rethrow(e);
        }

        System.out.println("BytesInternal.parseUtf8[2](-):" + builder.toString());
    }

    private static void readUtf8_SB1(
            @org.jetbrains.annotations.NotNull @NotNull Bytes bytes, @org.jetbrains.annotations.NotNull @NotNull StringBuilder appendable, @org.jetbrains.annotations.NotNull @NotNull StopCharTester tester)
            throws IOException, IllegalArgumentException, IllegalStateException,
            BufferUnderflowException {
        System.out.println("BytesInternal.readUtf8_SB1(appendable:"+appendable.toString()+")");

        try {
            @org.jetbrains.annotations.Nullable NativeBytesStore nb = (NativeBytesStore) bytes.bytesStore();
            int i = 0, len = Maths.toInt32(bytes.readRemaining());
            long address = nb.address + nb.translate(bytes.readPosition());

            //final byte[] chars = StringUtils.extractChars(appendable);
            @org.jetbrains.annotations.Nullable Memory memory = nb.memory;
            int appendableLength = appendable.capacity();
            System.out.println("BytesInternal.readUtf8_SB1(): appendableLength: " + appendableLength);

            for (; i < len && i < appendableLength; i++) {
                int c = memory.readByte(address + i);
                if (c < 0) { // we have hit a non-ASCII character.
                    System.out.println("BytesInternal.readUtf8_SB1(): we have hit a non-ASCII character: " + c);
                    break;
                }
                if (tester.isStopChar(c)) {
                    bytes.readSkip(i + 1);
                    StringUtils.setCount(appendable, i);
                    return;
                }

                System.out.println("BytesInternal.readUtf8_SB1(): Write char: " + (char)c);
                appendable.setLength(i+1);
                appendable.setCharAt(i, (char)c);
                //chars[i] = (char) c;
//            appendable.append((char) c);
            }

            System.out.println("BytesInternal.readUtf8_SB1(): Set appendable count:" + i);
            StringUtils.setCount(appendable, i);

            bytes.readSkip(i);
            if (i < len) {
                System.out.println("BytesInternal.readUtf8_SB1(): i < len: " + i + ", " + len);
                readUtf8_SB2(bytes, appendable, tester);
            }
        } finally {
            System.out.println("BytesInternal.readUtf8_SB1(-):" + appendable.toString());
        }
    }

    private static void readUtf8_SB2(
            @org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes,
            @org.jetbrains.annotations.NotNull @NotNull StringBuilder appendable,
            @org.jetbrains.annotations.NotNull @NotNull StopCharTester tester)
            throws UTFDataFormatException {
        System.out.println("BytesInternal.readUtf8_SB2()");

        try {
            // https://stackoverflow.com/a/33349765/99256
            while (true) {
                int c = bytes.readUnsignedByte();
                System.out.println("BytesInternal.readUtf8_SB2(): readUnsignedByte: c = " + Integer.toBinaryString(c));

                switch (c >> 4) {
                    case 0:
                    case 1:
                    case 2:
                    case 3:
                    case 4:
                    case 5:
                    case 6:
                    case 7:
                /* 0xxxxxxx */
                        System.out.println("BytesInternal.readUtf8_SB2(): case 1: c = '" + (char)c + "'");

                        if (tester.isStopChar(c)) {
                            System.out.println("BytesInternal.readUtf8_SB2(): case 1: -> stop char: '" + (char)c + "'");
                            return;
                        }
                        appendable.append((char) c);
                        break;

                    case 12:
                    case 13: {
                /* 110x xxxx 10xx xxxx */
                        int char2 = bytes.readUnsignedByte();
                        if ((char2 & 0xC0) != 0x80)
                            throw new UTFDataFormatException(
                                    "malformed input around byte");
                        int c2 = (char) (((c & 0x1F) << 6) |
                                (char2 & 0x3F));

                        System.out.println("BytesInternal.readUtf8_SB2(): case 2: c2 = '" + (char)c2 + "'");

                        if (tester.isStopChar(c2))
                            return;
                        appendable.append((char) c2);
                        break;
                    }

                    case 14: {
                /* 1110 xxxx 10xx xxxx 10xx xxxx */
                        int char2 = bytes.readUnsignedByte();
                        int char3 = bytes.readUnsignedByte();

                        if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80))
                            throw new UTFDataFormatException(
                                    "malformed input around byte ");
                        int c3 = (char) (((c & 0x0F) << 12) |
                                ((char2 & 0x3F) << 6) |
                                (char3 & 0x3F));
                        if (tester.isStopChar(c3))
                            return;

                        System.out.println("BytesInternal.readUtf8_SB2(): case 3: c3 = '" + (char)c3 + "'");
                        appendable.append((char) c3);
                        break;
                    }

                    default:
                /* 10xx xxxx, 1111 xxxx */
                        System.out.println("BytesInternal.readUtf8_SB2(): case 4: malformed input around byte: " + c);
                        throw new UTFDataFormatException("malformed input around byte");
                }
            }
        } finally {
            System.out.println("BytesInternal.readUtf8_SB2(-):" + appendable.toString());
        }
    }

    private static void readUtf81(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull Appendable appendable, @org.jetbrains.annotations.NotNull @NotNull StopCharTester tester)
            throws IOException, IllegalArgumentException, BufferUnderflowException {
        System.out.println("BytesInternal.readUtf81()");

        int len = Maths.toInt32(bytes.readRemaining());
        System.out.println("BytesInternal.readUtf81(): len = " + len);

        while (len-- > 0) {
            int c = bytes.readUnsignedByte();
            if (c >= 128) {
                System.out.println("BytesInternal.readUtf81(-): non-ascii: " + c);
                bytes.readSkip(-1);
                break;
            }
            if (tester.isStopChar(c)) {
                System.out.println("BytesInternal.readUtf81(-): stop char: " + (char) c);
                return;
            }

            System.out.println("BytesInternal.readUtf81(): append char: " + (char) c);
            appendable.append((char) c);
        }
        if (len <= 0) {
            System.out.println("BytesInternal.readUtf81(): len <= 0 -> return: " + len);
            return;
        }

        readUtf82(bytes, appendable, tester);
        System.out.println("BytesInternal.readUtf81(-)");
    }

    private static void readUtf82(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull Appendable appendable, @org.jetbrains.annotations.NotNull @NotNull StopCharTester tester)
            throws IOException {
        System.out.println("BytesInternal.readUtf82()");
        try {
            while (true) {
                int c = bytes.readUnsignedByte();
                switch (c >> 4) {
                    case 0:
                    case 1:
                    case 2:
                    case 3:
                    case 4:
                    case 5:
                    case 6:
                    case 7:
                /* 0xxxxxxx */
                        System.out.println("BytesInternal.readUtf82(): case 1");
                        if (tester.isStopChar(c))
                            return;
                        appendable.append((char) c);
                        break;

                    case 12:
                    case 13: {
                /* 110x xxxx 10xx xxxx */
                        System.out.println("BytesInternal.readUtf82(): case 2");
                        int char2 = bytes.readUnsignedByte();
                        if ((char2 & 0xC0) != 0x80)
                            throw new UTFDataFormatException(
                                    "malformed input around byte");
                        int c2 = (char) (((c & 0x1F) << 6) |
                                (char2 & 0x3F));
                        if (tester.isStopChar(c2))
                            return;
                        appendable.append((char) c2);
                        break;
                    }

                    case 14: {
                /* 1110 xxxx 10xx xxxx 10xx xxxx */
                        System.out.println("BytesInternal.readUtf82(): case 3");
                        int char2 = bytes.readUnsignedByte();
                        int char3 = bytes.readUnsignedByte();

                        if (((char2 & 0xC0) != 0x80) || ((char3 & 0xC0) != 0x80))
                            throw new UTFDataFormatException(
                                    "malformed input around byte ");
                        int c3 = (char) (((c & 0x0F) << 12) |
                                ((char2 & 0x3F) << 6) |
                                (char3 & 0x3F));
                        if (tester.isStopChar(c3))
                            return;
                        appendable.append((char) c3);
                        break;
                    }

                    default:
                /* 10xx xxxx, 1111 xxxx */
                        System.out.println("BytesInternal.readUtf82(): default");
                        throw new UTFDataFormatException(
                                "malformed input around byte ");
                }
            }
        } finally {
            System.out.println("BytesInternal.readUtf82(-)");
        }
    }

    @ForceInline
    public static void parseUtf8(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull Appendable builder, @org.jetbrains.annotations.NotNull @NotNull StopCharsTester tester)
            throws IllegalArgumentException, BufferUnderflowException, IORuntimeException {
        AppendableUtil.setLength(builder, 0);
        try {
            AppendableUtil.readUtf8AndAppend(bytes, builder, tester);
        } catch (IOException e) {
            throw Jvm.rethrow(e);
        }
    }

    @ForceInline
    public static void parse8bit(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull StringBuilder builder, @org.jetbrains.annotations.NotNull @NotNull StopCharsTester tester) {
        builder.setLength(0);
        AppendableUtil.read8bitAndAppend(bytes, builder, tester);
    }

    @ForceInline
    public static void parse8bit(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull Bytes builder, @org.jetbrains.annotations.NotNull @NotNull StopCharsTester tester)
            throws BufferUnderflowException, BufferOverflowException, IllegalArgumentException {
        builder.readPosition(0);

        read8bitAndAppend(bytes, builder, tester);
    }

    @ForceInline
    public static void parse8bit(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull StringBuilder builder, @org.jetbrains.annotations.NotNull @NotNull StopCharTester tester) {
        builder.setLength(0);
        read8bitAndAppend(bytes, builder, tester);
    }

    @ForceInline
    public static void parse8bit(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull Bytes builder, @org.jetbrains.annotations.NotNull @NotNull StopCharTester tester)
            throws BufferUnderflowException, BufferOverflowException, IllegalArgumentException {
        builder.clear();

        read8bitAndAppend(bytes, builder, tester);
    }

    private static void read8bitAndAppend(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull StringBuilder appendable, @org.jetbrains.annotations.NotNull @NotNull StopCharTester tester) {
        while (true) {
            int c = bytes.readUnsignedByte();
            if (tester.isStopChar(c))
                return;
            appendable.append((char) c);
            if (bytes.readRemaining() == 0)
                return;
        }
    }

    private static void read8bitAndAppend(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull Bytes bytes2, @org.jetbrains.annotations.NotNull @NotNull StopCharTester tester)
            throws BufferUnderflowException, IllegalArgumentException, BufferOverflowException {
        while (true) {
            int c = bytes.readUnsignedByte();
            if (tester.isStopChar(c))
                return;
            bytes2.writeUnsignedByte(c);
            if (bytes.readRemaining() == 0)
                return;
        }
    }

    private static void read8bitAndAppend(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput bytes, @org.jetbrains.annotations.NotNull @NotNull Bytes bytes2, @org.jetbrains.annotations.NotNull @NotNull StopCharsTester tester)
            throws BufferUnderflowException, IllegalArgumentException, BufferOverflowException {
        int ch = bytes.readUnsignedByte();
        do {
            int next = bytes.readUnsignedByte();
            if (tester.isStopChar(ch, next)) {
                bytes.readSkip(-1);
                return;
            }
            bytes2.writeUnsignedByte(ch);
            ch = next;
        } while (bytes.readRemaining() > 1);

        if (tester.isStopChar(ch, -1)) {
            bytes.readSkip(-1);
            return;
        }
        bytes2.writeUnsignedByte(ch);
    }

    public static double parseDouble(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput in)
            throws BufferUnderflowException {
        long value = 0;
        int exp = 0;
        boolean negative = false;
        int decimalPlaces = Integer.MIN_VALUE;
        int ch;
        do {
            ch = in.readUnsignedByte();
        } while (ch == ' ');

        try {
            switch (ch) {
                case 'N':
                    if (compareRest(in, "aN"))
                        return Double.NaN;
                    in.readSkip(-1);

                    return Double.NaN;
                case 'I':
                    //noinspection SpellCheckingInspection
                    if (compareRest(in, "nfinity"))
                        return Double.POSITIVE_INFINITY;
                    in.readSkip(-1);
                    return Double.NaN;
                case '-':
                    if (compareRest(in, "Infinity"))
                        return Double.NEGATIVE_INFINITY;
                    negative = true;
                    ch = in.readUnsignedByte();
                    break;
            }
            int tens = 0;
            while (true) {
                if (ch >= '0' && ch <= '9') {
                    while (value >= MAX_VALUE_DIVIDE_10) {
                        value >>>= 1;
                        exp++;
                    }
                    value = value * 10 + (ch - '0');
                    decimalPlaces++;

                } else if (ch == '.') {
                    decimalPlaces = 0;

                } else if (ch == 'E' || ch == 'e') {
                    tens = (int) parseLong(in);
                    break;

                } else {
                    break;
                }
                if (in.readRemaining() == 0)
                    break;
                ch = in.readUnsignedByte();
            }
            if (decimalPlaces < 0)
                decimalPlaces = 0;

            return asDouble(value, exp, negative, decimalPlaces - tens);
        } finally {
            ((ByteStringParser) in).lastDecimalPlaces(decimalPlaces);
        }
    }

    static boolean compareRest(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput in, @org.jetbrains.annotations.NotNull @NotNull String s)
            throws BufferUnderflowException {
        if (s.length() > in.readRemaining())
            return false;
        long position = in.readPosition();
        for (int i = 0; i < s.length(); i++) {
            if (in.readUnsignedByte() != s.charAt(i)) {
                in.readPosition(position);
                return false;
            }
        }
        int ch;
        if (Character.isLetterOrDigit(ch = in.readUnsignedByte())) {
            in.readPosition(position);
            return false;
        }
        while (Character.isWhitespace(ch) && ch >= ' ')
            ch = in.readUnsignedByte();
        return true;
    }

    @ForceInline
    public static long parseLong(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput in)
            throws BufferUnderflowException {
        long num = 0;
        boolean negative = false;
        int b = in.peekUnsignedByte();
        if (b == '0') {
            in.readSkip(1);
            b = in.peekUnsignedByte();
            if (b == 'x' || b == 'X') {
                in.readSkip(1);
                return parseLongHexaDecimal(in);
            }
        }
        while (in.readRemaining() > 0) {
            b = in.readUnsignedByte();
            // if (b >= '0' && b <= '9')
            if ((b - ('0' + Integer.MIN_VALUE)) <= 9 + Integer.MIN_VALUE) {
                num = num * 10 + b - '0';
            } else if (b == '-') {
                negative = true;
            } else if (b == ']' || b == '}') {
                in.readSkip(-1);
                break;
            } else if (b == '.') {
                consumeDecimals(in);
                break;
            } else if (b == '_') {
                // ignore
            } else {
                break;
            }
        }
        return negative ? -num : num;
    }

    private static long parseLongHexaDecimal(@org.jetbrains.annotations.NotNull StreamingDataInput in) {
        long num = 0;
        while (in.readRemaining() > 0) {
            int b = in.readUnsignedByte();
            // if (b >= '0' && b <= '9')
            if ((b - ('0' + Integer.MIN_VALUE)) <= 9 + Integer.MIN_VALUE) {
                num = (num << 4) + b - '0';
                // if (b >= 'A' && b <= 'F')
            } else if ((b - ('A' + Integer.MIN_VALUE)) < 6 + Integer.MIN_VALUE) {
                num = (num << 4) + b - ('A' - 10);
                // if (b >= 'a' && b <= 'f')
            } else if ((b - ('a' + Integer.MIN_VALUE)) < 6 + Integer.MIN_VALUE) {
                num = (num << 4) + b - ('a' - 10);
            } else if (b == ']' || b == '}') {
                in.readSkip(-1);
                break;
            } else if (b == '.') {
                consumeDecimals(in);
                break;
            } else if (b == '_') {
                // ignore
            } else {
                break;
            }
        }
        return num;
    }

    static void consumeDecimals(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput in) {
        int b;
        while (in.readRemaining() > 0) {
            b = in.readUnsignedByte();
            if (b < '0' || b > '9') {
                break;
            }
        }
    }

    @ForceInline
    public static long parseLongDecimal(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput in)
            throws BufferUnderflowException {
        long num = 0;
        boolean negative = false;
        int decimalPlaces = Integer.MIN_VALUE;
        while (in.readRemaining() > 0) {
            int b = in.readUnsignedByte();
            // if (b >= '0' && b <= '9')
            if ((b - ('0' + Integer.MIN_VALUE)) <= 9 + Integer.MIN_VALUE) {
                num = num * 10 + b - '0';
                decimalPlaces++;
            } else if (b == '.') {
                decimalPlaces = 0;
            } else if (b == '-') {
                negative = true;
            } else if (b == ']' || b == '}') {
                in.readSkip(-1);
                break;
            } else if (b == '_') {
                // ignore
            } else {
                break;
            }
        }
        ((ByteStringParser) in).lastDecimalPlaces(decimalPlaces);
        return negative ? -num : num;
    }

    @ForceInline
    public static long parseHexLong(@org.jetbrains.annotations.NotNull @NotNull StreamingDataInput in)
            throws BufferUnderflowException {
        long num = 0;
        while (in.readRemaining() > 0) {
            int b = in.readUnsignedByte();
            // if (b >= '0' && b <= '9')
            if ((b - ('0' + Integer.MIN_VALUE)) <= 9 + Integer.MIN_VALUE) {
                num = num * 16 + b - '0';
            } else if ((b - ('A' + Integer.MIN_VALUE)) <= 6 + Integer.MIN_VALUE) {
                num = num * 16 + b - 'A' + 10;
            } else if ((b - ('a' + Integer.MIN_VALUE)) <= 6 + Integer.MIN_VALUE) {
                num = num * 16 + b - 'a' + 10;
            } else if (b == ']' || b == '}') {
                in.readSkip(-1);
                break;
            } else if (b == '_') {
                // ignore
            } else {
                break;
            }
        }
        return num;
    }

    public static long parseLong(@org.jetbrains.annotations.NotNull @NotNull RandomDataInput in, long offset)
            throws BufferUnderflowException {
        long num = 0;
        boolean negative = false;
        while (true) {
            int b = in.readUnsignedByte(offset++);
            // if (b >= '0' && b <= '9')
            if ((b - ('0' + Integer.MIN_VALUE)) <= 9 + Integer.MIN_VALUE)
                num = num * 10 + b - '0';
            else if (b == '-')
                negative = true;
            else if (b != '_')
                break;
        }
        return negative ? -num : num;
    }

    public static boolean skipTo(@org.jetbrains.annotations.NotNull @NotNull ByteStringParser parser, @org.jetbrains.annotations.NotNull @NotNull StopCharTester tester) {
        while (parser.readRemaining() > 0) {
            int ch = parser.readUnsignedByte();
            if (tester.isStopChar(ch))
                return true;
        }
        return false;
    }

    public static float addAndGetFloat(@org.jetbrains.annotations.NotNull @NotNull RandomDataInput in, long offset, float adding)
            throws BufferUnderflowException, IllegalArgumentException, BufferOverflowException {
        for (; ; ) {
            int value = in.readVolatileInt(offset);
            float value1 = Float.intBitsToFloat(value) + adding;
            int value2 = Float.floatToRawIntBits(value1);
            if (in.compareAndSwapInt(offset, value, value2))
                return value1;
        }
    }

    public static double addAndGetDouble(@org.jetbrains.annotations.NotNull @NotNull RandomDataInput in, long offset, double adding)
            throws BufferUnderflowException, IllegalArgumentException, BufferOverflowException {
        for (; ; ) {
            long value = in.readVolatileLong(offset);
            double value1 = Double.longBitsToDouble(value) + adding;
            long value2 = Double.doubleToRawLongBits(value1);
            if (in.compareAndSwapLong(offset, value, value2))
                return value1;
        }
    }

    public static int addAndGetInt(@org.jetbrains.annotations.NotNull @NotNull RandomDataInput in, long offset, int adding)
            throws BufferUnderflowException, IllegalArgumentException, BufferOverflowException {
        // TODO use Memory.addAndGetInt
        for (; ; ) {
            int value = in.readVolatileInt(offset);
            int value2 = value + adding;
            if (in.compareAndSwapInt(offset, value, value2))
                return value2;
        }
    }

    public static long addAndGetLong(@org.jetbrains.annotations.NotNull @NotNull RandomDataInput in, long offset, long adding)
            throws BufferUnderflowException, IllegalArgumentException, BufferOverflowException {
        // TODO use Memory.addAndGetLong
        for (; ; ) {
            long value = in.readVolatileLong(offset);
            long value2 = value + adding;
            if (in.compareAndSwapLong(offset, value, value2))
                return value2;
        }
    }

    /**
     * display the hex data of {@link Bytes} from the position() to the limit()
     *
     * @param bytes the buffer you wish to toString()
     * @return hex representation of the buffer, from example [0D ,OA, FF]
     */
    public static String toHexString(@org.jetbrains.annotations.NotNull @NotNull final Bytes bytes, long offset, long len)
            throws BufferUnderflowException {
        if (len == 0)
            return "";

        int width = 16;
        @org.jetbrains.annotations.NotNull int[] lastLine = new int[width];
        @org.jetbrains.annotations.NotNull String sep = "";
        long position = bytes.readPosition();
        long limit = bytes.readLimit();

        try {
            bytes.readPositionRemaining(offset, len);

            @org.jetbrains.annotations.NotNull final StringBuilder builder = new StringBuilder();
            long start = offset / width * width;
            long end = (offset + len + width - 1) / width * width;
            for (long i = start; i < end; i += width) {
                // check for duplicate rows
                if (i + width < end) {
                    boolean same = true;

                    for (int j = 0; j < width && i + j < offset + len; j++) {
                        int ch = bytes.readUnsignedByte(i + j);
                        same &= (ch == lastLine[j]);
                        lastLine[j] = ch;
                    }
                    if (i > start && same) {
                        sep = "........\n";
                        continue;
                    }
                }
                builder.append(sep);
                sep = "";
                String str = Long.toHexString(i);
                for (int j = str.length(); j < 8; j++)
                    builder.append('0');
                builder.append(str);
                for (int j = 0; j < width; j++) {
                    if (j == width / 2)
                        builder.append(' ');
                    if (i + j < offset || i + j >= offset + len) {
                        builder.append("   ");

                    } else {
                        builder.append(' ');
                        int ch = bytes.readUnsignedByte(i + j);
                        builder.append(HEXI_DECIMAL[ch >> 4]);
                        builder.append(HEXI_DECIMAL[ch & 15]);
                    }
                }
                builder.append(' ');
                for (int j = 0; j < width; j++) {
                    if (j == width / 2)
                        builder.append(' ');
                    if (i + j < offset || i + j >= offset + len) {
                        builder.append(' ');

                    } else {
                        int ch = bytes.readUnsignedByte(i + j);
                        if (ch < ' ' || ch > 126)
                            ch = '\u00B7';
                        builder.append((char) ch);
                    }
                }
                builder.append("\n");
            }
            return builder.toString();
        } finally {
            bytes.readLimit(limit);
            bytes.readPosition(position);
        }
    }

    public static void appendTimeMillis(@org.jetbrains.annotations.NotNull @NotNull ByteStringAppender b, long timeInMS)
            throws BufferOverflowException, IllegalArgumentException {
        int hours = (int) (timeInMS / (60 * 60 * 1000));
        if (hours > 99) {
            b.append(hours); // can have over 24 hours.
        } else {
            b.writeByte((byte) (hours / 10 + '0'));
            b.writeByte((byte) (hours % 10 + '0'));
        }
        b.writeByte((byte) ':');
        int minutes = (int) ((timeInMS / (60 * 1000)) % 60);
        b.writeByte((byte) (minutes / 10 + '0'));
        b.writeByte((byte) (minutes % 10 + '0'));
        b.writeByte((byte) ':');
        int seconds = (int) ((timeInMS / 1000) % 60);
        b.writeByte((byte) (seconds / 10 + '0'));
        b.writeByte((byte) (seconds % 10 + '0'));
        b.writeByte((byte) '.');
        int millis = (int) (timeInMS % 1000);
        b.writeByte((byte) (millis / 100 + '0'));
        b.writeByte((byte) (millis / 10 % 10 + '0'));
        b.writeByte((byte) (millis % 10 + '0'));
    }

    public static boolean equalBytesAny(@org.jetbrains.annotations.NotNull @NotNull BytesStore b1, @org.jetbrains.annotations.NotNull @NotNull BytesStore b2, long remaining)
            throws BufferUnderflowException {
        @org.jetbrains.annotations.Nullable BytesStore bs1 = b1.bytesStore();
        @org.jetbrains.annotations.Nullable BytesStore bs2 = b2.bytesStore();
        long i = 0;
        for (; i < remaining - 7 &&
                canReadBytesAt(bs1, b1.readPosition() + i, 8) &&
                canReadBytesAt(bs2, b2.readPosition() + i, 8); i += 8) {
            long l1 = bs1.readLong(b1.readPosition() + i);
            long l2 = bs2.readLong(b2.readPosition() + i);
            if (l1 != l2)
                return false;
        }
        if (i < remaining - 3 &&
                canReadBytesAt(bs1, b1.readPosition() + i, 4) &&
                canReadBytesAt(bs2, b2.readPosition() + i, 4)) {
            int i1 = bs1.readInt(b1.readPosition() + i);
            int i2 = bs2.readInt(b2.readPosition() + i);
            if (i1 != i2)
                return false;
            i += 4;
        }
        for (; i < remaining &&
                canReadBytesAt(bs1, b1.readPosition() + i, 1) &&
                canReadBytesAt(bs2, b2.readPosition() + i, 1); i++) {
            byte i1 = bs1.readByte(b1.readPosition() + i);
            byte i2 = bs2.readByte(b2.readPosition() + i);
            if (i1 != i2)
                return false;
        }
        return true;
    }

    public static void appendDateMillis(@org.jetbrains.annotations.NotNull @NotNull ByteStringAppender b, long timeInMS)
            throws BufferOverflowException {
        DateCache dateCache = dateCacheTL.get();
        if (dateCache == null) {
            dateCacheTL.set(dateCache = new DateCache());
        }
        long date = timeInMS / 86400000;
        if (dateCache.lastDay != date) {
            dateCache.lastDateStr = dateCache.dateFormat.format(new Date(timeInMS)).getBytes(ISO_8859_1);
            dateCache.lastDay = date;

        } else {
            assert dateCache.lastDateStr != null;
        }
        b.write(dateCache.lastDateStr);
    }

    @org.jetbrains.annotations.NotNull
    public static <E extends Enum<E>, S extends StreamingDataInput<S>> E readEnum(@org.jetbrains.annotations.NotNull StreamingDataInput input, @org.jetbrains.annotations.NotNull Class<E> eClass)
            throws BufferUnderflowException, IORuntimeException {
        Bytes bytes = acquireBytes();
        input.read8bit(bytes);

        return (E) EnumInterner.ENUM_INTERNER.get(eClass).intern(bytes);
    }

    public static void writeFully(@org.jetbrains.annotations.NotNull @NotNull BytesStore bytes, long offset, long length, @org.jetbrains.annotations.NotNull StreamingDataOutput sdo)
            throws BufferUnderflowException, BufferOverflowException {
        long i = 0;
        for (; i < length - 7; i += 8)
            sdo.writeLong(bytes.readLong(offset + i));
        if (i < length - 3) {
            sdo.writeInt(bytes.readInt(offset + i));
            i += 4;
        }
        for (; i < length; i++)
            sdo.writeByte(bytes.readByte(offset + i));
    }

    @org.jetbrains.annotations.NotNull
    public static byte[] toByteArray(@org.jetbrains.annotations.NotNull RandomDataInput in)
            throws IllegalArgumentException {
        int len = Maths.toInt32(in.readRemaining());
        @org.jetbrains.annotations.NotNull byte[] bytes = new byte[len];
        in.read(in.readPosition(), bytes, 0, bytes.length);
        return bytes;
    }

    public static void copy(@org.jetbrains.annotations.NotNull RandomDataInput input, @org.jetbrains.annotations.NotNull OutputStream output)
            throws IOException {
        @org.jetbrains.annotations.NotNull byte[] bytes = new byte[512];
        long start = input.readPosition();
        for (int i = 0, len; (len = (int) input.read(start + i, bytes, 0, bytes.length)) > 0; i += len) {
            output.write(bytes, 0, len);
        }
    }

    public static void copy(@org.jetbrains.annotations.NotNull InputStream input, @org.jetbrains.annotations.NotNull StreamingDataOutput output)
            throws IOException, IllegalArgumentException, BufferOverflowException {
        @org.jetbrains.annotations.NotNull byte[] bytes = new byte[512];
        for (int len; (len = input.read(bytes)) > 0; ) {
            output.write(bytes, 0, len);
        }
    }

    public static Boolean parseBoolean(@org.jetbrains.annotations.NotNull ByteStringParser parser, @org.jetbrains.annotations.NotNull StopCharTester tester) {
        Bytes sb = acquireBytes();
        parseUtf8(parser, sb, tester);
        if (sb.length() == 0)
            return null;
        switch (sb.charAt(0)) {
            case 't':
            case 'T':
                return sb.length() == 1 || StringUtils.equalsCaseIgnore(sb, "true") ? true : null;
            case 'y':
            case 'Y':
                return sb.length() == 1 || StringUtils.equalsCaseIgnore(sb, "yes") ? true : null;
            case '0':
                return sb.length() == 1 ? false : null;
            case '1':
                return sb.length() == 1 ? true : null;
            case 'f':
            case 'F':
                return sb.length() == 1 || StringUtils.equalsCaseIgnore(sb, "false") ? false : null;
            case 'n':
            case 'N':
                return sb.length() == 1 || StringUtils.equalsCaseIgnore(sb, "no") ? false : null;
            default:
                return null;
        }
    }

    @org.jetbrains.annotations.NotNull
    public static BytesStore subBytes(RandomDataInput from, long start, long length) {
        @org.jetbrains.annotations.NotNull BytesStore ret = NativeBytesStore.nativeStore(length);
        ret.write(0L, from, start, length);
        return ret;
    }

    public static long findByte(@org.jetbrains.annotations.NotNull RandomDataInput bytes, byte stopByte) {
        long start = bytes.readPosition();
        long remaining = bytes.readRemaining();
        for (long i = 0; i < remaining; i++) {
            if (bytes.readByte(start + i) == stopByte)
                return i;
        }
        throw new IllegalArgumentException("Stop byte " + stopByte + " not found");
    }

    @org.jetbrains.annotations.NotNull
    public static Bytes fromHexString(@org.jetbrains.annotations.NotNull String s) {
        Bytes in = Bytes.from(s);
        Bytes out = Bytes.elasticByteBuffer();
        OUTER:
        while (in.readRemaining() > 0) {
            in.parseHexLong();
            for (int i = 0; i < 16; i++) {
                if (in.peekUnsignedByte() == ' ') {
                    in.readSkip(1);
                    if (in.peekUnsignedByte() == ' ')
                        break OUTER;
                }
                long value = in.parseHexLong();
                out.writeByte((byte) value);
            }
            if (in.readByte(in.readPosition() - 1) <= ' ')
                in.readSkip(-1);
            in.skipTo(StopCharTesters.CONTROL_STOP);
        }
        return out;
    }

    public static void readHistogram(@org.jetbrains.annotations.NotNull StreamingDataInput in, @org.jetbrains.annotations.NotNull Histogram histogram) {
        int powersOf2 = Maths.toUInt31(in.readStopBit());
        int fractionBits = Maths.toUInt31(in.readStopBit());
        long overRange = in.readStopBit();
        long totalCount = in.readStopBit();
        long floor = in.readStopBit();
        histogram.init(powersOf2, fractionBits, overRange, totalCount, floor);
        int length = Maths.toUInt31(in.readStopBit());
        int[] ints = histogram.sampleCount();
        for (int i = 0; i < length; i++)
            ints[i] = Maths.toUInt31(in.readStopBit());
    }

    public static void writeHistogram(@org.jetbrains.annotations.NotNull StreamingDataOutput out, @org.jetbrains.annotations.NotNull Histogram histogram) {
        out.writeStopBit(histogram.powersOf2());
        out.writeStopBit(histogram.fractionBits());
        out.writeStopBit(histogram.overRange());
        out.writeStopBit(histogram.totalCount());
        out.writeStopBit(histogram.floor());
        int[] ints = histogram.sampleCount();
        out.writeStopBit(ints.length);
        for (int i : ints)
            out.writeStopBit(i);
    }

    public static ByteBuffer asByteBuffer(@org.jetbrains.annotations.NotNull BytesStore bytesStore) {
        return asByteBuffer(BYTE_BUFFER_TL, bytesStore);
    }

    public static ByteBuffer asByteBuffer2(@org.jetbrains.annotations.NotNull BytesStore bytesStore) {
        return asByteBuffer(BYTE_BUFFER2_TL, bytesStore);
    }

    private static ByteBuffer asByteBuffer(@org.jetbrains.annotations.NotNull ThreadLocal<ByteBuffer> byteBufferTL, @org.jetbrains.annotations.NotNull BytesStore bytesStore) {
        ByteBuffer byteBuffer = byteBufferTL.get();
        assignBytesStoreToByteBuffer(bytesStore, byteBuffer);
        return byteBuffer;
    }

    public static void assignBytesStoreToByteBuffer(@org.jetbrains.annotations.NotNull BytesStore bytesStore, @org.jetbrains.annotations.NotNull ByteBuffer byteBuffer) {
        long address = bytesStore.address(bytesStore.readPosition());
        long capacity = bytesStore.readRemaining();
        ByteBuffers.setAddressCapacity(byteBuffer, address, capacity);
        byteBuffer.clear();
    }

    static class DateCache {
        final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
        private long lastDay = Long.MIN_VALUE;
        @org.jetbrains.annotations.Nullable
        @Nullable
        private byte[] lastDateStr = null;

        DateCache() {
            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        }
    }

    private static boolean canReadBytesAt(
            final BytesStore bs, final long offset, final int length) {
        return bs.readLimit() - offset >= length;
    }
}