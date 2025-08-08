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

import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.CairoException;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.griffin.CompiledQuery;
import io.questdb.griffin.SqlCompiler;
import io.questdb.griffin.SqlExecutionContextImpl;
import io.questdb.cutlass.http.HttpChunkedResponse;
import io.questdb.cutlass.http.HttpConnectionContext;
import io.questdb.cutlass.http.HttpPostPutProcessor;
import io.questdb.cutlass.http.HttpRequestHandler;
import io.questdb.cutlass.http.HttpRequestHeader;
import io.questdb.cutlass.http.HttpRequestProcessor;
import io.questdb.cutlass.http.LocalValue;
import io.questdb.cutlass.json.JsonException;
import io.questdb.griffin.SqlException;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.network.PeerDisconnectedException;
import io.questdb.network.PeerIsSlowToReadException;
import io.questdb.network.ServerDisconnectException;
import io.questdb.std.str.Utf8Sequence;
import io.questdb.std.str.Utf8s;

import java.io.Closeable;
import java.util.UUID;

public final class MCPProcessor implements HttpPostPutProcessor, HttpRequestHandler, Closeable {
    private static final Log LOG = LogFactory.getLog(MCPProcessor.class);
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
    private static final String CONTENT_TYPE_SSE = "text/event-stream";
    private static final LocalValue<MCPProcessorState> LV_STATE = new LocalValue<>();

    private final CairoEngine engine;
    private final MCPProcessorConfiguration configuration;
    private final String sessionId;
    private final byte requiredAuthType;
    private final SqlExecutionContextImpl sqlExecutionContext;
    private MCPProcessorState transientState;

    public MCPProcessor(
            MCPProcessorConfiguration configuration,
            CairoEngine engine
    ) {
        this.configuration = configuration;
        this.engine = engine;
        this.sessionId = UUID.randomUUID().toString();
        this.requiredAuthType = configuration.getRequiredAuthType();
        this.sqlExecutionContext = new SqlExecutionContextImpl(engine, 1);
    }

    @Override
    public void close() {
    }

    @Override
    public HttpRequestProcessor getDefaultProcessor() {
        return this;
    }

    @Override
    public HttpRequestProcessor getProcessor(HttpRequestHeader requestHeader) {
        return this;
    }

    @Override
    public byte getRequiredAuthType() {
        return requiredAuthType;
    }

    @Override
    public void onChunk(long lo, long hi) throws PeerDisconnectedException, PeerIsSlowToReadException, ServerDisconnectException {
        if (hi > lo && transientState != null) {
            transientState.requestBody.putNonAscii(lo, hi);
        }
    }

    @Override
    public void onHeadersReady(HttpConnectionContext context) {
        transientState = LV_STATE.get(context);
        if (transientState == null) {
            LOG.debug().$("new MCP state").$();
            transientState = new MCPProcessorState(configuration.getMaxRequestSize());
            LV_STATE.set(context, transientState);
        } else {
            transientState.clear();
        }
    }

    @Override
    public void onRequestComplete(HttpConnectionContext context) throws PeerDisconnectedException, PeerIsSlowToReadException {
        HttpRequestHeader requestHeader = context.getRequestHeader();
        Utf8Sequence method = requestHeader.getMethod();

        if (Utf8s.equalsAscii("POST", method)) {
            handlePostRequest(context);
        } else if (Utf8s.equalsAscii("GET", method)) {
            handleGetRequest(context);
        } else {
            sendErrorResponse(context, 405, "Method not allowed");
        }
    }

    private void handlePostRequest(HttpConnectionContext context) throws PeerDisconnectedException, PeerIsSlowToReadException {
        HttpChunkedResponse response = context.getChunkedResponse();

        try {
            // Parse the JSON request using zero-allocation JsonLexer
            transientState.parseRequest();

            int requestId = 0;
            if (transientState.requestId instanceof Number) {
                requestId = ((Number) transientState.requestId).intValue();
            } else if (transientState.requestId != null) {
                try {
                    requestId = Integer.parseInt(transientState.requestId.toString());
                } catch (NumberFormatException e) {
                    requestId = 0;
                }
            }

            int methodId = transientState.method;

            response.status(200, CONTENT_TYPE_SSE);
            response.headers().put("Cache-Control: no-cache\r\n");
            response.headers().put("Transfer-Encoding: chunked\r\n");
            response.headers().put("Mcp-Session-Id: ").put(sessionId).put("\r\n");
            response.headers().put("MCP-Protocol-Version: ").put(MCP_PROTOCOL_VERSION).put("\r\n");
            response.sendHeader();

            // Handle different MCP methods
            if (methodId == 0) { // initialize
                handleInitializeMethod(response, requestId);
            } else if (methodId == 1) { // tools/list
                handleToolsListMethod(response, requestId);
            } else if (methodId == 2) { // tools/call
                handleToolsCallMethod(context, response, requestId);
            } else {
                sendJsonError(response, requestId, -32601, "Method not found");
            }

        } catch (JsonException e) {
            LOG.error().$("JSON parsing error: ").$(e.getMessage()).$();
            sendJsonError(response, 0, -32700, "Parse error: " + e.getMessage());
        } catch (Exception e) {
            LOG.error().$("Error processing MCP request: ").$(e.getMessage()).$();
            sendJsonError(response, 0, -32603, "Internal error: " + e.getMessage());
        }
    }

