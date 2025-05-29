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

package io.questdb.test.griffin.engine.functions.finance;

import io.questdb.griffin.FunctionFactory;
import io.questdb.griffin.engine.functions.finance.TickToDoubleFunctionFactory;
import io.questdb.std.Numbers;
import io.questdb.std.Rnd;
import io.questdb.test.griffin.engine.AbstractFunctionFactoryTest;
import io.questdb.test.tools.TestUtils;
import org.junit.Test;

public class TickToDoubleFunctionFactoryTest extends AbstractFunctionFactoryTest {

    public static final String[] INVALID_INPUTS = new String[]{
            "-",
            "1-",
            "-1",
            "1-1",
            "1-111",
            "1-113",
            "1-114",
            "1-118",
            "1-119",
            "1-320",
            "1-1111",
            "33-12ć",
            "ć3-120",
    };
    public static final String[][] VALID_INPUT_OUTPUT = new String[][]{
            {"1-000", "1.0"},
            {"1-160", "1.5"},
            {"1-002", "1.0078125"},
            {"1-00+", "1.015625"},
            {"1-005", "1.015625"},
            {"1-006", "1.0234375"},
            {"1-007", "1.0234375"},
            {"1-310", "1.96875"},
            {"1-317", "1.9921875"},
            {"111-000", "111.0"},
            {"1111111111-000", "1.111111111E9"},
            {"1111111111-000", "1.111111111E9"},
    };

    @Test
    public void testFuzz() throws Exception {
        assertMemoryLeak(() -> {
            Rnd rnd = TestUtils.generateRandom(LOG);
            int whole = rnd.nextInt(200);
            int frac32 = rnd.nextInt(32);
            int frac128 = rnd.nextInt(4);
            double value = whole + frac32 / 32.0 + frac128 / 128.0;
            String tick = String.format("%d-%02d%c", whole, frac32, intToFrac4Char(frac128));
            sink.clear();
            Numbers.append(sink, value);
            String valueStr = sink.toString();
            assertQuery(String.format("tick_to_double\n%s\n", valueStr),
                    String.format("select tick_to_double('%s')", tick));
        });
    }

    @Test
    public void testInvalid() throws Exception {
        assertMemoryLeak(() -> {
            for (String input : INVALID_INPUTS) {
                assertQuery("tick_to_double\nnull\n", "select tick_to_double('" + input + "')");
            }
        });
    }

    @Test
    public void testInvalidNonConst() throws Exception {
        assertMemoryLeak(() -> {
            execute("CREATE TABLE tango (tick VARCHAR)");
            for (String input : INVALID_INPUTS) {
                execute("INSERT INTO tango VALUES ('" + input + "');");
            }
            StringBuilder expected = new StringBuilder("tick_to_double\n");
            for (String ignored : INVALID_INPUTS) {
                expected.append("null\n");
            }
            assertQuery(expected, "SELECT tick_to_double(tick) FROM tango");
        });
    }

    @Test
    public void testValid() throws Exception {
        for (String[] pair : VALID_INPUT_OUTPUT) {
            assertQuery("tick_to_double\n" + pair[1] + "\n", "select tick_to_double('" + pair[0] + "')");
        }
    }

    @Test
    public void testValidNonConst() throws Exception {
        assertMemoryLeak(() -> {
            execute("CREATE TABLE tango (tick VARCHAR)");
            for (String[] pair : VALID_INPUT_OUTPUT) {
                execute("INSERT INTO tango VALUES ('" + pair[0] + "');");
            }
            StringBuilder expected = new StringBuilder("tick_to_double\n");
            for (String[] pair : VALID_INPUT_OUTPUT) {
                expected.append(pair[1]).append('\n');
            }
            assertQuery(expected, "SELECT tick_to_double(tick) FROM tango");
        });
    }

    private static char intToFrac4Char(int frac4) {
        switch (frac4) {
            case 0:
                return '0';
            case 1:
                return '2';
            case 2:
                return '+';
            case 3:
                return '6';
            default:
                throw new IllegalArgumentException("" + frac4);
        }
    }

    @Override
    protected FunctionFactory getFunctionFactory() {
        return new TickToDoubleFunctionFactory();
    }
}
