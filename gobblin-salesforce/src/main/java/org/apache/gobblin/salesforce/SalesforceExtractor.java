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

package org.apache.gobblin.salesforce;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.apache.gobblin.salesforce.SalesforceConfigurationKeys;
import org.apache.http.HttpEntity;
import org.apache.http.NameValuePair;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.message.BasicNameValuePair;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sforce.async.AsyncApiException;
import com.sforce.async.BatchInfo;
import com.sforce.async.BatchInfoList;
import com.sforce.async.BatchStateEnum;
import com.sforce.async.BulkConnection;
import com.sforce.async.ConcurrencyMode;
import com.sforce.async.ContentType;
import com.sforce.async.JobInfo;
import com.sforce.async.OperationEnum;
import com.sforce.async.QueryResultList;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.ws.ConnectorConfig;

import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.configuration.WorkUnitState;
import org.apache.gobblin.password.PasswordManager;
import org.apache.gobblin.source.extractor.DataRecordException;
import org.apache.gobblin.source.extractor.exception.HighWatermarkException;
import org.apache.gobblin.source.extractor.exception.RecordCountException;
import org.apache.gobblin.source.extractor.exception.RestApiClientException;
import org.apache.gobblin.source.extractor.exception.RestApiConnectionException;
import org.apache.gobblin.source.extractor.exception.SchemaException;
import org.apache.gobblin.source.extractor.extract.Command;
import org.apache.gobblin.source.extractor.extract.CommandOutput;
import org.apache.gobblin.source.extractor.partition.Partitioner;
import org.apache.gobblin.source.jdbc.SqlQueryUtils;
import org.apache.gobblin.source.extractor.extract.restapi.RestApiCommand;
import org.apache.gobblin.source.extractor.extract.restapi.RestApiCommand.RestApiCommandType;
import org.apache.gobblin.source.extractor.extract.restapi.RestApiConnector;
import org.apache.gobblin.source.extractor.extract.restapi.RestApiExtractor;
import org.apache.gobblin.source.extractor.resultset.RecordSet;
import org.apache.gobblin.source.extractor.resultset.RecordSetList;
import org.apache.gobblin.source.extractor.schema.Schema;
import org.apache.gobblin.source.extractor.utils.InputStreamCSVReader;
import org.apache.gobblin.source.extractor.utils.Utils;
import org.apache.gobblin.source.extractor.watermark.Predicate;
import org.apache.gobblin.source.extractor.watermark.WatermarkType;
import org.apache.gobblin.source.workunit.WorkUnit;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;


/**
 * An implementation of salesforce extractor for extracting data from SFDC
 */
@Slf4j
public class SalesforceExtractor extends RestApiExtractor {

  private static final String SOQL_RESOURCE = "/queryAll";
  public static final String SALESFORCE_TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'.000Z'";
  private static final String SALESFORCE_DATE_FORMAT = "yyyy-MM-dd";
  private static final String SALESFORCE_HOUR_FORMAT = "HH";
  private static final String SALESFORCE_SOAP_SERVICE = "/services/Soap/u";
  private static final Gson GSON = new Gson();
  private static final int MAX_PK_CHUNKING_SIZE = 250000;
  private static final int MIN_PK_CHUNKING_SIZE = 100000;
  private static final int DEFAULT_PK_CHUNKING_SIZE = 200000;
  private static final String ENABLE_PK_CHUNKING_KEY = "salesforce.enablePkChunking";
  private static final String PK_CHUNKING_SIZE_KEY = "salesforce.pkChunkingSize";
  private static final int MAX_RETRY_INTERVAL_SECS = 600;
  // avoid using too many bulk API calls by only allowing PK chunking only if max partitions is configured <= this
  private static final int PK_CHUNKING_MAX_PARTITIONS_LIMIT = 3;
  private static final String FETCH_RETRY_LIMIT_KEY = "salesforce.fetchRetryLimit";
  private static final int DEFAULT_FETCH_RETRY_LIMIT = 5;
  private static final String BULK_API_USE_QUERY_ALL = "salesforce.bulkApiUseQueryAll";
  private static final boolean DEFAULT_BULK_API_USE_QUERY_ALL = false;
  private static final String PK_CHUNKING_SKIP_COUNT_CHECK = "salesforce.pkChunkingSkipCountCheck";
  private static final boolean DEFAULT_PK_CHUNKING_SKIP_COUNT_CHECK = false;


  private boolean pullStatus = true;
  private String nextUrl;

  private BulkConnection bulkConnection = null;
  private boolean bulkApiInitialRun = true;
  private JobInfo bulkJob = new JobInfo();
  private BufferedReader bulkBufferedReader = null;
  private List<BatchIdAndResultId> bulkResultIdList = Lists.newArrayList();
  private int bulkResultIdCount = 0;
  private boolean bulkJobFinished = true;
  private List<String> bulkRecordHeader;
  private int bulkResultColumCount;
  private boolean newBulkResultSet = true;
  private int bulkRecordCount = 0;
  private int prevBulkRecordCount = 0;
  private List<String> csvRecord;

  private final boolean pkChunking;
  private final int pkChunkingSize;
  private final SalesforceConnector sfConnector;
  private final int fetchRetryLimit;
  private final int batchSize;

  private final boolean pkChunkingSkipCountCheck;
  private final boolean bulkApiUseQueryAll;

