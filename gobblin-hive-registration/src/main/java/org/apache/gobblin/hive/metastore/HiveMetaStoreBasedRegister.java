/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.hive.metastore;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.apache.gobblin.hive.AutoCloseableHiveLock;
import org.apache.gobblin.kafka.schemareg.KafkaSchemaRegistry;
import org.apache.gobblin.kafka.schemareg.KafkaSchemaRegistryFactory;
import org.apache.gobblin.kafka.schemareg.SchemaRegistryException;
import org.apache.gobblin.source.extractor.extract.kafka.KafkaSource;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.AlreadyExistsException;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.serde2.avro.AvroSerdeUtils;
import org.apache.thrift.TException;
import org.joda.time.DateTime;

import com.codahale.metrics.Timer;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.primitives.Ints;

import org.apache.gobblin.annotation.Alpha;
import org.apache.gobblin.configuration.State;
import org.apache.gobblin.hive.HiveMetaStoreClientFactory;
import org.apache.gobblin.hive.HiveLock;
import org.apache.gobblin.hive.HiveMetastoreClientPool;
import org.apache.gobblin.hive.HivePartition;
import org.apache.gobblin.hive.HiveRegProps;
import org.apache.gobblin.hive.HiveRegister;
import org.apache.gobblin.hive.HiveRegistrationUnit.Column;
import org.apache.gobblin.hive.HiveTable;
import org.apache.gobblin.hive.spec.HiveSpec;
import org.apache.gobblin.metrics.GobblinMetrics;
import org.apache.gobblin.metrics.GobblinMetricsRegistry;
import org.apache.gobblin.metrics.MetricContext;
import org.apache.gobblin.metrics.event.EventSubmitter;
import org.apache.gobblin.util.AutoReturnableObject;


/**
 * An implementation of {@link HiveRegister} that uses {@link IMetaStoreClient} for Hive registration.
 *
 * <p>
 *   An instance of this class is constructed with a {@link State} object or obtained via
 *   {@link HiveRegister#get(State)}. Property {@link HiveRegProps#HIVE_DB_ROOT_DIR} is required for registering
 *   a table or a partition if the database does not exist.
 * </p>
 *
 * <p>
 *   The {@link #register(HiveSpec)} method is asynchronous and returns immediately. Registration is performed in a
 *   thread pool whose size is controlled by {@link HiveRegProps#HIVE_REGISTER_THREADS}.
 * </p>
 *
 * @author Ziyang Liu
 */
@Slf4j
@Alpha
public class HiveMetaStoreBasedRegister extends HiveRegister {

  public static final String HIVE_REGISTER_METRICS_PREFIX = "hiveRegister.";
  public static final String ADD_PARTITION_TIMER = HIVE_REGISTER_METRICS_PREFIX + "addPartitionTimerTimer";
  public static final String GET_HIVE_PARTITION = HIVE_REGISTER_METRICS_PREFIX + "getPartitionTimer";
  public static final String ALTER_PARTITION = HIVE_REGISTER_METRICS_PREFIX + "alterPartitionTimer";
  public static final String TABLE_EXISTS = HIVE_REGISTER_METRICS_PREFIX + "tableExistsTimer";
  public static final String ALTER_TABLE = HIVE_REGISTER_METRICS_PREFIX + "alterTableTimer";
  public static final String GET_HIVE_DATABASE = HIVE_REGISTER_METRICS_PREFIX + "getDatabaseTimer";
  public static final String CREATE_HIVE_DATABASE = HIVE_REGISTER_METRICS_PREFIX + "createDatabaseTimer";
  public static final String CREATE_HIVE_TABLE = HIVE_REGISTER_METRICS_PREFIX + "createTableTimer";
  public static final String GET_HIVE_TABLE = HIVE_REGISTER_METRICS_PREFIX + "getTableTimer";
  public static final String GET_AND_SET_LATEST_SCHEMA = HIVE_REGISTER_METRICS_PREFIX + "getAndSetLatestSchemaTimer";
  public static final String DROP_TABLE = HIVE_REGISTER_METRICS_PREFIX + "dropTableTimer";
  public static final String PATH_REGISTER_TIMER = HIVE_REGISTER_METRICS_PREFIX + "pathRegisterTimer";
  public static final String SKIP_PARTITION_DIFF_COMPUTATION = HIVE_REGISTER_METRICS_PREFIX + "skip.partition.diff.computation";
  public static final String FETCH_LATEST_SCHEMA = HIVE_REGISTER_METRICS_PREFIX + "fetchLatestSchemaFromSchemaRegistry";
  //A config which when enabled checks for the existence of a partition in Hive before adding the partition.
  // This is done to minimize the add_partition calls sent to Hive.
  public static final String REGISTER_PARTITION_WITH_PULL_MODE = HIVE_REGISTER_METRICS_PREFIX + "registerPartitionWithPullMode";
  /**
   * To reduce lock aquisition and RPC to metaStoreClient, we cache the result of query regarding to
   * the existence of databases and tables in {@link #tableAndDbExistenceCache},
   * so that for databases/tables existed in cache, a RPC for query the existence can be saved.
   *
   * We make this optimization configurable by setting {@link #OPTIMIZED_CHECK_ENABLED} to be true.
   */
  public static final String OPTIMIZED_CHECK_ENABLED = "hiveRegister.cacheDbTableExistence";

