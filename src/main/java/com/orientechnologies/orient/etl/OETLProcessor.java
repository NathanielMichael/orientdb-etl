/*
 *
 *  * Copyright 2010-2014 Orient Technologies LTD (info(at)orientechnologies.com)
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.orientechnologies.orient.etl;

import com.orientechnologies.common.io.OIOUtils;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.core.Orient;
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx;
import com.orientechnologies.orient.core.exception.OConfigurationException;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.etl.extract.OExtractor;
import com.orientechnologies.orient.etl.loader.OLoader;
import com.orientechnologies.orient.etl.transform.OTransformer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimerTask;

/**
 * ETL processor class.
 * 
 * @author Luca Garulli (l.garulli-at-orientechnologies.com)
 */
public class OETLProcessor implements OETLComponent {
  protected final OExtractor           extractor;
  protected final List<OTransformer>   transformers;
  protected final OLoader              loader;
  protected final OETLComponentFactory factory     = new OETLComponentFactory();
  protected long                       startTime;
  protected long                       elapsed;

  protected long                       dumpEveryMs = 0;
  protected OETLProcessorStats         stats       = new OETLProcessorStats();

  protected class OETLProcessorStats {
    public long lastProgress = 0;
    public long lastLap      = 0;
    public long warnings     = 0;
    public long errors       = 0;
  }

  public OETLProcessor(final OExtractor iExtractor, final OTransformer[] iTransformers, final OLoader iLoader) {
    extractor = iExtractor;
    transformers = Arrays.asList(iTransformers);
    loader = iLoader;
  }

  public OETLProcessor(final OExtractor iExtractor, final List<OTransformer> iTransformers, final OLoader iLoader) {
    extractor = iExtractor;
    transformers = iTransformers;
    loader = iLoader;
  }

  public OETLProcessor(final Object iExtractor, final Object iTransformer, final Object iLoader) {
    extractor = null;
    transformers = null;
    loader = null;
  }

  public static void main(final String[] args) {
    String dbURL = null;
    String dbUser = "admin";
    String dbPassword = "admin";
    boolean dbAutoCreate = true;

    Object cfgExtract = null;
    Object cfgTransformers = null;
    Object cfgLoaders = null;

    for (int i = 0; i < args.length; ++i) {
      final String arg = args[i];

      if (arg.equalsIgnoreCase("-dbUrl"))
        dbURL = args[++i];
      else if (arg.equalsIgnoreCase("-dbUser"))
        dbUser = args[++i];
      else if (arg.equalsIgnoreCase("-dbPassword"))
        dbPassword = args[++i];
      else if (arg.equalsIgnoreCase("-dbAutoCreate"))
        dbAutoCreate = Boolean.parseBoolean(args[++i]);
      else if (arg.equalsIgnoreCase("-config")) {
        final String cfgPath = args[++i];
        try {
          final String config = OIOUtils.readFileAsString(new File(cfgPath));
          final ODocument cfg = new ODocument().fromJSON(config);

          cfgExtract = cfg.field("extractors");
          cfgTransformers = cfg.field("transformers");
          cfgLoaders = cfg.field("loaders");

        } catch (IOException e) {
          throw new OConfigurationException("Error on loading config file: " + cfgPath);
        }
      } else if (arg.equalsIgnoreCase("-e")) {
        cfgExtract = new ODocument().fromJSON(args[++i]);
      } else if (arg.equalsIgnoreCase("-t")) {
        cfgTransformers = new ODocument().fromJSON(args[++i]);
      } else if (arg.equalsIgnoreCase("-l")) {
        cfgLoaders = new ODocument().fromJSON(args[++i]);
      }
    }

    if (dbURL == null)
      throw new IllegalArgumentException("Argument dbURL not found");

    final ODatabaseDocumentTx db = new ODatabaseDocumentTx(dbURL);

    if (db.exists())
      db.open(dbUser, dbPassword);
    else {
      if (dbAutoCreate)
        db.create();
      else
        throw new IllegalArgumentException("Database '" + dbURL + "' not exists and 'dbAutoCreate' setting is false");
    }

    final List<OExtractor> extractors = new ArrayList<OExtractor>();
    final List<OTransformer> transformers = new ArrayList<OTransformer>();
    final List<OLoader> loaders = new ArrayList<OLoader>();

    if (cfgExtract instanceof Iterable) {
      for (Object o : (Iterable) cfgExtract)
        extractors.add((OExtractor) o);
    } else if (cfgExtract instanceof OExtractor)
      extractors.add((OExtractor) cfgExtract);

    final OETLProcessor processor = new OETLProcessor(extractors, transformers, loaders);

    processor.configure(db, null);
    processor.execute();
  }

  public void execute() {
    begin();

    extractor.extract();

    Object current = null;
    while (extractor.hasNext()) {
      // EXTRACTOR
      current = extractor.next();

      // TRANSFORM
      for (OTransformer t : transformers) {
        current = t.transform(current);
        if (current == null)
          break;
      }

      // LOAD
      loader.load(current);
    }

    end();
  }

  @Override
  public void configure(final ODatabaseDocumentTx iDatabase, final ODocument iConfiguration) {
    extractor.configure(iDatabase, iConfiguration);
    for (OTransformer t : transformers)
      t.configure(iDatabase, iConfiguration);
    loader.configure(iDatabase, iConfiguration);
  }

  @Override
  public String getName() {
    return "Processor";
  }

  protected void end() {
    elapsed = System.currentTimeMillis() - startTime;
  }

  protected void begin() {
    if (dumpEveryMs > 0) {
      Orient.instance().getTimer().schedule(new TimerTask() {
        @Override
        public void run() {
          dumpProgress();
        }
      }, dumpEveryMs, dumpEveryMs);

      startTime = System.currentTimeMillis();
    }
  }

  protected void dumpProgress() {
    final long now = System.currentTimeMillis();
    final long extractorProgress = extractor.getProgress();
    final long extractorTotal = extractor.getTotal();

    final float extractorItemsSec = (extractorProgress - stats.lastProgress) * 1000f / (now - stats.lastLap);

    OLogManager.instance().info(this,
        "+ %3.2f%% -> extracted %d/%d (%.2f items/sec) -> transformed %d -> loaded %d [%d warnings, %d errors]",
        (extractorProgress * 100 / extractorTotal), extractorProgress, extractorTotal, extractorItemsSec, extractor.getProgress(),
        loader.getProgress(), stats.warnings, stats.errors);

    stats.lastLap = now;
  }
}