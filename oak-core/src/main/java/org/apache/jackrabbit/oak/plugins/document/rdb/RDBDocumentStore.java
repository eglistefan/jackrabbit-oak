/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.plugins.document.rdb;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.jackrabbit.oak.plugins.document.UpdateUtils.checkConditions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Lock;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.sql.DataSource;

import org.apache.jackrabbit.oak.cache.CacheStats;
import org.apache.jackrabbit.oak.cache.CacheValue;
import org.apache.jackrabbit.oak.plugins.document.Collection;
import org.apache.jackrabbit.oak.plugins.document.Document;
import org.apache.jackrabbit.oak.plugins.document.DocumentMK;
import org.apache.jackrabbit.oak.plugins.document.DocumentStore;
import org.apache.jackrabbit.oak.plugins.document.DocumentStoreException;
import org.apache.jackrabbit.oak.plugins.document.NodeDocument;
import org.apache.jackrabbit.oak.plugins.document.Revision;
import org.apache.jackrabbit.oak.plugins.document.StableRevisionComparator;
import org.apache.jackrabbit.oak.plugins.document.UpdateOp;
import org.apache.jackrabbit.oak.plugins.document.UpdateOp.Condition;
import org.apache.jackrabbit.oak.plugins.document.UpdateOp.Key;
import org.apache.jackrabbit.oak.plugins.document.UpdateOp.Operation;
import org.apache.jackrabbit.oak.plugins.document.UpdateUtils;
import org.apache.jackrabbit.oak.plugins.document.cache.CacheInvalidationStats;
import org.apache.jackrabbit.oak.plugins.document.mongo.MongoDocumentStore;
import org.apache.jackrabbit.oak.plugins.document.util.StringValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.cache.Cache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Striped;

/**
 * Implementation of {@link DocumentStore} for relational databases.
 * 
 * <h3>Supported Databases</h3>
 * <p>
 * The code is supposed to be sufficiently generic to run with a variety of
 * database implementations. However, the tables are created when required to
 * simplify testing, and <em>that</em> code specifically supports these
 * databases:
 * <ul>
 * <li>h2</li>
 * <li>IBM DB2</li>
 * <li>Postgres</li>
 * <li>MariaDB (MySQL) (experimental)</li>
 * <li>Oracle (experimental)</li>
 * </ul>
 * 
 * <h3>Table Layout</h3>
 * <p>
 * Data for each of the DocumentStore's {@link Collection}s is stored in its own
 * database table (with a name matching the collection).
 * <p>
 * The tables essentially implement key/value storage, where the key usually is
 * derived from an Oak path, and the value is a serialization of a
 * {@link Document} (or a part of one). Additional fields are used for queries,
 * debugging, and concurrency control:
 * <table style="text-align: left;">
 * <thead>
 * <tr>
 * <th>Column</th>
 * <th>Type</th>
 * <th>Description</th>
 * </tr>
 * </thead> <tbody>
 * <tr>
 * <th>ID</th>
 * <td>varchar(512) not null primary key</td>
 * <td>the document's key (for databases that can not handle 512 character
 * primary keys, such as MySQL, varbinary is possible as well; note that this
 * currently needs to be hardcoded)</td>
 * </tr>
 * <tr>
 * <th>MODIFIED</th>
 * <td>bigint</td>
 * <td>low-resolution timestamp
 * </tr>
 * <tr>
 * <th>HASBINARY</th>
 * <td>smallint</td>
 * <td>flag indicating whether the document has binary properties
 * </tr>
 * <tr>
 * <th>DELETEDONCE</th>
 * <td>smallint</td>
 * <td>flag indicating whether the document has been deleted once
 * </tr>
 * <tr>
 * <th>MODCOUNT</th>
 * <td>bigint</td>
 * <td>modification counter, used for avoiding overlapping updates</td>
 * </tr>
 * <tr>
 * <th>DSIZE</th>
 * <td>bigint</td>
 * <td>the approximate size of the document's JSON serialization (for debugging
 * purposes)</td>
 * </tr>
 * <tr>
 * <th>DATA</th>
 * <td>varchar(16384)</td>
 * <td>the document's JSON serialization (only used for small document sizes, in
 * which case BDATA (below) is not set), or a sequence of JSON serialized update
 * operations to be applied against the last full serialization</td>
 * </tr>
 * <tr>
 * <th>BDATA</th>
 * <td>blob</td>
 * <td>the document's JSON serialization (usually GZIPped, only used for "large"
 * documents)</td>
 * </tr>
 * </tbody>
 * </table>
 * <p>
 * The names of database tables can be prefixed; the purpose is mainly for
 * testing, as tables can also be dropped automatically when the store is
 * disposed (this only happens for those tables that have been created on
 * demand)
 * <p>
 * <em>Note that the database needs to be created/configured to support all Unicode
 * characters in text fields, and to collate by Unicode code point (in DB2: "collate using identity",
 * in Postgres: "C").
 * THIS IS NOT THE DEFAULT!</em>
 * <p>
 * <em>For MySQL, the database parameter "max_allowed_packet" needs to be increased to support ~16 blobs.</em>
 * 
 * <h3>Caching</h3>
 * <p>
 * The cache borrows heavily from the {@link MongoDocumentStore} implementation;
 * however it does not support the off-heap mechanism yet.
 * 
 * <h3>Queries</h3>
 * <p>
 * The implementation currently supports only three indexed properties:
 * "_bin", "deletedOnce", and "_modified". Attempts to use a different indexed property will
 * cause a {@link DocumentStoreException}.
 */
public class RDBDocumentStore implements DocumentStore {

    /**
     * Creates a {@linkplain RDBDocumentStore} instance using the provided
     * {@link DataSource}, {@link DocumentMK.Builder}, and {@link RDBOptions}.
     */
    public RDBDocumentStore(DataSource ds, DocumentMK.Builder builder, RDBOptions options) {
        try {
            initialize(ds, builder, options);
        } catch (Exception ex) {
            throw new DocumentStoreException("initializing RDB document store", ex);
        }
    }

    /**
     * Creates a {@linkplain RDBDocumentStore} instance using the provided
     * {@link DataSource}, {@link DocumentMK.Builder}, and default
     * {@link RDBOptions}.
     */
    public RDBDocumentStore(DataSource ds, DocumentMK.Builder builder) {
        this(ds, builder, new RDBOptions());
    }

    @Override
    public <T extends Document> T find(Collection<T> collection, String id) {
        return find(collection, id, Integer.MAX_VALUE);
    }

    @Override
    public <T extends Document> T find(final Collection<T> collection, final String id, int maxCacheAge) {
        return readDocumentCached(collection, id, maxCacheAge);
    }

    @Nonnull
    @Override
    public <T extends Document> List<T> query(Collection<T> collection, String fromKey, String toKey, int limit) {
        return query(collection, fromKey, toKey, null, 0, limit);
    }

    @Nonnull
    @Override
    public <T extends Document> List<T> query(Collection<T> collection, String fromKey, String toKey, String indexedProperty,
            long startValue, int limit) {
        return internalQuery(collection, fromKey, toKey, indexedProperty, startValue, limit);
    }

    @Override
    public <T extends Document> void remove(Collection<T> collection, String id) {
        delete(collection, id);
        invalidateCache(collection, id, true);
    }

    @Override
    public <T extends Document> void remove(Collection<T> collection, List<String> ids) {
        for (String id : ids) {
            invalidateCache(collection, id, true);
        }
        delete(collection, ids);
    }

    @Override
    public <T extends Document> int remove(Collection<T> collection,
                                            Map<String, Map<Key, Condition>> toRemove) {
        int num = delete(collection, toRemove);
        for (String id : toRemove.keySet()) {
            invalidateCache(collection, id, true);
        }
        return num;
    }

    @Override
    public <T extends Document> boolean create(Collection<T> collection, List<UpdateOp> updateOps) {
        return internalCreate(collection, updateOps);
    }

    @Override
    public <T extends Document> void update(Collection<T> collection, List<String> keys, UpdateOp updateOp) {
        internalUpdate(collection, keys, updateOp);
    }

    @Override
    public <T extends Document> T createOrUpdate(Collection<T> collection, UpdateOp update) {
        return internalCreateOrUpdate(collection, update, true, false);
    }

    @Override
    public <T extends Document> T findAndUpdate(Collection<T> collection, UpdateOp update) {
        return internalCreateOrUpdate(collection, update, false, true);
    }

    @Override
    public CacheInvalidationStats invalidateCache() {
        for (NodeDocument nd : nodesCache.asMap().values()) {
            nd.markUpToDate(0);
        }
        return null;
    }

    @Override
    public <T extends Document> void invalidateCache(Collection<T> collection, String id) {
        invalidateCache(collection, id, false);
    }