  private final HiveMetastoreClientPool clientPool;
  private final HiveLock locks;
  private final EventSubmitter eventSubmitter;
  private final MetricContext metricContext;
  private final boolean shouldUpdateLatestSchema;
  private final boolean registerPartitionWithPullMode;

  /**
   * Local cache that contains records for both databases and tables.
   * To distinguish tables with the same name but in different databases,
   * use the <databaseName>:<tableName> as the key.
   *
   * The value(true/false) in cache doesn't really matter, the existence of entry in cache guarantee the table is existed on hive.
   * The value in k-v pair in cache indicates:
   * when the first time a table/database is loaded into the cache, whether they existed on the remote hiveMetaStore side.
   */
  CacheLoader<String, Boolean> cacheLoader = new CacheLoader<String, Boolean>() {
  @Override
    public Boolean load(String key) throws Exception {
      return true;
    }
  };
  Cache<String, Boolean> tableAndDbExistenceCache = CacheBuilder.newBuilder().build(cacheLoader);


  private final boolean optimizedChecks;
  //If this is true, after we know the partition is existing, we will skip the partition in stead of getting the existing
  // partition and computing the diff to see if it needs to be updated. Use this only when you can make sure the metadata
  //for a partition is immutable
  private final boolean skipDiffComputation;

  private Optional<KafkaSchemaRegistry> schemaRegistry = Optional.absent();
  private String topicName = "";
  public HiveMetaStoreBasedRegister(State state, Optional<String> metastoreURI) throws IOException {
    super(state);
    this.locks = new HiveLock(state.getProperties());

    this.optimizedChecks = state.getPropAsBoolean(this.OPTIMIZED_CHECK_ENABLED, true);
    this.skipDiffComputation = state.getPropAsBoolean(this.SKIP_PARTITION_DIFF_COMPUTATION, false);
    this.shouldUpdateLatestSchema = state.getPropAsBoolean(this.FETCH_LATEST_SCHEMA, false);
    this.registerPartitionWithPullMode = state.getPropAsBoolean(this.REGISTER_PARTITION_WITH_PULL_MODE, false);
    if(state.getPropAsBoolean(this.FETCH_LATEST_SCHEMA, false)) {
      this.schemaRegistry = Optional.of(KafkaSchemaRegistryFactory.getSchemaRegistry(state.getProperties()));
      topicName = state.getProp(KafkaSource.TOPIC_NAME);
    }

    GenericObjectPoolConfig config = new GenericObjectPoolConfig();
    config.setMaxTotal(this.props.getNumThreads());
    config.setMaxIdle(this.props.getNumThreads());
    this.clientPool = HiveMetastoreClientPool.get(this.props.getProperties(), metastoreURI);

    this.metricContext =
        GobblinMetricsRegistry.getInstance().getMetricContext(state, HiveMetaStoreBasedRegister.class, GobblinMetrics.getCustomTagsFromState(state));

    this.eventSubmitter = new EventSubmitter.Builder(this.metricContext, "org.apache.gobblin.hive.HiveMetaStoreBasedRegister").build();
  }

