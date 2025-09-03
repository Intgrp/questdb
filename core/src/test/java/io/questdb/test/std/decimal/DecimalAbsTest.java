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

package io.questdb.test.std.decimal;

import io.questdb.std.BytecodeAssemblerStack;
import io.questdb.std.decimal.DecimalBytecodeGenerator;
import io.questdb.std.Decimal256;
import io.questdb.std.decimal.DecimalType;
import org.junit.Assert;
import org.junit.Test;

public class DecimalAbsTest {
    @Test
    public void testAbsBasic() {
        BytecodeAssemblerStack asm = new BytecodeAssemblerStack();

        // For the purpose of this test, we're relying on the Decimal256 loader/storer to pass around the decimal value
        // One can change the layout of the type on memory by modifying type just below
        DecimalType type = DecimalType.DECIMAL256;

        AbsTester tester = generateInstance(asm, type);

        Decimal256 value = Decimal256.fromLong(-123, 0);
        Decimal256 result = new Decimal256();

        tester.abs(value, result);

        Assert.assertEquals("123", result.toString());
    }

    /**
     * Generate a new instance of the AbsTester, following the provided type as layout.
     * @param asm the bytecode assembler that will be used to generate the bytecode
     * @param type the layout type used inside the function
     * @return a new instance of AbsTester generated specifically for the provided type
     */
    private static AbsTester generateInstance(BytecodeAssemblerStack asm, DecimalType type) {
        asm.init(AbsTester.class);

        asm.setupPool();
        int stackMapTableIndex = asm.poolUtf8("StackMapTable");
        int thisClassIndex = asm.poolClass(asm.poolUtf8("io/questdb/test/std/decimal/DecimalAbsTestBasic"));
        int interfaceClassIndex = asm.poolClass(AbsTester.class);
        int absNameIndex = asm.poolUtf8("abs");
        int absSigIndex = asm.poolUtf8("(Lio/questdb/std/Decimal256;Lio/questdb/std/Decimal256;)V");
        int decimalGeneratorIndex = DecimalBytecodeGenerator.poolUtils(asm);
        asm.finishPool();

        asm.defineClass(thisClassIndex);
        asm.interfaceCount(1);
        asm.putShort(interfaceClassIndex);
        asm.fieldCount(0);
        asm.methodCount(2);
        asm.defineDefaultConstructor();


        asm.startMethod(absNameIndex, absSigIndex, 15, 15);
        asm.registerParameter((0x07 << 24) | (decimalGeneratorIndex + DecimalBytecodeGenerator.CONSTANT_DECIMAL256_CLASS_OFFSET));
        asm.registerParameter((0x07 << 24) | (decimalGeneratorIndex + DecimalBytecodeGenerator.CONSTANT_DECIMAL256_CLASS_OFFSET));

        // Generate the bytecode to load from a Decimal256 passed as a function parameter (here first argument so 1 - as this is 0).
        // It returns the address of the value on local variables so that we can refer to it later on.
        int v = DecimalBytecodeGenerator.loadDecimal256(asm, 1, type, decimalGeneratorIndex);

        // Generate the bytecode for a null check, specific to the layout type that we're passing on.
        int nullBranch = DecimalBytecodeGenerator.prepareNullCheck(asm, v, type, decimalGeneratorIndex);
        // We generate the bytecode to handle the case where the value is null as it is storer-specific.
        DecimalBytecodeGenerator.storeDecimal256Null(asm, 2, decimalGeneratorIndex);
        asm.return_();
        // Finally, we patch the jmp address so that the JVM can jump in the right place if the value isn't null.
        DecimalBytecodeGenerator.finishNullCheck(asm, nullBranch);

        // Generate the bytecode for the actual abs operation (a simple neg check and jump if already positive).
        DecimalBytecodeGenerator.generateAbs(asm, type, v, decimalGeneratorIndex);

        // Generate the bytecode to store the type from local variable to a Decimal256 passed as parameter.
        DecimalBytecodeGenerator.storeDecimal256(asm, v, 2, type, decimalGeneratorIndex);

        asm.return_();
        asm.endMethodCode();

        // exceptions
        asm.putShort(0);

        // We need to write the stack-map for every jumps so that the JVM can typecheck our generated bytecode
        // and complain if we messed something up.
        asm.doStackMapTables(thisClassIndex, stackMapTableIndex);

        asm.endMethod();

        asm.putShort(0);

        return asm.newInstance();
    }

    @FunctionalInterface
    public interface AbsTester {
        void abs(Decimal256 value, Decimal256 result);
    }
}