    private <T extends Document> void invalidateCache(Collection<T> collection, String id, boolean remove) {
        if (collection == Collection.NODES) {
            invalidateNodesCache(id, remove);
        }
    }

    private void invalidateNodesCache(String id, boolean remove) {
        StringValue key = new StringValue(id);
        Lock lock = getAndLock(id);
        try {
            if (remove) {
                nodesCache.invalidate(key);
            } else {
                NodeDocument entry = nodesCache.getIfPresent(key);
                if (entry != null) {
                    entry.markUpToDate(0);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    // used for diagnostics
    private String droppedTables = "";

    public String getDroppedTables() {
        return this.droppedTables;
    }

    @Override
    public void dispose() {
        if (!this.tablesToBeDropped.isEmpty()) {
            String dropped = "";
            LOG.debug("attempting to drop: " + this.tablesToBeDropped);
            for (String tname : this.tablesToBeDropped) {
                Connection con = null;
                try {
                    con = this.ch.getRWConnection();
                    Statement stmt = null;
                    try {
                        stmt = con.createStatement();
                        stmt.execute("drop table " + tname);
                        stmt.close();
                        con.commit();
                        dropped += tname + " ";
                    } catch (SQLException ex) {
                        LOG.debug("attempting to drop: " + tname, ex);
                    } finally {
                        this.ch.closeStatement(stmt);
                    }
                } catch (SQLException ex) {
                    LOG.debug("attempting to drop: " + tname, ex);
                } finally {
                    this.ch.closeConnection(con);
                }
            }
            this.droppedTables = dropped.trim();
        }
        this.ch = null;
    }

    @Override
    public <T extends Document> T getIfCached(Collection<T> collection, String id) {
        if (collection != Collection.NODES) {
            return null;
        } else {
            NodeDocument doc = nodesCache.getIfPresent(new StringValue(id));
            return castAsT(doc);
        }
    }

    @Override
    public CacheStats getCacheStats() {
        return this.cacheStats;
    }

    @Override
    public Map<String, String> getMetadata() {
        return metadata;
    }

    // implementation

    enum FETCHFIRSTSYNTAX { FETCHFIRST, LIMIT, TOP};


    private static void versionCheck(DatabaseMetaData md, int xmaj, int xmin, String description) throws SQLException {
        int maj = md.getDatabaseMajorVersion();
        int min = md.getDatabaseMinorVersion();
        if (maj < xmaj || (maj == xmaj && min < xmin)) {
            LOG.info("Unsupported " + description + " version: " + maj + "." + min + ", expected at least " + xmaj + "." + xmin);
        }
    }

    /**
     * Defines variation in the capabilities of different RDBs.
     */
    protected enum DB {
        DEFAULT("default") {
        },

        H2("H2") {
            @Override
            public void checkVersion(DatabaseMetaData md) throws SQLException {
                versionCheck(md, 1, 4, description);
            }
        },

        POSTGRES("PostgreSQL") {
            @Override
            public void checkVersion(DatabaseMetaData md) throws SQLException {
                versionCheck(md, 9, 3, description);
            }

            @Override
            public String getTableCreationStatement(String tableName) {
                return ("create table " + tableName + " (ID varchar(512) not null primary key, MODIFIED bigint, HASBINARY smallint, DELETEDONCE smallint, MODCOUNT bigint, CMODCOUNT bigint, DSIZE bigint, DATA varchar(16384), BDATA bytea)");
            }

            @Override
            public String getAdditionalDiagnostics(RDBConnectionHandler ch, String tableName) {
                Connection con = null;
                Map<String, String> result = new HashMap<String, String>();
                try {
                    con = ch.getROConnection();
                    String cat = con.getCatalog();
                    PreparedStatement stmt = con.prepareStatement("SELECT pg_encoding_to_char(encoding), datcollate FROM pg_database WHERE datname=?");
                    stmt.setString(1, cat);
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        result.put("pg_encoding_to_char(encoding)", rs.getString(1));
                        result.put("datcollate", rs.getString(2));
                    }
                    stmt.close();
                    con.commit();
                } catch (SQLException ex) {
                    LOG.debug("while getting diagnostics", ex);
                } finally {
                    ch.closeConnection(con);
                }
                return result.toString();
            }
        },

        DB2("DB2") {
            @Override
            public void checkVersion(DatabaseMetaData md) throws SQLException {
                versionCheck(md, 10, 5, description);
            }

            @Override
            public String getAdditionalDiagnostics(RDBConnectionHandler ch, String tableName) {
                Connection con = null;
                Map<String, String> result = new HashMap<String, String>();
                try {
                    con = ch.getROConnection();
                    // we can't look up by schema as con.getSchema is JDK 1.7
                    PreparedStatement stmt = con.prepareStatement("SELECT CODEPAGE, COLLATIONSCHEMA, COLLATIONNAME, TABSCHEMA FROM SYSCAT.COLUMNS WHERE COLNAME=? and COLNO=0 AND UPPER(TABNAME)=UPPER(?)");
                    stmt.setString(1, "ID");
                    stmt.setString(2, tableName);
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next() && result.size() < 20) {
                        // thus including the schema name here
                        String schema = rs.getString("TABSCHEMA").trim();
                        result.put(schema + ".CODEPAGE", rs.getString("CODEPAGE").trim());
                        result.put(schema + ".COLLATIONSCHEMA", rs.getString("COLLATIONSCHEMA").trim());
                        result.put(schema + ".COLLATIONNAME", rs.getString("COLLATIONNAME").trim());
                    }
                    stmt.close();
                    con.commit();
                } catch (SQLException ex) {
                    LOG.debug("while getting diagnostics", ex);
                } finally {
                    ch.closeConnection(con);
                }
                return result.toString();
            }
},

        ORACLE("Oracle") {
            @Override
            public void checkVersion(DatabaseMetaData md) throws SQLException {
                versionCheck(md, 12, 1, description);
            }

            @Override
            public String getInitializationStatement() {
                // see https://issues.apache.org/jira/browse/OAK-1914
                // for some reason, the default for NLS_SORT is incorrect
                return ("ALTER SESSION SET NLS_SORT='BINARY'");
            }

            @Override
            public String getTableCreationStatement(String tableName) {
                // see https://issues.apache.org/jira/browse/OAK-1914
                return ("create table " + tableName + " (ID varchar(512) not null primary key, MODIFIED number, HASBINARY number, DELETEDONCE number, MODCOUNT number, CMODCOUNT number, DSIZE number, DATA varchar(4000), BDATA blob)");
            }

            @Override
            public String getAdditionalDiagnostics(RDBConnectionHandler ch, String tableName) {
                Connection con = null;
                Map<String, String> result = new HashMap<String, String>();
                try {
                    con = ch.getROConnection();
                    Statement stmt = con.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT PARAMETER, VALUE from NLS_DATABASE_PARAMETERS WHERE PARAMETER IN ('NLS_COMP', 'NLS_CHARACTERSET')");
                    while (rs.next()) {
                        result.put(rs.getString(1), rs.getString(2));
                    }
                    stmt.close();
                    con.commit();
                } catch (SQLException ex) {
                    LOG.debug("while getting diagnostics", ex);
                } finally {
                    ch.closeConnection(con);
                }
                return result.toString();
            }
        },

        MYSQL("MySQL") {
            @Override
            public void checkVersion(DatabaseMetaData md) throws SQLException {
                versionCheck(md, 5, 5, description);
            }

            @Override
            public boolean isPrimaryColumnByteEncoded() {
                // TODO: we should dynamically detect this
                return true;
            }

            @Override
            public String getTableCreationStatement(String tableName) {
                // see https://issues.apache.org/jira/browse/OAK-1913
                return ("create table " + tableName + " (ID varbinary(512) not null primary key, MODIFIED bigint, HASBINARY smallint, DELETEDONCE smallint, MODCOUNT bigint, CMODCOUNT bigint, DSIZE bigint, DATA varchar(16000), BDATA longblob)");
            }

            @Override
            public FETCHFIRSTSYNTAX getFetchFirstSyntax() {
                return FETCHFIRSTSYNTAX.LIMIT;
            }

            @Override
            public String getConcatQueryString(int dataOctetLimit, int dataLength) {
                return "CONCAT(DATA, ?)";
            }

            @Override
            public String getAdditionalDiagnostics(RDBConnectionHandler ch, String tableName) {
                Connection con = null;
                Map<String, String> result = new HashMap<String, String>();
                try {
                    con = ch.getROConnection();
                    PreparedStatement stmt = con.prepareStatement("SHOW TABLE STATUS LIKE ?");
                    stmt.setString(1, tableName);
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        result.put("collation", rs.getString("Collation"));
                    }
                    stmt.close();
                    con.commit();
                } catch (SQLException ex) {
                    LOG.debug("while getting diagnostics", ex);
                } finally {
                    ch.closeConnection(con);
                }
                return result.toString();
            }
        },

        MSSQL("Microsoft SQL Server") {
            @Override
            public void checkVersion(DatabaseMetaData md) throws SQLException {
                versionCheck(md, 11, 0, description);
            }

            @Override
            public boolean isPrimaryColumnByteEncoded() {
                // TODO: we should dynamically detect this
                return true;
            }

            @Override
            public String getTableCreationStatement(String tableName) {
                // see https://issues.apache.org/jira/browse/OAK-2395
                return ("create table " + tableName + " (ID varbinary(512) not null primary key, MODIFIED bigint, HASBINARY smallint, DELETEDONCE smallint, MODCOUNT bigint, CMODCOUNT bigint, DSIZE bigint, DATA nvarchar(4000), BDATA varbinary(max))");
            }

            @Override
            public FETCHFIRSTSYNTAX getFetchFirstSyntax() {
                return FETCHFIRSTSYNTAX.TOP;
            }

            @Override
            public String getConcatQueryString(int dataOctetLimit, int dataLength) {
                /*
                 * To avoid truncation when concatenating force an error when
                 * limit is above the octet limit
                 */
                return "CASE WHEN LEN(DATA) <= " + (dataOctetLimit - dataLength) + " THEN (DATA + CAST(? AS nvarchar("
                        + dataOctetLimit + "))) ELSE (DATA + CAST(DATA AS nvarchar(max))) END";

            }

            @Override
            public String getGreatestQueryString(String column) {
                return "(select MAX(mod) from (VALUES (" + column + "), (?)) AS ALLMOD(mod))";
            }

            @Override
            public String getAdditionalDiagnostics(RDBConnectionHandler ch, String tableName) {
                Connection con = null;
                Map<String, String> result = new HashMap<String, String>();
                try {
                    con = ch.getROConnection();
                    String cat = con.getCatalog();
                    PreparedStatement stmt = con.prepareStatement("SELECT collation_name FROM sys.databases WHERE name=?");
                    stmt.setString(1, cat);
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        result.put("collation_name", rs.getString(1));
                    }
                    stmt.close();
                    con.commit();
                } catch (SQLException ex) {
                    LOG.debug("while getting diagnostics", ex);
                } finally {
                    ch.closeConnection(con);
                }
                return result.toString();
            }
        };

        /**
         * Check the database brand and version
         */
        public void checkVersion(DatabaseMetaData md) throws SQLException {
            LOG.info("Unknown database type: " + md.getDatabaseProductName());
        }

        /**
         * If the primary column is encoded in bytes.
         * Default false
         * @return boolean
         */
        public boolean isPrimaryColumnByteEncoded() {
            return false;
        }

        /**
         * Allows case in select. Default true.
         */
        public boolean allowsCaseInSelect() {
            return true;
        }

        /**
         * Query syntax for "FETCH FIRST"
         */
        public FETCHFIRSTSYNTAX getFetchFirstSyntax() {
            return FETCHFIRSTSYNTAX.FETCHFIRST;
        }

        /**
         * Returns the CONCAT function or its equivalent function or sub-query.
         * Note that the function MUST NOT cause a truncated value to be
         * written!
         *
         * @param dataOctetLimit
         *            expected capacity of data column
         * @param dataLength
         *            length of string to be inserted
         * 
         * @return the concat query string
         */
        public String getConcatQueryString(int dataOctetLimit, int dataLength) {
            return "DATA || CAST(? AS varchar(" + dataOctetLimit + "))";
        }

        /**
         * Returns the GREATEST function or its equivalent function or sub-query
         * supported.
         *
         * @return the greatest query string
         */
        public String getGreatestQueryString(String column) {
            return "GREATEST(" + column + ", ?)";
        }

        /**
         * Query for any required initialization of the DB.
         * 
         * @return the DB initialization SQL string
         */
        public @Nonnull String getInitializationStatement() {
            return "";
        }

        /**
         * Table creation statement string
         *
         * @param tableName
         * @return the table creation string
         */
        public String getTableCreationStatement(String tableName) {
            return "create table "
                    + tableName
                    + " (ID varchar(512) not null primary key, MODIFIED bigint, HASBINARY smallint, DELETEDONCE smallint, MODCOUNT bigint, CMODCOUNT bigint, DSIZE bigint, DATA varchar(16384), BDATA blob("
                    + 1024 * 1024 * 1024 + "))";
        }

        public String getAdditionalDiagnostics(RDBConnectionHandler ch, String tableName) {
            return "";
        }

        protected String description;

        private DB(String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return this.description;
        }

        @Nonnull
        public static DB getValue(String desc) {
            for (DB db : DB.values()) {
                if (db.description.equals(desc)) {
                    return db;
                } else if (db == DB2 && desc.startsWith("DB2/")) {
                    return db;
                }
            }

            LOG.error("DB type " + desc + " unknown, trying default settings");
            DEFAULT.description = desc + " - using default settings";
            return DEFAULT;
        }
    }