  @Override
  protected void registerPath(HiveSpec spec) throws IOException {
    try (Timer.Context context = this.metricContext.timer(PATH_REGISTER_TIMER).time();
        AutoReturnableObject<IMetaStoreClient> client = this.clientPool.getClient()) {
      Table table = HiveMetaStoreUtils.getTable(spec.getTable());

      createDbIfNotExists(client.get(), table.getDbName());
      createOrAlterTable(client.get(), table, spec);

      Optional<HivePartition> partition = spec.getPartition();
      if (partition.isPresent()) {
        addOrAlterPartition(client.get(), table, partition.get());
      }
      HiveMetaStoreEventHelper.submitSuccessfulPathRegistration(eventSubmitter, spec);
    } catch (TException e) {
      HiveMetaStoreEventHelper.submitFailedPathRegistration(eventSubmitter, spec, e);
      throw new IOException(e);
    }
  }
  //TODO: We need to find a better to get the latest schema
  private void updateSchema(HiveSpec spec, Table table) throws IOException{

    if (this.schemaRegistry.isPresent()) {
      try (Timer.Context context = this.metricContext.timer(GET_AND_SET_LATEST_SCHEMA).time()) {
        String latestSchema = this.schemaRegistry.get().getLatestSchema(topicName).toString();
        spec.getTable().getSerDeProps().setProp(AvroSerdeUtils.AvroTableProperties.SCHEMA_LITERAL.getPropName(), latestSchema);
        table.getSd().setSerdeInfo(HiveMetaStoreUtils.getSerDeInfo(spec.getTable()));
      } catch (SchemaRegistryException | IOException e) {
        log.error(String.format("Error when fetch latest schema for topic %s", topicName), e);
        throw new IOException(e);
      }
    }
  }



  /**
   * If table existed on Hive side will return false;
   * Or will create the table thru. RPC and return retVal from remote MetaStore.
   */
  private boolean ensureHiveTableExistenceBeforeAlternation(String tableName, String dbName, IMetaStoreClient client,
      Table table, HiveSpec spec) throws TException, IOException{
    try (AutoCloseableHiveLock lock = this.locks.getTableLock(dbName, tableName)) {
      try {
        try (Timer.Context context = this.metricContext.timer(CREATE_HIVE_TABLE).time()) {
          client.createTable(getTableWithCreateTimeNow(table));
          log.info(String.format("Created Hive table %s in db %s", tableName, dbName));
          return true;
        } catch (AlreadyExistsException e) {
        }
      }catch (TException e) {
        log.error(
            String.format("Unable to create Hive table %s in db %s: " + e.getMessage(), tableName, dbName), e);
        throw e;
      }

      log.info("Table {} already exists in db {}.", tableName, dbName);
      try {
        HiveTable existingTable;
        try (Timer.Context context = this.metricContext.timer(GET_HIVE_TABLE).time()) {
          existingTable = HiveMetaStoreUtils.getHiveTable(client.getTable(dbName, tableName));
        }
        if(shouldUpdateLatestSchema) {
          updateSchema(spec, table);
        }
        if (needToUpdateTable(existingTable, HiveMetaStoreUtils.getHiveTable(table))) {
          try (Timer.Context context = this.metricContext.timer(ALTER_TABLE).time()) {
            client.alter_table(dbName, tableName, getNewTblByMergingExistingTblProps(table, existingTable));
          }
          log.info(String.format("updated Hive table %s in db %s", tableName, dbName));
        }
      } catch (TException e2) {
        log.error(
            String.format("Unable to create or alter Hive table %s in db %s: " + e2.getMessage(), tableName, dbName),
            e2);
        throw e2;
      }
      // When the logic up to here it means table already existed in db and alteration happen. Return false.
      return false;
    }
  }


  /**
   * If databse existed on Hive side will return false;
   * Or will create the table thru. RPC and return retVal from remote MetaStore.
   * @param hiveDbName is the hive databases to be checked for existence
   */
  private boolean ensureHiveDbExistence(String hiveDbName, IMetaStoreClient client) throws IOException{
    try (AutoCloseableHiveLock lock = this.locks.getDbLock(hiveDbName)) {
      Database db = new Database();
      db.setName(hiveDbName);

      try {
        try (Timer.Context context = this.metricContext.timer(GET_HIVE_DATABASE).time()) {
          client.getDatabase(db.getName());
        }
        return false;
      } catch (NoSuchObjectException nsoe) {
        // proceed with create
      } catch (TException te) {
        throw new IOException(te);
      }

      Preconditions.checkState(this.hiveDbRootDir.isPresent(),
          "Missing required property " + HiveRegProps.HIVE_DB_ROOT_DIR);
      db.setLocationUri(new Path(this.hiveDbRootDir.get(), hiveDbName + HIVE_DB_EXTENSION).toString());

      try {
        try (Timer.Context context = this.metricContext.timer(CREATE_HIVE_DATABASE).time()) {
          client.createDatabase(db);
        }
        log.info("Created database " + hiveDbName);
        HiveMetaStoreEventHelper.submitSuccessfulDBCreation(this.eventSubmitter, hiveDbName);
        return true;
      } catch (AlreadyExistsException e) {
        return false;
      } catch (TException e) {
        HiveMetaStoreEventHelper.submitFailedDBCreation(this.eventSubmitter, hiveDbName, e);
        throw new IOException("Unable to create Hive database " + hiveDbName, e);
      }
    }
  }