  public SalesforceExtractor(WorkUnitState state) {
    super(state);
    this.sfConnector = (SalesforceConnector) this.connector;

    // don't allow pk chunking if max partitions too high or have user specified partitions
    if (state.getPropAsBoolean(Partitioner.HAS_USER_SPECIFIED_PARTITIONS, false)
        || state.getPropAsInt(ConfigurationKeys.SOURCE_MAX_NUMBER_OF_PARTITIONS,
        ConfigurationKeys.DEFAULT_MAX_NUMBER_OF_PARTITIONS) > PK_CHUNKING_MAX_PARTITIONS_LIMIT) {
      if (state.getPropAsBoolean(ENABLE_PK_CHUNKING_KEY, false)) {
        log.warn("Max partitions too high, so PK chunking is not enabled");
      }

      this.pkChunking = false;
    } else {
      this.pkChunking = state.getPropAsBoolean(ENABLE_PK_CHUNKING_KEY, false);
    }

    this.pkChunkingSize =
        Math.max(MIN_PK_CHUNKING_SIZE,
            Math.min(MAX_PK_CHUNKING_SIZE, state.getPropAsInt(PK_CHUNKING_SIZE_KEY, DEFAULT_PK_CHUNKING_SIZE)));

    this.pkChunkingSkipCountCheck = state.getPropAsBoolean(PK_CHUNKING_SKIP_COUNT_CHECK, DEFAULT_PK_CHUNKING_SKIP_COUNT_CHECK);
    this.bulkApiUseQueryAll = state.getPropAsBoolean(BULK_API_USE_QUERY_ALL, DEFAULT_BULK_API_USE_QUERY_ALL);

    // Get batch size from .pull file
    int tmpBatchSize = state.getPropAsInt(ConfigurationKeys.SOURCE_QUERYBASED_FETCH_SIZE,
        ConfigurationKeys.DEFAULT_SOURCE_FETCH_SIZE);

    this.batchSize = tmpBatchSize == 0 ? ConfigurationKeys.DEFAULT_SOURCE_FETCH_SIZE : tmpBatchSize;
    this.fetchRetryLimit = state.getPropAsInt(FETCH_RETRY_LIMIT_KEY, DEFAULT_FETCH_RETRY_LIMIT);
  }

  @Override
  protected RestApiConnector getConnector(WorkUnitState state) {
    return new SalesforceConnector(state);
  }

  /**
   * true is further pull required else false
   */
  public void setPullStatus(boolean pullStatus) {
    this.pullStatus = pullStatus;
  }

  /**
   * url for the next pull from salesforce
   */
  public void setNextUrl(String nextUrl) {
    this.nextUrl = nextUrl;
  }

  private boolean isBulkJobFinished() {
    return this.bulkJobFinished;
  }

  private void setBulkJobFinished(boolean bulkJobFinished) {
    this.bulkJobFinished = bulkJobFinished;
  }

  public boolean isNewBulkResultSet() {
    return this.newBulkResultSet;
  }

  public void setNewBulkResultSet(boolean newBulkResultSet) {
    this.newBulkResultSet = newBulkResultSet;
  }

  @Override
  public HttpEntity getAuthentication() throws RestApiConnectionException {
    log.debug("Authenticating salesforce");
    return this.connector.getAuthentication();
  }

  @Override
  public List<Command> getSchemaMetadata(String schema, String entity) throws SchemaException {
    log.debug("Build url to retrieve schema");
    return constructGetCommand(this.sfConnector.getFullUri("/sobjects/" + entity.trim() + "/describe"));
  }

  @Override
  public JsonArray getSchema(CommandOutput<?, ?> response) throws SchemaException {
    log.info("Get schema from salesforce");

    String output;
    Iterator<String> itr = (Iterator<String>) response.getResults().values().iterator();
    if (itr.hasNext()) {
      output = itr.next();
    } else {
      throw new SchemaException("Failed to get schema from salesforce; REST response has no output");
    }

    JsonArray fieldJsonArray = new JsonArray();
    JsonElement element = GSON.fromJson(output, JsonObject.class);
    JsonObject jsonObject = element.getAsJsonObject();

    try {
      JsonArray array = jsonObject.getAsJsonArray("fields");
      for (JsonElement columnElement : array) {
        JsonObject field = columnElement.getAsJsonObject();
        Schema schema = new Schema();
        schema.setColumnName(field.get("name").getAsString());

        String dataType = field.get("type").getAsString();
        String elementDataType = "string";
        List<String> mapSymbols = null;
        JsonObject newDataType =
            this.convertDataType(field.get("name").getAsString(), dataType, elementDataType, mapSymbols);
        log.debug("ColumnName:" + field.get("name").getAsString() + ";   old datatype:" + dataType + ";   new datatype:"
            + newDataType);

        schema.setDataType(newDataType);
        schema.setLength(field.get("length").getAsLong());
        schema.setPrecision(field.get("precision").getAsInt());
        schema.setScale(field.get("scale").getAsInt());
        schema.setNullable(field.get("nillable").getAsBoolean());
        schema.setFormat(null);
        schema.setComment((field.get("label").isJsonNull() ? null : field.get("label").getAsString()));
        schema
            .setDefaultValue((field.get("defaultValue").isJsonNull() ? null : field.get("defaultValue").getAsString()));
        schema.setUnique(field.get("unique").getAsBoolean());

        String jsonStr = GSON.toJson(schema);
        JsonObject obj = GSON.fromJson(jsonStr, JsonObject.class).getAsJsonObject();
        fieldJsonArray.add(obj);
      }
    } catch (Exception e) {
      throw new SchemaException("Failed to get schema from salesforce; error - " + e.getMessage(), e);
    }
    return fieldJsonArray;
  }

