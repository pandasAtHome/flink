/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.formats.csv;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.serialization.BulkWriter;
import org.apache.flink.api.common.serialization.BulkWriter.Factory;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.connector.file.src.FileSourceSplit;
import org.apache.flink.connector.file.src.impl.StreamFormatAdapter;
import org.apache.flink.connector.file.src.reader.BulkFormat;
import org.apache.flink.connector.file.table.factories.BulkReaderFormatFactory;
import org.apache.flink.connector.file.table.factories.BulkWriterFormatFactory;
import org.apache.flink.connector.file.table.format.BulkDecodingFormat;
import org.apache.flink.core.fs.FileStatus;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.formats.common.Converter;
import org.apache.flink.formats.csv.RowDataToCsvConverters.RowDataToCsvConverter;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.Projection;
import org.apache.flink.table.connector.format.EncodingFormat;
import org.apache.flink.table.connector.format.FileBasedStatisticsReportableInputFormat;
import org.apache.flink.table.connector.format.ProjectableDecodingFormat;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.source.DynamicTableSource.Context;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.factories.DynamicTableFactory;
import org.apache.flink.table.plan.stats.TableStats;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.RowType;

import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.dataformat.csv.CsvMapper;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.dataformat.csv.CsvSchema;

import org.apache.commons.lang3.StringEscapeUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.apache.flink.formats.csv.CsvFormatOptions.ALLOW_COMMENTS;
import static org.apache.flink.formats.csv.CsvFormatOptions.ARRAY_ELEMENT_DELIMITER;
import static org.apache.flink.formats.csv.CsvFormatOptions.DISABLE_QUOTE_CHARACTER;
import static org.apache.flink.formats.csv.CsvFormatOptions.ESCAPE_CHARACTER;
import static org.apache.flink.formats.csv.CsvFormatOptions.FIELD_DELIMITER;
import static org.apache.flink.formats.csv.CsvFormatOptions.IGNORE_PARSE_ERRORS;
import static org.apache.flink.formats.csv.CsvFormatOptions.NULL_LITERAL;
import static org.apache.flink.formats.csv.CsvFormatOptions.QUOTE_CHARACTER;
import static org.apache.flink.util.Preconditions.checkNotNull;

/** CSV format factory for file system. */
@Internal
public class CsvFileFormatFactory implements BulkReaderFormatFactory, BulkWriterFormatFactory {

    @Override
    public String factoryIdentifier() {
        return CsvCommons.IDENTIFIER;
    }

    @Override
    public Set<ConfigOption<?>> requiredOptions() {
        return Collections.emptySet();
    }

    @Override
    public Set<ConfigOption<?>> optionalOptions() {
        return CsvCommons.optionalOptions();
    }

    @Override
    public Set<ConfigOption<?>> forwardOptions() {
        return CsvCommons.forwardOptions();
    }

    @Override
    public BulkDecodingFormat<RowData> createDecodingFormat(
            DynamicTableFactory.Context context, ReadableConfig formatOptions) {

        return new CsvBulkDecodingFormat(formatOptions);
    }