  /**
   * @return true if the db is successfully created.
   *         false if the db already exists.
   * @throws IOException
   */
  private boolean createDbIfNotExists(IMetaStoreClient client, String dbName) throws IOException {
    boolean retVal;
    if (this.optimizedChecks) {
      try {
        retVal = this.tableAndDbExistenceCache.get(dbName, new Callable<Boolean>() {
          @Override
          public Boolean call() throws Exception {
            return ensureHiveDbExistence(dbName, client);
          }
        });
      } catch (ExecutionException ee) {
        throw new IOException("Database existence checking throwing execution exception.");
      }
      return retVal;
    } else {
      return this.ensureHiveDbExistence(dbName, client);
    }
  }

  /**
   * @deprecated Use {@link #createDbIfNotExists(IMetaStoreClient, String)} directly.
   */
  @Deprecated
  public boolean createDbIfNotExists(String dbName) throws IOException {
    try (AutoReturnableObject<IMetaStoreClient> client = this.clientPool.getClient()) {
      return createDbIfNotExists(client.get(), dbName);
    }
  }

  /**
   * @deprecated Please use {@link #createOrAlterTable(IMetaStoreClient, Table, HiveSpec)} instead.
   */
  @Deprecated
  @Override
  public boolean createTableIfNotExists(HiveTable table) throws IOException {
    try (AutoReturnableObject<IMetaStoreClient> client = this.clientPool.getClient();
        AutoCloseableHiveLock lock = this.locks.getTableLock(table.getDbName(), table.getTableName())) {
      return createTableIfNotExists(client.get(), HiveMetaStoreUtils.getTable(table), table);
    }
  }

  /**
   * @deprecated Use {@link #addOrAlterPartition} instead.
   */
  @Deprecated
  @Override
  public boolean addPartitionIfNotExists(HiveTable table, HivePartition partition) throws IOException {
    try (AutoReturnableObject<IMetaStoreClient> client = this.clientPool.getClient();
        AutoCloseableHiveLock lock = this.locks.getTableLock(table.getDbName(), table.getTableName())) {
      try {
        try (Timer.Context context = this.metricContext.timer(GET_HIVE_PARTITION).time()) {
          client.get().getPartition(table.getDbName(), table.getTableName(), partition.getValues());
        }
        return false;
      } catch (NoSuchObjectException e) {
        try (Timer.Context context = this.metricContext.timer(ADD_PARTITION_TIMER).time()) {
          client.get().add_partition(getPartitionWithCreateTimeNow(HiveMetaStoreUtils.getPartition(partition)));
        }
        HiveMetaStoreEventHelper.submitSuccessfulPartitionAdd(this.eventSubmitter, table, partition);
        return true;
      }
    } catch (TException e) {
      HiveMetaStoreEventHelper.submitFailedPartitionAdd(this.eventSubmitter, table, partition, e);
      throw new IOException(String.format("Unable to add partition %s in table %s in db %s", partition.getValues(),
          table.getTableName(), table.getDbName()), e);
    }
  }