    private static final String MODIFIED = "_modified";
    private static final String MODCOUNT = "_modCount";

    /**
     * Optional counter for changes to "_collisions" map ({@link NodeDocument#COLLISIONS}).
     */
    private static final String COLLISIONSMODCOUNT = "_collisionsModCount";

    private static final String ID = "_id";

    private static final Logger LOG = LoggerFactory.getLogger(RDBDocumentStore.class);

    private final Comparator<Revision> comparator = StableRevisionComparator.REVERSE;

    private Exception callStack;

    private RDBConnectionHandler ch;

    // from options
    private Set<String> tablesToBeDropped = new HashSet<String>();

    // table names
    private String tnNodes, tnClusterNodes, tnSettings; 

    // ratio between Java characters and UTF-8 encoding
    // a) single characters will fit into 3 bytes
    // b) a surrogate pair (two Java characters) will fit into 4 bytes
    // thus...
    private static final int CHAR2OCTETRATIO = 3;

    // capacity of DATA column
    private int dataLimitInOctets = 16384;

    // number of retries for updates
    private static final int RETRIES = 10;

    // see OAK-2044
    protected static final boolean USECMODCOUNT = true;

    // DB-specific information
    private DB db;

    private Map<String, String> metadata;

    // set of supported indexed properties
    private static final Set<String> INDEXEDPROPERTIES = new HashSet<String>(Arrays.asList(new String[] { MODIFIED,
            NodeDocument.HAS_BINARY_FLAG, NodeDocument.DELETED_ONCE }));

    // set of properties not serialized to JSON
    private static final Set<String> COLUMNPROPERTIES = new HashSet<String>(Arrays.asList(new String[] { ID,
            NodeDocument.HAS_BINARY_FLAG, NodeDocument.DELETED_ONCE, COLLISIONSMODCOUNT, MODIFIED, MODCOUNT }));

    private final RDBDocumentSerializer SR = new RDBDocumentSerializer(this, COLUMNPROPERTIES);

    private void initialize(DataSource ds, DocumentMK.Builder builder, RDBOptions options) throws Exception {

        this.tnNodes = RDBJDBCTools.createTableName(options.getTablePrefix(), "NODES");
        this.tnClusterNodes = RDBJDBCTools.createTableName(options.getTablePrefix(), "CLUSTERNODES");
        this.tnSettings = RDBJDBCTools.createTableName(options.getTablePrefix(), "SETTINGS");

        this.ch = new RDBConnectionHandler(ds);
        this.callStack = LOG.isDebugEnabled() ? new Exception("call stack of RDBDocumentStore creation") : null;

        this.nodesCache = builder.buildDocumentCache(this);
        this.cacheStats = new CacheStats(nodesCache, "Document-Documents", builder.getWeigher(), builder.getDocumentCacheSize());

        Connection con = this.ch.getRWConnection();
        DatabaseMetaData md = con.getMetaData();
        String dbDesc = md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
        String driverDesc = md.getDriverName() + " " + md.getDriverVersion();
        String dbUrl = md.getURL();

        this.db = DB.getValue(md.getDatabaseProductName());
        this.metadata = ImmutableMap.<String,String>builder()
                .put("type", "rdb")
                .put("db", md.getDatabaseProductName())
                .put("version", md.getDatabaseProductVersion())
                .build();
        db.checkVersion(md);

        if (! "".equals(db.getInitializationStatement())) {
            Statement stmt = null;
            try {
                stmt = con.createStatement();
                stmt.execute(db.getInitializationStatement());
                stmt.close();
                con.commit();
            }
            finally {
                this.ch.closeStatement(stmt);
            }
        }

        List<String> tablesCreated = new ArrayList<String>();
        List<String> tablesPresent = new ArrayList<String>();
        try {
            createTableFor(con, Collection.CLUSTER_NODES, tablesCreated, tablesPresent);
            createTableFor(con, Collection.NODES, tablesCreated, tablesPresent);
            createTableFor(con, Collection.SETTINGS, tablesCreated, tablesPresent);
        } finally {
            con.commit();
            con.close();
        }

        if (options.isDropTablesOnClose()) {
            tablesToBeDropped.addAll(tablesCreated);
        }

        String diag = db.getAdditionalDiagnostics(this.ch, this.tnNodes);

        LOG.info("RDBDocumentStore instantiated for database " + dbDesc + ", using driver: " + driverDesc + ", connecting to: "
                + dbUrl + (diag.isEmpty() ? "" : (", properties: " + diag)));
        if (!tablesPresent.isEmpty()) {
            LOG.info("Tables present upon startup: " + tablesPresent);
        }
        if (!tablesCreated.isEmpty()) {
            LOG.info("Tables created upon startup: " + tablesCreated
                    + (options.isDropTablesOnClose() ? " (will be dropped on exit)" : ""));
        }
    }

