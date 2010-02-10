/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.    
 */
package org.apache.openjpa.slice.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;

import javax.sql.DataSource;
import javax.sql.XADataSource;

import org.apache.openjpa.conf.OpenJPAConfiguration;
import org.apache.openjpa.jdbc.conf.JDBCConfiguration;
import org.apache.openjpa.jdbc.conf.JDBCConfigurationImpl;
import org.apache.openjpa.jdbc.schema.DataSourceFactory;
import org.apache.openjpa.lib.conf.BooleanValue;
import org.apache.openjpa.lib.conf.ConfigurationProvider;
import org.apache.openjpa.lib.conf.PluginValue;
import org.apache.openjpa.lib.conf.StringListValue;
import org.apache.openjpa.lib.conf.StringValue;
import org.apache.openjpa.lib.jdbc.DecoratingDataSource;
import org.apache.openjpa.lib.jdbc.DelegatingDataSource;
import org.apache.openjpa.lib.log.Log;
import org.apache.openjpa.lib.log.LogFactory;
import org.apache.openjpa.lib.log.LogFactoryImpl;
import org.apache.openjpa.lib.util.Localizer;
import org.apache.openjpa.slice.DistributedBrokerImpl;
import org.apache.openjpa.slice.DistributionPolicy;
import org.apache.openjpa.slice.ExecutorServiceValue;
import org.apache.openjpa.slice.ProductDerivation;
import org.apache.openjpa.slice.Slice;
import org.apache.openjpa.util.UserException;

/**
 * Implements a distributed configuration of JDBCStoreManagers.
 * The original configuration properties are analyzed to create a set of
 * Slice specific properties with defaulting rules. 
 * 
 * @author Pinaki Poddar
 * 
 */
