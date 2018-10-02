/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.hydrator.format.plugin;

import co.cask.cdap.api.data.batch.Output;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.dataset.lib.KeyValue;
import co.cask.cdap.api.plugin.PluginConfig;
import co.cask.cdap.etl.api.Emitter;
import co.cask.cdap.etl.api.PipelineConfigurer;
import co.cask.cdap.etl.api.batch.BatchRuntimeContext;
import co.cask.cdap.etl.api.batch.BatchSink;
import co.cask.cdap.etl.api.batch.BatchSinkContext;
import co.cask.hydrator.common.LineageRecorder;
import co.cask.hydrator.common.batch.sink.SinkOutputFormatProvider;
import co.cask.hydrator.format.output.FileOutputFormatter;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes data to files on Google Cloud Storage.
 *
 * @param <T> the type of plugin config
 */
public abstract class AbstractFileSink<T extends PluginConfig & FileSinkProperties>
  extends BatchSink<StructuredRecord, Object, Object> {
  private final T config;
  private FileOutputFormatter<Object, Object> outputFormatter;

  protected AbstractFileSink(T config) {
    this.config = config;
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) {
    config.validate();
  }

  @Override
  public final void prepareRun(BatchSinkContext context) {
    config.validate();

    // set format specific properties.
    outputFormatter = config.getFormat().getFileOutputFormatter(config.getProperties().getProperties(),
                                                                config.getSchema());
    if (outputFormatter == null) {
      // should never happen, as validation should enforce the allowed formats
      throw new IllegalArgumentException(String.format("Format '%s' cannot be used to write data.",
                                                       config.getFormat()));
    }

    // record field level lineage information
    // needs to happen before context.addOutput(), otherwise an external dataset without schema will be created.
    Schema schema = config.getSchema();
    if (schema == null) {
      schema = context.getInputSchema();
    }
    LineageRecorder lineageRecorder = new LineageRecorder(context, config.getReferenceName());
    lineageRecorder.createExternalDataset(schema);
    if (schema != null && schema.getFields() != null && !schema.getFields().isEmpty()) {
      recordLineage(lineageRecorder,
                    schema.getFields().stream().map(Schema.Field::getName).collect(Collectors.toList()));
    }

    Map<String, String> outputProperties = new HashMap<>(outputFormatter.getFormatConfig());
    outputProperties.putAll(getFileSystemProperties(context));
    outputProperties.put(FileOutputFormat.OUTDIR, getOutputDir(context.getLogicalStartTime()));

    context.addOutput(Output.of(config.getReferenceName(),
                                new SinkOutputFormatProvider(outputFormatter.getFormatClassName(), outputProperties)));
  }

  @Override
  public void initialize(BatchRuntimeContext context) throws Exception {
    super.initialize(context);
    outputFormatter = config.getFormat().getFileOutputFormatter(config.getProperties().getProperties(),
                                                                config.getSchema());
  }

  @Override
  public void transform(StructuredRecord input, Emitter<KeyValue<Object, Object>> emitter) throws Exception {
    emitter.emit(outputFormatter.transform(input));
  }

  /**
   * Override this to provide any additional Configuration properties that are required by the FileSystem.
   * For example, if the FileSystem requires setting properties for credentials, those should be returned by
   * this method.
   */
  protected Map<String, String> getFileSystemProperties(BatchSinkContext context) {
    return Collections.emptyMap();
  }

  /**
   * Override this to specify a custom field level operation name and description.
   */
  protected void recordLineage(LineageRecorder lineageRecorder, List<String> outputFields) {
    lineageRecorder.recordWrite("Write", String.format("Wrote to %s files.", config.getFormat().name().toLowerCase()),
                                outputFields);
  }

  private String getOutputDir(long logicalStartTime) {
    String suffix = config.getSuffix();
    String timeSuffix = suffix == null || suffix.isEmpty() ? "" : new SimpleDateFormat(suffix).format(logicalStartTime);
    return String.format("%s/%s", config.getPath(), timeSuffix);
  }
}
