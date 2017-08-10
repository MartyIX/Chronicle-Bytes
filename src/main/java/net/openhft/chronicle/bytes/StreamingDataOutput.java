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

import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.annotation.NotNull;
import net.openhft.chronicle.core.util.Histogram;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Position based access.  Once data has been read, the position() moves.
 * <p>The use of this instance is single threaded, though the use of the data
 */
public interface StreamingDataOutput<S extends StreamingDataOutput<S>> extends StreamingCommon<S> {

    @org.jetbrains.annotations.NotNull
    S writePosition(long position) throws BufferOverflowException;

    @org.jetbrains.annotations.NotNull
    @NotNull
    S writeLimit(long limit) throws BufferOverflowException;

    /**
     * Skip a number of bytes by moving the readPosition. Must be less than or equal to the readLimit.
     *
     * @param bytesToSkip bytes to skip.
     * @return this
     * @throws BufferOverflowException if the offset is outside the limits of the Bytes
     */
    @org.jetbrains.annotations.NotNull
    S writeSkip(long bytesToSkip) throws BufferOverflowException;

    /**
     * @return Bytes as an OutputStream
     */
    @org.jetbrains.annotations.NotNull
    @NotNull
    default OutputStream outputStream() {
        return new StreamingOutputStream(this);
    }

