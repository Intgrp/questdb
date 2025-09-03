/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.std;

import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.bytes.Bytes;
import io.questdb.std.ex.BytecodeException;
import io.questdb.std.str.Utf8Sequence;
import io.questdb.std.str.Utf8Sink;
import org.jetbrains.annotations.Nullable;

import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class BytecodeAssemblerStack {

    private static final int ACC_PRIVATE = 0x02;
    private static final int ACC_PUBLIC = 0x01;
    private static final Log LOG = LogFactory.getLog(BytecodeAssemblerStack.class);
    private static final int O_POOL_COUNT = 8;
    private static final int aload = 0x19;
    private static final int aload_0 = 0x2a;
    private static final int aload_1 = 0x2b;
    private static final int aload_2 = 0x2c;
    private static final int aload_3 = 0x2d;
    private static final int bipush = 0x10;
    private static final int iconst_0 = 3;
    private static final int iconst_m1 = 2;
    private static final int iinc = 0x84;
    private static final int iload = 0x15;
    private static final int iload_0 = 0x1a;
    private static final int iload_1 = 0x1b;
    private static final int iload_2 = 0x1c;
    private static final int iload_3 = 0x1d;
    private static final int invokespecial = 183;
    private static final int istore = 0x36;
    private static final int istore_0 = 0x3b;
    private static final int istore_1 = 0x3c;
    private static final int istore_2 = 0x3d;
    private static final int istore_3 = 0x3e;
    private static final int lload = 0x16;
    private static final int lload_0 = 0x1e;
    private static final int lload_1 = 0x1f;
    private static final int lload_2 = 0x20;
    private static final int lload_3 = 0x21;
    private static final int lstore = 0x37;
    private static final int lstore_0 = 0x3f;
    private static final int lstore_1 = 0x40;
    private static final int lstore_2 = 0x41;
    private static final int lstore_3 = 0x42;
    private static final int sipush = 0x11;
    private static final int new_ = 0xbb;
    private final ObjIntHashMap<Class<?>> classCache = new ObjIntHashMap<>();
    private final Utf8Appender utf8Appender = new Utf8Appender();
    private final CharSequenceIntHashMap utf8Cache = new CharSequenceIntHashMap();
    private ByteBuffer buf;
    private int codeAttributeIndex;
    private int codeAttributeStart;
    private int codeStart;
    private int defaultConstructorDescIndex;
    private int defaultConstructorMethodIndex;
    private int defaultConstructorNameIndex;
    private Class<?> host;
    private int objectClassIndex;
    private int poolCount;
    private int stackMapTableCut;
    // Keep tracks of operand stack and local variable slot types during the program flow
    // we store the types with this layout:
    // ----------------------
    // | 8 bits | 24 bits   |
    // ----------------------
    // | Tag    | Opt value |
    // ----------------------
    // Tag: defines the verification type (e.g., 0 for Top, 1 for Integer, 4 for Long, etc.)
    // Opt value: used for classes, to store the index of the class in the constant pool
    private IntStack stack;
    private IntList locals;
    // Last used local variable index.
    private int localIndex;
    // Stored frames (set at each setJmp), with the following format:
    // pos | local len | locals... | stack len | stack...
    private IntList frames;
    private int frameCount;

    public BytecodeAssemblerStack() {
        this.buf = ByteBuffer.allocate(1024 * 1024).order(ByteOrder.BIG_ENDIAN);
        this.poolCount = 1;
        this.localIndex = 0;
        this.stack = new IntStack();
        this.locals = new IntList();
        this.frames = new IntList();
        this.frameCount = 0;
    }

    public void aload(int value) {
        stack.push(locals.get(value));
        optimisedIO(aload_0, aload_1, aload_2, aload_3, aload, value);
    }

    /**
     * Register a parameter in the local variable table.
     * @param type the type (of the format tag | opt) to register.
     */
    public void registerParameter(int type) {
        locals.set(localIndex++, type);
        if ((type & 0xFF000000 >> 24) == 0x04) {
            locals.set(localIndex++, 0x00);
        }
    }

    public void append_frame(int itemCount, int offset) {
        putByte(0xfc + itemCount - 1);
        putShort(offset);
    }

    public void defineClass(int thisClassIndex) {
        defineClass(thisClassIndex, objectClassIndex);
    }

    public void defineClass(int thisClassIndex, int superclassIndex) {
        // access flags
        putShort(ACC_PUBLIC);
        // this class index
        putShort(thisClassIndex);
        // super class
        putShort(superclassIndex);
    }

    public void defineDefaultConstructor() {
        defineDefaultConstructor(defaultConstructorMethodIndex);
    }

    public void defineDefaultConstructor(int superIndex) {
        // constructor method entry
        startMethod(defaultConstructorNameIndex, defaultConstructorDescIndex, 1, 1);
        // code
        aload(0);
        putByte(invokespecial);
        putShort(superIndex);
        return_();
        endMethodCode();
        // exceptions
        putShort(0);
        // attribute count
        putShort(0);
        endMethod();
    }

    public void defineField(int nameIndex, int typeIndex) {
        putShort(ACC_PRIVATE);
        putShort(nameIndex);
        putShort(typeIndex);
        // attribute count
        putShort(0);
    }

    public void dump(String path) {
        try (FileOutputStream fos = new FileOutputStream(path)) {
            int p = buf.position();
            int l = buf.limit();
            buf.flip();
            fos.getChannel().write(buf);
            buf.limit(l);
            buf.position(p);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void dup() {
        putByte(0x59);
    }

    @SuppressWarnings("unused")
    public void dup2() {
        putByte(0x5c);
    }

    public void endMethod() {
        putInt(codeAttributeStart - 4, position() - codeAttributeStart);
    }

    public void endMethodCode() {
        int len = position() - codeStart;
        if (len > 64 * 1024) {
            LOG.error().$("Too much input to generate ").$(host.getName()).$(". Bytecode is too long").$();
            throw BytecodeException.INSTANCE;
        }
        putInt(codeStart - 4, position() - codeStart);
    }

    public void endStackMapTables() {
        putInt(stackMapTableCut, position() - stackMapTableCut - 4);
    }

    public void fieldCount(int count) {
        putShort(count);
    }

    public void finishPool() {
        putShort(O_POOL_COUNT, poolCount);
    }

    public void full_frame(int offset) {
        putByte(0xff);
        putShort(offset);
    }

    public int getCodeStart() {
        return codeStart;
    }

    public int getPoolCount() {
        return poolCount;
    }

    public void getfield(int index) {
        putByte(0xb4);
        putShort(index);
    }

    public int goto_() {
        return genericGoto(0xa7);
    }

    public void i2b() {
        putByte(0x91);
    }

    public void i2l() {
        stack.pop();
        stack.push(0x04 << 24);
        putByte(0x85);
    }


    public void l2i() {
        stack.pop();
        stack.push(0x01 << 24);
        putByte(0x88);
    }

    public void i2s() {
        putByte(0x93);
    }

    public void iadd() {
        stack.pop();
        putByte(0x60);
    }

    /**
     * Boolean AND int.
     * Both value1 and value2 must be of type int. They are popped from the operand stack. An int result is calculated by taking the bitwise AND (conjunction) of value1 and value2. The result is pushed onto the operand stack.
     */
    public void iand() {
        stack.pop();
        putByte(0x7e);
    }

    /**
     * The immediate byte is sign-extended to an int value. That value is pushed onto the operand stack.
     * @param v the immediate byte.
     */
    public void bipush(byte v) {
        stack.push(VERIFICATION_INTEGER << 24);
        putByte(bipush);
        putByte(v);
    }

    /**
     * Push any int value between 2⁻¹⁵ and 2¹⁵-1 onto the operand stack.
     * @param v the value to push.
     */
    public void iconst(int v) {
        stack.push(VERIFICATION_INTEGER << 24);
        if (v == -1) {
            putByte(iconst_m1);
        } else if (v > -1 && v < 6) {
            putByte(iconst_0 + v);
        } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            putByte(bipush);
            putByte((byte) v);
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            putByte(sipush);
            putShort((short) v);
        } else {
            throw new IllegalArgumentException("Illegal value " + v + " for iconst");
        }
    }

    public int if_icmpge() {
        stack.pop();
        return genericGoto(0xa2);
    }

    public int if_icmple() {
        stack.pop();
        return genericGoto(0xa4);
    }

    public int if_icmpne() {
        stack.pop();
        return genericGoto(0xa0);
    }

    @SuppressWarnings("unused")
    public int ifle() {
        return genericGoto(0x9e);
    }

    public int iflt() {
        return genericGoto(0x9b);
    }

    /**
     * Branch if int from the operand stack (popped) is greater than zero.
     * @return the branch position in the generated bytecode, it may be used with setJmp to update
     * the branch target.
     */
    public int ifgt() {
        return genericGoto(0x9d);
    }

    /**
     * Branch if int from the operand stack (popped) is greater than or equal to zero.
     * @return the branch position in the generated bytecode, it may be used with setJmp to update
     * the branch target.
     */
    public int ifge() {
        return genericGoto(0x9c);
    }

    public int ifne() {
        return genericGoto(0x9a);
    }

    public int ifeq() {
        return genericGoto(0x99);
    }

    public void iinc(int index, int inc) {
        putByte(iinc);
        putByte(index);
        putByte(inc);
    }

    public void iload(int value) {
        stack.push(locals.get(value));
        optimisedIO(iload_0, iload_1, iload_2, iload_3, iload, value);
    }

    public void ineg() {
        putByte(0x74);
    }

    /**
     * Negate long.
     * The value must be of type long. It is popped from the operand stack. The long result is the arithmetic negation of value, -value. The result is pushed onto the operand stack.
     */
    public void lneg() {
        putByte(0x75);
    }

    public void init(Class<?> host) {
        this.host = host;
        this.buf.clear();
        this.poolCount = 1;
        this.localIndex = 0;
        this.utf8Cache.clear();
        this.classCache.clear();
    }

    public void invokeVirtual(int index, int params, int returnType) {
        for (int i = 0; i < params; i++) {
            stack.pop();
        }
        stack.pop(); // pop object
        putByte(182);
        putShort(index);
        if (returnType != 0) {
            stack.push(returnType);
        }
    }


    public void invokeStatic(int index, int params) {
        for (int i = 0; i < params; i++) {
            stack.pop();
        }
        putByte(184);
        putShort(index);
    }

    public void istore(int value) {
        locals.set(value, stack.pop());
        if (value >= localIndex) {
            localIndex = value + 1;
        }
        optimisedIO(istore_0, istore_1, istore_2, istore_3, istore, value);
    }

    public void lcmp() {
        stack.pop();
        stack.pop();
        stack.push(0x01 << 24);
        putByte(0x94);
    }

    /**
     * Boolean OR int.
     * Both value1 and value2 must be of type int. They are popped from the operand stack. An int result is calculated by taking the bitwise inclusive OR of value1 and value2. The result is pushed onto the operand stack.
     */
    public void ior() {
        stack.pop();
        putByte(0x80);
    }

    /**
     * Push the long constant 0 onto the operand stack.
     */
    public void lconst_0() {
        stack.push(VERIFICATION_LONG << 24);
        putByte(0x09);
    }

    /**
     * Push the long constant 1 onto the operand stack.
     */
    public void lconst_1() {
        stack.push(VERIFICATION_LONG << 24);
        putByte(0xa);
    }

    private static int VERIFICATION_TOP = 0x00;
    private static int VERIFICATION_INTEGER = 0x01;
    private static int VERIFICATION_LONG = 0x04;
    private static int VERIFICATION_OBJECT = 0x07;

    /**
     * Boolean XOR long.
     * Both value1 and value2 must be of type long. They are popped from the operand stack. A long result is calculated by taking the bitwise exclusive OR of value1 and value2. The result is pushed onto the operand stack.
     */
    public void lxor() {
        stack.pop();
        putByte(0x83);
    }

    /**
     * Add long.
     * Both value1 and value2 must be of type long. The values are popped from the operand stack. The long result is value1 + value2. The result is pushed onto the operand stack.
     */
    public void ladd() {
        stack.pop();
        putByte(0x61);
    }

    /**
     * Boolean AND long.
     * Both value1 and value2 must be of type long. They are popped from the operand stack. A long result is calculated by taking the bitwise AND of value1 and value2. The result is pushed onto the operand stack.
     */
    public void land() {
        stack.pop();
        putByte(0x7f);
    }

    public void ldc(int index) {
        if (index < 256) {
            stack.push(0x01 << 24);
            putByte(0x12);
            putByte(index);
        } else {
            ldc_w(index);
        }
    }

    public void ldc2_w(int index) {
        stack.push(0x04 << 24);
        putByte(0x14);
        putShort(index);
    }

    public void ldc_w(int index) {
        stack.push(0x01 << 24);
        putByte(0x13);
        putShort(index);
    }

    public void lload(int value) {
        stack.push(locals.get(value));
        optimisedIO(lload_0, lload_1, lload_2, lload_3, lload, value);
    }

    public void imul() {
        stack.pop();
        putByte(0x68);
    }

    public <T> Class<T> loadClass() {
        Class<T> x = loadClass(host);
        assert x != null;
        return x;
    }

    public void lreturn() {
        putByte(0xad);
    }

    public void lstore(int value) {
        locals.set(value, stack.pop());
        if (value + 1 >= localIndex) {
            localIndex = value + 2;
        }
        optimisedIO(lstore_0, lstore_1, lstore_2, lstore_3, lstore, value);
    }

    public void localPop(int index) {
        locals.set(index, 0x00);
    }

    /**
     * Store long into local variable.
     * @return the local variable index
     */
    public int lstore() {
        locals.set(localIndex, stack.pop());
        optimisedIO(lstore_0, lstore_1, lstore_2, lstore_3, lstore, localIndex);
        int index = localIndex;
        localIndex += 2;
        return index;
    }


    /**
     * Store int into local variable.
     * @return the local variable index
     */
    public int istore() {
        locals.set(localIndex, stack.pop());
        optimisedIO(istore_0, istore_1, istore_2, istore_3, istore, localIndex);
        int index = localIndex;
        localIndex += 1;
        return index;
    }

    public void methodCount(int count) {
        putShort(count);
    }

    public <T> T newInstance() {
        Class<T> x = loadClass(host);
        assert x != null;
        try {
            return x.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            LOG.critical().$("could not create an instance of ").$(host.getName()).$(", cause: ").$(e).$();
            throw BytecodeException.INSTANCE;
        }
    }

    public void new_(int classIndex) {
        putByte(new_);
        putShort(classIndex);
    }

    public int poolClass(int classIndex) {
        putByte(0x07);
        putShort(classIndex);
        return poolCount++;
    }

    public int poolClass(Class<?> clazz) {
        int index = classCache.keyIndex(clazz);
        if (index > -1) {
            String name = clazz.getName();
            putByte(0x01);
            int n;
            putShort(n = name.length());
            for (int i = 0; i < n; i++) {
                char c = name.charAt(i);
                if (c == '.') {
                    putByte('/');
                } else {
                    putByte(c);
                }
            }
            int result = poolClass(this.poolCount++);
            classCache.putAt(index, clazz, result);
            return result;
        }
        return classCache.valueAt(index);
    }

    public int poolDoubleConst(double value) {
        putByte(0x06);
        putDouble(value);
        int index = poolCount;
        poolCount += 2;
        return index;
    }

    public int poolField(int classIndex, int nameAndTypeIndex) {
        return poolRef(0x09, classIndex, nameAndTypeIndex);
    }

    public int poolIntConst(int value) {
        putByte(0x03);
        putInt(value);
        return poolCount++;
    }

    public int poolInterfaceMethod(Class<?> clazz, String name, String sig) {
        return poolInterfaceMethod(poolClass(clazz), poolNameAndType(poolUtf8(name), poolUtf8(sig)));
    }

    public int poolInterfaceMethod(int classIndex, String name, String sig) {
        return poolInterfaceMethod(classIndex, poolNameAndType(poolUtf8(name), poolUtf8(sig)));
    }

    public int poolInterfaceMethod(int classIndex, int nameAndTypeIndex) {
        return poolRef(0x0B, classIndex, nameAndTypeIndex);
    }

    public int poolLongConst(long value) {
        putByte(0x05);
        putLong(value);
        int index = poolCount;
        poolCount += 2;
        return index;
    }

    public int poolMethod(int classIndex, int nameAndTypeIndex) {
        return poolRef(0x0A, classIndex, nameAndTypeIndex);
    }

    public int poolMethod(int classIndex, CharSequence methodName, CharSequence signature) {
        return poolMethod(classIndex, poolNameAndType(poolUtf8(methodName), poolUtf8(signature)));
    }

    public int poolMethod(Class<?> clazz, CharSequence methodName, CharSequence signature) {
        return poolMethod(poolClass(clazz), poolNameAndType(poolUtf8(methodName), poolUtf8(signature)));
    }

    public int poolNameAndType(int nameIndex, int typeIndex) {
        return poolRef(0x0C, nameIndex, typeIndex);
    }

    public int poolStringConst(int utf8Index) {
        putByte(0x8);
        putShort(utf8Index);
        return poolCount++;
    }

    public Utf8Appender poolUtf8() {
        putByte(0x01);
        utf8Appender.lenpos = position();
        utf8Appender.utf8len = 0;
        putShort(0);
        return utf8Appender;
    }

    public int poolUtf8(CharSequence cs) {
        int index = utf8Cache.keyIndex(cs);
        if (index > -1) {
            putByte(0x01);
            int n = cs.length();
            int pos = buf.position();
            putShort(0);
            for (int i = 0; i < n; ) {
                final char c = cs.charAt(i++);
                if (c < 128) {
                    putByte(c);
                } else {
                    if (c < 2048) {
                        putByte((char) (192 | c >> 6));
                        putByte((char) (128 | c & 63));
                    } else if (Character.isSurrogate(c)) {
                        i = encodeSurrogate(c, cs, i, n);
                    } else {
                        putByte((char) (224 | c >> 12));
                        putByte((char) (128 | c >> 6 & 63));
                        putByte((char) (128 | c & 63));
                    }
                }
            }
            buf.putShort(pos, (short) (buf.position() - pos - 2));
            utf8Cache.putAt(index, cs, poolCount);
            return this.poolCount++;
        }

        return utf8Cache.valueAt(index);
    }

    public void pop() {
        putByte(0x57);
    }

    public int position() {
        return buf.position();
    }

    public void putByte(int b) {
        if (buf.remaining() == 0) {
            resize();
        }
        buf.put((byte) b);
    }

    public void putDouble(double value) {
        if (buf.remaining() < 4) {
            resize();
        }
        buf.putDouble(value);
    }

    public void putITEM_Integer() {
        putByte(0x01);
    }

    public void putITEM_Long() {
        putByte(0x04);
    }

    public void putITEM_Object(int classIndex) {
        putByte(0x07);
        putShort(classIndex);
    }

    public void putITEM_Top() {
        putByte(0);
    }

    public void putLong(long value) {
        if (buf.remaining() < 4) {
            resize();
        }
        buf.putLong(value);
    }

    public void putShort(int v) {
        putShort((short) v);
    }

    public void putShort(int pos, int v) {
        buf.putShort(pos, (short) v);
    }

    public void putfield(int index) {
        putByte(181);
        putShort(index);
    }

    public void return_() {
        putByte(0xb1);
    }

    public void same_frame(int offset) {
        if (offset < 64) {
            putByte(offset);
        } else {
            putByte(251);
            putShort(offset);
        }
    }

    public void setJmp(int branch, int target) {
        putShort(branch, target - branch + 1);
        frames.add(target);
        int localSizeIdx = frames.size();
        frames.add(0);
        for (int i = 1, n = locals.size(); i < n; i++) {
            int type = locals.get(i);
            if (type == 0) {
                break;
            }
            frames.add(type);
            if (type == (0x04 << 24)) {
                i++;
            }
        }
        frames.set(localSizeIdx, frames.size() - localSizeIdx - 1);
        frames.add(stack.size());
        for (int i = 0, n = stack.size(); i < n; i++) {
            frames.add(stack.peek(i));
        }
        frameCount++;
    }

    public void interfaceCount(int count) {
        putShort(count);
    }

    public void doStackMapTables(int thisClassIndex, int stackMapTableIndex) {
        if (frames.size() == 0) {
            putShort(0);
            return;
        }

        putShort(1);
        startStackMapTables(stackMapTableIndex, frameCount);
        int start = codeStart;
        int ptr = 0;
        int n = frames.size();
        while (ptr < n) {
            int position = frames.get(ptr++);
            full_frame(position - start);
            int locals = frames.get(ptr++);
            putShort(locals + 1);

            // Always this at the top
            putByte(0x07);
            putShort(thisClassIndex);

            for (int i = 0; i < locals; i++) {
                int type = frames.get(ptr++);
                putVerificationType(type);
            }

            int stackLen = frames.get(ptr++);
            putShort(stackLen);
            for (int i = 0; i < stackLen; i++) {
                int type = frames.get(ptr++);
                putVerificationType(type);
            }
            start = position + 1;
        }
        endStackMapTables();
    }

    private void putVerificationType(int type) {
        switch (type >> 24) {
            case 0x00: // Top
                putByte(0x00);
                break;
            case 0x01: // Integer
                putByte(0x01);
                break;
            case 0x04: // Long
                putByte(0x04);
                break;
            case 0x07: // Class
                putByte(0x07);
                putShort(type & 0xFFFF);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported stack type: " + type);
        }
    }

    public void setupPool() {
        // magic
        putInt(0xCAFEBABE);
        // version
        putInt(0x33);
        // skip pool count, write later when we know the value
        putShort(0);

        // add standard stuff
        objectClassIndex = poolClass(Object.class);
        defaultConstructorMethodIndex = poolMethod(objectClassIndex, poolNameAndType(
                        defaultConstructorNameIndex = poolUtf8("<init>"),
                        defaultConstructorDescIndex = poolUtf8("()V")
                )
        );
        codeAttributeIndex = poolUtf8("Code");
    }

    public void startMethod(int nameIndex, int descriptorIndex, int maxStack, int maxLocal) {
        // access flags
        putShort(ACC_PUBLIC);
        // name index
        putShort(nameIndex);
        // descriptor index
        putShort(descriptorIndex);
        // attribute count
        putShort(1);

        // code
        putShort(codeAttributeIndex);

        // attribute len
        putInt(0);
        // come back to this later
        this.codeAttributeStart = position();
        // max stack
        putShort(maxStack);
        // max locals
        putShort(maxLocal);

        // code len
        putInt(0);
        this.codeStart = position();

        localIndex = 1;
        locals.clear();
        for (int i = 0; i < maxLocal; i++) {
            locals.add(0);
        }
        stack.clear();
    }

    public void startStackMapTables(int attributeNameIndex, int frameCount) {
        putShort(attributeNameIndex);
        this.stackMapTableCut = position();
        // length - we will come back here
        putInt(0);
        // number of entries
        putShort(frameCount);
    }

    private int encodeSurrogate(char c, CharSequence in, int pos, int hi) {
        int dword;
        if (Character.isHighSurrogate(c)) {
            if (hi - pos < 1) {
                putByte('?');
                return pos;
            } else {
                char c2 = in.charAt(pos++);
                if (Character.isLowSurrogate(c2)) {
                    dword = Character.toCodePoint(c, c2);
                } else {
                    putByte('?');
                    return pos;
                }
            }
        } else if (Character.isLowSurrogate(c)) {
            putByte('?');
            return pos;
        } else {
            dword = c;
        }
        putByte((char) (240 | dword >> 18));
        putByte((char) (128 | dword >> 12 & 63));
        putByte((char) (128 | dword >> 6 & 63));
        putByte((char) (128 | dword & 63));

        return pos;
    }

    private int genericGoto(int cmd) {
        stack.pop();
        putByte(cmd);
        int pos = position();
        putShort(0);
        return pos;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private <T> Class<T> loadClass(Class<?> host) {
        byte[] b = new byte[position()];
        System.arraycopy(buf.array(), 0, b, 0, b.length);
        return (Class<T>) Unsafe.defineAnonymousClass(host, b);
    }

    public void nop() {
        putByte(0x00);
    }

    private void optimisedIO(int code0, int code1, int code2, int code3, int code, int value) {
        switch (value) {
            case 0:
                putByte(code0);
                break;
            case 1:
                putByte(code1);
                break;
            case 2:
                putByte(code2);
                break;
            case 3:
                putByte(code3);
                break;
            default:
                putByte(code);
                putByte(value);
                break;
        }
    }

    private int poolRef(int op, int name, int type) {
        putByte(op);
        putShort(name);
        putShort(type);
        return poolCount++;
    }

    private void putInt(int pos, int v) {
        buf.putInt(pos, v);
    }

    private void putInt(int v) {
        if (buf.remaining() < 4) {
            resize();
        }
        buf.putInt(v);
    }

    private void putShort(short v) {
        if (buf.remaining() < 2) {
            resize();
        }
        buf.putShort(v);
    }

    private void resize() {
        ByteBuffer b = ByteBuffer.allocate(buf.capacity() * 2).order(ByteOrder.BIG_ENDIAN);
        System.arraycopy(buf.array(), 0, b.array(), 0, buf.capacity());
        b.position(buf.position());
        buf = b;
    }

    public class Utf8Appender implements Utf8Sink {
        private int lenpos;
        private int utf8len = 0;

        public int $() {
            putShort(lenpos, utf8len);
            return poolCount++;
        }

        @Override
        public Utf8Sink put(@Nullable Utf8Sequence us) {
            if (us != null) {
                int size = us.size();
                for (int i = 0; i < size; i++) {
                    BytecodeAssemblerStack.this.putByte(us.byteAt(i));
                }
                utf8len += size;
            }
            return this;
        }

        @Override
        public Utf8Appender put(byte b) {
            BytecodeAssemblerStack.this.putByte(b);
            utf8len++;
            return this;
        }

        @Override
        public Utf8Appender put(int value) {
            Utf8Sink.super.put(value);
            return this;
        }

        @Override
        public Utf8Appender put(@Nullable CharSequence cs) {
            Utf8Sink.super.put(cs);
            return this;
        }

        @Override
        public Utf8Appender putAscii(char c) {
            Utf8Sink.super.putAscii(c);
            return this;
        }

        @Override
        public Utf8Appender putAscii(@Nullable CharSequence cs) {
            if (cs != null) {
                int len = cs.length();
                for (int i = 0; i < len; i++) {
                    BytecodeAssemblerStack.this.putByte(cs.charAt(i));
                }
                utf8len += len;
            }
            return this;
        }

        @Override
        public Utf8Appender putNonAscii(long lo, long hi) {
            Bytes.checkedLoHiSize(lo, hi, BytecodeAssemblerStack.this.position());
            for (long p = lo; p < hi; p++) {
                BytecodeAssemblerStack.this.putByte(Unsafe.getUnsafe().getByte(p));
            }
            return this;
        }
    }
}