  @Override
  public List<Command> getHighWatermarkMetadata(String schema, String entity, String watermarkColumn,
      List<Predicate> predicateList) throws HighWatermarkException {
    log.debug("Build url to retrieve high watermark");
    String query = "SELECT " + watermarkColumn + " FROM " + entity;
    String defaultPredicate = " " + watermarkColumn + " != null";
    String defaultSortOrder = " ORDER BY " + watermarkColumn + " desc LIMIT 1";

    String existingPredicate = "";
    if (this.updatedQuery != null) {
      String queryLowerCase = this.updatedQuery.toLowerCase();
      int startIndex = queryLowerCase.indexOf(" where ");
      if (startIndex > 0) {
        existingPredicate = this.updatedQuery.substring(startIndex);
      }
    }
    query = query + existingPredicate;

    String limitString = getLimitFromInputQuery(query);
    query = query.replace(limitString, "");

    Iterator<Predicate> i = predicateList.listIterator();
    while (i.hasNext()) {
      Predicate predicate = i.next();
      query = SqlQueryUtils.addPredicate(query, predicate.getCondition());
    }
    query = SqlQueryUtils.addPredicate(query, defaultPredicate);
    query = query + defaultSortOrder;
    log.info("QUERY: " + query);

    try {
      return constructGetCommand(this.sfConnector.getFullUri(getSoqlUrl(query)));
    } catch (Exception e) {
      throw new HighWatermarkException("Failed to get salesforce url for high watermark; error - " + e.getMessage(), e);
    }
  }

  @Override
  public long getHighWatermark(CommandOutput<?, ?> response, String watermarkColumn, String format)
      throws HighWatermarkException {
    log.info("Get high watermark from salesforce");

    String output;
    Iterator<String> itr = (Iterator<String>) response.getResults().values().iterator();
    if (itr.hasNext()) {
      output = itr.next();
    } else {
      throw new HighWatermarkException("Failed to get high watermark from salesforce; REST response has no output");
    }

    JsonElement element = GSON.fromJson(output, JsonObject.class);
    long high_ts;
    try {
      JsonObject jsonObject = element.getAsJsonObject();
      JsonArray jsonArray = jsonObject.getAsJsonArray("records");
      if (jsonArray.size() == 0) {
        return -1;
      }

      String value = jsonObject.getAsJsonArray("records").get(0).getAsJsonObject().get(watermarkColumn).getAsString();
      if (format != null) {
        SimpleDateFormat inFormat = new SimpleDateFormat(format);
        Date date = null;
        try {
          date = inFormat.parse(value);
        } catch (ParseException e) {
          log.error("ParseException: " + e.getMessage(), e);
        }
        SimpleDateFormat outFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        high_ts = Long.parseLong(outFormat.format(date));
      } else {
        high_ts = Long.parseLong(value);
      }

    } catch (Exception e) {
      throw new HighWatermarkException("Failed to get high watermark from salesforce; error - " + e.getMessage(), e);
    }
    return high_ts;
  }

  @Override
  public List<Command> getCountMetadata(String schema, String entity, WorkUnit workUnit, List<Predicate> predicateList)
      throws RecordCountException {
    log.debug("Build url to retrieve source record count");
    String existingPredicate = "";
    if (this.updatedQuery != null) {
      String queryLowerCase = this.updatedQuery.toLowerCase();
      int startIndex = queryLowerCase.indexOf(" where ");
      if (startIndex > 0) {
        existingPredicate = this.updatedQuery.substring(startIndex);
      }
    }

    String query = "SELECT COUNT() FROM " + entity + existingPredicate;
    String limitString = getLimitFromInputQuery(query);
    query = query.replace(limitString, "");

    try {
      if (isNullPredicate(predicateList)) {
        log.info("QUERY with null predicate: " + query);
        return constructGetCommand(this.sfConnector.getFullUri(getSoqlUrl(query)));
      }
      Iterator<Predicate> i = predicateList.listIterator();
      while (i.hasNext()) {
        Predicate predicate = i.next();
        query = SqlQueryUtils.addPredicate(query, predicate.getCondition());
      }

      query = query + getLimitFromInputQuery(this.updatedQuery);
      log.info("QUERY: " + query);
      return constructGetCommand(this.sfConnector.getFullUri(getSoqlUrl(query)));
    } catch (Exception e) {
      throw new RecordCountException("Failed to get salesforce url for record count; error - " + e.getMessage(), e);
    }
  }

  @Override
  public long getCount(CommandOutput<?, ?> response) throws RecordCountException {
    log.info("Get source record count from salesforce");

    String output;
    Iterator<String> itr = (Iterator<String>) response.getResults().values().iterator();
    if (itr.hasNext()) {
      output = itr.next();
    } else {
      throw new RecordCountException("Failed to get count from salesforce; REST response has no output");
    }

    JsonElement element = GSON.fromJson(output, JsonObject.class);
    long count;
    try {
      JsonObject jsonObject = element.getAsJsonObject();
      count = jsonObject.get("totalSize").getAsLong();
    } catch (Exception e) {
      throw new RecordCountException("Failed to get record count from salesforce; error - " + e.getMessage(), e);
    }
    return count;
  }