  @Deprecated
  /**
   * @deprecated Please use {@link #createOrAlterTable(IMetaStoreClient, Table, HiveSpec)} instead.
   */
  private boolean createTableIfNotExists(IMetaStoreClient client, Table table, HiveTable hiveTable) throws IOException {
    String dbName = table.getDbName();
    String tableName = table.getTableName();

    try (AutoCloseableHiveLock lock = this.locks.getTableLock(dbName, tableName)) {
      boolean tableExists;
      try (Timer.Context context = this.metricContext.timer(TABLE_EXISTS).time()) {
        tableExists = client.tableExists(table.getDbName(), table.getTableName());
      }
      if (tableExists) {
        return false;
      }
      try (Timer.Context context = this.metricContext.timer(CREATE_HIVE_TABLE).time()) {
        client.createTable(getTableWithCreateTimeNow(table));
      }
      log.info(String.format("Created Hive table %s in db %s", tableName, dbName));
      HiveMetaStoreEventHelper.submitSuccessfulTableCreation(this.eventSubmitter, hiveTable);
      return true;
    } catch (TException e) {
      HiveMetaStoreEventHelper.submitFailedTableCreation(eventSubmitter, hiveTable, e);
      throw new IOException(String.format("Error in creating or altering Hive table %s in db %s", table.getTableName(),
          table.getDbName()), e);
    }
  }

  private void createOrAlterTable(IMetaStoreClient client, Table table, HiveSpec spec) throws TException, IOException {
    String dbName = table.getDbName();
    String tableName = table.getTableName();
    boolean tableExistenceInCache;
    if (this.optimizedChecks) {
      try {
        this.tableAndDbExistenceCache.get(dbName + ":" + tableName, new Callable<Boolean>() {
          @Override
          public Boolean call() throws Exception {
            return ensureHiveTableExistenceBeforeAlternation(tableName, dbName, client, table, spec);
          }
        });
      } catch (ExecutionException ee) {
        throw new IOException("Table existence checking throwing execution exception.");
      }
    } else {
      this.ensureHiveTableExistenceBeforeAlternation(tableName, dbName, client, table, spec);
    }
  }

  @Override
  public boolean existsTable(String dbName, String tableName) throws IOException {
    if (this.optimizedChecks && this.tableAndDbExistenceCache.getIfPresent(dbName + ":" + tableName ) != null ) {
      return true;
    }
    try (AutoReturnableObject<IMetaStoreClient> client = this.clientPool.getClient()) {
      try (Timer.Context context = this.metricContext.timer(TABLE_EXISTS).time()) {
        return client.get().tableExists(dbName, tableName);
      }
    } catch (TException e) {
      throw new IOException(String.format("Unable to check existence of table %s in db %s", tableName, dbName), e);
    }
  }

  @Override
  public boolean existsPartition(String dbName, String tableName, List<Column> partitionKeys,
      List<String> partitionValues) throws IOException {
    try (AutoReturnableObject<IMetaStoreClient> client = this.clientPool.getClient()) {
      try (Timer.Context context = this.metricContext.timer(GET_HIVE_PARTITION).time()) {
        client.get().getPartition(dbName, tableName, partitionValues);
      }
      return true;
    } catch (NoSuchObjectException e) {
      return false;
    } catch (TException e) {
      throw new IOException(String.format("Unable to check existence of partition %s in table %s in db %s",
          partitionValues, tableName, dbName), e);
    }
  }

  @Override
  public void dropTableIfExists(String dbName, String tableName) throws IOException {
    try (AutoReturnableObject<IMetaStoreClient> client = this.clientPool.getClient()) {
      boolean tableExists;
      try (Timer.Context context = this.metricContext.timer(TABLE_EXISTS).time()) {
        tableExists = client.get().tableExists(dbName, tableName);
      }
      if (tableExists) {
        try (Timer.Context context = this.metricContext.timer(DROP_TABLE).time()) {
          client.get().dropTable(dbName, tableName);
        }
        String metastoreURI = this.clientPool.getHiveConf().get(HiveMetaStoreClientFactory.HIVE_METASTORE_TOKEN_SIGNATURE, "null");
        HiveMetaStoreEventHelper.submitSuccessfulTableDrop(eventSubmitter, dbName, tableName, metastoreURI);
        log.info("Dropped table " + tableName + " in db " + dbName);
      }
    } catch (TException e) {
      HiveMetaStoreEventHelper.submitFailedTableDrop(eventSubmitter, dbName, tableName, e);
      throw new IOException(String.format("Unable to deregister table %s in db %s", tableName, dbName), e);
    }
  }