    /**
     * Write a stop bit encoded long
     *
     * @param x long to write
     * @return this.
     */
    @org.jetbrains.annotations.NotNull
    @NotNull
    default S writeStopBit(long x) throws BufferOverflowException {
        BytesInternal.writeStopBit(this, x);
        return (S) this;
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S writeStopBit(double d) throws BufferOverflowException {
        BytesInternal.writeStopBit(this, d);
        return (S) this;
    }

    /**
     * Write the same encoding as <code>writeUTF</code> with the following changes.  1) The length is stop bit encoded
     * i.e. one byte longer for short strings, but is not limited in length. 2) The string can be null.
     *
     * @param cs the string value to be written. Can be null.
     * @throws BufferOverflowException if there is not enough space left
     */
    @org.jetbrains.annotations.NotNull
    @NotNull
    default S writeUtf8(CharSequence cs)
            throws BufferOverflowException {
        BytesInternal.writeUtf8(this, cs);
        return (S) this;
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S writeUtf8(String s)
            throws BufferOverflowException {
        BytesInternal.writeUtf8(this, s);
        return (S) this;
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    @Deprecated
    default S writeUTFΔ(CharSequence cs) throws BufferOverflowException {
        return writeUtf8(cs);
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S write8bit(@Nullable CharSequence cs)
            throws BufferOverflowException {
        if (cs == null)
            return writeStopBit(-1);

        if (cs instanceof BytesStore)
            return write8bit((BytesStore) cs);

        if (cs instanceof String)
            return write8bit((String) cs);

        return write8bit(cs, 0, cs.length());
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S write8bit(@org.jetbrains.annotations.NotNull @NotNull CharSequence s, int start, int length)
            throws BufferOverflowException, IllegalArgumentException, IndexOutOfBoundsException {
        writeStopBit(length);
        for (int i = 0; i < length; i++) {
            char c = s.charAt(i + start);
            writeUnsignedByte(c);
        }
        return (S) this;
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S write(CharSequence cs)
            throws BufferOverflowException, BufferUnderflowException, IllegalArgumentException {
        if (cs instanceof BytesStore) {
            return write((BytesStore) cs);
        }
        return write(cs, 0, cs.length());
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S write(@org.jetbrains.annotations.NotNull @NotNull CharSequence s, int start, int length)
            throws BufferOverflowException, IllegalArgumentException, IndexOutOfBoundsException {
        for (int i = 0; i < length; i++) {
            char c = s.charAt(i + start);
            appendUtf8(c);
        }
        return (S) this;
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S write8bit(@Nullable @NotNull String s)
            throws BufferOverflowException {
        if (s == null)
            writeStopBit(-1);
        else
            write8bit(s, 0, s.length());
        return (S) this;
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S write8bit(@org.jetbrains.annotations.NotNull @NotNull BytesStore sdi)
            throws BufferOverflowException {
        long offset = sdi.readPosition();
        long readRemaining = sdi.readLimit() - offset;
        writeStopBit(readRemaining);
        write(sdi, offset, readRemaining);
        return (S) this;
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    S writeByte(byte i8) throws BufferOverflowException;

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S writeUnsignedByte(int i)
            throws BufferOverflowException, IllegalArgumentException {
        return writeByte((byte) Maths.toUInt8(i));
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    S writeShort(short i16) throws BufferOverflowException;

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S writeUnsignedShort(int u16)
            throws BufferOverflowException, IllegalArgumentException {
        return writeShort((short) Maths.toUInt16(u16));
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S writeInt24(int i) throws BufferOverflowException {
        writeUnsignedShort(i);
        return writeUnsignedByte(i >> 16);
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    S writeInt(int i) throws BufferOverflowException;

    @org.jetbrains.annotations.NotNull
    @NotNull
    S writeIntAdv(int i, int advance) throws BufferOverflowException;

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S writeUnsignedInt(long i)
            throws BufferOverflowException, IllegalArgumentException {
        return writeInt((int) Maths.toUInt32(i));
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    S writeLong(long i64) throws BufferOverflowException;

    @org.jetbrains.annotations.NotNull
    @NotNull
    S writeLongAdv(long i64, int advance) throws BufferOverflowException;

    @org.jetbrains.annotations.NotNull
    @NotNull
    S writeFloat(float f) throws BufferOverflowException;

    @org.jetbrains.annotations.NotNull
    @NotNull
    S writeDouble(double d) throws BufferOverflowException;

    /**
     * Write all data or fail.
     */
    @org.jetbrains.annotations.NotNull
    @NotNull
    default S write(@org.jetbrains.annotations.NotNull @NotNull BytesStore bytes)
            throws BufferOverflowException {
        assert bytes != this : "you should not write to yourself !";

        return write(bytes, bytes.readPosition(), Math.min(writeRemaining(), bytes.readRemaining()));
    }

    long realCapacity();

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S writeSome(@org.jetbrains.annotations.NotNull @NotNull Bytes bytes)
            throws BufferOverflowException {
        long length = Math.min(bytes.readRemaining(), writeRemaining());
        if (length + writePosition() >= 1 << 20)
            length = Math.min(bytes.readRemaining(), realCapacity() - writePosition());
        write(bytes, bytes.readPosition(), length);
        if (length == bytes.readRemaining()) {
            bytes.clear();
        } else {
            bytes.readSkip(length);
            if (bytes.writePosition() > bytes.realCapacity() / 2)
                bytes.compact();
        }
        return (S) this;
    }

    /**
     * Write all data or fail.
     */
    @org.jetbrains.annotations.NotNull
    @NotNull
    default S write(@org.jetbrains.annotations.NotNull @NotNull BytesStore bytes, long offset, long length)
            throws BufferOverflowException, BufferUnderflowException, IllegalArgumentException {
        BytesInternal.writeFully(bytes, offset, length, this);
        return (S) this;
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S write(@org.jetbrains.annotations.NotNull @NotNull byte[] bytes) throws BufferOverflowException {
        write(bytes, 0, bytes.length);
        return (S) this;
    }

    /**
     * Write all data or fail.
     */
    @org.jetbrains.annotations.NotNull
    @NotNull
    S write(byte[] bytes, int offset, int length) throws BufferOverflowException, IllegalArgumentException;

    @org.jetbrains.annotations.NotNull
    @NotNull
    S writeSome(ByteBuffer buffer) throws BufferOverflowException;

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S writeBoolean(boolean flag) throws BufferOverflowException {
        return writeByte(flag ? (byte) 'Y' : (byte) 'N');
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    S writeOrderedInt(int i) throws BufferOverflowException;

    @org.jetbrains.annotations.NotNull
    @NotNull
    S writeOrderedLong(long i) throws BufferOverflowException;

    /**
     * This is an expert level method for writing out data to native memory.
     *
     * @param address to write to.
     * @param size    in bytes.
     */
    void nativeWrite(long address, long size)
            throws BufferOverflowException;

    default <E extends Enum<E>> void writeEnum(@org.jetbrains.annotations.NotNull @NotNull E e)
            throws BufferOverflowException {
        write8bit(e.name());
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S appendUtf8(@org.jetbrains.annotations.NotNull @NotNull CharSequence cs)
            throws BufferOverflowException {
        return appendUtf8(cs, 0, cs.length());
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S appendUtf8(int codepoint) throws BufferOverflowException {
        BytesInternal.appendUtf8Char(this, codepoint);
        return (S) this;
    }

    @org.jetbrains.annotations.NotNull
    default S appendUtf8(char[] chars, int offset, int length)
            throws BufferOverflowException, IllegalArgumentException {
        System.out.println("StreamingDataOutput.appendUtf8(char[] chars, offset:" + offset + ", length:" + length + ")");
        int i;
        ascii:
        {
            for (i = 0; i < length; i++) {
                char c = chars[offset + i];
                if (c > 0x007F)
                    break ascii;
                writeByte((byte) c);
            }

            System.out.println("StreamingDataOutput.appendUtf8(-)[ascii path]");
            return (S) this;
        }
        for (; i < length; i++) {
            char c = chars[offset + i];
            BytesInternal.appendUtf8Char(this, c);
        }

        System.out.println("StreamingDataOutput.appendUtf8(-)[utf8 path]");
        return (S) this;
    }


    // length is number of character (not bytes)
    @org.jetbrains.annotations.NotNull
    default S appendUtf8(byte[] chars, int offset, int length, byte coder)
            throws BufferOverflowException, IllegalArgumentException {
        System.out.println("StreamingDataOutput.appendUtf8(byte[] chars, offset:" + offset + ", length:" + length + ", coder:"+coder+")");
        // https://sjohannes.wordpress.com/2009/05/18/utf-8-explained/

        if (coder == 0) { // LATIN1
            for (int i = 0; i < length; i++) {
                byte b = chars[offset + i];
                int b2 = (b & 0xFF);
                // char c = (char)b2;
                // System.out.println("StreamingDataOutput.appendUtf8(): [ASCII] [#"+i+"] b="+b2+" -> " + c + "; " + Integer.toBinaryString(c));
                // int unsignedB = (b & 0xFF);
                //
                // if (unsignedB <= 0x007F) {
                //     writeByte(b);
                // } else {
                //     writeByte((byte) (0xC0 | ((b >> 6) & 0x1F)));
                //     writeByte((byte) (0x80 | b & 0x3F));
                // }

                BytesInternal.appendUtf8Char(this, b2);
            }
        } else { // UTF16
            for (int i = 0; i < 2*length; i+=2) {
                byte b1 = chars[2*offset + i];
                byte b2 = chars[2*offset + i + 1];

                int uBE = ((b2 & 0xFF) << 8) | b1 & 0xFF; // @todo check behavior of "& 0xFF"
                char c = (char)uBE;
                System.out.println("StreamingDataOutput.appendUtf8(): [UTF16] " + c + "; " + Integer.toBinaryString(c));

                // Pound sign: https://unicode-table.com/en/00A3/
                // Euro sign: https://unicode-table.com/en/20AC/
                BytesInternal.appendUtf8Char(this, uBE);
            }
        }

        System.out.println("StreamingDataOutput.appendUtf8(-)");
        return (S) this;
    }

    @org.jetbrains.annotations.NotNull
    default S appendUtf8(byte[] chars, int offset, int length)
            throws BufferOverflowException, IllegalArgumentException {
        System.out.println("StreamingDataOutput.appendUtf8(chars, offset:" + offset + ", length:" + length + ")");
        // https://sjohannes.wordpress.com/2009/05/18/utf-8-explained/

        for (int i = 0; i < length; i++) {
            int b = chars[offset+i] & 0xFF; // unsigned byte
            System.out.println("StreamingDataOutput.appendUtf8(): b=" + Integer.toHexString(b));

            if (b >= 0xF0) {
                System.out.println("StreamingDataOutput.appendUtf8(): [0xF0]");

                int b2 = chars[offset+i+1] & 0xFF; // unsigned byte
                int b3 = chars[offset+i+2] & 0xFF; // unsigned byte
                int b4 = chars[offset+i+3] & 0xFF; // unsigned byte
                this.writeByte((byte) b4);
                this.writeByte((byte) b3);
                this.writeByte((byte) b2);
                this.writeByte((byte) b);

                i+= 3;
            } else if (b >= 0xE0) {
                System.out.println("StreamingDataOutput.appendUtf8(): [0xE0]");

                int b2 = chars[offset+i+1] & 0xFF; // unsigned byte
                int b3 = chars[offset+i+2] & 0xFF; // unsigned byte
                this.writeByte((byte) b3);
                this.writeByte((byte) b2);
                this.writeByte((byte) b);

                i+= 2;
            } else if (b >= 0xC0) {
                System.out.println("StreamingDataOutput.appendUtf8(): [0xC0]");

                int b2 = chars[offset+i + 1] & 0xFF; // unsigned byte
                this.writeByte((byte) b2);
                this.writeByte((byte) b);

                i+= 1;
            } else {
                System.out.println("StreamingDataOutput.appendUtf8(): [ascii] " + Integer.toBinaryString((byte) b));
                this.writeByte((byte) b);
            }
        }

        System.out.println("StreamingDataOutput.appendUtf8(-)");
        return (S) this;
    }

    @org.jetbrains.annotations.NotNull
    @NotNull
    default S appendUtf8(@org.jetbrains.annotations.NotNull @NotNull CharSequence cs, int offset, int length)
            throws BufferOverflowException, IllegalArgumentException {
        BytesInternal.appendUtf8(this, cs, offset, length);
        return (S) this;
    }

    default void copyFrom(@org.jetbrains.annotations.NotNull @NotNull InputStream input) throws IOException, BufferOverflowException, IllegalArgumentException {
        BytesInternal.copy(input, this);
    }

    default void writePositionRemaining(long position, long length) {
        writeLimit(position + length);
        writePosition(position);
    }

    default void writeHistogram(@org.jetbrains.annotations.NotNull @NotNull Histogram histogram) {
        BytesInternal.writeHistogram(this, histogram);
    }

    default void writeBigDecimal(@org.jetbrains.annotations.NotNull @NotNull BigDecimal bd) {
        writeBigInteger(bd.unscaledValue());
        writeStopBit(bd.scale());
    }

    default void writeBigInteger(@org.jetbrains.annotations.NotNull @NotNull BigInteger bi) {
        byte[] bytes = bi.toByteArray();
        writeStopBit(bytes.length);
        write(bytes);
    }
}
