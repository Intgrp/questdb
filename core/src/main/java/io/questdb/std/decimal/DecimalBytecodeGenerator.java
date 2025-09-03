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

package io.questdb.std.decimal;

import io.questdb.std.BytecodeAssemblerStack;

public class DecimalBytecodeGenerator {
    public static int CONSTANT_DECIMAL256_CLASS_OFFSET = 1;
    public static int CONSTANT_DECIMAL256_GET_HH_OFFSET = 5;
    public static int CONSTANT_DECIMAL256_GET_HL_OFFSET = 8;
    public static int CONSTANT_DECIMAL256_GET_LH_OFFSET = 11;
    public static int CONSTANT_DECIMAL256_GET_LL_OFFSET = 14;
    public static int CONSTANT_DECIMAL256_OFFSET = 0;
    public static int CONSTANT_DECIMAL256_OF_OFFSET = 18;
    public static int CONSTANT_LONG_MINUS_ONE_OFFSET = 21;
    public static int CONSTANT_LONG_SIGN_BITMASK_OFFSET = 19;
    public static int CONSTANT_INT_MIN_VALUE_OFFSET = 23;
    public static int CONSTANT_LONG_MIN_VALUE_OFFSET = 24;

    /**
     * Generate bytecode to convert any decimal to a positive one.
     * This method loads the decimal value from local variables.
     *
     * @param asm the BytecodeAssemblerStack that will be used to generate the bytecode.
     */
    public static void generateAbs(BytecodeAssemblerStack asm, DecimalType type, int decimalIndex, int poolIndex) {
        switch (type) {
            case DECIMAL8:
            case DECIMAL16:
            case DECIMAL32:
                generateAbsDecimal8_32(asm, decimalIndex);
                break;
            case DECIMAL64:
                generateAbsDecimal64(asm, decimalIndex);
                break;
            case DECIMAL128:
                generateAbsDecimal128(asm, decimalIndex, poolIndex);
                break;
            default:
                generateAbsDecimal256(asm, decimalIndex, poolIndex);
                break;
        }
    }