  @Override
  public void dropPartitionIfExists(String dbName, String tableName, List<Column> partitionKeys,
      List<String> partitionValues) throws IOException {
    try (AutoReturnableObject<IMetaStoreClient> client = this.clientPool.getClient()) {
      try (Timer.Context context = this.metricContext.timer(DROP_TABLE).time()) {
        client.get().dropPartition(dbName, tableName, partitionValues, false);
      }
      String metastoreURI = this.clientPool.getHiveConf().get(HiveMetaStoreClientFactory.HIVE_METASTORE_TOKEN_SIGNATURE, "null");
      HiveMetaStoreEventHelper.submitSuccessfulPartitionDrop(eventSubmitter, dbName, tableName, partitionValues, metastoreURI);
      log.info("Dropped partition " + partitionValues + " in table " + tableName + " in db " + dbName);
    } catch (NoSuchObjectException e) {
      // Partition does not exist. Nothing to do
    } catch (TException e) {
      HiveMetaStoreEventHelper.submitFailedPartitionDrop(eventSubmitter, dbName, tableName, partitionValues, e);
      throw new IOException(String.format("Unable to check existence of Hive partition %s in table %s in db %s",
          partitionValues, tableName, dbName), e);
    }
  }

  @Override
  public void addOrAlterPartition(HiveTable table, HivePartition partition) throws IOException {
    try (AutoReturnableObject<IMetaStoreClient> client = this.clientPool.getClient()) {
      addOrAlterPartition(client.get(), HiveMetaStoreUtils.getTable(table), partition);
    } catch (TException te) {
      throw new IOException(
          String.format("Failed to add/alter partition %s.%s@%s", table.getDbName(), table.getTableName(), partition.getValues()),
          te);
    }
  }

  private void addOrAlterPartitionWithPushMode(IMetaStoreClient client, Table table, HivePartition partition)
      throws TException, IOException {
    Partition nativePartition = HiveMetaStoreUtils.getPartition(partition);

    Preconditions.checkArgument(table.getPartitionKeysSize() == nativePartition.getValues().size(),
        String.format("Partition key size is %s but partition value size is %s", table.getPartitionKeys().size(),
            nativePartition.getValues().size()));

    try (AutoCloseableHiveLock lock =
        this.locks.getPartitionLock(table.getDbName(), table.getTableName(), nativePartition.getValues())) {

      try {
        try (Timer.Context context = this.metricContext.timer(ADD_PARTITION_TIMER).time()) {
          client.add_partition(getPartitionWithCreateTimeNow(nativePartition));
        }
        log.info(String.format("Added partition %s to table %s with location %s", stringifyPartition(nativePartition),
            table.getTableName(), nativePartition.getSd().getLocation()));
      } catch (TException e) {
        try {
          if (this.skipDiffComputation) {
            onPartitionExistWithoutComputingDiff(table, nativePartition, e);
          } else {
            onPartitionExist(client, table, partition, nativePartition, null);
          }
        } catch (Throwable e2) {
          log.error(String.format(
              "Unable to add or alter partition %s in table %s with location %s: " + e2.getMessage(),
              stringifyPartitionVerbose(nativePartition), table.getTableName(), nativePartition.getSd().getLocation()), e2);
          throw e2;
        }
      }
    }
  }

  private void addOrAlterPartition(IMetaStoreClient client, Table table, HivePartition partition)
      throws TException, IOException {
    if(!registerPartitionWithPullMode) {
      addOrAlterPartitionWithPushMode(client, table, partition);
    } else {
      addOrAlterPartitionWithPullMode(client, table, partition);
    }
  }
  private void addOrAlterPartitionWithPullMode(IMetaStoreClient client, Table table, HivePartition partition)
      throws TException, IOException {
    Partition nativePartition = HiveMetaStoreUtils.getPartition(partition);

    Preconditions.checkArgument(table.getPartitionKeysSize() == nativePartition.getValues().size(),
        String.format("Partition key size is %s but partition value size is %s", table.getPartitionKeys().size(),
            nativePartition.getValues().size()));

    try (AutoCloseableHiveLock lock =
        this.locks.getPartitionLock(table.getDbName(), table.getTableName(), nativePartition.getValues())) {

      Partition existedPartition;
      try {
        try (Timer.Context context = this.metricContext.timer(GET_HIVE_PARTITION).time()) {
          existedPartition =  client.getPartition(table.getDbName(), table.getTableName(), nativePartition.getValues());
          if (this.skipDiffComputation) {
            onPartitionExistWithoutComputingDiff(table, nativePartition, null);
          } else {
            onPartitionExist(client, table, partition, nativePartition, existedPartition);
          }
        }
      } catch (TException e) {
        try (Timer.Context context = this.metricContext.timer(ADD_PARTITION_TIMER).time()) {
          client.add_partition(getPartitionWithCreateTimeNow(nativePartition));
        }
        catch (Throwable e2) {
          log.error(String.format(
              "Unable to add or alter partition %s in table %s with location %s: " + e2.getMessage(),
              stringifyPartitionVerbose(nativePartition), table.getTableName(), nativePartition.getSd().getLocation()), e2);
          throw e2;
        }
        log.info(String.format("Added partition %s to table %s with location %s", stringifyPartition(nativePartition),
            table.getTableName(), nativePartition.getSd().getLocation()));
      }
    }
  }

