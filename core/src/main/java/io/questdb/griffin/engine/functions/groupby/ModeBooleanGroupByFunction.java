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


package io.questdb.griffin.engine.functions.groupby;

import io.questdb.cairo.ArrayColumnTypes;
import io.questdb.cairo.ColumnType;
import io.questdb.cairo.map.MapValue;
import io.questdb.cairo.sql.Function;
import io.questdb.cairo.sql.Record;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.engine.functions.BooleanFunction;
import io.questdb.griffin.engine.functions.GroupByFunction;
import io.questdb.griffin.engine.functions.UnaryFunction;
import org.jetbrains.annotations.NotNull;

import static io.questdb.std.Numbers.LONG_NULL;

// Unlike other mode functions, boolean only has two values, so a map is not required.
public class ModeBooleanGroupByFunction extends BooleanFunction implements UnaryFunction, GroupByFunction {
    final Function arg;
    int valueIndex; // a pointer to the map that allows you to derive the mode

    public ModeBooleanGroupByFunction(@NotNull Function arg) {
        this.arg = arg;
    }

    @Override
    public void computeFirst(MapValue mapValue, Record record, long rowId) {
        boolean value = arg.getBool(record);
        mapValue.putLong(valueIndex, value ? 0 : 1);
        mapValue.putLong(valueIndex + 1, value ? 1 : 0);
    }

    @Override
    public void computeNext(MapValue mapValue, Record record, long rowId) {
        boolean value = arg.getBool(record);
        mapValue.addLong(valueIndex, value ? 0 : 1);
        mapValue.addLong(valueIndex + 1, value ? 1 : 0);
    }

    @Override
    public Function getArg() {
        return arg;
    }

    @Override
    public boolean getBool(Record record) {
        final long falseCount = record.getLong(0);
        final long trueCount = record.getLong(1);
        return trueCount > falseCount;
    }

    @Override
    public String getName() {
        return "mode";
    }

    @Override
    public int getSampleByFlags() {
        return GroupByFunction.SAMPLE_BY_FILL_ALL;
    }

    @Override
    public int getValueIndex() {
        return valueIndex;
    }

    @Override
    public void initValueIndex(int valueIndex) {
        this.valueIndex = valueIndex;
    }

    @Override
    public void initValueTypes(ArrayColumnTypes columnTypes) {
        this.valueIndex = columnTypes.getColumnCount();
        columnTypes.add(ColumnType.LONG); // false counts
        columnTypes.add(ColumnType.LONG); // true counts
    }

    @Override
    public boolean isConstant() {
        return false;
    }

    @Override
    public boolean isThreadSafe() {
        return true;
    }

    @Override
    public void merge(MapValue destValue, MapValue srcValue) {
        long srcFalseCount = srcValue.getLong(valueIndex);
        long srcTrueCount = srcValue.getLong(valueIndex + 1);
        destValue.addLong(valueIndex, srcFalseCount);
        destValue.addLong(valueIndex + 1, srcTrueCount);
    }

    @Override
    public void setNull(MapValue mapValue) {
        mapValue.putLong(valueIndex, LONG_NULL);
        mapValue.putLong(valueIndex + 1, LONG_NULL);
    }

    @Override
    public boolean supportsParallelism() {
        return true;
    }

    @Override
    public void toPlan(PlanSink sink) {
        sink.val("mode(").val(arg).val(')');
    }

    @Override
    public void toTop() {
        UnaryFunction.super.toTop();
    }

}