    private void createTableFor(Connection con, Collection<? extends Document> col, List<String> tablesCreated, List<String> tablesPresent) throws SQLException {
        String dbname = this.db.toString();
        if (con.getMetaData().getURL() != null) {
            dbname += " (" + con.getMetaData().getURL() + ")";
        }
        String tableName = getTable(col);

        PreparedStatement checkStatement = null;
        ResultSet checkResultSet = null;
        Statement creatStatement = null;
        try {
            checkStatement = con.prepareStatement("select DATA from " + tableName + " where ID = ?");
            checkStatement.setString(1, "0:/");
            checkResultSet = checkStatement.executeQuery();

            if (col.equals(Collection.NODES)) {
                // try to discover size of DATA column
                ResultSetMetaData met = checkResultSet.getMetaData();
                this.dataLimitInOctets = met.getPrecision(1);
            }
            tablesPresent.add(tableName);
        } catch (SQLException ex) {
            // table does not appear to exist
            con.rollback();

            try {
                creatStatement = con.createStatement();
                creatStatement.execute(this.db.getTableCreationStatement(tableName));
                creatStatement.close();

                con.commit();

                tablesCreated.add(tableName);

                if (col.equals(Collection.NODES)) {
                    PreparedStatement pstmt = con.prepareStatement("select DATA from " + tableName + " where ID = ?");
                    pstmt.setString(1, "0:/");
                    ResultSet rs = pstmt.executeQuery();
                    ResultSetMetaData met = rs.getMetaData();
                    this.dataLimitInOctets = met.getPrecision(1);
                }
            }
            catch (SQLException ex2) {
                LOG.error("Failed to create table " + tableName + " in " + dbname, ex2);
                throw ex2;
            }
        }
        finally {
            this.ch.closeResultSet(checkResultSet);
            this.ch.closeStatement(checkStatement);
            this.ch.closeStatement(creatStatement);
        }
    }

    @Override
    protected void finalize() {
        if (this.ch != null && this.callStack != null) {
            LOG.debug("finalizing RDBDocumentStore that was not disposed", this.callStack);
        }
    }

    private <T extends Document> T readDocumentCached(final Collection<T> collection, final String id, int maxCacheAge) {
        if (collection != Collection.NODES) {
            return readDocumentUncached(collection, id, null);
        } else {
            CacheValue cacheKey = new StringValue(id);
            NodeDocument doc = null;
            if (maxCacheAge > 0) {
                // first try without lock
                doc = nodesCache.getIfPresent(cacheKey);
                if (doc != null) {
                    long lastCheckTime = doc.getLastCheckTime();
                    if (lastCheckTime != 0) {
                        if (maxCacheAge == Integer.MAX_VALUE || System.currentTimeMillis() - lastCheckTime < maxCacheAge) {
                            return castAsT(unwrap(doc));
                        }
                    }
                }
            }
            try {
                Lock lock = getAndLock(id);
                try {
                    // caller really wants the cache to be cleared
                    if (maxCacheAge == 0) {
                        invalidateNodesCache(id, true);
                        doc = null;
                    }
                    final NodeDocument cachedDoc = doc;
                    doc = nodesCache.get(cacheKey, new Callable<NodeDocument>() {
                        @Override
                        public NodeDocument call() throws Exception {
                            NodeDocument doc = (NodeDocument) readDocumentUncached(collection, id, cachedDoc);
                            if (doc != null) {
                                doc.seal();
                            }
                            return wrap(doc);
                        }
                    });
                    // inspect the doc whether it can be used
                    long lastCheckTime = doc.getLastCheckTime();
                    if (lastCheckTime != 0 && (maxCacheAge == 0 || maxCacheAge == Integer.MAX_VALUE)) {
                        // we either just cleared the cache or the caller does
                        // not care;
                    } else if (lastCheckTime != 0 && (System.currentTimeMillis() - lastCheckTime < maxCacheAge)) {
                        // is new enough
                    } else {
                        // need to at least revalidate
                        NodeDocument ndoc = (NodeDocument) readDocumentUncached(collection, id, cachedDoc);
                        if (ndoc != null) {
                            ndoc.seal();
                        }
                        doc = wrap(ndoc);
                        nodesCache.put(cacheKey, doc);
                    }
                } finally {
                    lock.unlock();
                }
                return castAsT(unwrap(doc));
            } catch (ExecutionException e) {
                throw new IllegalStateException("Failed to load document with " + id, e);
            }
        }
    }

    @CheckForNull
    private <T extends Document> boolean internalCreate(Collection<T> collection, List<UpdateOp> updates) {
        try {
            // try up to CHUNKSIZE ops in one transaction
            for (List<UpdateOp> chunks : Lists.partition(updates, CHUNKSIZE)) {
                List<T> docs = new ArrayList<T>();
                for (UpdateOp update : chunks) {
                    T doc = collection.newDocument(this);
                    update.increment(MODCOUNT, 1);
                    if (hasChangesToCollisions(update)) {
                        update.increment(COLLISIONSMODCOUNT, 1);
                    }
                    UpdateUtils.applyChanges(doc, update, comparator);
                    if (!update.getId().equals(doc.getId())) {
                        throw new DocumentStoreException("ID mismatch - UpdateOp: " + update.getId() + ", ID property: "
                                + doc.getId());
                    }
                    docs.add(doc);
                }
                insertDocuments(collection, docs);
                for (T doc : docs) {
                    addToCache(collection, doc);
                }
            }
            return true;
        } catch (DocumentStoreException ex) {
            return false;
        }
    }

    @CheckForNull
    private <T extends Document> T internalCreateOrUpdate(Collection<T> collection, UpdateOp update, boolean allowCreate,
            boolean checkConditions) {
        T oldDoc = readDocumentCached(collection, update.getId(), Integer.MAX_VALUE);

        if (oldDoc == null) {
            if (!allowCreate) {
                return null;
            } else if (!update.isNew()) {
                throw new DocumentStoreException("Document does not exist: " + update.getId());
            }
            T doc = collection.newDocument(this);
            if (checkConditions && !checkConditions(doc, update.getConditions())) {
                return null;
            }
            update.increment(MODCOUNT, 1);
            if (hasChangesToCollisions(update)) {
                update.increment(COLLISIONSMODCOUNT, 1);
            }
            UpdateUtils.applyChanges(doc, update, comparator);
            try {
                insertDocuments(collection, Collections.singletonList(doc));
                addToCache(collection, doc);
                return oldDoc;
            } catch (DocumentStoreException ex) {
                // may have failed due to a race condition; try update instead
                // this is an edge case, so it's ok to bypass the cache
                // (avoiding a race condition where the DB is already updated
                // but the cache is not)
                oldDoc = readDocumentUncached(collection, update.getId(), null);
                if (oldDoc == null) {
                    // something else went wrong
                    LOG.error("insert failed, but document " + update.getId() + " is not present, aborting", ex);
                    throw (ex);
                }
                return internalUpdate(collection, update, oldDoc, checkConditions, RETRIES);
            }
        } else {
            T result = internalUpdate(collection, update, oldDoc, checkConditions, RETRIES);
            if (allowCreate && result == null) {
                // TODO OAK-2655 need to implement some kind of retry
                LOG.error("update of " + update.getId() + " failed, race condition?");
                throw new DocumentStoreException("update of " + update.getId() + " failed, race condition?");
            }
            return result;
        }
    }