  private void onPartitionExist(IMetaStoreClient client, Table table, HivePartition partition, Partition nativePartition, Partition existedPartition) throws TException {
    HivePartition existingPartition;
    if(existedPartition == null) {
      try (Timer.Context context = this.metricContext.timer(GET_HIVE_PARTITION).time()) {
        existingPartition = HiveMetaStoreUtils.getHivePartition(
            client.getPartition(table.getDbName(), table.getTableName(), nativePartition.getValues()));
      }
    } else {
      existingPartition = HiveMetaStoreUtils.getHivePartition(existedPartition);
    }

    if (needToUpdatePartition(existingPartition, partition)) {
      log.info(String.format("Partition update required. ExistingPartition %s, newPartition %s",
          stringifyPartition(existingPartition), stringifyPartition(partition)));
      Partition newPartition = getPartitionWithCreateTime(nativePartition, existingPartition);
      log.info(String.format("Altering partition %s", newPartition));
      try (Timer.Context context = this.metricContext.timer(ALTER_PARTITION).time()) {
        client.alter_partition(table.getDbName(), table.getTableName(), newPartition);
      }
      log.info(String.format("Updated partition %s in table %s with location %s", stringifyPartition(newPartition),
          table.getTableName(), nativePartition.getSd().getLocation()));
    } else {
      log.debug(String.format("Partition %s in table %s with location %s already exists and no need to update",
          stringifyPartition(nativePartition), table.getTableName(), nativePartition.getSd().getLocation()));
    }
  }

  private void onPartitionExistWithoutComputingDiff(Table table, Partition nativePartition, TException e) throws TException {
    if(e == null) {
      return;
    }
    if (e instanceof AlreadyExistsException) {
      log.debug(String.format("Partition %s in table %s with location %s already exists and no need to update",
          stringifyPartition(nativePartition), table.getTableName(), nativePartition.getSd().getLocation()));
    }
    else {
      throw e;
    }
  }

  private static String stringifyPartition(Partition partition) {
    if (log.isDebugEnabled()) {
      return stringifyPartitionVerbose(partition);
    }
    return Arrays.toString(partition.getValues().toArray());
  }

  private static String stringifyPartition(HivePartition partition) {
    return partition.toString();
  }

  private static String stringifyPartitionVerbose(Partition partition) {
    return partition.toString();
  }

  @Override
  public Optional<HiveTable> getTable(String dbName, String tableName) throws IOException {
    try (AutoReturnableObject<IMetaStoreClient> client = this.clientPool.getClient()) {
      Table hiveTable;
      try (Timer.Context context = this.metricContext.timer(GET_HIVE_TABLE).time()) {
        hiveTable = client.get().getTable(dbName, tableName);
      }
      return Optional.of(HiveMetaStoreUtils.getHiveTable(hiveTable));
    } catch (NoSuchObjectException e) {
      return Optional.<HiveTable> absent();
    } catch (TException e) {
      throw new IOException("Unable to get table " + tableName + " in db " + dbName, e);
    }
  }

  @Override
  public Optional<HivePartition> getPartition(String dbName, String tableName, List<Column> partitionKeys,
      List<String> partitionValues) throws IOException {

    try (AutoReturnableObject<IMetaStoreClient> client = this.clientPool.getClient()) {
      Partition hivePartition;
      try (Timer.Context context = this.metricContext.timer(GET_HIVE_PARTITION).time()) {
        hivePartition = client.get().getPartition(dbName, tableName, partitionValues);
      }
      return Optional.of(HiveMetaStoreUtils.getHivePartition(hivePartition));
    } catch (NoSuchObjectException e) {
      return Optional.<HivePartition> absent();
    } catch (TException e) {
      throw new IOException(
          "Unable to get partition " + partitionValues + " from table " + tableName + " in db " + dbName, e);
    }
  }