    private void handleGetRequest(HttpConnectionContext context) throws PeerDisconnectedException, PeerIsSlowToReadException {
        HttpChunkedResponse response = context.getChunkedResponse();

        response.status(200, CONTENT_TYPE_SSE);
        response.headers().put("Cache-Control: no-cache\r\n");
        response.headers().put("Transfer-Encoding: chunked\r\n");
        response.headers().put("Mcp-Session-Id: ").put(sessionId).put("\r\n");
        response.headers().put("MCP-Protocol-Version: ").put(MCP_PROTOCOL_VERSION).put("\r\n");
        response.sendHeader();

        response.putAscii("data: ");
        response.putAscii("{\"type\":\"session\",\"sessionId\":\"");
        response.putAscii(sessionId);
        response.putAscii("\"}\n\n");
        response.sendChunk(false);
    }

    private void handleInitializeMethod(HttpChunkedResponse response, int requestId)
            throws PeerDisconnectedException, PeerIsSlowToReadException {

        response.putAscii("data: {\"jsonrpc\":\"2.0\",\"id\":");
        response.put(requestId);
        response.putAscii(",\"result\":{");
        response.putAscii("\"protocolVersion\":\"");
        response.putAscii(MCP_PROTOCOL_VERSION);
        response.putAscii("\",\"capabilities\":{");
        response.putAscii("\"tools\":{},");
        response.putAscii("\"resources\":{\"subscribe\":false,\"listChanged\":false},");
        response.putAscii("\"prompts\":{}");
        response.putAscii("},\"serverInfo\":{");
        response.putAscii("\"name\":\"QuestDB MCP Server\",");
        response.putAscii("\"version\":\"1.0.0\"");
        response.putAscii("}}}\n\n");
        response.sendChunk(true);
    }

    private void handleToolsListMethod(HttpChunkedResponse response, int requestId)
            throws PeerDisconnectedException, PeerIsSlowToReadException {

        response.putAscii("data: {\"jsonrpc\":\"2.0\",\"id\":");
        response.put(requestId);
        response.putAscii(",\"result\":{\"tools\":[");

        // SQL Query tool
        response.putAscii("{\"name\":\"query\",");
        response.putAscii("\"description\":\"Execute SQL query against QuestDB\",");
        response.putAscii("\"inputSchema\":{");
        response.putAscii("\"type\":\"object\",");
        response.putAscii("\"properties\":{");
        response.putAscii("\"sql\":{\"type\":\"string\",\"description\":\"SQL query to execute\"},");
        response.putAscii("\"limit\":{\"type\":\"integer\",\"description\":\"Maximum number of rows to return\",\"default\":100}");
        response.putAscii("},\"required\":[\"sql\"]}},");

        // List Tables tool
        response.putAscii("{\"name\":\"tables\",");
        response.putAscii("\"description\":\"List all tables in QuestDB\",");
        response.putAscii("\"inputSchema\":{\"type\":\"object\",\"properties\":{}}},");

        // Table Structure tool
        response.putAscii("{\"name\":\"columns\",");
        response.putAscii("\"description\":\"Get column information for a specific table\",");
        response.putAscii("\"inputSchema\":{");
        response.putAscii("\"type\":\"object\",");
        response.putAscii("\"properties\":{");
        response.putAscii("\"table\":{\"type\":\"string\",\"description\":\"Table name\"}");
        response.putAscii("},\"required\":[\"table\"]}},");

        // Table Statistics tool
        response.putAscii("{\"name\":\"stats\",");
        response.putAscii("\"description\":\"Get storage statistics for a table (row count, disk size, partitions)\",");
        response.putAscii("\"inputSchema\":{");
        response.putAscii("\"type\":\"object\",");
        response.putAscii("\"properties\":{");
        response.putAscii("\"table\":{\"type\":\"string\",\"description\":\"Table name\"}");
        response.putAscii("},\"required\":[\"table\"]}},");

        // Table Partitions tool
        response.putAscii("{\"name\":\"partitions\",");
        response.putAscii("\"description\":\"Get partition details for a table\",");
        response.putAscii("\"inputSchema\":{");
        response.putAscii("\"type\":\"object\",");
        response.putAscii("\"properties\":{");
        response.putAscii("\"table\":{\"type\":\"string\",\"description\":\"Table name\"}");
        response.putAscii("},\"required\":[\"table\"]}}");

        response.putAscii("]}}\n\n");
        response.sendChunk(true);
    }