  @Override
  public List<Command> getDataMetadata(String schema, String entity, WorkUnit workUnit, List<Predicate> predicateList)
      throws DataRecordException {
    log.debug("Build url to retrieve data records");
    String query = this.updatedQuery;
    String url = null;
    try {
      if (this.getNextUrl() != null && this.pullStatus == true) {
        url = this.getNextUrl();
      } else {
        if (isNullPredicate(predicateList)) {
          log.info("QUERY:" + query);
          return constructGetCommand(this.sfConnector.getFullUri(getSoqlUrl(query)));
        }

        String limitString = getLimitFromInputQuery(query);
        query = query.replace(limitString, "");

        Iterator<Predicate> i = predicateList.listIterator();
        while (i.hasNext()) {
          Predicate predicate = i.next();
          query = SqlQueryUtils.addPredicate(query, predicate.getCondition());
        }

        if (Boolean.valueOf(this.workUnitState.getProp(ConfigurationKeys.SOURCE_QUERYBASED_IS_SPECIFIC_API_ACTIVE))) {
          query = SqlQueryUtils.addPredicate(query, "IsDeleted = true");
        }

        query = query + limitString;
        log.info("QUERY: " + query);
        url = this.sfConnector.getFullUri(getSoqlUrl(query));
      }
      return constructGetCommand(url);
    } catch (Exception e) {
      throw new DataRecordException("Failed to get salesforce url for data records; error - " + e.getMessage(), e);
    }
  }

  private static String getLimitFromInputQuery(String query) {
    String inputQuery = query.toLowerCase();
    int limitIndex = inputQuery.indexOf(" limit");
    if (limitIndex > 0) {
      return query.substring(limitIndex);
    }
    return "";
  }

  @Override
  public Iterator<JsonElement> getData(CommandOutput<?, ?> response) throws DataRecordException {
    log.debug("Get data records from response");

    String output;
    Iterator<String> itr = (Iterator<String>) response.getResults().values().iterator();
    if (itr.hasNext()) {
      output = itr.next();
    } else {
      throw new DataRecordException("Failed to get data from salesforce; REST response has no output");
    }

    List<JsonElement> rs = Lists.newArrayList();
    JsonElement element = GSON.fromJson(output, JsonObject.class);
    JsonArray partRecords;
    try {
      JsonObject jsonObject = element.getAsJsonObject();

      partRecords = jsonObject.getAsJsonArray("records");
      if (jsonObject.get("done").getAsBoolean()) {
        setPullStatus(false);
      } else {
        setNextUrl(this.sfConnector.getFullUri(
            jsonObject.get("nextRecordsUrl").getAsString().replaceAll(this.sfConnector.getServicesDataEnvPath(), "")));
      }

      JsonArray array = Utils.removeElementFromJsonArray(partRecords, "attributes");
      Iterator<JsonElement> li = array.iterator();
      while (li.hasNext()) {
        JsonElement recordElement = li.next();
        rs.add(recordElement);
      }
      return rs.iterator();
    } catch (Exception e) {
      throw new DataRecordException("Failed to get records from salesforce; error - " + e.getMessage(), e);
    }
  }

  @Override
  public boolean getPullStatus() {
    return this.pullStatus;
  }

  @Override
  public String getNextUrl() {
    return this.nextUrl;
  }

  public static String getSoqlUrl(String soqlQuery) throws RestApiClientException {
    String path = SOQL_RESOURCE + "/";
    NameValuePair pair = new BasicNameValuePair("q", soqlQuery);
    List<NameValuePair> qparams = new ArrayList<>();
    qparams.add(pair);
    return buildUrl(path, qparams);
  }

  private static String buildUrl(String path, List<NameValuePair> qparams) throws RestApiClientException {
    URIBuilder builder = new URIBuilder();
    builder.setPath(path);
    ListIterator<NameValuePair> i = qparams.listIterator();
    while (i.hasNext()) {
      NameValuePair keyValue = i.next();
      builder.setParameter(keyValue.getName(), keyValue.getValue());
    }
    URI uri;
    try {
      uri = builder.build();
    } catch (Exception e) {
      throw new RestApiClientException("Failed to build url; error - " + e.getMessage(), e);
    }
    return new HttpGet(uri).getURI().toString();
  }

  private static boolean isNullPredicate(List<Predicate> predicateList) {
    if (predicateList == null || predicateList.size() == 0) {
      return true;
    }
    return false;
  }

  @Override
  public String getWatermarkSourceFormat(WatermarkType watermarkType) {
    switch (watermarkType) {
      case TIMESTAMP:
        return "yyyy-MM-dd'T'HH:mm:ss";
      case DATE:
        return "yyyy-MM-dd";
      default:
        return null;
    }
  }

  @Override
  public String getHourPredicateCondition(String column, long value, String valueFormat, String operator) {
    log.info("Getting hour predicate from salesforce");
    String Formattedvalue = Utils.toDateTimeFormat(Long.toString(value), valueFormat, SALESFORCE_HOUR_FORMAT);
    return column + " " + operator + " " + Formattedvalue;
  }