  @Override
  public void alterTable(HiveTable table) throws IOException {
    try (AutoReturnableObject<IMetaStoreClient> client = this.clientPool.getClient()) {
      Table existingTable;
      //During alter table we need to persist the existing property of iceberg in HMS
      try (Timer.Context context = this.metricContext.timer(GET_HIVE_TABLE).time()) {
        existingTable = client.get().getTable(table.getDbName(), table.getTableName());
      } catch (Exception e){
        throw new IOException("Cannot get table " + table.getTableName() + " in db " + table.getDbName(), e);
      }
      try (Timer.Context context = this.metricContext.timer(ALTER_TABLE).time()) {
        table.getProps().addAllIfNotExist(HiveMetaStoreUtils.getTableProps(existingTable));
        client.get().alter_table(table.getDbName(), table.getTableName(),
            getTableWithCreateTimeNow(HiveMetaStoreUtils.getTable(table)));
      }
      HiveMetaStoreEventHelper.submitSuccessfulTableAlter(eventSubmitter, table);
    } catch (TException e) {
      HiveMetaStoreEventHelper.submitFailedTableAlter(eventSubmitter, table, e);
      throw new IOException("Unable to alter table " + table.getTableName() + " in db " + table.getDbName(), e);
    }
  }

  @Override
  public void alterPartition(HiveTable table, HivePartition partition) throws IOException {
    try (AutoReturnableObject<IMetaStoreClient> client = this.clientPool.getClient()) {
      try (Timer.Context context = this.metricContext.timer(ALTER_PARTITION).time()) {
        client.get().alter_partition(table.getDbName(), table.getTableName(),
            getPartitionWithCreateTimeNow(HiveMetaStoreUtils.getPartition(partition)));
      }
      HiveMetaStoreEventHelper.submitSuccessfulPartitionAlter(eventSubmitter, table, partition);
    } catch (TException e) {
      HiveMetaStoreEventHelper.submitFailedPartitionAlter(eventSubmitter, table, partition, e);
      throw new IOException(String.format("Unable to alter partition %s in table %s in db %s", partition.getValues(),
          table.getTableName(), table.getDbName()), e);
    }
  }

  private Partition getPartitionWithCreateTimeNow(Partition partition) {
    return getPartitionWithCreateTime(partition, Ints.checkedCast(DateTime.now().getMillis() / 1000));
  }

  private Partition getPartitionWithCreateTime(Partition partition, HivePartition referencePartition) {
    return getPartitionWithCreateTime(partition,
        Ints.checkedCast(referencePartition.getCreateTime().or(DateTime.now().getMillis() / 1000)));
  }

  /**
   * Sets create time if not already set.
   */
  private Partition getPartitionWithCreateTime(Partition partition, int createTime) {
    if (partition.isSetCreateTime() && partition.getCreateTime() > 0) {
      return partition;
    }
    Partition actualPartition = partition.deepCopy();
    actualPartition.setCreateTime(createTime);
    return actualPartition;
  }

  private Table getTableWithCreateTimeNow(Table table) {
    return gettableWithCreateTime(table, Ints.checkedCast(DateTime.now().getMillis() / 1000));
  }

  private Table getTableWithCreateTime(Table table, HiveTable referenceTable) {
    return gettableWithCreateTime(table,
        Ints.checkedCast(referenceTable.getCreateTime().or(DateTime.now().getMillis() / 1000)));
  }

  /**
   * Sets create time if not already set.
   */
  private Table gettableWithCreateTime(Table table, int createTime) {
    if (table.isSetCreateTime() && table.getCreateTime() > 0) {
      return table;
    }
    Table actualtable = table.deepCopy();
    actualtable.setCreateTime(createTime);
    return actualtable;
  }


  /**
   * Used to merge properties from existingTable to newTable.
   * e.g. New table will inherit creation time from existing table.
   *
   * This method is extensible for customized logic in merging table properties.
   * @param newTable
   * @param existingTable
   */
  protected Table getNewTblByMergingExistingTblProps(Table newTable, HiveTable existingTable) {
    return getTableWithCreateTime(newTable, existingTable);
  }
}