    private void handleToolsCallMethod(HttpConnectionContext context, HttpChunkedResponse response, int requestId)
            throws PeerDisconnectedException, PeerIsSlowToReadException {

        int toolNameId = transientState.toolName;
        if (toolNameId == -1) {
            sendJsonError(response, requestId, -32602, "Missing tool name");
            return;
        }

        try {
            sqlExecutionContext.with(context.getSecurityContext(), null, null, context.getFd(), null);

            if (toolNameId == 0) { // query
                executeQueryTool(response, requestId);
            } else if (toolNameId == 1) { // tables
                executeTablesTool(response, requestId);
            } else if (toolNameId == 2) { // columns
                executeColumnsTool(response, requestId);
            } else if (toolNameId == 3) { // stats
                executeStatsTool(response, requestId);
            } else if (toolNameId == 4) { // partitions
                executePartitionsTool(response, requestId);
            } else {
                sendJsonError(response, requestId, -32602, "Unknown tool");
            }

        } catch (SqlException e) {
            sendJsonError(response, requestId, -32603, "SQL error: " + e.getMessage());
        } catch (CairoException e) {
            sendJsonError(response, requestId, -32603, "Database error: " + e.getFlyweightMessage());
        } catch (Exception e) {
            LOG.error().$("Error executing tool: ").$(e.getMessage()).$();
            sendJsonError(response, requestId, -32603, "Internal error: " + e.getMessage());
        }
    }