    /**
     * @return previous version of document or <code>null</code>
     */
    @CheckForNull
    private <T extends Document> T internalUpdate(Collection<T> collection, UpdateOp update, T oldDoc, boolean checkConditions,
            int maxRetries) {
        T doc = applyChanges(collection, oldDoc, update, checkConditions);
        if (doc == null) {
            // conditions not met
            return null;
        } else {
            Lock l = getAndLock(update.getId());
            try {
                boolean success = false;

                int retries = maxRetries;
                while (!success && retries > 0) {
                    long lastmodcount = modcountOf(oldDoc);
                    success = updateDocument(collection, doc, update, lastmodcount);
                    if (!success) {
                        retries -= 1;
                        oldDoc = readDocumentCached(collection, update.getId(), Integer.MAX_VALUE);
                        if (oldDoc != null) {
                            long newmodcount = modcountOf(oldDoc);
                            if (lastmodcount == newmodcount) {
                                // cached copy did not change so it probably was
                                // updated by a different instance, get a fresh one
                                oldDoc = readDocumentUncached(collection, update.getId(), null);
                            }
                        }

                        if (oldDoc == null) {
                            // document was there but is now gone
                            LOG.debug("failed to apply update because document is gone in the meantime: " + update.getId(), new Exception("call stack"));
                            return null;
                        }

                        doc = applyChanges(collection, oldDoc, update, checkConditions);
                        if (doc == null) {
                            return null;
                        }
                    } else {
                        if (collection == Collection.NODES) {
                            applyToCache((NodeDocument) oldDoc, (NodeDocument) doc);
                        }
                    }
                }

                if (!success) {
                    throw new DocumentStoreException("failed update of " + doc.getId() + " (race?) after " + maxRetries
                            + " retries");
                }

                return oldDoc;
            } finally {
                l.unlock();
            }
        }
    }

    @CheckForNull
    private <T extends Document> T applyChanges(Collection<T> collection, T oldDoc, UpdateOp update, boolean checkConditions) {
        T doc = collection.newDocument(this);
        oldDoc.deepCopy(doc);
        if (checkConditions && !checkConditions(doc, update.getConditions())) {
            return null;
        }
        if (hasChangesToCollisions(update)) {
            update.increment(COLLISIONSMODCOUNT, 1);
        }
        update.increment(MODCOUNT, 1);
        UpdateUtils.applyChanges(doc, update, comparator);
        doc.seal();
        return doc;
    }

    @CheckForNull
    private <T extends Document> void internalUpdate(Collection<T> collection, List<String> ids, UpdateOp update) {

        if (isAppendableUpdate(update) && !requiresPreviousState(update)) {
            long modified = getModifiedFromUpdate(update);
            String appendData = SR.asString(update);

            for (List<String> chunkedIds : Lists.partition(ids, CHUNKSIZE)) {
                // remember what we already have in the cache
                Map<String, NodeDocument> cachedDocs = Collections.emptyMap();
                if (collection == Collection.NODES) {
                    cachedDocs = new HashMap<String, NodeDocument>();
                    for (String key : chunkedIds) {
                        cachedDocs.put(key, nodesCache.getIfPresent(new StringValue(key)));
                    }
                }

                Connection connection = null;
                String tableName = getTable(collection);
                boolean success = false;
                try {
                    connection = this.ch.getRWConnection();
                    success = dbBatchedAppendingUpdate(connection, tableName, chunkedIds, modified, appendData);
                    connection.commit();
                } catch (SQLException ex) {
                    success = false;
                    this.ch.rollbackConnection(connection);
                } finally {
                    this.ch.closeConnection(connection);
                }
                if (success) {
                    for (Entry<String, NodeDocument> entry : cachedDocs.entrySet()) {
                        T oldDoc = castAsT(entry.getValue());
                        if (oldDoc == null) {
                            // make sure concurrently loaded document is
                            // invalidated
                            nodesCache.invalidate(new StringValue(entry.getKey()));
                        } else {
                            T newDoc = applyChanges(collection, oldDoc, update, true);
                            if (newDoc != null) {
                                applyToCache((NodeDocument) oldDoc, (NodeDocument) newDoc);
                            }
                        }
                    }
                } else {
                    for (String id : chunkedIds) {
                        UpdateOp up = update.copy();
                        up = up.shallowCopy(id);
                        internalCreateOrUpdate(collection, up, false, true);
                    }
                }
            }
        } else {
            for (String id : ids) {
                UpdateOp up = update.copy();
                up = up.shallowCopy(id);
                internalCreateOrUpdate(collection, up, false, true);
            }
        }
    }

    private <T extends Document> List<T> internalQuery(Collection<T> collection, String fromKey, String toKey,
            String indexedProperty, long startValue, int limit) {
        Connection connection = null;
        String tableName = getTable(collection);
        List<T> result = new ArrayList<T>();
        if (indexedProperty != null && (!INDEXEDPROPERTIES.contains(indexedProperty))) {
            String message = "indexed property " + indexedProperty + " not supported, query was '>= '" + startValue
                    + "'; supported properties are " + INDEXEDPROPERTIES;
            LOG.info(message);
            throw new DocumentStoreException(message);
        }
        try {
            long now = System.currentTimeMillis();
            connection = this.ch.getROConnection();
            List<RDBRow> dbresult = dbQuery(connection, tableName, fromKey, toKey, indexedProperty, startValue, limit);
            connection.commit();
            for (RDBRow r : dbresult) {
                T doc = runThroughCache(collection, r, now);
                result.add(doc);
            }
        } catch (Exception ex) {
            LOG.error("SQL exception on query", ex);
            throw new DocumentStoreException(ex);
        } finally {
            this.ch.closeConnection(connection);
        }
        return result;
    }

    private <T extends Document> String getTable(Collection<T> collection) {
        if (collection == Collection.CLUSTER_NODES) {
            return this.tnClusterNodes;
        } else if (collection == Collection.NODES) {
            return this.tnNodes;
        } else if (collection == Collection.SETTINGS) {
            return this.tnSettings;
        } else {
            throw new IllegalArgumentException("Unknown collection: " + collection.toString());
        }
    }

    @CheckForNull
    private <T extends Document> T readDocumentUncached(Collection<T> collection, String id, NodeDocument cachedDoc) {
        Connection connection = null;
        String tableName = getTable(collection);
        try {
            long lastmodcount = -1;
            if (cachedDoc != null) {
                lastmodcount = modcountOf(cachedDoc);
            }
            connection = this.ch.getROConnection();
            RDBRow row = dbRead(connection, tableName, id, lastmodcount);
            connection.commit();
            if (row == null) {
                return null;
            } else {
                if (lastmodcount == row.getModcount()) {
                    // we can re-use the cached document
                    cachedDoc.markUpToDate(System.currentTimeMillis());
                    return castAsT(cachedDoc);
                } else {
                    return SR.fromRow(collection, row);
                }
            }
        } catch (Exception ex) {
            throw new DocumentStoreException(ex);
        } finally {
            this.ch.closeConnection(connection);
        }
    }

    private <T extends Document> void delete(Collection<T> collection, String id) {
        Connection connection = null;
        String tableName = getTable(collection);
        try {
            connection = this.ch.getRWConnection();
            dbDelete(connection, tableName, Collections.singletonList(id));
            connection.commit();
        } catch (Exception ex) {
            throw new DocumentStoreException(ex);
        } finally {
            this.ch.closeConnection(connection);
        }
    }

    private <T extends Document> int delete(Collection<T> collection, List<String> ids) {
        int numDeleted = 0;
        for (List<String> sublist : Lists.partition(ids, 64)) {
            Connection connection = null;
            String tableName = getTable(collection);
            try {
                connection = this.ch.getRWConnection();
                numDeleted += dbDelete(connection, tableName, sublist);
                connection.commit();
            } catch (Exception ex) {
                throw new DocumentStoreException(ex);
            } finally {
                this.ch.closeConnection(connection);
            }
        }
        return numDeleted;
    }