  @Override
  public String getDatePredicateCondition(String column, long value, String valueFormat, String operator) {
    log.info("Getting date predicate from salesforce");
    String Formattedvalue = Utils.toDateTimeFormat(Long.toString(value), valueFormat, SALESFORCE_DATE_FORMAT);
    return column + " " + operator + " " + Formattedvalue;
  }

  @Override
  public String getTimestampPredicateCondition(String column, long value, String valueFormat, String operator) {
    log.info("Getting timestamp predicate from salesforce");
    String Formattedvalue = Utils.toDateTimeFormat(Long.toString(value), valueFormat, SALESFORCE_TIMESTAMP_FORMAT);
    return column + " " + operator + " " + Formattedvalue;
  }

  @Override
  public Map<String, String> getDataTypeMap() {
    Map<String, String> dataTypeMap = ImmutableMap.<String, String> builder().put("url", "string")
        .put("textarea", "string").put("reference", "string").put("phone", "string").put("masterrecord", "string")
        .put("location", "string").put("id", "string").put("encryptedstring", "string").put("email", "string")
        .put("DataCategoryGroupReference", "string").put("calculated", "string").put("anyType", "string")
        .put("address", "string").put("blob", "string").put("date", "date").put("datetime", "timestamp")
        .put("time", "time").put("object", "string").put("string", "string").put("int", "int").put("long", "long")
        .put("double", "double").put("percent", "double").put("currency", "double").put("decimal", "double")
        .put("boolean", "boolean").put("picklist", "string").put("multipicklist", "string").put("combobox", "string")
        .put("list", "string").put("set", "string").put("map", "string").put("enum", "string").build();
    return dataTypeMap;
  }

  @Override
  public Iterator<JsonElement> getRecordSetFromSourceApi(String schema, String entity, WorkUnit workUnit,
      List<Predicate> predicateList) throws IOException {
    log.debug("Getting salesforce data using bulk api");
    RecordSet<JsonElement> rs = null;

    try {
      //Get query result ids in the first run
      //result id is used to construct url while fetching data
      if (this.bulkApiInitialRun == true) {
        // set finish status to false before starting the bulk job
        this.setBulkJobFinished(false);
        this.bulkResultIdList = getQueryResultIds(entity, predicateList);
        log.info("Number of bulk api resultSet Ids:" + this.bulkResultIdList.size());
      }

      // Get data from input stream
      // If bulk load is not finished, get data from the stream
      // Skip empty result sets since they will cause the extractor to terminate early
      while (!this.isBulkJobFinished() && (rs == null || rs.isEmpty())) {
        rs = getBulkData();
      }

      // Set bulkApiInitialRun to false after the completion of first run
      this.bulkApiInitialRun = false;

      // If bulk job is finished, get soft deleted records using Rest API
      boolean isSoftDeletesPullDisabled = Boolean.valueOf(this.workUnit
          .getProp(SalesforceConfigurationKeys.SOURCE_QUERYBASED_SALESFORCE_IS_SOFT_DELETES_PULL_DISABLED));
      if (rs == null || rs.isEmpty()) {
        // Get soft delete records only if IsDeleted column exists and soft deletes pull is not disabled
        if (this.columnList.contains("IsDeleted") && !isSoftDeletesPullDisabled) {
          return this.getSoftDeletedRecords(schema, entity, workUnit, predicateList);
        }
        log.info("Ignoring soft delete records");
      }

      return rs.iterator();

    } catch (Exception e) {
      throw new IOException("Failed to get records using bulk api; error - " + e.getMessage(), e);
    }
  }

  /**
   * Get soft deleted records using Rest Api
     * @return iterator with deleted records
   */
  private Iterator<JsonElement> getSoftDeletedRecords(String schema, String entity, WorkUnit workUnit,
      List<Predicate> predicateList) throws DataRecordException {
    return this.getRecordSet(schema, entity, workUnit, predicateList);
  }

  /**
   * Login to salesforce
   * @return login status
   */
  public boolean bulkApiLogin() throws Exception {
    log.info("Authenticating salesforce bulk api");
    boolean success = false;
    String hostName = this.workUnitState.getProp(ConfigurationKeys.SOURCE_CONN_HOST_NAME);
    String apiVersion = this.workUnitState.getProp(ConfigurationKeys.SOURCE_CONN_VERSION);
    if (Strings.isNullOrEmpty(apiVersion)) {
      // queryAll was introduced in version 39.0, so need to use a higher version when using queryAll with the bulk api
      apiVersion = this.bulkApiUseQueryAll ? "42.0" : "29.0";
    }

    String soapAuthEndPoint = hostName + SALESFORCE_SOAP_SERVICE + "/" + apiVersion;
    try {
      ConnectorConfig partnerConfig = new ConnectorConfig();
      if (super.workUnitState.contains(ConfigurationKeys.SOURCE_CONN_USE_PROXY_URL)
          && !super.workUnitState.getProp(ConfigurationKeys.SOURCE_CONN_USE_PROXY_URL).isEmpty()) {
        partnerConfig.setProxy(super.workUnitState.getProp(ConfigurationKeys.SOURCE_CONN_USE_PROXY_URL),
            super.workUnitState.getPropAsInt(ConfigurationKeys.SOURCE_CONN_USE_PROXY_PORT));
      }

      String accessToken = sfConnector.getAccessToken();

      if (accessToken == null) {
        boolean isConnectSuccess = sfConnector.connect();
        if (isConnectSuccess) {
          accessToken = sfConnector.getAccessToken();
        }
      }

      if (accessToken != null) {
        String serviceEndpoint = sfConnector.getInstanceUrl() + SALESFORCE_SOAP_SERVICE + "/" + apiVersion;
        partnerConfig.setSessionId(accessToken);
        partnerConfig.setServiceEndpoint(serviceEndpoint);
      } else {
        String securityToken = this.workUnitState.getProp(ConfigurationKeys.SOURCE_CONN_SECURITY_TOKEN);
        String password = PasswordManager.getInstance(this.workUnitState)
            .readPassword(this.workUnitState.getProp(ConfigurationKeys.SOURCE_CONN_PASSWORD));
        partnerConfig.setUsername(this.workUnitState.getProp(ConfigurationKeys.SOURCE_CONN_USERNAME));
        partnerConfig.setPassword(password + securityToken);
      }

      partnerConfig.setAuthEndpoint(soapAuthEndPoint);

      new PartnerConnection(partnerConfig);
      String soapEndpoint = partnerConfig.getServiceEndpoint();
      String restEndpoint = soapEndpoint.substring(0, soapEndpoint.indexOf("Soap/")) + "async/" + apiVersion;

      ConnectorConfig config = createConfig();
      config.setSessionId(partnerConfig.getSessionId());
      config.setRestEndpoint(restEndpoint);


      this.bulkConnection = getBulkConnection(config);
      success = true;
    } catch (RuntimeException e) {
      throw new RuntimeException("Failed to connect to salesforce bulk api; error - " + e, e);
    }
    return success;
  }

