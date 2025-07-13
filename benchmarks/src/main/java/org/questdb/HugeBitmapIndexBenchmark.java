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

package org.questdb;

import io.questdb.cairo.BitmapIndexBwdReader;
import io.questdb.cairo.BitmapIndexReader;
import io.questdb.cairo.BitmapIndexUtils;
import io.questdb.cairo.BitmapIndexWriter;
import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.DefaultCairoConfiguration;
import io.questdb.cairo.sql.RowCursor;
import io.questdb.cairo.vm.Vm;
import io.questdb.cairo.vm.api.MemoryMA;
import io.questdb.std.FilesFacade;
import io.questdb.std.MemoryTag;
import io.questdb.std.Numbers;
import io.questdb.std.Rnd;
import io.questdb.std.str.Path;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

import static io.questdb.cairo.TableUtils.COLUMN_NAME_TXN_NONE;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class HugeBitmapIndexBenchmark {

    private static final CairoConfiguration configuration = new DefaultCairoConfiguration(".");
    private static final Path path = new Path();
    private static final BitmapIndexReader reader;
    private static final Rnd rnd = new Rnd(System.currentTimeMillis(), System.nanoTime());

    public static void create(CairoConfiguration configuration, Path path, CharSequence name, int valueBlockCapacity) {
        int plen = path.size();
        try {
            final FilesFacade ff = configuration.getFilesFacade();
            try (
                    MemoryMA mem = Vm.getSmallCMARWInstance(
                            ff,
                            BitmapIndexUtils.keyFileName(path, name, COLUMN_NAME_TXN_NONE),
                            MemoryTag.MMAP_DEFAULT,
                            configuration.getWriterFileOpenOpts()
                    )
            ) {
                BitmapIndexWriter.initKeyMemory(mem, Numbers.ceilPow2(valueBlockCapacity));
            }
            ff.touch(BitmapIndexUtils.valueFileName(path.trimTo(plen), name, COLUMN_NAME_TXN_NONE));
        } finally {
            path.trimTo(plen);
        }
    }

    public static void main(String[] args) throws RunnerException {

        Options opt = new OptionsBuilder()
                .include(HugeBitmapIndexBenchmark.class.getSimpleName())
                .warmupIterations(5)
                .measurementIterations(5)
                .forks(1)
                .build();

        new Runner(opt).run();
    }

    @Setup(Level.Iteration)
    public void reset() {
    }


    @Benchmark
    public long testLookup() {
        RowCursor c = reader.getCursor(true, rnd.nextInt(80_000_000), Long.MIN_VALUE, Long.MAX_VALUE);
        long s = 0;
        while (c.hasNext()) {
            s += c.next();
        }
        return s;
    }

    static {
        path.of(configuration.getDbRoot());
        int plen = path.size();
        create(configuration, path.trimTo(plen), "x", 8);
        try (BitmapIndexWriter writer = new BitmapIndexWriter(configuration, path, "x", COLUMN_NAME_TXN_NONE)) {
            for (int i = 0; i < 80_000_000; i++) {
                writer.add(i, i * 4);
                writer.add(i, i * 4 + 1);
                writer.add(i, i * 4 + 2);
                writer.add(i, i * 4 + 3);
            }
        }
        reader = new BitmapIndexBwdReader(configuration, path.trimTo(plen), "x", COLUMN_NAME_TXN_NONE, 0);
    }
}