    private <T extends Document> int delete(Collection<T> collection,
                                            Map<String, Map<Key, Condition>> toRemove) {
        int numDeleted = 0;
        String tableName = getTable(collection);
        Map<String, Map<Key, Condition>> subMap = Maps.newHashMap();
        Iterator<Entry<String, Map<Key, Condition>>> it = toRemove.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, Map<Key, Condition>> entry = it.next();
            subMap.put(entry.getKey(), entry.getValue());
            if (subMap.size() == 64 || !it.hasNext()) {
                Connection connection = null;
                try {
                    connection = this.ch.getRWConnection();
                    numDeleted += dbDelete(connection, tableName, subMap);
                    connection.commit();
                } catch (Exception ex) {
                    throw DocumentStoreException.convert(ex);
                } finally {
                    this.ch.closeConnection(connection);
                }
                subMap.clear();
            }
        }
        return numDeleted;
    }

    private <T extends Document> boolean updateDocument(@Nonnull Collection<T> collection, @Nonnull T document,
            @Nonnull UpdateOp update, Long oldmodcount) {
        Connection connection = null;
        String tableName = getTable(collection);
        try {
            connection = this.ch.getRWConnection();
            Long modified = (Long) document.get(MODIFIED);
            Number flagB = (Number) document.get(NodeDocument.HAS_BINARY_FLAG);
            Boolean hasBinary = flagB != null && flagB.intValue() == NodeDocument.HAS_BINARY_VAL;
            Boolean flagD = (Boolean) document.get(NodeDocument.DELETED_ONCE);
            Boolean deletedOnce = flagD != null && flagD.booleanValue();
            Long modcount = (Long) document.get(MODCOUNT);
            Long cmodcount = (Long) document.get(COLLISIONSMODCOUNT);
            boolean success = false;

            // every 16th update is a full rewrite
            if (isAppendableUpdate(update) && modcount % 16 != 0) {
                String appendData = SR.asString(update);
                if (appendData.length() < this.dataLimitInOctets / CHAR2OCTETRATIO) {
                    try {
                        success = dbAppendingUpdate(connection, tableName, document.getId(), modified, hasBinary, deletedOnce,
                                modcount, cmodcount, oldmodcount, appendData);
                        connection.commit();
                    } catch (SQLException ex) {
                        continueIfStringOverflow(ex);
                        this.ch.rollbackConnection(connection);
                        success = false;
                    }
                }
            }
            if (!success) {
                String data = SR.asString(document);
                success = dbUpdate(connection, tableName, document.getId(), modified, hasBinary, deletedOnce, modcount, cmodcount,
                        oldmodcount, data);
                connection.commit();
            }
            return success;
        } catch (SQLException ex) {
            this.ch.rollbackConnection(connection);
            throw new DocumentStoreException(ex);
        } finally {
            this.ch.closeConnection(connection);
        }
    }

    private static void continueIfStringOverflow(SQLException ex) throws SQLException {
        String state = ex.getSQLState();
        if ("22001".equals(state) /* everybody */|| ("72000".equals(state) && 1489 == ex.getErrorCode()) /* Oracle */) {
            // ok
        } else {
            throw (ex);
        }
    }

    /*
     * currently we use append for all updates, but this might change in the
     * future
     */
    private static boolean isAppendableUpdate(UpdateOp update) {
        return true;
    }

    /*
     * check whether this update operation requires knowledge about the previous
     * state
     */
    private static boolean requiresPreviousState(UpdateOp update) {
        return !update.getConditions().isEmpty();
    }

    private static long getModifiedFromUpdate(UpdateOp update) {
        for (Map.Entry<Key, Operation> change : update.getChanges().entrySet()) {
            Operation op = change.getValue();
            if (op.type == UpdateOp.Operation.Type.MAX || op.type == UpdateOp.Operation.Type.SET) {
                if (MODIFIED.equals(change.getKey().getName())) {
                    return Long.parseLong(op.value.toString());
                }
            }
        }
        return 0L;
    }

    private <T extends Document> void insertDocuments(Collection<T> collection, List<T> documents) {
        Connection connection = null;
        String tableName = getTable(collection);
        List<String> ids = new ArrayList<String>();
        try {
            connection = this.ch.getRWConnection();
            for (T document : documents) {
                String data = SR.asString(document);
                Long modified = (Long) document.get(MODIFIED);
                Number flagB = (Number) document.get(NodeDocument.HAS_BINARY_FLAG);
                Boolean hasBinary = flagB != null && flagB.intValue() == NodeDocument.HAS_BINARY_VAL;
                Boolean flagD = (Boolean) document.get(NodeDocument.DELETED_ONCE);
                Boolean deletedOnce = flagD != null && flagD.booleanValue();
                Long modcount = (Long) document.get(MODCOUNT);
                Long cmodcount = (Long) document.get(COLLISIONSMODCOUNT);
                String id = document.getId();
                ids.add(id);
                dbInsert(connection, tableName, id, modified, hasBinary, deletedOnce, modcount, cmodcount, data);
            }
            connection.commit();
        } catch (SQLException ex) {
            LOG.debug("insert of " + ids + " failed", ex);
            this.ch.rollbackConnection(connection);
            throw new DocumentStoreException(ex);
        } finally {
            this.ch.closeConnection(connection);
        }
    }

    // configuration

    // Whether to use GZIP compression
    private static final boolean NOGZIP = Boolean
            .getBoolean("org.apache.jackrabbit.oak.plugins.document.rdb.RDBDocumentStore.NOGZIP");
    // Number of documents to insert at once for batch create
    private static final int CHUNKSIZE = Integer.getInteger(
            "org.apache.jackrabbit.oak.plugins.document.rdb.RDBDocumentStore.CHUNKSIZE", 64);

    private static byte[] asBytes(String data) {
        byte[] bytes;
        try {
            bytes = data.getBytes("UTF-8");
        } catch (UnsupportedEncodingException ex) {
            LOG.error("UTF-8 not supported??", ex);
            throw new DocumentStoreException(ex);
        }

        if (NOGZIP) {
            return bytes;
        } else {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length());
                GZIPOutputStream gos = new GZIPOutputStream(bos) {
                    {
                        // TODO: make this configurable
                        this.def.setLevel(Deflater.BEST_SPEED);
                    }
                };
                gos.write(bytes);
                gos.close();
                return bos.toByteArray();
            } catch (IOException ex) {
                LOG.error("Error while gzipping contents", ex);
                throw new DocumentStoreException(ex);
            }
        }
    }

    private void setIdInStatement(PreparedStatement stmt, int idx, String id) throws SQLException {
        if (db.isPrimaryColumnByteEncoded()) {
            try {
                stmt.setBytes(idx, id.getBytes("UTF-8"));
            } catch (UnsupportedEncodingException ex) {
                LOG.error("UTF-8 not supported??", ex);
                throw new DocumentStoreException(ex);
            }
        } else {
            stmt.setString(idx, id);
        }
    }

    private String getIdFromRS(ResultSet rs, int idx) throws SQLException {
        String id;
        if (db.isPrimaryColumnByteEncoded()) {
            try {
                id = new String(rs.getBytes(idx), "UTF-8");

            } catch (UnsupportedEncodingException ex) {
                LOG.error("UTF-8 not supported??", ex);
                throw new DocumentStoreException(ex);
            }
        } else {
            id = rs.getString(idx);
        }
        return id;
    }

    @CheckForNull
    private RDBRow dbRead(Connection connection, String tableName, String id, long lastmodcount) throws SQLException {
        PreparedStatement stmt;
        boolean useCaseStatement = lastmodcount != -1 && this.db.allowsCaseInSelect();
        if (useCaseStatement) {
            // either we don't have a previous version of the document
            // or the database does not support CASE in SELECT
            stmt = connection.prepareStatement("select MODIFIED, MODCOUNT, CMODCOUNT, HASBINARY, DELETEDONCE, DATA, BDATA from "
                    + tableName + " where ID = ?");
        } else {
            // the case statement causes the actual row data not to be
            // sent in case we already have it
            stmt = connection
                    .prepareStatement("select MODIFIED, MODCOUNT, CMODCOUNT, HASBINARY, DELETEDONCE, case MODCOUNT when ? then null else DATA end as DATA, "
                            + "case MODCOUNT when ? then null else BDATA end as BDATA from " + tableName + " where ID = ?");
        }

        try {
            if (useCaseStatement) {
                setIdInStatement(stmt, 1, id);
            }
            else {
                stmt.setLong(1, lastmodcount);
                stmt.setLong(2, lastmodcount);
                setIdInStatement(stmt, 3, id);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                long modified = rs.getLong(1);
                long modcount = rs.getLong(2);
                long cmodcount = rs.getLong(3);
                long hasBinary = rs.getLong(4);
                long deletedOnce = rs.getLong(5);
                String data = rs.getString(6);
                byte[] bdata = rs.getBytes(7);
                return new RDBRow(id, hasBinary == 1, deletedOnce == 1, modified, modcount, cmodcount, data, bdata);
            } else {
                return null;
            }
        } catch (SQLException ex) {
            LOG.error("attempting to read " + id + " (id length is " + id.length() + ")", ex);
            // DB2 throws an SQLException for invalid keys; handle this more
            // gracefully
            if ("22001".equals(ex.getSQLState())) {
                this.ch.rollbackConnection(connection);
                return null;
            } else {
                throw (ex);
            }
        } finally {
            stmt.close();
        }
    }

    private List<RDBRow> dbQuery(Connection connection, String tableName, String minId, String maxId, String indexedProperty,
            long startValue, int limit) throws SQLException {
        String t = "select ";
        if (limit != Integer.MAX_VALUE && this.db.getFetchFirstSyntax() == FETCHFIRSTSYNTAX.TOP) {
            t += "TOP " + limit +  " ";
        }
        t += "ID, MODIFIED, MODCOUNT, CMODCOUNT, HASBINARY, DELETEDONCE, DATA, BDATA from " + tableName
                + " where ID > ? and ID < ?";
        if (indexedProperty != null) {
            if (MODIFIED.equals(indexedProperty)) {
                t += " and MODIFIED >= ?";
            } else if (NodeDocument.HAS_BINARY_FLAG.equals(indexedProperty)) {
                if (startValue != NodeDocument.HAS_BINARY_VAL) {
                    throw new DocumentStoreException("unsupported value for property " + NodeDocument.HAS_BINARY_FLAG);
                }
                t += " and HASBINARY = 1";
            } else if (NodeDocument.DELETED_ONCE.equals(indexedProperty)) {
                if (startValue != 1) {
                    throw new DocumentStoreException("unsupported value for property " + NodeDocument.DELETED_ONCE);
                }
                t += " and DELETEDONCE = 1";
            } else {
                throw new DocumentStoreException("unsupported indexed property: " + indexedProperty);
            }
        }
        t += " order by ID";

        if (limit != Integer.MAX_VALUE) {
            switch (this.db.getFetchFirstSyntax()) {
                case LIMIT:
                    t += " LIMIT " + limit;
                    break;
                case FETCHFIRST:
                    t += " FETCH FIRST " + limit + " ROWS ONLY";
                    break;
                default:
                    break;
            }
        }

        PreparedStatement stmt = connection.prepareStatement(t);
        List<RDBRow> result = new ArrayList<RDBRow>();
        try {
            int si = 1;
            setIdInStatement(stmt, si++, minId);
            setIdInStatement(stmt, si++, maxId);

            if (MODIFIED.equals(indexedProperty)) {
                stmt.setLong(si++, startValue);
            }
            if (limit != Integer.MAX_VALUE) {
                stmt.setFetchSize(limit);
            }
            ResultSet rs = stmt.executeQuery();
            while (rs.next() && result.size() < limit) {
                String id = getIdFromRS(rs, 1);

                if (id.compareTo(minId) < 0 || id.compareTo(maxId) > 0) {
                    throw new DocumentStoreException("unexpected query result: '" + minId + "' < '" + id + "' < '" + maxId
                            + "' - broken DB collation?");
                }
                long modified = rs.getLong(2);
                long modcount = rs.getLong(3);
                long cmodcount = rs.getLong(4);
                long hasBinary = rs.getLong(5);
                long deletedOnce = rs.getLong(6);
                String data = rs.getString(7);
                byte[] bdata = rs.getBytes(8);
                result.add(new RDBRow(id, hasBinary == 1, deletedOnce == 1, modified, modcount, cmodcount, data, bdata));
            }
        } finally {
            stmt.close();
        }
        return result;
    }

    private boolean dbUpdate(Connection connection, String tableName, String id, Long modified, Boolean hasBinary,
            Boolean deletedOnce, Long modcount, Long cmodcount, Long oldmodcount, String data) throws SQLException {
        String t = "update "
                + tableName
                + " set MODIFIED = ?, HASBINARY = ?, DELETEDONCE = ?, MODCOUNT = ?, CMODCOUNT = ?, DSIZE = ?, DATA = ?, BDATA = ? where ID = ?";
        if (oldmodcount != null) {
            t += " and MODCOUNT = ?";
        }
        PreparedStatement stmt = connection.prepareStatement(t);
        try {
            int si = 1;
            stmt.setObject(si++, modified, Types.BIGINT);
            stmt.setObject(si++, hasBinary ? 1 : 0, Types.SMALLINT);
            stmt.setObject(si++, deletedOnce ? 1 : 0, Types.SMALLINT);
            stmt.setObject(si++, modcount, Types.BIGINT);
            stmt.setObject(si++, cmodcount == null ? Long.valueOf(0) : cmodcount, Types.BIGINT);
            stmt.setObject(si++, data.length(), Types.BIGINT);

            if (data.length() < this.dataLimitInOctets / CHAR2OCTETRATIO) {
                stmt.setString(si++, data);
                stmt.setBinaryStream(si++, null, 0);
            } else {
                stmt.setString(si++, "\"blob\"");
                byte[] bytes = asBytes(data);
                stmt.setBytes(si++, bytes);
            }

            setIdInStatement(stmt, si++, id);

            if (oldmodcount != null) {
                stmt.setObject(si++, oldmodcount, Types.BIGINT);
            }
            int result = stmt.executeUpdate();
            if (result != 1) {
                LOG.debug("DB update failed for " + tableName + "/" + id + " with oldmodcount=" + oldmodcount);
            }
            return result == 1;
        } finally {
            stmt.close();
        }
    }

    private boolean dbAppendingUpdate(Connection connection, String tableName, String id, Long modified, Boolean hasBinary,
            Boolean deletedOnce, Long modcount, Long cmodcount, Long oldmodcount, String appendData) throws SQLException {
        StringBuilder t = new StringBuilder();
        t.append("update " + tableName + " set MODIFIED = " + this.db.getGreatestQueryString("MODIFIED")
                + ", HASBINARY = ?, DELETEDONCE = ?, MODCOUNT = ?, CMODCOUNT = ?, DSIZE = DSIZE + ?, ");
        t.append("DATA = " + this.db.getConcatQueryString(this.dataLimitInOctets, appendData.length()) + " ");
        t.append("where ID = ?");
        if (oldmodcount != null) {
            t.append(" and MODCOUNT = ?");
        }
        PreparedStatement stmt = connection.prepareStatement(t.toString());
        try {
            int si = 1;
            stmt.setObject(si++, modified, Types.BIGINT);
            stmt.setObject(si++, hasBinary ? 1 : 0, Types.SMALLINT);
            stmt.setObject(si++, deletedOnce ? 1 : 0, Types.SMALLINT);
            stmt.setObject(si++, modcount, Types.BIGINT);
            stmt.setObject(si++, cmodcount == null ? Long.valueOf(0) : cmodcount, Types.BIGINT);
            stmt.setObject(si++, 1 + appendData.length(), Types.BIGINT);
            stmt.setString(si++, "," + appendData);
            setIdInStatement(stmt, si++, id);

            if (oldmodcount != null) {
                stmt.setObject(si++, oldmodcount, Types.BIGINT);
            }
            int result = stmt.executeUpdate();
            if (result != 1) {
                LOG.debug("DB append update failed for " + tableName + "/" + id + " with oldmodcount=" + oldmodcount);
            }
            return result == 1;
        } finally {
            stmt.close();
        }
    }

    private boolean dbBatchedAppendingUpdate(Connection connection, String tableName, List<String> ids, Long modified,
            String appendData) throws SQLException {
        StringBuilder t = new StringBuilder();
        t.append("update " + tableName + " set MODIFIED = " + this.db.getGreatestQueryString("MODIFIED")
                + ", MODCOUNT = MODCOUNT + 1, DSIZE = DSIZE + ?, ");
        t.append("DATA = " + this.db.getConcatQueryString(this.dataLimitInOctets, appendData.length()) + " ");
        t.append("where ID in (");
        for (int i = 0; i < ids.size(); i++) {
            if (i != 0) {
                t.append(',');
            }
            t.append('?');
        }
        t.append(")");
        PreparedStatement stmt = connection.prepareStatement(t.toString());
        try {
            int si = 1;
            stmt.setObject(si++, modified, Types.BIGINT);
            stmt.setObject(si++, 1 + appendData.length(), Types.BIGINT);
            stmt.setString(si++, "," + appendData);
            for (String id : ids) {
                setIdInStatement(stmt, si++, id);
            }
            int result = stmt.executeUpdate();
            if (result != ids.size()) {
                LOG.debug("DB update failed: only " + result + " of " + ids.size() + " updated. Table: " + tableName + ", IDs:"
                        + ids);
            }
            return result == ids.size();
        } finally {
            stmt.close();
        }
    }

    private boolean dbInsert(Connection connection, String tableName, String id, Long modified, Boolean hasBinary,
            Boolean deletedOnce, Long modcount, Long cmodcount, String data) throws SQLException {
        PreparedStatement stmt = connection.prepareStatement("insert into " + tableName
                + "(ID, MODIFIED, HASBINARY, DELETEDONCE, MODCOUNT, CMODCOUNT, DSIZE, DATA, BDATA) values (?, ?, ?, ?, ?, ?, ?, ?, ?)");
        try {
            int si = 1;
            setIdInStatement(stmt, si++, id);
            stmt.setObject(si++, modified, Types.BIGINT);
            stmt.setObject(si++, hasBinary ? 1 : 0, Types.SMALLINT);
            stmt.setObject(si++, deletedOnce ? 1 : 0, Types.SMALLINT);
            stmt.setObject(si++, modcount, Types.BIGINT);
            stmt.setObject(si++, cmodcount == null ? Long.valueOf(0) : cmodcount, Types.BIGINT);
            stmt.setObject(si++, data.length(), Types.BIGINT);
            if (data.length() < this.dataLimitInOctets / CHAR2OCTETRATIO) {
                stmt.setString(si++, data);
                stmt.setBinaryStream(si++, null, 0);
            } else {
                stmt.setString(si++, "\"blob\"");
                byte[] bytes = asBytes(data);
                stmt.setBytes(si++, bytes);
            }
            int result = stmt.executeUpdate();
            if (result != 1) {
                LOG.debug("DB insert failed for " + tableName + "/" + id);
            }
            return result == 1;
        } finally {
            stmt.close();
        }
    }

    private int dbDelete(Connection connection, String tableName, List<String> ids) throws SQLException {

        PreparedStatement stmt;
        int cnt = ids.size();

        if (cnt == 1) {
            stmt = connection.prepareStatement("delete from " + tableName + " where ID=?");
        } else {
            StringBuilder inClause = new StringBuilder();
            for (int i = 0; i < cnt; i++) {
                inClause.append('?');
                if (i != cnt - 1) {
                    inClause.append(',');
                }
            }
            stmt = connection.prepareStatement("delete from " + tableName + " where ID in (" + inClause.toString() + ")");
        }

        try {
            for (int i = 0; i < cnt; i++) {
                setIdInStatement(stmt, i + 1, ids.get(i));
            }
            int result = stmt.executeUpdate();
            if (result != cnt) {
                LOG.debug("DB delete failed for " + tableName + "/" + ids);
            }
            return result;
        } finally {
            stmt.close();
        }
    }

    private int dbDelete(Connection connection, String tableName,
                         Map<String, Map<Key, Condition>> toDelete)
            throws SQLException, DocumentStoreException {
        String or = "";
        StringBuilder whereClause = new StringBuilder();
        for (Entry<String, Map<Key, Condition>> entry : toDelete.entrySet()) {
            whereClause.append(or);
            or = " or ";
            whereClause.append("ID=?");
            for (Entry<Key, Condition> c : entry.getValue().entrySet()) {
                if (!c.getKey().getName().equals(MODIFIED)) {
                    throw new DocumentStoreException(
                            "Unsupported condition: " + c);
                }
                whereClause.append(" and MODIFIED");
                if (c.getValue().type == Condition.Type.EQUALS
                        && c.getValue().value instanceof Long) {
                    whereClause.append("=?");
                } else if (c.getValue().type == Condition.Type.EXISTS) {
                    whereClause.append(" is not null");
                } else {
                    throw new DocumentStoreException(
                            "Unsupported condition: " + c);
                }
            }
        }

        PreparedStatement stmt= connection.prepareStatement(
                "delete from " + tableName + " where " + whereClause);
        try {
            int i = 1;
            for (Entry<String, Map<Key, Condition>> entry : toDelete.entrySet()) {
                setIdInStatement(stmt, i++, entry.getKey());
                for (Entry<Key, Condition> c : entry.getValue().entrySet()) {
                    if (c.getValue().type == Condition.Type.EQUALS) {
                        stmt.setLong(i++, (Long) c.getValue().value);
                    }
                }
            }
            return stmt.executeUpdate();
        } finally {
            stmt.close();
        }
    }

    @Override
    public void setReadWriteMode(String readWriteMode) {
        // ignored
    }

    @SuppressWarnings("unchecked")
    private static <T extends Document> T castAsT(NodeDocument doc) {
        return (T) doc;
    }

    // Memory Cache
    private Cache<CacheValue, NodeDocument> nodesCache;
    private CacheStats cacheStats;
    private final Striped<Lock> locks = Striped.lock(64);

    private Lock getAndLock(String key) {
        Lock l = locks.get(key);
        l.lock();
        return l;
    }

    @CheckForNull
    private static NodeDocument unwrap(@Nonnull NodeDocument doc) {
        return doc == NodeDocument.NULL ? null : doc;
    }

    @Nonnull
    private static NodeDocument wrap(@CheckForNull NodeDocument doc) {
        return doc == null ? NodeDocument.NULL : doc;
    }

    @Nonnull
    private static String idOf(@Nonnull Document doc) {
        String id = doc.getId();
        if (id == null) {
            throw new IllegalArgumentException("non-null ID expected");
        }
        return id;
    }

    private static long modcountOf(@Nonnull Document doc) {
        Number n = doc.getModCount();
        return n != null ? n.longValue() : -1;
    }

    /**
     * Adds a document to the {@link #nodesCache} iff there is no document in
     * the cache with the document key. This method does not acquire a lock from
     * {@link #locks}! The caller must ensure a lock is held for the given
     * document.
     * 
     * @param doc
     *            the document to add to the cache.
     * @return either the given <code>doc</code> or the document already present
     *         in the cache.
     */
    @Nonnull
    private NodeDocument addToCache(@Nonnull final NodeDocument doc) {
        if (doc == NodeDocument.NULL) {
            throw new IllegalArgumentException("doc must not be NULL document");
        }
        doc.seal();
        // make sure we only cache the document if it wasn't
        // changed and cached by some other thread in the
        // meantime. That is, use get() with a Callable,
        // which is only used when the document isn't there
        try {
            CacheValue key = new StringValue(idOf(doc));
            for (;;) {
                NodeDocument cached = nodesCache.get(key, new Callable<NodeDocument>() {
                    @Override
                    public NodeDocument call() {
                        return doc;
                    }
                });
                if (cached != NodeDocument.NULL) {
                    return cached;
                } else {
                    nodesCache.invalidate(key);
                }
            }
        } catch (ExecutionException e) {
            // will never happen because call() just returns
            // the already available doc
            throw new IllegalStateException(e);
        }
    }

    @Nonnull
    private void applyToCache(@Nonnull final NodeDocument oldDoc, @Nonnull final NodeDocument newDoc) {
        NodeDocument cached = addToCache(newDoc);
        if (cached == newDoc) {
            // successful
            return;
        } else if (oldDoc == null) {
            // this is an insert and some other thread was quicker
            // loading it into the cache -> return now
            return;
        } else {
            CacheValue key = new StringValue(idOf(newDoc));
            // this is an update (oldDoc != null)
            if (Objects.equal(cached.getModCount(), oldDoc.getModCount())) {
                nodesCache.put(key, newDoc);
            } else {
                // the cache entry was modified by some other thread in
                // the meantime. the updated cache entry may or may not
                // include this update. we cannot just apply our update
                // on top of the cached entry.
                // therefore we must invalidate the cache entry
                nodesCache.invalidate(key);
            }
        }
    }

    private <T extends Document> void addToCache(Collection<T> collection, T doc) {
        if (collection == Collection.NODES) {
            Lock lock = getAndLock(idOf(doc));
            try {
                addToCache((NodeDocument) doc);
            } finally {
                lock.unlock();
            }
        }
    }

    private <T extends Document> T runThroughCache(Collection<T> collection, RDBRow row, long now) {

        if (collection != Collection.NODES) {
            // not in the cache anyway
            return SR.fromRow(collection, row);
        }

        String id = row.getId();
        CacheValue cacheKey = new StringValue(id);
        NodeDocument inCache = nodesCache.getIfPresent(cacheKey);
        Number modCount = row.getModcount();

        // do not overwrite document in cache if the
        // existing one in the cache is newer
        if (inCache != null && inCache != NodeDocument.NULL) {
            // check mod count
            Number cachedModCount = inCache.getModCount();
            if (cachedModCount == null) {
                throw new IllegalStateException("Missing " + Document.MOD_COUNT);
            }
            if (modCount.longValue() <= cachedModCount.longValue()) {
                // we can use the cached document
                inCache.markUpToDate(now);
                return castAsT(inCache);
            }
        }

        NodeDocument fresh = (NodeDocument) SR.fromRow(collection, row);
        fresh.seal();

        Lock lock = getAndLock(id);
        try {
            inCache = nodesCache.getIfPresent(cacheKey);
            if (inCache != null && inCache != NodeDocument.NULL) {
                // check mod count
                Number cachedModCount = inCache.getModCount();
                if (cachedModCount == null) {
                    throw new IllegalStateException("Missing " + Document.MOD_COUNT);
                }
                if (modCount.longValue() > cachedModCount.longValue()) {
                    nodesCache.put(cacheKey, fresh);
                } else {
                    fresh = inCache;
                }
            } else {
                nodesCache.put(cacheKey, fresh);
            }
        } finally {
            lock.unlock();
        }
        return castAsT(fresh);
    }

    private boolean hasChangesToCollisions(UpdateOp update) {
        if (! USECMODCOUNT) return false;

        for (Entry<Key, Operation> e : checkNotNull(update).getChanges().entrySet()) {
            Key k = e.getKey();
            Operation op = e.getValue();
            if (op.type == Operation.Type.SET_MAP_ENTRY) {
                if (NodeDocument.COLLISIONS.equals(k.getName())) {
                    return true;
                }
            }
        }
        return false;
    }
}