  /**
   * get BulkConnection instance
   * @return
   */
  public BulkConnection getBulkConnection(ConnectorConfig config) throws AsyncApiException {
    return new BulkConnection(config);
  }

  /**
   * Get Record set using salesforce specific API(Bulk API)
   * @param entity/tablename
   * @param predicateList of all predicate conditions
     * @return iterator with batch of records
   */
  private List<BatchIdAndResultId> getQueryResultIds(String entity, List<Predicate> predicateList) throws Exception {
    if (!bulkApiLogin()) {
      throw new IllegalArgumentException("Invalid Login");
    }

    try {
      boolean usingPkChunking = false;

      // Set bulk job attributes
      this.bulkJob.setObject(entity);
      this.bulkJob.setOperation(this.bulkApiUseQueryAll ? OperationEnum.queryAll : OperationEnum.query);
      this.bulkJob.setConcurrencyMode(ConcurrencyMode.Parallel);

      // use pk chunking if pk chunking is configured and the expected record count is larger than the pk chunking size
      if (this.pkChunking && (this.pkChunkingSkipCountCheck || getExpectedRecordCount() > this.pkChunkingSize)) {
        log.info("Enabling pk chunking with size {}", this.pkChunkingSize);
        this.bulkConnection.addHeader("Sforce-Enable-PKChunking", "chunkSize=" + this.pkChunkingSize);
        usingPkChunking = true;
      }

      // Result type as CSV
      this.bulkJob.setContentType(ContentType.CSV);

      this.bulkJob = this.bulkConnection.createJob(this.bulkJob);
      this.bulkJob = this.bulkConnection.getJobStatus(this.bulkJob.getId());

      // Construct query with the predicates
      String query = this.updatedQuery;
      if (!isNullPredicate(predicateList)) {
        String limitString = getLimitFromInputQuery(query);
        query = query.replace(limitString, "");

        Iterator<Predicate> i = predicateList.listIterator();
        while (i.hasNext()) {
          Predicate predicate = i.next();
          query = SqlQueryUtils.addPredicate(query, predicate.getCondition());
        }

        query = query + limitString;
      }

      log.info("QUERY:" + query);
      ByteArrayInputStream bout = new ByteArrayInputStream(query.getBytes(ConfigurationKeys.DEFAULT_CHARSET_ENCODING));

      BatchInfo bulkBatchInfo = this.bulkConnection.createBatchFromStream(this.bulkJob, bout);

      long expectedSizePerBatch = usingPkChunking ? this.pkChunkingSize : this.getExpectedRecordCount();

      int retryInterval = Math.min(MAX_RETRY_INTERVAL_SECS,
          30 + (int) Math.ceil((float) expectedSizePerBatch / 10000) * 2);
      log.info("Salesforce bulk api retry interval in seconds:" + retryInterval);

      // Get batch info with complete resultset (info id - refers to the resultset id corresponding to entire resultset)
      bulkBatchInfo = this.bulkConnection.getBatchInfo(this.bulkJob.getId(), bulkBatchInfo.getId());

      // wait for completion, failure, or formation of PK chunking batches
      while ((bulkBatchInfo.getState() != BatchStateEnum.Completed)
          && (bulkBatchInfo.getState() != BatchStateEnum.Failed)
          && (!usingPkChunking || bulkBatchInfo.getState() != BatchStateEnum.NotProcessed)) {
        Thread.sleep(retryInterval * 1000);
        bulkBatchInfo = this.bulkConnection.getBatchInfo(this.bulkJob.getId(), bulkBatchInfo.getId());
        log.debug("Bulk Api Batch Info:" + bulkBatchInfo);
        log.info("Waiting for bulk resultSetIds");
      }

      // Wait for pk chunking batches
      BatchInfoList batchInfoList = this.bulkConnection.getBatchInfoList(this.bulkJob.getId());

      if (usingPkChunking && bulkBatchInfo.getState() == BatchStateEnum.NotProcessed) {
        bulkBatchInfo = waitForPkBatches(batchInfoList, retryInterval);
      }

      if (bulkBatchInfo.getState() == BatchStateEnum.Failed) {
        log.error("Bulk batch failed: " + bulkBatchInfo.toString());
        throw new RuntimeException("Failed to get bulk batch info for jobId " + bulkBatchInfo.getJobId()
            + " error - " + bulkBatchInfo.getStateMessage());
      }

      // Get resultset ids of all the batches from the batch info list
      List<BatchIdAndResultId> batchIdAndResultIdList = Lists.newArrayList();

      for (BatchInfo bi : batchInfoList.getBatchInfo()) {
        QueryResultList list = this.bulkConnection.getQueryResultList(this.bulkJob.getId(), bi.getId());

        for (String result : list.getResult()) {
          batchIdAndResultIdList.add(new BatchIdAndResultId(bi.getId(), result));
        }
      }

      log.info("QueryResultList: " + batchIdAndResultIdList);

      return batchIdAndResultIdList;

    } catch (RuntimeException | AsyncApiException | InterruptedException e) {
      throw new RuntimeException(
          "Failed to get query result ids from salesforce using bulk api; error - " + e.getMessage(), e);
    }
  }

