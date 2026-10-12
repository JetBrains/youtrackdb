/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.structure.io.binary.types;

import org.apache.tinkerpop.gremlin.process.traversal.strategy.TraversalStrategyProxy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.StandardOrderSemanticsStrategy;
import org.apache.tinkerpop.gremlin.structure.io.Buffer;
import org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryReader;
import org.apache.tinkerpop.gremlin.structure.io.binary.GraphBinaryWriter;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TraversalStrategySerializerTest {

    @Test
    public void shouldRoundTripStandardOrderSemanticsStrategy() throws Exception {
        final GraphBinaryWriter writer = new GraphBinaryWriter();
        final GraphBinaryReader reader = new GraphBinaryReader();
        final TestBuffer buffer = new TestBuffer(1024);

        writer.write(StandardOrderSemanticsStrategy.instance(), buffer);
        buffer.readerIndex(0);
        final TraversalStrategyProxy<?> result = (TraversalStrategyProxy<?>) reader.read(buffer);

        assertEquals(StandardOrderSemanticsStrategy.class, result.getStrategyClass());
        assertTrue(result.getConfiguration().isEmpty());
    }

    private static final class TestBuffer implements Buffer {
        private final ByteBuffer buffer;
        private int readerIndex;
        private int writerIndex;
        private int markedWriterIndex;

        private TestBuffer(final int capacity) {
            this.buffer = ByteBuffer.allocate(capacity);
        }

        @Override
        public int readableBytes() {
            return writerIndex - readerIndex;
        }

        @Override
        public int readerIndex() {
            return readerIndex;
        }

        @Override
        public Buffer readerIndex(final int readerIndex) {
            this.readerIndex = readerIndex;
            return this;
        }

        @Override
        public int writerIndex() {
            return writerIndex;
        }

        @Override
        public Buffer writerIndex(final int writerIndex) {
            this.writerIndex = writerIndex;
            return this;
        }

        @Override
        public Buffer markWriterIndex() {
            markedWriterIndex = writerIndex;
            return this;
        }

        @Override
        public Buffer resetWriterIndex() {
            writerIndex = markedWriterIndex;
            return this;
        }

        @Override
        public int capacity() {
            return buffer.capacity();
        }

        @Override
        public boolean isDirect() {
            return false;
        }

        @Override
        public boolean readBoolean() {
            return readByte() != 0;
        }

        @Override
        public byte readByte() {
            return buffer.get(readerIndex++);
        }

        @Override
        public short readShort() {
            final short value = buffer.getShort(readerIndex);
            readerIndex += Short.BYTES;
            return value;
        }

        @Override
        public int readInt() {
            final int value = buffer.getInt(readerIndex);
            readerIndex += Integer.BYTES;
            return value;
        }

        @Override
        public long readLong() {
            final long value = buffer.getLong(readerIndex);
            readerIndex += Long.BYTES;
            return value;
        }

        @Override
        public float readFloat() {
            final float value = buffer.getFloat(readerIndex);
            readerIndex += Float.BYTES;
            return value;
        }

        @Override
        public double readDouble() {
            final double value = buffer.getDouble(readerIndex);
            readerIndex += Double.BYTES;
            return value;
        }

        @Override
        public Buffer readBytes(final byte[] destination) {
            return readBytes(destination, 0, destination.length);
        }

        @Override
        public Buffer readBytes(final byte[] destination, final int dstIndex, final int length) {
            final ByteBuffer source = buffer.duplicate();
            source.position(readerIndex);
            source.get(destination, dstIndex, length);
            readerIndex += length;
            return this;
        }

        @Override
        public Buffer readBytes(final ByteBuffer destination) {
            final int length = destination.remaining();
            final ByteBuffer source = buffer.duplicate();
            source.position(readerIndex);
            source.limit(readerIndex + length);
            destination.put(source);
            readerIndex += length;
            return this;
        }

        @Override
        public Buffer readBytes(final OutputStream out, final int length) throws IOException {
            final byte[] bytes = new byte[length];
            readBytes(bytes);
            out.write(bytes);
            return this;
        }

        @Override
        public Buffer writeBoolean(final boolean value) {
            return writeByte(value ? 1 : 0);
        }

        @Override
        public Buffer writeByte(final int value) {
            buffer.put(writerIndex++, (byte) value);
            return this;
        }

        @Override
        public Buffer writeShort(final int value) {
            buffer.putShort(writerIndex, (short) value);
            writerIndex += Short.BYTES;
            return this;
        }

        @Override
        public Buffer writeInt(final int value) {
            buffer.putInt(writerIndex, value);
            writerIndex += Integer.BYTES;
            return this;
        }

        @Override
        public Buffer writeLong(final long value) {
            buffer.putLong(writerIndex, value);
            writerIndex += Long.BYTES;
            return this;
        }

        @Override
        public Buffer writeFloat(final float value) {
            buffer.putFloat(writerIndex, value);
            writerIndex += Float.BYTES;
            return this;
        }

        @Override
        public Buffer writeDouble(final double value) {
            buffer.putDouble(writerIndex, value);
            writerIndex += Double.BYTES;
            return this;
        }

        @Override
        public Buffer writeBytes(final byte[] source) {
            return writeBytes(source, 0, source.length);
        }

        @Override
        public Buffer writeBytes(final ByteBuffer source) {
            final int length = source.remaining();
            final ByteBuffer destination = buffer.duplicate();
            destination.position(writerIndex);
            destination.put(source);
            writerIndex += length;
            return this;
        }

        @Override
        public Buffer writeBytes(final byte[] source, final int srcIndex, final int length) {
            final ByteBuffer destination = buffer.duplicate();
            destination.position(writerIndex);
            destination.put(source, srcIndex, length);
            writerIndex += length;
            return this;
        }

        @Override
        public boolean release() {
            return false;
        }

        @Override
        public Buffer retain() {
            return this;
        }

        @Override
        public int referenceCount() {
            return 1;
        }

        @Override
        public int nioBufferCount() {
            return 1;
        }

        @Override
        public ByteBuffer[] nioBuffers() {
            return new ByteBuffer[] {nioBuffer()};
        }

        @Override
        public ByteBuffer[] nioBuffers(final int index, final int length) {
            return new ByteBuffer[] {nioBuffer(index, length)};
        }

        @Override
        public ByteBuffer nioBuffer() {
            return nioBuffer(readerIndex, readableBytes());
        }

        @Override
        public ByteBuffer nioBuffer(final int index, final int length) {
            return buffer.asReadOnlyBuffer().position(index).limit(index + length).slice();
        }

        @Override
        public Buffer getBytes(final int index, final byte[] destination) {
            final ByteBuffer source = buffer.duplicate();
            source.position(index);
            source.get(destination);
            return this;
        }
    }
}
