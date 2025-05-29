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

package io.questdb.griffin.engine.functions.finance;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.sql.Function;
import io.questdb.cairo.sql.Record;
import io.questdb.griffin.FunctionFactory;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.engine.functions.DoubleFunction;
import io.questdb.griffin.engine.functions.UnaryFunction;
import io.questdb.griffin.engine.functions.constants.ConstantFunction;
import io.questdb.std.Chars;
import io.questdb.std.IntList;
import io.questdb.std.Numbers;
import io.questdb.std.NumericException;
import io.questdb.std.ObjList;
import io.questdb.std.str.Utf8Sequence;
import io.questdb.std.str.Utf8s;

public class TickToDoubleFunctionFactory implements FunctionFactory {
    private static final String FUNCTION_NAME = "tick_to_double";

    @Override
    public String getSignature() {
        return FUNCTION_NAME + "(Ø)";
    }

    @Override
    public Function newInstance(
            int position,
            ObjList<Function> args,
            IntList argPositions,
            CairoConfiguration configuration,
            SqlExecutionContext sqlExecutionContext
    ) throws SqlException {
        Function arg = args.getQuick(0);
        return arg.isConstant()
                ? new ConstFunc(arg.getVarcharA(null))
                : new Func(arg);
    }

    static int frac4CharToInt(char frac4) throws NumericException {
        switch (frac4) {
            case '0':
                return 0;
            case '2':
                return 1;
            case '5':
            case '+':
                return 2;
            case '6':
            case '7':
                return 3;
            default:
                throw NumericException.INSTANCE;
        }
    }

    static double tickToDouble(Utf8Sequence varchar) {
        if (varchar == null || !varchar.isAscii()) {
            return Double.NaN;
        }
        CharSequence tick = varchar.asAsciiCharSequence();
        int dashPos = Chars.indexOf(tick, '-');
        if (dashPos != tick.length() - 4) {
            return Double.NaN;
        }
        try {
            CharSequence whole = tick.subSequence(0, dashPos);
            double value = Numbers.parseInt(whole);

            CharSequence frac = tick.subSequence(dashPos + 1, tick.length() - 1);
            int frac32 = Numbers.parseInt(frac);
            if (frac32 < 0 || frac32 > 31) {
                return Double.NaN;
            }
            value += frac32 / 32.0;

            char frac128 = tick.charAt(tick.length() - 1);
            value += frac4CharToInt(frac128) / 128.0;

            return value;
        } catch (NumericException e) {
            return Double.NaN;
        }
    }

    private static class ConstFunc extends DoubleFunction implements ConstantFunction {

        private final String arg;
        private final double result;

        private ConstFunc(Utf8Sequence arg) {
            this.arg = Utf8s.toString(arg);
            this.result = tickToDouble(arg);
        }

        @Override
        public double getDouble(Record rec) {
            return result;
        }

        @Override
        public void toPlan(PlanSink sink) {
            sink.val(FUNCTION_NAME).val('(').val(arg).val(')');
        }
    }

    private static class Func extends DoubleFunction implements UnaryFunction {

        private final Function arg;

        Func(Function arg) {
            this.arg = arg;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public double getDouble(Record rec) {
            return tickToDouble(arg.getVarcharA(rec));
        }

        @Override
        public void toPlan(PlanSink sink) {
            sink.val(FUNCTION_NAME).val('(').val(arg).val(')');
        }
    }
}
