/**
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
package org.apache.tez.runtime.library.common.shuffle.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BoundedByteArrayOutputStream;
import org.apache.tez.runtime.library.common.InputAttemptIdentifier;
import org.apache.tez.runtime.library.common.task.local.output.TezTaskOutputFiles;


class MapOutput {
  private static final Log LOG = LogFactory.getLog(MapOutput.class);
  private static AtomicInteger ID = new AtomicInteger(0);
  
  public static enum Type {
    WAIT,
    MEMORY,
    DISK
  }
  
  private InputAttemptIdentifier attemptIdentifier;
  private final int id;
  
  private final MergeManager merger;
  
  private final long size;
  
  private final byte[] memory;
  private BoundedByteArrayOutputStream byteStream;
  
  private final FileSystem localFS;
  private final Path tmpOutputPath;
  private final Path outputPath;
  private final OutputStream disk; 
  
  private final Type type;
  
  private final boolean primaryMapOutput;
  
  MapOutput(InputAttemptIdentifier attemptIdentifier, MergeManager merger, long size, 
            Configuration conf, LocalDirAllocator localDirAllocator,
            int fetcher, boolean primaryMapOutput, 
            TezTaskOutputFiles mapOutputFile)
         throws IOException {
    this.id = ID.incrementAndGet();
    this.attemptIdentifier = attemptIdentifier;
    this.merger = merger;

    type = Type.DISK;

    memory = null;
    byteStream = null;

    this.size = size;
    
    this.localFS = FileSystem.getLocal(conf);
    outputPath =
      mapOutputFile.getInputFileForWrite(this.attemptIdentifier.getInputIdentifier().getInputIndex(), size);
    tmpOutputPath = outputPath.suffix(String.valueOf(fetcher));

    disk = localFS.create(tmpOutputPath);
    
    this.primaryMapOutput = primaryMapOutput;
  }
  
  MapOutput(InputAttemptIdentifier attemptIdentifier, MergeManager merger, int size, 
            boolean primaryMapOutput) {
    this.id = ID.incrementAndGet();
    this.attemptIdentifier = attemptIdentifier;
    this.merger = merger;

    type = Type.MEMORY;
    byteStream = new BoundedByteArrayOutputStream(size);
    memory = byteStream.getBuffer();

    this.size = size;
    
    localFS = null;
    disk = null;
    outputPath = null;
    tmpOutputPath = null;
    
    this.primaryMapOutput = primaryMapOutput;
  }

  public MapOutput(InputAttemptIdentifier attemptIdentifier) {
    this.id = ID.incrementAndGet();
    this.attemptIdentifier = attemptIdentifier;

    type = Type.WAIT;
    merger = null;
    memory = null;
    byteStream = null;
    
    size = -1;
    
    localFS = null;
    disk = null;
    outputPath = null;
    tmpOutputPath = null;

    this.primaryMapOutput = false;
}
  
  public boolean isPrimaryMapOutput() {
    return primaryMapOutput;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof MapOutput) {
      return id == ((MapOutput)obj).id;
    }
    return false;
  }

  @Override
  public int hashCode() {
    return id;
  }

  public Path getOutputPath() {
    return outputPath;
  }

  public byte[] getMemory() {
    return memory;
  }

  public BoundedByteArrayOutputStream getArrayStream() {
    return byteStream;
  }
  
  public OutputStream getDisk() {
    return disk;
  }

  public InputAttemptIdentifier getAttemptIdentifier() {
    return this.attemptIdentifier;
  }

  public Type getType() {
    return type;
  }

  public long getSize() {
    return size;
  }

  public void commit() throws IOException {
    if (type == Type.MEMORY) {
      merger.closeInMemoryFile(this);
    } else if (type == Type.DISK) {
      localFS.rename(tmpOutputPath, outputPath);
      merger.closeOnDiskFile(outputPath);
    } else {
      throw new IOException("Cannot commit MapOutput of type WAIT!");
    }
  }
  
  public void abort() {
    if (type == Type.MEMORY) {
      merger.unreserve(memory.length);
    } else if (type == Type.DISK) {
      try {
        localFS.delete(tmpOutputPath, false);
      } catch (IOException ie) {
        LOG.info("failure to clean up " + tmpOutputPath, ie);
      }
    } else {
      throw new IllegalArgumentException
                   ("Cannot commit MapOutput with of type WAIT!");
    }
  }
  
  public String toString() {
    return "MapOutput( AttemptIdentifier: " + attemptIdentifier + ", Type: " + type + ")";
  }
  
  public static class MapOutputComparator 
  implements Comparator<MapOutput> {
    public int compare(MapOutput o1, MapOutput o2) {
      if (o1.id == o2.id) { 
        return 0;
      }
      
      if (o1.size < o2.size) {
        return -1;
      } else if (o1.size > o2.size) {
        return 1;
      }
      
      if (o1.id < o2.id) {
        return -1;
      } else {
        return 1;
      
      }
    }
  }
  
}