public class DistributedJDBCConfigurationImpl extends JDBCConfigurationImpl
        implements DistributedJDBCConfiguration {

    private final List<Slice> _slices = new ArrayList<Slice>();
    private List<String> _activeSliceNames = new ArrayList<String>();
    private Slice _master;
    
    private DecoratingDataSource virtualDataSource;
    
    protected BooleanValue lenientPlugin;
    protected StringValue masterPlugin;
    protected StringListValue namesPlugin;
    protected ExecutorServiceValue executorServicePlugin;
    protected PluginValue distributionPolicyPlugin;

    public static final String DOT = ".";
    public static final String REGEX_DOT = "\\.";
    public static final String PREFIX_SLICE = ProductDerivation.PREFIX_SLICE + DOT;
    public static final String PREFIX_OPENJPA = "openjpa.";
    private static Localizer _loc =
            Localizer.forPackage(DistributedJDBCConfigurationImpl.class);

    /**
     * Configure itself as well as underlying slices.
     * 
     */
    public DistributedJDBCConfigurationImpl(ConfigurationProvider cp) {
        super(true, false);
        Map p = cp.getProperties();
        String pUnit = getPersistenceUnitName(p);
        setDiagnosticContext(pUnit);
        
        brokerPlugin.setString(DistributedBrokerImpl.class.getName());
        
        distributionPolicyPlugin = addPlugin("DistributionPolicy", true);
        distributionPolicyPlugin.setDynamic(true);
        
        lenientPlugin = addBoolean("Lenient");
        
        masterPlugin = addString("Master");
        
        namesPlugin = addStringList("Names");
        
        executorServicePlugin = new ExecutorServiceValue();
        addValue(executorServicePlugin);
        
        setSlices(p);
    }
    
    private String getPersistenceUnitName(Map p) {
        Object unit = p.get(PREFIX_OPENJPA+id.getProperty());
        return (unit == null) ? "?" : unit.toString();
    }
    
    private void setDiagnosticContext(String unit) {
        LogFactory logFactory = getLogFactory();
        if (logFactory instanceof LogFactoryImpl) {
            ((LogFactoryImpl)logFactory).setDiagnosticContext(unit);
        }
    }

    /**
     * Gets the name of the active slices.
     */
    public List<String> getActiveSliceNames() {
        if (_activeSliceNames.isEmpty()) {
            for (Slice slice:_slices)
                if (slice.isActive())
                    _activeSliceNames.add(slice.getName());
        }
        return _activeSliceNames;
    }
    
    /**
     * Gets the name of the available slices.
     */
    public List<String> getAvailableSliceNames() {
        List<String> result = new ArrayList<String>();
        for (Slice slice:_slices)
            result.add(slice.getName());
        return result;
    }
    
    /**
     * Gets the slices of given status. Null returns all irrespective of status.
     */
    public List<Slice> getSlices(Slice.Status...statuses) {
        if (statuses == null)
            return Collections.unmodifiableList(_slices);
        List<Slice> result = new ArrayList<Slice>();
        for (Slice slice:_slices) {
            for (Slice.Status status:statuses)
                if (slice.getStatus().equals(status))
                    result.add(slice);
        }
        return result;
    }
    
    /**
     * Gets the master slice. 
     */
    public Slice getMaster() {
        return _master;
    }

    /**
     * Get the configuration for given slice.
     */
    public Slice getSlice(String name) {
        for (Slice slice:_slices)
            if (slice.getName().equals(name))
                return slice;
        throw new UserException(_loc.get("slice-not-found", name,
                    getActiveSliceNames()));
    }

    public DistributionPolicy getDistributionPolicyInstance() {
        if (distributionPolicyPlugin.get() == null) {
            distributionPolicyPlugin.instantiate(DistributionPolicy.class,
                    this, true);
        }
        return (DistributionPolicy) distributionPolicyPlugin.get();
    }

    public void setDistributionPolicyInstance(String val) {
        distributionPolicyPlugin.set(val);
    }

    public Object getConnectionFactory() {
        if (virtualDataSource == null) {
            DistributedDataSource ds = createDistributedDataStore();
            virtualDataSource =
                    DataSourceFactory.installDBDictionary(
                            getDBDictionaryInstance(), ds, this, false);
        }
        return virtualDataSource;
    }

    /**
     * Create a virtual DistributedDataSource as a composite of individual
     * slices as per configuration, optionally ignoring slices that can not be
     * connected.
     */
    private DistributedDataSource createDistributedDataStore() {
        List<DataSource> dataSources = new ArrayList<DataSource>();
        boolean isLenient = lenientPlugin.get();
        boolean isXA = true;
        for (Slice slice : _slices) {
            JDBCConfiguration conf = (JDBCConfiguration)slice.getConfiguration();
            Log log = conf.getConfigurationLog();
            String url = getConnectionInfo(conf);
            if (log.isInfoEnabled())
                log.info(_loc.get("slice-connect", slice, url));
            try {
                DataSource ds = DataSourceFactory.newDataSource(conf, false);
                DecoratingDataSource dds = new DecoratingDataSource(ds);
                ds = DataSourceFactory.installDBDictionary(
                        conf.getDBDictionaryInstance(), dds, conf, false);
                if (verifyDataSource(slice, ds)) {
                    dataSources.add(ds);
                    isXA &= isXACompliant(ds);
                }
            } catch (Throwable ex) {
                handleBadConnection(isLenient, slice, ex);
            }
        }
        if (dataSources.isEmpty())
            throw new UserException(_loc.get("no-slice"));
        DistributedDataSource result = new DistributedDataSource(dataSources);
        return result;
    }

    String getConnectionInfo(OpenJPAConfiguration conf) {
        String result = conf.getConnectionURL();
        if (result == null) {
            result = conf.getConnectionDriverName();
            String props = conf.getConnectionProperties();
            if (props != null)
                result += "(" + props + ")";
        }
        return result;
    }

    boolean isXACompliant(DataSource ds) {
        if (ds instanceof DelegatingDataSource)
            return ((DelegatingDataSource) ds).getInnermostDelegate() 
               instanceof XADataSource;
        return ds instanceof XADataSource;
    }

    /**
     * Verify that a connection can be established to the given slice. If
     * connection can not be established then slice is set to INACTIVE state.
     */
    private boolean verifyDataSource(Slice slice, DataSource ds) {
        Connection con = null;
        try {
            con = ds.getConnection();
            slice.setStatus(Slice.Status.ACTIVE);
            if (con == null) {
                slice.setStatus(Slice.Status.INACTIVE);
                return false;
            }
            return true;
        } catch (SQLException ex) {
            slice.setStatus(Slice.Status.INACTIVE);
            return false;
        } finally {
            if (con != null)
                try {
                    con.close();
                } catch (SQLException ex) {
                    // ignore
                }
        }
    }

    /**
     * Either throw a user exception or add the configuration to the given list,
     * based on <code>isLenient</code>.
     */
    private void handleBadConnection(boolean isLenient, Slice slice,
            Throwable ex) {
        OpenJPAConfiguration conf = slice.getConfiguration();
        String url = conf.getConnectionURL();
        Log log = getLog(LOG_RUNTIME);
        if (isLenient) {
            if (ex != null) {
                log.warn(_loc.get("slice-connect-known-warn", slice, url, ex
                        .getCause()));
            } else {
                log.warn(_loc.get("slice-connect-warn", slice, url));
            }
        } else if (ex != null) {
            throw new UserException(_loc.get("slice-connect-known-error",
                    slice, url, ex), ex.getCause());
        } else {
            throw new UserException(_loc.get("slice-connect-error", slice, url));
        }
    }

    /**
     * Create individual slices with configurations from the given properties.
     */
    void setSlices(Map original) {
        List<String> sliceNames = findSlices(original);
        Log log = getConfigurationLog();
        if (sliceNames.isEmpty()) {
            throw new UserException(_loc.get("slice-none-configured"));
        } 
        String unit = getPersistenceUnitName(original);
        for (String key : sliceNames) {
            JDBCConfiguration child = new JDBCConfigurationImpl();
            child.fromProperties(createSliceProperties(original, key));
            child.setId(unit+DOT+key);
            Slice slice = new Slice(key, child);
            _slices.add(slice);
            if (log.isTraceEnabled())
                log.trace(_loc.get("slice-configuration", key, child
                        .toProperties(false)));
        }
        setMaster(original);
    }

    /**
     * Finds the slices. If <code>openjpa.slice.Names</code> property is 
     * specified then the slices are ordered in the way they are listed. 
     * Otherwise scans all available slices by looking for property of the form
     * <code>openjpa.slice.XYZ.abc</code> where <code>XYZ</code> is the slice
     * identifier and <code>abc</code> is any openjpa property name. The slices
     * are then ordered alphabetically by their identifier.
     */
    private List<String> findSlices(Map p) {
        List<String> sliceNames = new ArrayList<String>();
        
        Log log = getConfigurationLog();
        String key = PREFIX_SLICE + namesPlugin.getProperty();
        boolean explicit = p.containsKey(key);
        if (explicit) {
            String[] values = p.get(key).toString().split("\\,");
            for (String name:values)
                if (!sliceNames.contains(name.trim()))
                    sliceNames.add(name.trim());
        } else {
            if (log.isWarnEnabled())
                log.warn(_loc.get("no-slice-names", key));
            sliceNames = scanForSliceNames(p);
            Collections.sort(sliceNames);
        }
        if (log.isInfoEnabled()) {
            log.info(_loc.get("slice-available", sliceNames));
        }
        return sliceNames;
    }
    
    /**
     * Scan the given map for slice-specific property of the form 
     * <code>openjpa.slice.XYZ.abc</code> (while ignoring 
     * <code>openjpa.slice.XYZ</code> as they refer to slice-wide property)
     * to determine the names of all available slices.
     */
    private List<String> scanForSliceNames(Map p) {
        List<String> sliceNames = new ArrayList<String>();
        for (Object o : p.keySet()) {
            String key = o.toString();
            if (key.startsWith(PREFIX_SLICE) && getPartCount(key) > 3) {
                String sliceName =
                    chopTail(chopHead(o.toString(), PREFIX_SLICE), DOT);
                if (!sliceNames.contains(sliceName))
                    sliceNames.add(sliceName);
            }
        }
        return sliceNames;
    }

    private static int getPartCount(String s) {
        return (s == null) ? 0 : s.split(REGEX_DOT).length;
    }
    
    private static String chopHead(String s, String head) {
        if (s.startsWith(head))
            return s.substring(head.length());
        return s;
    }

    private static String chopTail(String s, String tail) {
        int i = s.lastIndexOf(tail);
        if (i == -1)
            return s;
        return s.substring(0, i);
    }

    /**
     * Creates given <code>slice</code> specific configuration properties from
     * given <code>original</code> key-value map. The rules are
     * <LI> if key begins with <code>"slice.XXX."</code> where
     * <code>XXX</code> is the given slice name, then replace
     * <code>"slice.XXX.</code> with <code>openjpa.</code>.
     * <LI>if key begins with <code>"slice."</code> but not with
     * <code>"slice.XXX."</code>, the ignore i.e. any property of other
     * slices or global slice property e.g.
     * <code>slice.DistributionPolicy</code>
     * <code>if key starts with <code>"openjpa."</code> and a corresponding
     * <code>"slice.XXX."</code> property does not exist, then use this as
     * default property
     * <code>property with any other prefix is simply copied
     *
     */
    Map createSliceProperties(Map original, String slice) {
        Map result = new Properties();
        String prefix = PREFIX_SLICE + slice + DOT;
        for (Object o : original.keySet()) {
            String key = o.toString();
            if (key.startsWith(prefix)) {
                String newKey = PREFIX_OPENJPA + key.substring(prefix.length());
                result.put(newKey, original.get(o));
            } else if (key.startsWith(PREFIX_SLICE)) {
                // ignore keys that are in 'slice.' namespace but not this slice
            } else if (key.startsWith(PREFIX_OPENJPA)) {
                String newKey = prefix + key.substring(PREFIX_OPENJPA.length());
                if (!original.containsKey(newKey))
                    result.put(key, original.get(o));
            } else { // keys that are neither "openjpa" nor "slice" namespace
                result.put(key, original.get(o));
            }
        }
        return result;
    }

    /**
     * Determine the master slice.
     */
    private void setMaster(Map original) {
        String key = PREFIX_SLICE + masterPlugin.getProperty();
        Object masterSlice = original.get(key);
        Log log = getConfigurationLog();
        List<Slice> activeSlices = getSlices(null);
        if (masterSlice == null) {
            _master = activeSlices.get(0);
            if (log.isWarnEnabled())
                log.warn(_loc.get("no-master-slice", key, _master));
            return;
        }
        for (Slice slice:activeSlices)
            if (slice.getName().equals(masterSlice))
                _master = slice;
        if (_master == null) {
            _master = activeSlices.get(0);
        }
    }
    
    public String getExecutorService() {
        return executorServicePlugin.getString();
    }

    public void setExecutorService(ExecutorService txnManager) {
        executorServicePlugin.set(txnManager);
    }

    public ExecutorService getExecutorServiceInstance() {
        if (executorServicePlugin.get() == null) {
            executorServicePlugin.instantiate(ExecutorService.class, this);
        }
        return (ExecutorService) executorServicePlugin.get();
    }    
}