  /**
   * Get a buffered reader wrapping the query result stream for the result with the specified index
   * @param index index the {@link #bulkResultIdList}
   * @return a {@link BufferedReader}
   * @throws AsyncApiException
   */
  private BufferedReader getBulkBufferedReader(int index) throws AsyncApiException {
    return new BufferedReader(new InputStreamReader(
        this.bulkConnection.getQueryResultStream(this.bulkJob.getId(), this.bulkResultIdList.get(index).getBatchId(),
            this.bulkResultIdList.get(index).getResultId()), ConfigurationKeys.DEFAULT_CHARSET_ENCODING));
  }

  /**
   * Fetch records into a {@link RecordSetList} up to the configured batch size {@link #batchSize}. This batch is not
   * the entire Salesforce result batch. It is an internal batch in the extractor for buffering a subset of the result
   * stream that comes from a Salesforce batch for more efficient processing.
   * @param rs the record set to fetch into
   * @param initialRecordCount Initial record count to use. This should correspond to the number of records already in rs.
   *                           This is used to limit the number of records returned in rs to {@link #batchSize}.
   * @throws DataRecordException
   * @throws IOException
   */
  private void fetchResultBatch(RecordSetList<JsonElement> rs, int initialRecordCount)
      throws DataRecordException, IOException {
    int recordCount = initialRecordCount;

    // Stream the resultset through CSV reader to identify columns in each record
    InputStreamCSVReader reader = new InputStreamCSVReader(this.bulkBufferedReader);

    // Get header if it is first run of a new resultset
    if (this.isNewBulkResultSet()) {
      this.bulkRecordHeader = reader.nextRecord();
      this.bulkResultColumCount = this.bulkRecordHeader.size();
      this.setNewBulkResultSet(false);
    }

    // Get record from CSV reader stream
    while ((this.csvRecord = reader.nextRecord()) != null) {
      // Convert CSV record to JsonObject
      JsonObject jsonObject = Utils.csvToJsonObject(this.bulkRecordHeader, this.csvRecord, this.bulkResultColumCount);
      rs.add(jsonObject);
      recordCount++;
      this.bulkRecordCount++;

      // Insert records in record set until it reaches the batch size
      if (recordCount >= batchSize) {
        log.info("Total number of records processed so far: " + this.bulkRecordCount);
        break;
      }
    }
  }

  /**
   * Reinitialize the state of {@link #bulkBufferedReader} to handle network disconnects
   * @throws IOException
   * @throws AsyncApiException
   */
  private void reinitializeBufferedReader() throws IOException, AsyncApiException {
    // close reader and get a new input stream to reconnect to resolve intermittent network errors
    this.bulkBufferedReader.close();
    this.bulkBufferedReader = getBulkBufferedReader(this.bulkResultIdCount - 1);

    // if the result set is partially processed then we need to skip over processed records
    if (!isNewBulkResultSet()) {
      List<String> lastCsvRecord = null;
      InputStreamCSVReader reader = new InputStreamCSVReader(this.bulkBufferedReader);

      // skip header
      reader.nextRecord();

      int recordsToSkip = this.bulkRecordCount - this.prevBulkRecordCount;
      log.info("Skipping {} records on retry: ", recordsToSkip);

      for (int i = 0; i < recordsToSkip; i++) {
        lastCsvRecord = reader.nextRecord();
      }

      // make sure the last record processed before the error was the last record skipped so that the next
      // unprocessed record is processed in the next call to fetchResultBatch()
      if (recordsToSkip > 0) {
        if (!this.csvRecord.equals(lastCsvRecord)) {
          throw new RuntimeException("Repositioning after reconnecting did not point to the expected record");
        }
      }
    }
  }