    private void executeQueryTool(HttpChunkedResponse response, int requestId)
            throws PeerDisconnectedException, PeerIsSlowToReadException, SqlException {

        CharSequence sql = transientState.sqlQuery;
        if (sql.length() == 0) {
            sendJsonError(response, requestId, -32602, "Missing SQL query");
            return;
        }

        int limit = transientState.queryLimit;
        if (limit <= 0) limit = 100;

        try (SqlCompiler compiler = engine.getSqlCompiler()) {
            CompiledQuery compiledQuery = compiler.compile(sql, sqlExecutionContext);

            if (compiledQuery.getType() != CompiledQuery.SELECT) {
                sendJsonError(response, requestId, -32602, "Only SELECT queries are supported");
                return;
            }

            try (RecordCursorFactory factory = compiledQuery.getRecordCursorFactory()) {
                try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
                    sendQueryResult(response, requestId, cursor, factory, limit);
                }
            }
        }
    }

    private void executeTablesTool(HttpChunkedResponse response, int requestId)
            throws PeerDisconnectedException, PeerIsSlowToReadException, SqlException {

        try (SqlCompiler compiler = engine.getSqlCompiler()) {
            CompiledQuery compiledQuery = compiler.compile("SELECT table_name FROM tables()", sqlExecutionContext);

            try (RecordCursorFactory factory = compiledQuery.getRecordCursorFactory()) {
                try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
                    sendQueryResult(response, requestId, cursor, factory, 1000);
                }
            }
        }
    }

    private void executeColumnsTool(HttpChunkedResponse response, int requestId)
            throws PeerDisconnectedException, PeerIsSlowToReadException, SqlException {

        CharSequence tableName = transientState.tableName;
        if (tableName.length() == 0) {
            sendJsonError(response, requestId, -32602, "Missing table name");
            return;
        }

        try (SqlCompiler compiler = engine.getSqlCompiler()) {
            // QuestDB's table_columns() function shows the actual table structure
            String sql = "SELECT * FROM table_columns('" + escapeQuotes(tableName) + "')";
            CompiledQuery compiledQuery = compiler.compile(sql, sqlExecutionContext);

            try (RecordCursorFactory factory = compiledQuery.getRecordCursorFactory()) {
                try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
                    sendQueryResult(response, requestId, cursor, factory, 1000);
                }
            }
        }
    }

    private void executeStatsTool(HttpChunkedResponse response, int requestId)
            throws PeerDisconnectedException, PeerIsSlowToReadException, SqlException {

        CharSequence tableName = transientState.tableName;
        if (tableName.length() == 0) {
            sendJsonError(response, requestId, -32602, "Missing table name");
            return;
        }

        try (SqlCompiler compiler = engine.getSqlCompiler()) {
            // Use QuestDB's table_storage() function to get comprehensive stats
            String sql = "SELECT * FROM table_storage() WHERE tableName = '" + escapeQuotes(tableName) + "'";

            CompiledQuery compiledQuery = compiler.compile(sql, sqlExecutionContext);

            try (RecordCursorFactory factory = compiledQuery.getRecordCursorFactory()) {
                try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
                    sendQueryResult(response, requestId, cursor, factory, 1);
                }
            }
        }
    }

    private void executePartitionsTool(HttpChunkedResponse response, int requestId)
            throws PeerDisconnectedException, PeerIsSlowToReadException, SqlException {

        CharSequence tableName = transientState.tableName;
        if (tableName.length() == 0) {
            sendJsonError(response, requestId, -32602, "Missing table name");
            return;
        }

        try (SqlCompiler compiler = engine.getSqlCompiler()) {
            // Use QuestDB's table_partitions() function to get partition details
            String sql = "SELECT * FROM table_partitions('" + escapeQuotes(tableName) + "') ORDER BY index DESC";

            CompiledQuery compiledQuery = compiler.compile(sql, sqlExecutionContext);

            try (RecordCursorFactory factory = compiledQuery.getRecordCursorFactory()) {
                try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
                    sendQueryResult(response, requestId, cursor, factory, 20);
                }
            }
        }
    }

    private void sendQueryResult(HttpChunkedResponse response, int requestId, RecordCursor cursor,
                                 RecordCursorFactory factory, int limit)
            throws PeerDisconnectedException, PeerIsSlowToReadException {

        response.putAscii("data: {\"jsonrpc\":\"2.0\",\"id\":");
        response.put(requestId);
        response.putAscii(",\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"");

        // Build the result as formatted text
        int columnCount = factory.getMetadata().getColumnCount();
        int rowCount = 0;

        // Add header
        boolean firstCol = true;
        for (int i = 0; i < columnCount; i++) {
            if (!firstCol) {
                response.putAscii(" | ");
            }
            firstCol = false;
            response.putAscii(factory.getMetadata().getColumnName(i));
        }
        response.putAscii("\\n");

        // Add separator line
        firstCol = true;
        for (int i = 0; i < columnCount; i++) {
            if (!firstCol) {
                response.putAscii("-+-");
            }
            firstCol = false;
            String colName = factory.getMetadata().getColumnName(i);
            for (int j = 0; j < colName.length(); j++) {
                response.putAscii("-");
            }
        }
        response.putAscii("\\n");

        // Write rows
        while (cursor.hasNext() && rowCount < limit) {
            var record = cursor.getRecord();
            firstCol = true;

            for (int i = 0; i < columnCount; i++) {
                if (!firstCol) {
                    response.putAscii(" | ");
                }
                firstCol = false;

                // Get column value based on type
                switch (factory.getMetadata().getColumnType(i)) {
                    case io.questdb.cairo.ColumnType.STRING:
                        CharSequence str = record.getStrA(i);
                        if (str == null) {
                            response.putAscii("null");
                        } else {
                            response.escapeJsonStr(str);
                        }
                        break;
                    case io.questdb.cairo.ColumnType.INT:
                        response.put(record.getInt(i));
                        break;
                    case io.questdb.cairo.ColumnType.LONG:
                        response.put(record.getLong(i));
                        break;
                    case io.questdb.cairo.ColumnType.DOUBLE:
                        response.put(record.getDouble(i));
                        break;
                    case io.questdb.cairo.ColumnType.BOOLEAN:
                        response.putAscii(record.getBool(i) ? "true" : "false");
                        break;
                    case io.questdb.cairo.ColumnType.TIMESTAMP:
                        response.putISODate(record.getTimestamp(i));
                        break;
                    default:
                        response.putAscii("unknown");
                        break;
                }
            }
            response.putAscii("\\n");
            rowCount++;
        }

        if (rowCount == 0) {
            response.putAscii("No results found.");
        }

        response.putAscii("\"}],\"isError\":false}}\n\n");
        response.sendChunk(true);
    }

    private String escapeQuotes(CharSequence str) {
        if (str == null || str.length() == 0) return "";
        return str.toString().replace("'", "''");
    }

    private void sendJsonError(HttpChunkedResponse response, int id, int code, String message)
            throws PeerDisconnectedException, PeerIsSlowToReadException {

        response.putAscii("data: {\"jsonrpc\":\"2.0\",\"id\":");
        response.put(id);
        response.putAscii(",\"error\":{\"code\":");
        response.put(code);
        response.putAscii(",\"message\":\"");
        response.escapeJsonStr(message);
        response.putAscii("\"}}\n\n");
        response.sendChunk(true);
    }

    private void sendErrorResponse(HttpConnectionContext context, int status, String message)
            throws PeerDisconnectedException, PeerIsSlowToReadException {

        HttpChunkedResponse response = context.getChunkedResponse();
        response.status(status, "text/plain");
        response.sendHeader();
        response.putAscii(message);
        response.sendChunk(true);
    }
}