    /**
     * Load local variables from a Decimal256, using the layout of the given type.
     *
     * @param asm               the BytecodeAssemblerStack that will be used to generate the bytecode.
     * @param decimalLocalIndex index of the local variable referencing the Decimal256.
     * @param type              the layout type to use to store the Decimal256.
     * @param poolIndex         index of the pool entry returned by poolUtils().
     * @return the local index for the stored variable
     */
    public static int loadDecimal256(BytecodeAssemblerStack asm, int decimalLocalIndex, DecimalType type, int poolIndex) {
        int index;
        switch (type) {
            case DECIMAL8:
                asm.aload(decimalLocalIndex);
                asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_GET_LL_OFFSET, 0, 0x04 << 24);
                asm.l2i();
                asm.i2b();
                return asm.istore();
            case DECIMAL16:
                asm.aload(decimalLocalIndex);
                asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_GET_LL_OFFSET, 0, 0x04 << 24);
                asm.l2i();
                asm.i2s();
                return asm.istore();
            case DECIMAL32:
                asm.aload(decimalLocalIndex);
                asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_GET_LL_OFFSET, 0, 0x04 << 24);
                asm.l2i();
                return asm.istore();
            case DECIMAL64:
                asm.aload(decimalLocalIndex);
                asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_GET_LL_OFFSET, 0, 0x04 << 24);
                return asm.lstore();
            case DECIMAL128:
                asm.aload(decimalLocalIndex);
                asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_GET_LH_OFFSET, 0, 0x04 << 24);
                index = asm.lstore();
                asm.aload(decimalLocalIndex);
                asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_GET_LL_OFFSET, 0, 0x04 << 24);
                asm.lstore();
                return index;
            default:
                asm.aload(decimalLocalIndex);
                asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_GET_HH_OFFSET, 0, 0x04 << 24);
                index = asm.lstore();
                asm.aload(decimalLocalIndex);
                asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_GET_HL_OFFSET, 0, 0x04 << 24);
                asm.lstore();
                asm.aload(decimalLocalIndex);
                asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_GET_LH_OFFSET, 0, 0x04 << 24);
                asm.lstore();
                asm.aload(decimalLocalIndex);
                asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_GET_LL_OFFSET, 0, 0x04 << 24);
                asm.lstore();
                return index;
        }
    }

    /**
     * Pools the runtime constants required by the DecimalBytecodeGenerator.
     * You can use the indexes with the given offset to retrieve any useful constants.
     *
     * @return the first index of the pool.
     */
    public static int poolUtils(BytecodeAssemblerStack asm) {
        int index = asm.poolUtf8("io/questdb/std/Decimal256");
        assert CONSTANT_DECIMAL256_OFFSET == 0;
        int classIndex = asm.poolClass(index);
        assert classIndex == index + CONSTANT_DECIMAL256_CLASS_OFFSET;
        int getSigIndex = asm.poolUtf8("()J");
        int getHHIndex = asm.poolMethod(classIndex, asm.poolNameAndType(asm.poolUtf8("getHh"), getSigIndex));
        assert getHHIndex == index + CONSTANT_DECIMAL256_GET_HH_OFFSET;
        int getHLIndex = asm.poolMethod(classIndex, asm.poolNameAndType(asm.poolUtf8("getHl"), getSigIndex));
        assert getHLIndex == index + CONSTANT_DECIMAL256_GET_HL_OFFSET;
        int getLHIndex = asm.poolMethod(classIndex, asm.poolNameAndType(asm.poolUtf8("getLh"), getSigIndex));
        assert getLHIndex == index + CONSTANT_DECIMAL256_GET_LH_OFFSET;
        int getLLIndex = asm.poolMethod(classIndex, asm.poolNameAndType(asm.poolUtf8("getLl"), getSigIndex));
        assert getLLIndex == index + CONSTANT_DECIMAL256_GET_LL_OFFSET;
        int ofIndex = asm.poolMethod(classIndex, asm.poolNameAndType(asm.poolUtf8("of"), asm.poolUtf8("(JJJJI)V")));
        assert ofIndex == index + CONSTANT_DECIMAL256_OF_OFFSET;

        int longSignBitmaskIndex = asm.poolLongConst(0x8000000000000000L);
        assert longSignBitmaskIndex == index + CONSTANT_LONG_SIGN_BITMASK_OFFSET;
        int minusOneIndex = asm.poolLongConst(-1L);
        assert minusOneIndex == index + CONSTANT_LONG_MINUS_ONE_OFFSET;

        int intMinValueIndex = asm.poolIntConst(Integer.MIN_VALUE);
        assert intMinValueIndex == index + CONSTANT_INT_MIN_VALUE_OFFSET;

        int longMinValueIndex = asm.poolLongConst(Long.MIN_VALUE);
        assert longMinValueIndex == index + CONSTANT_LONG_MIN_VALUE_OFFSET;

        return index;
    }

    /**
     * Store a local variable to a Decimal256, using the layout of the given type.
     *
     * @param asm            the BytecodeAssemblerStack that will be used to generate the bytecode.
     * @param localIndex     index of the local variable referencing the value to store.
     * @param parameterIndex index of the local variable referencing the Decimal256.
     * @param type           the layout type to use to store the Decimal256.
     * @param poolIndex      index of the pool entry returned by poolUtils().
     */
    public static void storeDecimal256(BytecodeAssemblerStack asm, int localIndex, int parameterIndex, DecimalType type, int poolIndex) {
        switch (type) {
            case DECIMAL8:
            case DECIMAL16:
            case DECIMAL32:
                storeDecimal256FromInt(asm, localIndex, parameterIndex, poolIndex);
                break;
            case DECIMAL64:
                storeDecimal256FromLong(asm, localIndex, parameterIndex, poolIndex);
                break;
            case DECIMAL128:
                storeDecimal256From128(asm, localIndex, parameterIndex, poolIndex);
                break;
            default:
                storeDecimal256From256(asm, localIndex, parameterIndex, poolIndex);
        }
    }

    /**
     * Store null to a Decimal256.
     *
     * @param asm            the BytecodeAssemblerStack that will be used to generate the bytecode.
     * @param parameterIndex index of the local variable referencing the Decimal256.
     * @param poolIndex      index of the pool entry returned by poolUtils().
     */
    public static void storeDecimal256Null(BytecodeAssemblerStack asm, int parameterIndex, int poolIndex) {
        asm.aload(parameterIndex);
        asm.ldc2_w(poolIndex + CONSTANT_LONG_MIN_VALUE_OFFSET);
        asm.lconst_0();
        asm.lconst_0();
        asm.lconst_0();
        asm.iconst(0);
        asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_OF_OFFSET, 9, 0);
    }

    /**
     * Store a positive local variable to a Decimal256, using the layout of the given type.
     *
     * @param asm            the BytecodeAssemblerStack that will be used to generate the bytecode.
     * @param localIndex     index of the local variable referencing the value to store.
     * @param parameterIndex index of the local variable referencing the Decimal256.
     * @param type           the layout type to use to store the Decimal256.
     * @param poolIndex      index of the pool entry returned by poolUtils().
     */
    public static void storePositiveDecimal256(BytecodeAssemblerStack asm, int localIndex, int parameterIndex, DecimalType type, int poolIndex) {
        switch (type) {
            case DECIMAL8:
            case DECIMAL16:
            case DECIMAL32:
                storePositiveDecimal256FromInt(asm, localIndex, parameterIndex, poolIndex);
                break;
            case DECIMAL64:
                storePositiveDecimal256FromLong(asm, localIndex, parameterIndex, poolIndex);
                break;
            case DECIMAL128:
                storePositiveDecimal256From128(asm, localIndex, parameterIndex, poolIndex);
                break;
            default:
                storePositiveDecimal256From256(asm, localIndex, parameterIndex, poolIndex);
        }
    }

    private static void generateAbsDecimal128(BytecodeAssemblerStack asm, int decimalIndex, int poolIndex) {
        asm.lload(decimalIndex);
        asm.lconst_0();
        asm.lcmp();
        // We skip the negate operation if the value is already positive.
        int branch = asm.ifge();

        // low = ~low + 1
        asm.lload(decimalIndex + 2);
        asm.ldc2_w(poolIndex + CONSTANT_LONG_MINUS_ONE_OFFSET);
        asm.lxor();
        asm.lconst_1();
        asm.ladd();
        asm.lstore(decimalIndex + 2);

        // high = ~high + (low == 0 ? 1 : 0)
        asm.lload(decimalIndex);
        asm.ldc2_w(poolIndex + CONSTANT_LONG_MINUS_ONE_OFFSET);
        asm.lxor();

        // low == 0 ? 1 : 0
        asm.lload(decimalIndex + 2);
        asm.lconst_0();
        asm.lcmp(); // if low != 0, 1 is pushed
        asm.ineg();
        asm.iconst(1);
        asm.iadd();

        asm.i2l();
        asm.ladd();

        asm.lstore(decimalIndex);

        // Updates the branch created by ifge to jump to the end of the operation.
        asm.setJmp(branch, asm.position());
    }

    private static void generateAbsDecimal256(BytecodeAssemblerStack asm, int decimalIndex, int poolIndex) {
        asm.lload(decimalIndex);
        asm.lconst_0();
        asm.lcmp();
        // We skip the negate operation if the value is already positive.
        int branch = asm.ifge();

        // ll = ~ll + 1;
        // long c = ll == 0L ? 1L : 0L;
        // lh = ~lh + c;
        // c = (c == 1L && lh == 0L) ? 1L : 0L;
        // hl = ~hl + c;
        // c = (c == 1L && hl == 0L) ? 1L : 0L;
        // hh = ~hh + c;

        // ll = ~ll + 1
        asm.lload(decimalIndex + 6);
        asm.ldc2_w(poolIndex + CONSTANT_LONG_MINUS_ONE_OFFSET);
        asm.lxor();
        asm.lconst_1();
        asm.ladd();
        asm.lstore(decimalIndex + 6);

        // c = ll == 0L ? 1L : 0L;
        asm.lload(decimalIndex + 6);
        asm.lconst_0();
        asm.lcmp(); // if low != 0, 1 is pushed
        asm.ineg();
        asm.iconst(1);
        asm.iadd();
        int c = asm.istore();

        // lh = ~lh + c;
        asm.lload(decimalIndex + 4);
        asm.ldc2_w(poolIndex + CONSTANT_LONG_MINUS_ONE_OFFSET);
        asm.lxor();
        asm.iload(c);
        asm.i2l();
        asm.ladd();
        asm.lstore(decimalIndex + 4);

        // c = (c == 1L && lh == 0L) ? 1L : 0L;
        asm.iload(c);
        asm.lload(decimalIndex + 6);
        asm.lconst_0();
        asm.lcmp(); // if low != 0, 1 is pushed
        asm.iconst(1);
        asm.iadd(); // if low was 1, then it becomes 2 and 0 becomes 1, as 2&1 = 0 and 1&1 = 1 then we have the expected result
        asm.iand();
        asm.istore(c);

        // hl = ~hl + c;
        asm.lload(decimalIndex + 2);
        asm.ldc2_w(poolIndex + CONSTANT_LONG_MINUS_ONE_OFFSET);
        asm.lxor();
        asm.iload(c);
        asm.i2l();
        asm.ladd();
        asm.lstore(decimalIndex + 2);

        // c = (c == 1L && hl == 0L) ? 1L : 0L;
        asm.iload(c);
        asm.lload(decimalIndex + 4);
        asm.lconst_0();
        asm.lcmp(); // if low != 0, 1 is pushed
        asm.iconst(1);
        asm.iadd(); // if low was 1, then it becomes 2 and 0 becomes 1, as 2&1 = 0 and 1&1 = 1 then we have the expected result
        asm.iand();
        asm.istore(c);

        // hl = ~hl + c;
        asm.lload(decimalIndex);
        asm.ldc2_w(poolIndex + CONSTANT_LONG_MINUS_ONE_OFFSET);
        asm.lxor();
        asm.iload(c);
        asm.i2l();
        asm.ladd();
        asm.lstore(decimalIndex);

        // Remove the c variable from locals
        asm.localPop(c);

        // Updates the branch created by ifge to jump to the end of the operation.
        asm.setJmp(branch, asm.position());
    }

    private static void generateAbsDecimal64(BytecodeAssemblerStack asm, int decimalIndex) {
        asm.lload(decimalIndex);
        asm.lconst_0();
        asm.lcmp();
        // We skip the negate operation if the value is already positive.
        int branch = asm.ifge();

        asm.lload(decimalIndex);
        asm.lneg();
        asm.lstore(decimalIndex);

        // Updates the branch created by ifge to jump to the end of the operation.
        asm.setJmp(branch, asm.position());
    }

    private static void generateAbsDecimal8_32(BytecodeAssemblerStack asm, int decimalIndex) {
        asm.iload(decimalIndex);
        // We skip the negate operation if the value is already positive.
        int branch = asm.ifge();

        asm.iload(decimalIndex);
        asm.ineg();
        asm.istore(decimalIndex);

        // Updates the branch created by ifge to jump to the end of the operation.
        asm.setJmp(branch, asm.position());
    }

    private static void storeDecimal256From128(BytecodeAssemblerStack asm, int localIndex, int parameterIndex, int poolIndex) {
        // We need to store -1 in hh and hl if the value is negative.
        asm.lload(localIndex);
        asm.ldc2_w(poolIndex + CONSTANT_LONG_SIGN_BITMASK_OFFSET);
        asm.land();
        asm.lconst_0();
        asm.lcmp(); // If the value is negative, a comparison of the sign with 0 will push -1 on the stack.
        // We can now increase it to a long and use it for hh, hl and lh.
        asm.i2l();
        int s = asm.lstore();

        // call to of(hh, hl, lh, ll, scale);
        asm.aload(parameterIndex);
        asm.lload(s);
        asm.lload(s);
        asm.lload(localIndex);
        asm.lload(localIndex + 2);
        asm.iconst(0);
        asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_OF_OFFSET, 9, 0);
    }

    private static void storeDecimal256From256(BytecodeAssemblerStack asm, int localIndex, int parameterIndex, int poolIndex) {
        // call to of(hh, hl, lh, ll, scale);
        asm.aload(parameterIndex);
        asm.lload(localIndex);
        asm.lload(localIndex + 2);
        asm.lload(localIndex + 4);
        asm.lload(localIndex + 6);
        asm.iconst(0);
        asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_OF_OFFSET, 9, 0);
    }

    private static void storeDecimal256FromInt(BytecodeAssemblerStack asm, int localIndex, int parameterIndex, int poolIndex) {
        // We need to store -1 in hh, hl and lh if the value is negative.
        asm.iload(localIndex);
        asm.i2l();
        asm.ldc2_w(poolIndex + CONSTANT_LONG_SIGN_BITMASK_OFFSET);
        asm.land();
        asm.lconst_0();
        asm.lcmp(); // If the value is negative, a comparison of the sign with 0 will push -1 on the stack.
        // We can now increase it to a long and use it for hh, hl and lh.
        asm.i2l();
        int s = asm.lstore();

        // call to of(hh, hl, lh, ll, scale);
        asm.aload(parameterIndex);
        asm.lload(s);
        asm.lload(s);
        asm.lload(s);
        asm.iload(localIndex);
        asm.i2l();
        asm.iconst(0);
        asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_OF_OFFSET, 9, 0);
    }

    private static void storeDecimal256FromLong(BytecodeAssemblerStack asm, int localIndex, int parameterIndex, int poolIndex) {
        // We need to store -1 in hh, hl and lh if the value is negative.
        asm.lload(localIndex);
        asm.ldc2_w(poolIndex + CONSTANT_LONG_SIGN_BITMASK_OFFSET);
        asm.land();
        asm.lconst_0();
        asm.lcmp(); // If the value is negative, a comparison of the sign with 0 will push -1 on the stack.
        // We can now increase it to a long and use it for hh, hl and lh.
        asm.i2l();
        int s = asm.lstore();

        // call to of(hh, hl, lh, ll, scale);
        asm.aload(parameterIndex);
        asm.lload(s);
        asm.lload(s);
        asm.lload(s);
        asm.lload(localIndex);
        asm.iconst(0);
        asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_OF_OFFSET, 9, 0);
    }

    private static void storePositiveDecimal256From128(BytecodeAssemblerStack asm, int localIndex, int parameterIndex, int poolIndex) {
        // call to of(hh, hl, lh, ll, scale);
        asm.aload(parameterIndex);
        asm.lconst_0();
        asm.lconst_0();
        asm.lload(localIndex);
        asm.lload(localIndex + 2);
        asm.iconst(0);
        asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_OF_OFFSET, 9, 0);
    }

    private static void storePositiveDecimal256From256(BytecodeAssemblerStack asm, int localIndex, int parameterIndex, int poolIndex) {
        // call to of(hh, hl, lh, ll, scale);
        asm.aload(parameterIndex);
        asm.lload(localIndex);
        asm.lload(localIndex + 2);
        asm.lload(localIndex + 4);
        asm.lload(localIndex + 6);
        asm.iconst(0);
        asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_OF_OFFSET, 9, 0);
    }

    private static void storePositiveDecimal256FromInt(BytecodeAssemblerStack asm, int localIndex, int parameterIndex, int poolIndex) {
        // call to of(hh, hl, lh, ll, scale);
        asm.aload(parameterIndex);
        asm.lconst_0();
        asm.lconst_0();
        asm.lconst_0();
        asm.iload(localIndex);
        asm.i2l();
        asm.iconst(0);
        asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_OF_OFFSET, 9, 0);
    }

    private static void storePositiveDecimal256FromLong(BytecodeAssemblerStack asm, int localIndex, int parameterIndex, int poolIndex) {
        // call to of(hh, hl, lh, ll, scale);
        asm.aload(parameterIndex);
        asm.lconst_0();
        asm.lconst_0();
        asm.lconst_0();
        asm.lload(localIndex);
        asm.iconst(0);
        asm.invokeVirtual(poolIndex + CONSTANT_DECIMAL256_OF_OFFSET, 9, 0);
    }

    /**
     * Generates the bytecode to check whether a local decimal is null or not.
     * After calling this method, the caller should write the necessary bytecode to write the null
     * value out and return and then call finishNullCheck().
     *
     * @param asm            the BytecodeAssemblerStack that will be used to generate the bytecode.
     * @param localIndex     index of the local variable referencing the decimal value.
     * @param type           the layout of the local variable.
* @param poolIndex      index of the pool entry returned by poolUtils().
     * @return the branch that needs to be patched with finishNullCheck().
     */
    public static int prepareNullCheck(BytecodeAssemblerStack asm, int localIndex, DecimalType type, int poolIndex) {
        switch (type) {
            case DECIMAL8:
                return prepareNullCheckByte(asm, localIndex);
            case DECIMAL16:
                return prepareNullCheckShort(asm, localIndex);
            case DECIMAL32:
                return prepareNullCheckInt(asm, localIndex, poolIndex);
            case DECIMAL64:
                return prepareNullCheckLong(asm, localIndex, poolIndex);
            case DECIMAL128:
                return prepareNullCheck128(asm, localIndex, poolIndex);
            default:
                return prepareNullCheck256(asm, localIndex, poolIndex);
        }
    }

    /**
     * Finish the null check code generation by patching the branch that was created by prepareNullCheck().
     * @param asm the BytecodeAssemblerStack that will be used to generate the bytecode.
     * @param branch the branch that was created by prepareNullCheck().
     */
    public static void finishNullCheck(BytecodeAssemblerStack asm, int branch) {
        asm.setJmp(branch, asm.position());
    }

    private static int prepareNullCheckByte(BytecodeAssemblerStack asm, int localIndex) {
        // The null value of a byte is Byte.MIN_VALUE

        asm.iload(localIndex);
        asm.bipush(Byte.MIN_VALUE);
        return asm.if_icmpne();
    }

    private static int prepareNullCheckShort(BytecodeAssemblerStack asm, int localIndex) {
        // The null value of a short is Short.MIN_VALUE

        asm.iload(localIndex);
        asm.iconst(Short.MIN_VALUE);
        return asm.if_icmpne();
    }

    private static int prepareNullCheckInt(BytecodeAssemblerStack asm, int localIndex, int poolIndex) {
        // The null value of an int is Int.MIN_VALUE

        asm.iload(localIndex);
        asm.ldc_w(poolIndex + CONSTANT_INT_MIN_VALUE_OFFSET);
        return asm.if_icmpne();
    }

    private static int prepareNullCheckLong(BytecodeAssemblerStack asm, int localIndex, int poolIndex) {
        // The null value of a long is Long.MIN_VALUE

        asm.lload(localIndex);
        asm.ldc2_w(poolIndex + CONSTANT_LONG_MIN_VALUE_OFFSET);
        asm.lcmp(); // If both value are equal, 0 is pushed on the stack.
        return asm.ifne();
    }

    private static int prepareNullCheck128(BytecodeAssemblerStack asm, int localIndex, int poolIndex) {
        // The null value of a decimal 128 is Long.MIN_VALUE and -1

        asm.lload(localIndex);
        asm.ldc2_w(poolIndex + CONSTANT_LONG_MIN_VALUE_OFFSET);
        asm.lcmp(); // If both value are equal, 0 is pushed on the stack.
        asm.lload(localIndex + 2);
        asm.lconst_0();
        asm.lcmp();
        asm.ior();
        return asm.ifne();
    }

    private static int prepareNullCheck256(BytecodeAssemblerStack asm, int localIndex, int poolIndex) {
        // The null value of a decimal 256 is Long.MIN_VALUE, -1, -1 and -1

        asm.lload(localIndex);
        asm.ldc2_w(poolIndex + CONSTANT_LONG_MIN_VALUE_OFFSET);
        asm.lcmp(); // If both value are equal, 0 is pushed on the stack.
        asm.lload(localIndex + 2);
        asm.lconst_0();
        asm.lcmp();
        asm.ior();
        asm.lload(localIndex + 4);
        asm.lconst_0();
        asm.lcmp();
        asm.ior();
        asm.lload(localIndex + 6);
        asm.lconst_0();
        asm.lcmp();
        asm.ior();
        return asm.ifne();
    }
}
