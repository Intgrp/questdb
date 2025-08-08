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

package io.questdb.cutlass.http.processors;

import io.questdb.cutlass.json.JsonException;
import io.questdb.cutlass.json.JsonLexer;
import io.questdb.cutlass.json.JsonParser;
import io.questdb.std.Chars;
import io.questdb.std.Mutable;
import io.questdb.std.Numbers;
import io.questdb.std.NumericException;
import io.questdb.std.str.DirectUtf8Sink;
import io.questdb.std.str.StringSink;

import java.io.Closeable;

class MCPProcessorState implements Mutable, Closeable, JsonParser {
    final DirectUtf8Sink requestBody;
    final JsonLexer jsonLexer;
    final StringSink tempSink = new StringSink();
    int method = -1; // -1=unknown, 0=initialize, 1=tools/list, 2=tools/call, 3=notifications/initialized, etc.
    Object requestId;
    int toolName = -1; // -1=unknown, 0=query, 1=tables, 2=columns, 3=stats, 4=partitions
    final StringSink sqlQuery = new StringSink();
    final StringSink tableName = new StringSink();
    int queryLimit = 100;
    boolean parsingId = false;
    boolean parsingMethod = false;
    boolean parsingToolName = false;
    boolean parsingToolArguments = false;
    boolean parsingSql = false;
    boolean parsingLimit = false;
    boolean parsingTable = false;
    int jsonDepth = 0;
    
    MCPProcessorState(int bufferSize) {
        requestBody = new DirectUtf8Sink(bufferSize);
        jsonLexer = new JsonLexer(1024, 1024);
    }

    @Override
    public void clear() {
        requestBody.clear();
        jsonLexer.clear();
        tempSink.clear();
        method = -1;
        requestId = null;
        toolName = -1;
        sqlQuery.clear();
        queryLimit = 100;
        tableName.clear();
        parsingId = false;
        parsingMethod = false;
        parsingToolName = false;
        parsingToolArguments = false;
        parsingSql = false;
        parsingLimit = false;
        parsingTable = false;
        jsonDepth = 0;
    }

    @Override
    public void close() {
        requestBody.close();
        jsonLexer.close();
    }
    
    @Override
    public void onEvent(int code, CharSequence tag, int position) throws JsonException {
        switch (code) {
            case JsonLexer.EVT_OBJ_START:
                jsonDepth++;
                break;
            case JsonLexer.EVT_OBJ_END:
                jsonDepth--;
                // Reset parsing flags when leaving objects
                if (jsonDepth == 1) {
                    parsingToolArguments = false;
                }
                break;
            case JsonLexer.EVT_NAME:
                resetParsingFlags();
                
                if (Chars.equals(tag, "method")) {
                    parsingMethod = true;
                } else if (Chars.equals(tag, "id")) {
                    parsingId = true;
                } else if (Chars.equals(tag, "name") && jsonDepth == 2) {
                    // Tool name is at params.name level
                    parsingToolName = true;
                } else if (Chars.equals(tag, "arguments") && jsonDepth == 2) {
                    parsingToolArguments = true;
                } else if (Chars.equals(tag, "sql") && parsingToolArguments) {
                    parsingSql = true;
                } else if (Chars.equals(tag, "limit") && parsingToolArguments) {
                    parsingLimit = true;
                } else if (Chars.equals(tag, "table") && parsingToolArguments) {
                    parsingTable = true;
                }
                break;
            case JsonLexer.EVT_VALUE:
                if (parsingMethod) {
                    // Compare method name and store as primitive
                    if (Chars.equals(tag, "initialize")) {
                        method = 0;
                    } else if (Chars.equals(tag, "tools/list")) {
                        method = 1;
                    } else if (Chars.equals(tag, "tools/call")) {
                        method = 2;
                    } else if (Chars.equals(tag, "notifications/initialized")) {
                        method = 3;
                    } else if (Chars.equals(tag, "notifications/cancelled")) {
                        method = 4;
                    } else {
                        method = -1;
                    }
                    parsingMethod = false;
                } else if (parsingId) {
                    try {
                        requestId = Numbers.parseInt(tag);
                    } catch (NumericException e) {
                        // Store as string only if it's not numeric
                        tempSink.clear();
                        tempSink.put(tag);
                        requestId = tempSink.toString();
                    }
                    parsingId = false;
                } else if (parsingToolName) {
                    // Compare tool name and store as primitive
                    if (Chars.equals(tag, "query")) {
                        toolName = 0;
                    } else if (Chars.equals(tag, "tables")) {
                        toolName = 1;
                    } else if (Chars.equals(tag, "columns")) {
                        toolName = 2;
                    } else if (Chars.equals(tag, "stats")) {
                        toolName = 3;
                    } else if (Chars.equals(tag, "partitions")) {
                        toolName = 4;
                    } else {
                        toolName = -1;
                    }
                    parsingToolName = false;
                } else if (parsingSql) {
                    sqlQuery.clear();
                    sqlQuery.put(tag);
                    parsingSql = false;
                } else if (parsingLimit) {
                    try {
                        queryLimit = Numbers.parseInt(tag);
                    } catch (NumericException e) {
                        queryLimit = 100;
                    }
                    parsingLimit = false;
                } else if (parsingTable) {
                    tableName.clear();
                    tableName.put(tag);
                    parsingTable = false;
                }
                break;
        }
    }
    
    private void resetParsingFlags() {
        parsingMethod = false;
        parsingId = false;
        parsingToolName = false;
        parsingSql = false;
        parsingLimit = false;
        parsingTable = false;
    }
    
    void parseRequest() throws JsonException {
        // Clear only parsing state, not extracted values
        jsonLexer.clear();
        method = -1;
        requestId = null;
        toolName = -1;
        sqlQuery.clear();
        tableName.clear();
        queryLimit = 100;
        parsingId = false;
        parsingMethod = false;
        parsingToolName = false;
        parsingToolArguments = false;
        parsingSql = false;
        parsingLimit = false;
        parsingTable = false;
        jsonDepth = 0;
        jsonLexer.parse(requestBody.ptr(), requestBody.ptr() + requestBody.size(), this);
        jsonLexer.parseLast();
    }
}