    /** CsvBulkDecodingFormat which implements {@link FileBasedStatisticsReportableInputFormat}. */
    @VisibleForTesting
    public static class CsvBulkDecodingFormat
            implements BulkDecodingFormat<RowData>,
                    ProjectableDecodingFormat<BulkFormat<RowData, FileSourceSplit>>,
                    FileBasedStatisticsReportableInputFormat {

        private final ReadableConfig formatOptions;

        public CsvBulkDecodingFormat(ReadableConfig formatOptions) {
            checkNotNull(formatOptions);
            this.formatOptions = formatOptions;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public BulkFormat<RowData, FileSourceSplit> createRuntimeDecoder(
                Context context, DataType physicalDataType, int[][] projections) {

            final DataType projectedDataType = Projection.of(projections).project(physicalDataType);
            final RowType projectedRowType = (RowType) projectedDataType.getLogicalType();

            final RowType physicalRowType = (RowType) physicalDataType.getLogicalType();
            final CsvSchema schema = buildCsvSchema(physicalRowType, formatOptions);

            final boolean ignoreParseErrors =
                    formatOptions.getOptional(IGNORE_PARSE_ERRORS).isPresent();
            final Converter<JsonNode, RowData, Void> converter =
                    (Converter)
                            new CsvToRowDataConverters(ignoreParseErrors)
                                    .createRowConverter(projectedRowType, true);
            CsvReaderFormat<RowData> csvReaderFormat =
                    new CsvReaderFormat<>(
                            () -> new CsvMapper(),
                            ignored -> schema,
                            JsonNode.class,
                            converter,
                            context.createTypeInformation(projectedDataType),
                            ignoreParseErrors);
            return new StreamFormatAdapter<>(csvReaderFormat);
        }

        @Override
        public ChangelogMode getChangelogMode() {
            return ChangelogMode.insertOnly();
        }

        @Override
        public TableStats reportStatistics(List<Path> files, DataType producedDataType) {
            // For Csv format, it's a heavy operation to obtain accurate statistics by scanning all
            // files. So, We obtain the estimated statistics by sampling, the specific way is to
            // sample the first 100 lines and calculate their row size, then compare row size with
            // total file size to get the estimated row count.
            final int totalSampleLineCnt = 100;
            try {
                long totalFileSize = 0;
                int sampledRowCnt = 0;
                long sampledRowSize = 0;
                for (Path file : files) {
                    FileSystem fs = FileSystem.get(file.toUri());
                    FileStatus status = fs.getFileStatus(file);
                    totalFileSize += status.getLen();

                    // sample the line size
                    if (sampledRowCnt < totalSampleLineCnt) {
                        try (InputStreamReader isr =
                                        new InputStreamReader(
                                                Files.newInputStream(
                                                        new File(file.toUri()).toPath()));
                                BufferedReader br = new BufferedReader(isr)) {
                            String line;
                            while (sampledRowCnt < totalSampleLineCnt
                                    && (line = br.readLine()) != null) {
                                sampledRowCnt += 1;
                                sampledRowSize +=
                                        (line.getBytes(StandardCharsets.UTF_8).length + 1);
                            }
                        }
                    }
                }

                // If line break is "\r\n", br.readLine() will ignore '\n' which make sampledRowSize
                // smaller than totalFileSize. This will influence test result.
                if (sampledRowCnt < totalSampleLineCnt) {
                    sampledRowSize = totalFileSize;
                }
                if (sampledRowSize == 0) {
                    return TableStats.UNKNOWN;
                }

                int realSampledLineCnt = Math.min(totalSampleLineCnt, sampledRowCnt);
                long estimatedRowCount = totalFileSize * realSampledLineCnt / sampledRowSize;
                return new TableStats(estimatedRowCount);
            } catch (Exception e) {
                return TableStats.UNKNOWN;
            }
        }
    }

    @Override
    public EncodingFormat<Factory<RowData>> createEncodingFormat(
            DynamicTableFactory.Context context, ReadableConfig formatOptions) {
        return new EncodingFormat<BulkWriter.Factory<RowData>>() {
            @Override
            public BulkWriter.Factory<RowData> createRuntimeEncoder(
                    DynamicTableSink.Context context, DataType physicalDataType) {

                final RowType rowType = (RowType) physicalDataType.getLogicalType();
                final CsvSchema schema = buildCsvSchema(rowType, formatOptions);

                final RowDataToCsvConverter converter =
                        RowDataToCsvConverters.createRowConverter(rowType);

                final CsvMapper mapper = new CsvMapper();
                final ObjectNode container = mapper.createObjectNode();

                final RowDataToCsvConverter.RowDataToCsvFormatConverterContext converterContext =
                        new RowDataToCsvConverter.RowDataToCsvFormatConverterContext(
                                mapper, container);

                return out ->
                        CsvBulkWriter.forSchema(mapper, schema, converter, converterContext, out);
            }

            @Override
            public ChangelogMode getChangelogMode() {
                return ChangelogMode.insertOnly();
            }
        };
    }

    private static CsvSchema buildCsvSchema(RowType rowType, ReadableConfig options) {
        final CsvSchema csvSchema = CsvRowSchemaConverter.convert(rowType);
        final CsvSchema.Builder csvBuilder = csvSchema.rebuild();
        // format properties
        options.getOptional(FIELD_DELIMITER)
                .map(s -> StringEscapeUtils.unescapeJava(s).charAt(0))
                .ifPresent(csvBuilder::setColumnSeparator);

        if (options.get(DISABLE_QUOTE_CHARACTER)) {
            csvBuilder.disableQuoteChar();
        } else {
            options.getOptional(QUOTE_CHARACTER)
                    .map(s -> s.charAt(0))
                    .ifPresent(csvBuilder::setQuoteChar);
        }

        options.getOptional(ALLOW_COMMENTS).ifPresent(csvBuilder::setAllowComments);

        options.getOptional(ARRAY_ELEMENT_DELIMITER)
                .ifPresent(csvBuilder::setArrayElementSeparator);

        options.getOptional(ESCAPE_CHARACTER)
                .map(s -> s.charAt(0))
                .ifPresent(csvBuilder::setEscapeChar);

        options.getOptional(NULL_LITERAL).ifPresent(csvBuilder::setNullValue);

        return csvBuilder.build();
    }
}