  /**
   * Fetch a result batch with retry for network errors
   * @param rs the {@link RecordSetList} to fetch into
   */
  private void fetchResultBatchWithRetry(RecordSetList<JsonElement> rs)
      throws AsyncApiException, DataRecordException, IOException {
    boolean success = false;
    int retryCount = 0;
    int recordCountBeforeFetch = this.bulkRecordCount;

    do {
      try {
        // reinitialize the reader to establish a new connection to handle transient network errors
        if (retryCount > 0) {
          reinitializeBufferedReader();
        }

        // on retries there may already be records in rs, so pass the number of records as the initial count
        fetchResultBatch(rs, this.bulkRecordCount - recordCountBeforeFetch);
        success = true;
      } catch (IOException e) {
        if (retryCount < this.fetchRetryLimit) {
          log.info("Exception while fetching data, retrying: " + e.getMessage(), e);
          retryCount++;
        } else {
          log.error("Exception while fetching data: " + e.getMessage(), e);
          throw e;
        }
      }
    } while (!success);
  }

  /**
   * Get data from the bulk api input stream
     * @return record set with each record as a JsonObject
   */
  private RecordSet<JsonElement> getBulkData() throws DataRecordException {
    log.debug("Processing bulk api batch...");
    RecordSetList<JsonElement> rs = new RecordSetList<>();

    try {
      // if Buffer is empty then get stream for the new resultset id
      if (this.bulkBufferedReader == null || !this.bulkBufferedReader.ready()) {

        // log the number of records from each result set after it is processed (bulkResultIdCount > 0)
        if (this.bulkResultIdCount > 0) {
          log.info("Result set {} had {} records", this.bulkResultIdCount,
              this.bulkRecordCount - this.prevBulkRecordCount);
        }

        // if there is unprocessed resultset id then get result stream for that id
        if (this.bulkResultIdCount < this.bulkResultIdList.size()) {
          log.info("Stream resultset for resultId:" + this.bulkResultIdList.get(this.bulkResultIdCount));
          this.setNewBulkResultSet(true);

          if (this.bulkBufferedReader != null) {
            this.bulkBufferedReader.close();
          }

          this.bulkBufferedReader = getBulkBufferedReader(this.bulkResultIdCount);
          this.bulkResultIdCount++;
          this.prevBulkRecordCount = bulkRecordCount;
        } else {
          // if result stream processed for all resultset ids then finish the bulk job
          log.info("Bulk job is finished");
          this.setBulkJobFinished(true);
          return rs;
        }
      }

      // fetch a batch of results with retry for network errors
      fetchResultBatchWithRetry(rs);

    } catch (Exception e) {
      throw new DataRecordException("Failed to get records from salesforce; error - " + e.getMessage(), e);
    }

    return rs;
  }

  @Override
  public void closeConnection() throws Exception {
    if (this.bulkConnection != null
        && !this.bulkConnection.getJobStatus(this.bulkJob.getId()).getState().toString().equals("Closed")) {
      log.info("Closing salesforce bulk job connection");
      this.bulkConnection.closeJob(this.bulkJob.getId());
    }
  }

  public static List<Command> constructGetCommand(String restQuery) {
    return Arrays.asList(new RestApiCommand().build(Arrays.asList(restQuery), RestApiCommandType.GET));
  }

  /**
   * Waits for the PK batches to complete. The wait will stop after all batches are complete or on the first failed batch
   * @param batchInfoList list of batch info
   * @param retryInterval the polling interval
   * @return the last {@link BatchInfo} processed
   * @throws InterruptedException
   * @throws AsyncApiException
   */
  private BatchInfo waitForPkBatches(BatchInfoList batchInfoList, int retryInterval)
      throws InterruptedException, AsyncApiException {
    BatchInfo batchInfo = null;
    BatchInfo[] batchInfos = batchInfoList.getBatchInfo();

    // Wait for all batches other than the first one. The first one is not processed in PK chunking mode
    for (int i = 1; i < batchInfos.length; i++) {
      BatchInfo bi = batchInfos[i];

      // get refreshed job status
      bi = this.bulkConnection.getBatchInfo(this.bulkJob.getId(), bi.getId());

      while ((bi.getState() != BatchStateEnum.Completed)
          && (bi.getState() != BatchStateEnum.Failed)) {
        Thread.sleep(retryInterval * 1000);
        bi = this.bulkConnection.getBatchInfo(this.bulkJob.getId(), bi.getId());
        log.debug("Bulk Api Batch Info:" + bi);
        log.info("Waiting for bulk resultSetIds");
      }

      batchInfo = bi;

      // exit if there was a failure
      if (batchInfo.getState() == BatchStateEnum.Failed) {
        break;
      }
    }

    return batchInfo;
  }

  //Moving config creation in a separate method for custom config parameters like setting up transport factory.
  public ConnectorConfig createConfig() {
    ConnectorConfig config = new ConnectorConfig();
    config.setCompression(true);
    try {
      config.setTraceFile("traceLogs.txt");
    } catch (FileNotFoundException e) {
      e.printStackTrace();
    }
    config.setTraceMessage(false);
    config.setPrettyPrintXml(true);

    if (super.workUnitState.contains(ConfigurationKeys.SOURCE_CONN_USE_PROXY_URL)
        && !super.workUnitState.getProp(ConfigurationKeys.SOURCE_CONN_USE_PROXY_URL).isEmpty()) {
      config.setProxy(super.workUnitState.getProp(ConfigurationKeys.SOURCE_CONN_USE_PROXY_URL),
          super.workUnitState.getPropAsInt(ConfigurationKeys.SOURCE_CONN_USE_PROXY_PORT));
    }
    return config;
  }

  @Data
  private static class BatchIdAndResultId {
    private final String batchId;
    private final String resultId;
  }
}
