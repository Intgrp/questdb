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

/**
 * Defines the several decimal types that exist.
 * Decimal are stored as two's complement signed integers with a scale and a precision.
 * The scale is the number of digits to the right of the decimal point.
 * The precision is the number of digits to the left of the decimal point.
 * These 2 fields are stored in the type field as metadata while the actual value is stored in the value field.
 * The value field is stored differently based on the precision of the type. A type that have a precision low enough
 * might require less space to store the value; thus the value field is stored in a compact form.
 *
 * For now, we support 8, 16, 32, 64, 128 and 256-bit decimals, each of these type store the signed integer with
 * a different layout.
 */
public enum DecimalType {
    // Stored as byte
    DECIMAL8,
    // Stored as short
    DECIMAL16,
    // Stored as int
    DECIMAL32,
    // Stored as long
    DECIMAL64,
    // Stored as 2 longs (high and low, respectively)
    DECIMAL128,
    // Stored as 4 longs (hh, hl, lh, ll respectively)
    DECIMAL256,
}
