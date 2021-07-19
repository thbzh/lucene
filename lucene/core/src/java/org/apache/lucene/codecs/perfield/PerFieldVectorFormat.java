/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.codecs.perfield;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;
import org.apache.lucene.codecs.VectorFormat;
import org.apache.lucene.codecs.VectorReader;
import org.apache.lucene.codecs.VectorWriter;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.VectorValues;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.util.IOUtils;

/**
 * Enables per field numeric vector support.
 *
 * <p>Note, when extending this class, the name ({@link #getName}) is written into the index. In
 * order for the field to be read, the name must resolve to your implementation via {@link
 * #forName(String)}. This method uses Java's {@link ServiceLoader Service Provider Interface} to
 * resolve format names.
 *
 * <p>Files written by each numeric vectors format have an additional suffix containing the format
 * name. For example, in a per-field configuration instead of <code>_1.dat</code> filenames would
 * look like <code>_1_Lucene40_0.dat</code>.
 *
 * @see ServiceLoader
 * @lucene.experimental
 */
public abstract class PerFieldVectorFormat extends VectorFormat {
  /** Name of this {@link VectorFormat}. */
  public static final String PER_FIELD_NAME = "PerFieldVectors90";

  /** {@link FieldInfo} attribute name used to store the format name for each field. */
  public static final String PER_FIELD_FORMAT_KEY =
      PerFieldVectorFormat.class.getSimpleName() + ".format";

  /** {@link FieldInfo} attribute name used to store the segment suffix name for each field. */
  public static final String PER_FIELD_SUFFIX_KEY =
      PerFieldVectorFormat.class.getSimpleName() + ".suffix";

  /** Sole constructor. */
  protected PerFieldVectorFormat() {
    super(PER_FIELD_NAME);
  }

  @Override
  public VectorWriter fieldsWriter(SegmentWriteState state) throws IOException {
    return new FieldsWriter(state);
  }

  @Override
  public VectorReader fieldsReader(SegmentReadState state) throws IOException {
    return new FieldsReader(state);
  }

  /**
   * Returns the numeric vector format that should be used for writing new segments of <code>field
   * </code>.
   *
   * <p>The field to format mapping is written to the index, so this method is only invoked when
   * writing, not when reading.
   */
  public abstract VectorFormat getVectorFormatForField(String field);

  private class FieldsWriter extends VectorWriter {
    private final Map<VectorFormat, WriterAndSuffix> formats;
    private final Map<String, Integer> suffixes = new HashMap<>();
    private final SegmentWriteState segmentWriteState;

    FieldsWriter(SegmentWriteState segmentWriteState) {
      this.segmentWriteState = segmentWriteState;
      formats = new HashMap<>();
    }

    @Override
    public void writeField(FieldInfo fieldInfo, VectorValues values) throws IOException {
      getInstance(fieldInfo).writeField(fieldInfo, values);
    }

    @Override
    public void finish() throws IOException {
      for (WriterAndSuffix was : formats.values()) {
        was.writer.finish();
      }
    }

    @Override
    public void close() throws IOException {
      IOUtils.close(formats.values());
    }

    private VectorWriter getInstance(FieldInfo field) throws IOException {
      VectorFormat format = getVectorFormatForField(field.name);
      if (format == null) {
        throw new IllegalStateException(
            "invalid null VectorFormat for field=\"" + field.name + "\"");
      }
      final String formatName = format.getName();

      field.putAttribute(PER_FIELD_FORMAT_KEY, formatName);
      Integer suffix = null;

      WriterAndSuffix writerAndSuffix = formats.get(format);
      if (writerAndSuffix == null) {
        // First time we are seeing this format; create a new instance

        String suffixAtt = field.getAttribute(PER_FIELD_SUFFIX_KEY);
        if (suffixAtt != null) {
          suffix = Integer.valueOf(suffixAtt);
        }

        if (suffix == null) {
          // bump the suffix
          suffix = suffixes.get(formatName);
          if (suffix == null) {
            suffix = 0;
          } else {
            suffix = suffix + 1;
          }
        }
        suffixes.put(formatName, suffix);

        String segmentSuffix =
            getFullSegmentSuffix(
                segmentWriteState.segmentSuffix, getSuffix(formatName, Integer.toString(suffix)));
        writerAndSuffix =
            new WriterAndSuffix(
                format.fieldsWriter(new SegmentWriteState(segmentWriteState, segmentSuffix)),
                suffix);
        formats.put(format, writerAndSuffix);
      } else {
        // we've already seen this format, so just grab its suffix
        assert suffixes.containsKey(formatName);
        suffix = writerAndSuffix.suffix;
      }

      field.putAttribute(PER_FIELD_SUFFIX_KEY, Integer.toString(suffix));
      return writerAndSuffix.writer;
    }
  }

