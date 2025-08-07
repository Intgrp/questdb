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

package io.questdb.griffin.engine.functions.catalogue;

import io.questdb.cairo.AbstractRecordCursorFactory;
import io.questdb.cairo.CairoColumn;
import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.CairoTable;
import io.questdb.cairo.ColumnType;
import io.questdb.cairo.GenericRecordMetadata;
import io.questdb.cairo.MetadataCacheReader;
import io.questdb.cairo.TableColumnMetadata;
import io.questdb.cairo.TableUtils;
import io.questdb.cairo.sql.Function;
import io.questdb.cairo.sql.NoRandomAccessRecordCursor;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.cutlass.pgwire.PGOids;
import io.questdb.griffin.FunctionFactory;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.engine.EmptyTableRandomRecordCursor;
import io.questdb.griffin.engine.functions.CursorFunction;
import io.questdb.std.CharSequenceObjHashMap;
import io.questdb.std.IntList;
import io.questdb.std.ObjList;

import static io.questdb.cutlass.pgwire.PGOids.PG_TYPE_TO_SIZE_MAP;

public class PgConstraintFunctionFactory implements FunctionFactory {
    private static final RecordMetadata METADATA;
    private static final String SIGNATURE = "pg_constraint()";

    @Override
    public String getSignature() {
        return SIGNATURE;
    }

    @Override
    public boolean isRuntimeConstant() {
        return true;
    }

    @Override
    public Function newInstance(
            int position,
            ObjList<Function> args,
            IntList argPositions,
            CairoConfiguration configuration,
            SqlExecutionContext sqlExecutionContext
    ) {
        return new CursorFunction(new ConstraintCatalogueCursorFactory()) {
            @Override
            public boolean isRuntimeConstant() {
                return true;
            }
        };
    }

    private static class ConstraintCatalogueCursorFactory extends AbstractRecordCursorFactory {
        public ConstraintCatalogueCursorFactory() {
            super(METADATA);
        }

        @Override
        public RecordCursor getCursor(SqlExecutionContext executionContext) {
            return EmptyTableRandomRecordCursor.INSTANCE;
        }

        @Override
        public boolean recordCursorSupportsRandomAccess() {
            return true;
        }

        @Override
        public void toPlan(PlanSink sink) {
            sink.type(SIGNATURE);
        }
    }

    static {
        final GenericRecordMetadata metadata = new GenericRecordMetadata();
        metadata.add(new TableColumnMetadata("oid", ColumnType.INT));
        metadata.add(new TableColumnMetadata("conname", ColumnType.STRING));
        metadata.add(new TableColumnMetadata("connamespace", ColumnType.INT));
        metadata.add(new TableColumnMetadata("contype", ColumnType.CHAR));
        metadata.add(new TableColumnMetadata("condeferrable", ColumnType.BOOLEAN));
        metadata.add(new TableColumnMetadata("condeferred", ColumnType.BOOLEAN));
        metadata.add(new TableColumnMetadata("convalidated", ColumnType.BOOLEAN));
        metadata.add(new TableColumnMetadata("conrelid", ColumnType.INT));
        metadata.add(new TableColumnMetadata("contypid", ColumnType.INT));
        metadata.add(new TableColumnMetadata("conindid", ColumnType.INT));
        metadata.add(new TableColumnMetadata("conparentid", ColumnType.INT));
        metadata.add(new TableColumnMetadata("confrelid", ColumnType.INT));
        metadata.add(new TableColumnMetadata("confupdtype", ColumnType.CHAR));
        metadata.add(new TableColumnMetadata("confdeltype", ColumnType.CHAR));
        metadata.add(new TableColumnMetadata("confmatchtype", ColumnType.CHAR));
        metadata.add(new TableColumnMetadata("conislocal", ColumnType.BOOLEAN));
        metadata.add(new TableColumnMetadata("coninhcount", ColumnType.INT));
        metadata.add(new TableColumnMetadata("connoinherit", ColumnType.BOOLEAN));
        metadata.add(new TableColumnMetadata("conkey", ColumnType.STRING));
        metadata.add(new TableColumnMetadata("confkey", ColumnType.STRING));
        metadata.add(new TableColumnMetadata("conpfeqop", ColumnType.STRING));
        metadata.add(new TableColumnMetadata("conppeqop", ColumnType.STRING));
        metadata.add(new TableColumnMetadata("conffeqop", ColumnType.STRING));
        metadata.add(new TableColumnMetadata("confdelsetcols", ColumnType.STRING));
        metadata.add(new TableColumnMetadata("conexclop", ColumnType.STRING));
        metadata.add(new TableColumnMetadata("conbin", ColumnType.STRING));
        METADATA = metadata;
    }
}