  /** VectorReader that can wrap multiple delegate readers, selected by field. */
  public static class FieldsReader extends VectorReader {

    private final Map<String, VectorReader> fields = new TreeMap<>();

    /**
     * Create a FieldsReader over a segment, opening VectorReaders for each VectorFormat specified
     * by the indexed numeric vector fields.
     *
     * @param readState defines the fields
     * @throws IOException if one of the delegate readers throws
     */
    public FieldsReader(final SegmentReadState readState) throws IOException {

      // Init each unique format:
      boolean success = false;
      Map<String, VectorReader> formats = new HashMap<>();
      try {
        // Read field name -> format name
        for (FieldInfo fi : readState.fieldInfos) {
          if (fi.hasVectorValues()) {
            final String fieldName = fi.name;
            final String formatName = fi.getAttribute(PER_FIELD_FORMAT_KEY);
            if (formatName != null) {
              // null formatName means the field is in fieldInfos, but has no vectors!
              final String suffix = fi.getAttribute(PER_FIELD_SUFFIX_KEY);
              if (suffix == null) {
                throw new IllegalStateException(
                    "missing attribute: " + PER_FIELD_SUFFIX_KEY + " for field: " + fieldName);
              }
              VectorFormat format = VectorFormat.forName(formatName);
              String segmentSuffix =
                  getFullSegmentSuffix(readState.segmentSuffix, getSuffix(formatName, suffix));
              if (!formats.containsKey(segmentSuffix)) {
                formats.put(
                    segmentSuffix,
                    format.fieldsReader(new SegmentReadState(readState, segmentSuffix)));
              }
              fields.put(fieldName, formats.get(segmentSuffix));
            }
          }
        }
        success = true;
      } finally {
        if (!success) {
          IOUtils.closeWhileHandlingException(formats.values());
        }
      }
    }

    /**
     * Return the underlying VectorReader for the given field
     *
     * @param field the name of a numeric vector field
     */
    public VectorReader getFieldReader(String field) {
      return fields.get(field);
    }

    @Override
    public void checkIntegrity() throws IOException {
      for (VectorReader reader : fields.values()) {
        reader.checkIntegrity();
      }
    }

    @Override
    public VectorValues getVectorValues(String field) throws IOException {
      VectorReader vectorReader = fields.get(field);
      if (vectorReader == null) {
        return null;
      } else {
        return vectorReader.getVectorValues(field);
      }
    }

    @Override
    public TopDocs search(String field, float[] target, int k) throws IOException {
      VectorReader vectorReader = fields.get(field);
      if (vectorReader == null) {
        return new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), new ScoreDoc[0]);
      } else {
        return vectorReader.search(field, target, k);
      }
    }

    @Override
    public void close() throws IOException {
      IOUtils.close(fields.values());
    }

    @Override
    public long ramBytesUsed() {
      long total = 0;
      for (VectorReader reader : fields.values()) {
        total += reader.ramBytesUsed();
      }
      return total;
    }
  }

  static String getSuffix(String formatName, String suffix) {
    return formatName + "_" + suffix;
  }

  static String getFullSegmentSuffix(String outerSegmentSuffix, String segmentSuffix) {
    if (outerSegmentSuffix.length() == 0) {
      return segmentSuffix;
    } else {
      return outerSegmentSuffix + "_" + segmentSuffix;
    }
  }

  private static class WriterAndSuffix implements Closeable {
    final VectorWriter writer;
    final int suffix;

    WriterAndSuffix(VectorWriter writer, int suffix) {
      this.writer = writer;
      this.suffix = suffix;
    }

    @Override
    public void close() throws IOException {
      writer.close();
    }
  }
}
