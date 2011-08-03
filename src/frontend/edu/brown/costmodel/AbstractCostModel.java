/***************************************************************************
 *   Copyright (C) 2009 by H-Store Project                                 *
 *   Brown University                                                      *
 *   Massachusetts Institute of Technology                                 *
 *   Yale University                                                       *
 *                                                                         *
 *   Permission is hereby granted, free of charge, to any person obtaining *
 *   a copy of this software and associated documentation files (the       *
 *   "Software"), to deal in the Software without restriction, including   *
 *   without limitation the rights to use, copy, modify, merge, publish,   *
 *   distribute, sublicense, and/or sell copies of the Software, and to    *
 *   permit persons to whom the Software is furnished to do so, subject to *
 *   the following conditions:                                             *
 *                                                                         *
 *   The above copyright notice and this permission notice shall be        *
 *   included in all copies or substantial portions of the Software.       *
 *                                                                         *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,       *
 *   EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF    *
 *   MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.*
 *   IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR     *
 *   OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, *
 *   ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR *
 *   OTHER DEALINGS IN THE SOFTWARE.                                       *
 ***************************************************************************/
package edu.brown.costmodel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Logger;
import org.voltdb.catalog.CatalogType;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Procedure;

import edu.brown.catalog.CatalogKey;
import edu.brown.catalog.CatalogUtil;
import edu.brown.designer.DesignerHints;
import edu.brown.statistics.Histogram;
import edu.brown.utils.LoggerUtil;
import edu.brown.utils.PartitionEstimator;
import edu.brown.utils.StringUtil;
import edu.brown.utils.LoggerUtil.LoggerBoolean;
import edu.brown.workload.TransactionTrace;
import edu.brown.workload.Workload;
import edu.brown.workload.filters.Filter;

/**
 * 
 * @author pavlo
 *
 */
public abstract class AbstractCostModel {
    private static final Logger LOG = Logger.getLogger(AbstractCostModel.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    
    /**
     * Child Class (keep this around just in case...)
     */
    protected final Class<? extends AbstractCostModel> child_class;

    /**
     * Keep track of the last PartitionPlan that was used so that we can automatically invalidate our own cache?
     * Really? Do we really want to always be able to do that ourselves?
     * Why not? It's not working the way we have it now? Go fuck yourself!
     */
    // protected PartitionPlan last_pplan = null;
    
    /** Caching Parameter */
    protected boolean use_caching = true;

    /** Enable Execution Calculation (if supported) */
    protected boolean use_execution = true;
    
    /** Enable Skew Calculations (if supported) */
    protected boolean use_skew = true;
    protected boolean use_skew_txns = true;
    protected boolean use_skew_java = false;
    
    /** Enable Multipartition Txn Penalty (if supported) */
    protected boolean use_multitpartition_penalty = true;
    
    
    /**
     * Weights
     */
    protected double execution_weight = 1.0;
    protected double skew_weight = 1.0;
    protected double entropy_weight_txn = 1.0;
    protected double java_exec_weight = 1.0;
    protected double multipartition_penalty = 1.0;
    
    /**
     * PartitionEstimator
     * This does all the heavy lifting for us
     */
    protected final PartitionEstimator p_estimator;
    protected int num_partitions;
    protected int num_tables;
    protected int num_procedures;
    
    /**
     * Which partitions executed the actual the java of the VoltProcedure
     */
    protected final Histogram<Integer> histogram_java_partitions = new Histogram<Integer>();
    
    /**
     * How many times did we execute each procedure
     */
    protected final Histogram<String> histogram_procs = new Histogram<String>();
    
    /**
     * How many times did we execute each procedure when it was either single- or multi-partition?
     */
    protected final Histogram<String> histogram_sp_procs = new Histogram<String>();
    protected final Histogram<String> histogram_mp_procs = new Histogram<String>();
    
    /**
     * This histogram keeps track of how many times txns touched a partition at least once
     * Note that this will only record an entry once per txn per partition. If you want the data
     * on the total number times the txns touched the partitions, you want the query access histogram 
     */
    protected final Histogram<Integer> histogram_txn_partitions = new Histogram<Integer>();
    
    /**
     * This histogram keeps track of how many times partitions were touched by any query in the txns
     * If a single txn has multiple queries that touch a particular partition, there will be an entry
     * added for each of those queries.
     */
    protected final Histogram<Integer> histogram_query_partitions = new Histogram<Integer>();
    
    /**
     * Since we have an iterative cost-model, keep track of the number of queries and txns that
     * we have examined. 
     */
    protected final AtomicLong query_ctr = new AtomicLong(0);
    protected final AtomicLong txn_ctr = new AtomicLong(0);
    
    /**
     * Debugging switch
     */
    private boolean enable_debugging = false;
    private final List<StringBuilder> last_debug = new ArrayList<StringBuilder>();
    
    // ----------------------------------------------------------------------------
    // CONSTRUCTOR
    // ----------------------------------------------------------------------------
    
    /**
     * Constructor
     */
    public AbstractCostModel(final Class<? extends AbstractCostModel> child_class, final Database catalog_db, final PartitionEstimator p_estimator) {
        this.child_class = child_class;
        this.p_estimator = p_estimator;
    }
    
    public final void clear() {
        this.clear(false);
    }
    
    /**
     * Clear out some of the internal counters 
     */
    public void clear(boolean force) {
        this.histogram_procs.clear();
        this.histogram_mp_procs.clear();
        this.histogram_sp_procs.clear();
        this.histogram_java_partitions.clear();
        this.histogram_query_partitions.clear();
        this.histogram_txn_partitions.clear();
        this.query_ctr.set(0);
        this.txn_ctr.set(0);
        this.last_debug.clear();
    }
    
    public PartitionEstimator getPartitionEstimator() {
        return p_estimator;
    }
    
    // ----------------------------------------------------------------------------
    // PREPARE METHODS
    // ----------------------------------------------------------------------------
    
    /**
     * Must be called before the next round of cost estimations for a new catalog
     * @param catalog_db
     */
    public final void prepare(final Database catalog_db) {
        // This is the start of a new run through the workload, so we need to
        // reinit our PartitionEstimator so that we are getting the proper catalog objects back
        this.p_estimator.initCatalog(catalog_db);
        this.num_partitions = CatalogUtil.getNumberOfPartitions(catalog_db);
        this.num_tables = catalog_db.getTables().size();
        this.num_procedures = catalog_db.getProcedures().size();
        
        // final boolean trace = LOG.isTraceEnabled();
        this.prepareImpl(catalog_db);
        
        // Construct a PartitionPlan for the current state of the catalog so that we 
        // know how to invalidate ourselves
        
        /* I don't think we need this anymore...
        PartitionPlan new_pplan = PartitionPlan.createFromCatalog(catalog_db);
        if (this.last_pplan != null) {
            Set<CatalogType> changed = new_pplan.getChangedEntries(this.last_pplan);
            if (!changed.isEmpty()) {
                if (trace) LOG.trace("Invalidating " + changed.size() + " catalog items that have changed from the last PartitionPlan");
                this.invalidateCache(changed);
            }
        }
        this.last_pplan = new_pplan;
        */
    }
    
    /**
     * Additional initialization that is needed before beginning the next round of estimations
     */
    public abstract void prepareImpl(final Database catalog_db);
    
    // ----------------------------------------------------------------------------
    // BASE METHODS
    // ----------------------------------------------------------------------------
    
    public void applyDesignerHints(DesignerHints hints) {
        this.setCachingEnabled(hints.enable_costmodel_caching);
        
        this.setEntropyEnabled(hints.enable_costmodel_skew);
        this.setEntropyWeight(hints.weight_costmodel_skew);
        
        this.setExecutionCostEnabled(hints.enable_costmodel_execution);
        this.setExecutionWeight(hints.weight_costmodel_execution);
        
        this.setMultiPartitionPenaltyEnabled(hints.enable_costmodel_multipartition_penalty);
        this.setMultiPartitionPenalty(hints.weight_costmodel_multipartition_penalty);
        
        this.setJavaExecutionWeightEnabled(hints.enable_costmodel_java_execution);
        this.setJavaExecutionWeight(hints.weight_costmodel_java_execution);
    }

    /**
     * Returns true if this procedure is only executed as a single-partition procedure
     * Returns false if this procedure was executed as a multi-partition procedure at least once
     * Returns null if there is no information about this procedure
     * @param catalog_proc
     * @return
     */
    public Boolean isAlwaysSinglePartition(Procedure catalog_proc) {
        assert(catalog_proc != null);
        String proc_key = CatalogKey.createKey(catalog_proc);
        Boolean ret = null;
        if (!this.histogram_mp_procs.contains(proc_key)) {
            if (this.histogram_sp_procs.contains(proc_key)) {
                ret = true;
            }
        } else {
            ret = false;
        }
        return (ret);
    }
    

    /**
     * Return the set of untouched partitions for the last costmodel estimate
     * @param num_partitions
     * @return
     */
    public Set<Integer> getUntouchedPartitions(int num_partitions) {
        Set<Integer> untouched = new HashSet<Integer>();
        for (int i = 0; i < num_partitions; i++) {
            // For now only consider where the java executes. Ideally we will want to
            // consider where the queries execute too, but we would need to isolate 
            // the single-partition txns from the multi-partition txns that are always 
            // going to touch every partition
            if (!(this.histogram_java_partitions.contains(i))) {
//                  this.histogram_txn_partitions.contains(i) ||
//                  this.histogram_query_partitions.contains(i))) {
                untouched.add(i);
            }
        } // FOR
        return (untouched);
    }
    
    
    public boolean isCachingEnabled() {
        return use_caching;
    }
    /**
     * 
     * @param caching
     */
    public void setCachingEnabled(boolean caching) {
        if (debug.get()) LOG.debug("Cost Model Caching: " + (caching ? "ENABLED" : "DISABLED"));
        this.use_caching = caching;
    }
    
    // ----------------------------------------------------------------------------
    // EXECUTION COSTS
    // ----------------------------------------------------------------------------
    
    public boolean isExecutionCostEnabled() {
        return use_execution;
    }
    public void setExecutionCostEnabled(boolean execution) {
        if (debug.get()) LOG.debug("Cost Model Execution: " + (execution ? "ENABLED" : "DISABLED"));
        this.use_execution = execution;
    }
    public void setExecutionWeight(double weight) {
        if (debug.get()) LOG.debug("Execution Cost Weight: " + weight);
        this.execution_weight = weight;
    }
    public double getExecutionWeight() {
        return (this.execution_weight);
    }
    
    // ----------------------------------------------------------------------------
    // ENTROPY COST
    // ----------------------------------------------------------------------------
    
    public boolean isEntropyEnabled() {
        return use_skew;
    }
    public void setEntropyEnabled(boolean entropy) {
        if (debug.get()) LOG.debug("Cost Model Entropy: " + (entropy ? "ENABLED" : "DISABLED"));
        this.use_skew = entropy;
    }
    public void setEntropyWeight(double weight) {
        if (debug.get()) LOG.debug("Entropy Cost Weight: " + weight);
        this.skew_weight = weight;
    }
    public double getEntropyWeight() {
        return (this.skew_weight);
    }
    
    // ----------------------------------------------------------------------------
    // MULTIPARTITION PENALTY
    // ----------------------------------------------------------------------------
    
    public boolean isMultiPartitionPenaltyEnabled() {
        return this.use_multitpartition_penalty;
    }
    public void setMultiPartitionPenaltyEnabled(boolean enable) {
        if (debug.get()) LOG.debug("Cost Model MultiPartition Penalty: " + (enable ? "ENABLED" : "DISABLED"));
        this.use_multitpartition_penalty = enable;
    }
    public void setMultiPartitionPenalty(double penalty) {
        if (debug.get()) LOG.debug("MultiPartition Penalty: " + penalty);
        this.multipartition_penalty = penalty;
    }
    public double getMultiPartitionPenalty() {
        return (this.multipartition_penalty);
    }
    
    
    // ----------------------------------------------------------------------------
    // JAVA EXECUTION WEIGHT (SKEW)
    // ----------------------------------------------------------------------------
    
    public boolean isJavaExecutionWeightEnabled() {
        return this.use_skew_java;
    }
    public void setJavaExecutionWeightEnabled(boolean enable) {
        if (debug.get()) LOG.debug("Cost Model Java Execution: " + (enable ? "ENABLED" : "DISABLED"));
        this.use_skew_java = enable;
    }
    public void setJavaExecutionWeight(double weight) {
        if (debug.get()) LOG.debug("Java Execution Weight: " + weight);
        this.java_exec_weight = weight;
    }
    public double getJavaExecutionWeight() {
        return (this.java_exec_weight);
    }

    // ----------------------------------------------------------------------------
    // PARTITION EXECUTION WEIGHT (SKEW)
    // ----------------------------------------------------------------------------
    
//    public boolean isEntropyTxnWeightEnabled() {
//        return this.use_skew_java;
//    }
//    public void setEntropyTxnWeightEnabled(boolean enable) {
//        if (debug.get()) LOG.debug("Cost Model Entropy Txn: " + (enable ? "ENABLED" : "DISABLED"));
//        this.use_skew_java = enable;
//    }
//    public void setEntropyTxnWeight(int weight) {
//        if (debug.get()) LOG.debug("Entropy Txn Weight: " + weight);
//        this.java_exec_weight = weight;
//    }
//    public int getEntropyTxnWeight() {
//        return (this.java_exec_weight);
//    }
    
    
    public Histogram<String> getProcedureHistogram() {
        return this.histogram_procs;
    }
    
    public Histogram<String> getSinglePartitionProcedureHistogram() {
        return this.histogram_sp_procs;
    }
    public Histogram<String> getMultiPartitionProcedureHistogram() {
        return this.histogram_mp_procs;
    }
    
    /**
     * Returns the histogram of how often a particular partition has to execute
     * the Java code for a transaction
     * @return
     */
    public Histogram<Integer> getJavaExecutionHistogram() {
        return this.histogram_java_partitions;
    }
    
    /**
     * Returns the histogram for how often partitions are accessed for txns
     * @return
     */
    public Histogram<Integer> getTxnPartitionAccessHistogram() {
        return this.histogram_txn_partitions;
    }
    
    /**
     * Returns the histogram for how often partitions are accessed for queries 
     * @return
     */
    public Histogram<Integer> getQueryPartitionAccessHistogram() {
        return this.histogram_query_partitions;
    }
    
    // ----------------------------------------------------------------------------
    // CACHE INVALIDATION METHODS
    // ----------------------------------------------------------------------------
    
    /**
     * Invalidate cache entries for the given CatalogKey
     * @param catalog_key
     */
    public abstract void invalidateCache(String catalog_key);
    
    /**
     *  Invalidate the cache entries for all of the given CatalogKeys
     * @param keys
     */
    public void invalidateCache(Iterable<String> keys) {
        for (String catalog_key : keys) {
            this.invalidateCache(catalog_key);
        }
    }
    
    /**
     * Invalidate a table's cache entry
     * @param catalog_tbl
     */
    public void invalidateCache(CatalogType catalog_item) {
        this.invalidateCache(CatalogKey.createKey(catalog_item));
    }
    
    /**
     * 
     * @param catalog_tbls
     */
    public <T extends CatalogType> void invalidateCache(Collection<T> catalog_items) {
        for (T catalog_item : catalog_items) {
            this.invalidateCache(CatalogKey.createKey(catalog_item));
        } // FOR
    }
    
    // ----------------------------------------------------------------------------
    // ESTIMATION METHODS
    // ----------------------------------------------------------------------------

    /**
     * 
     * @param workload TODO
     * @param xact
     * @return
     * @throws Exception
     */
    public abstract double estimateTransactionCost(Database catalog_db, Workload workload, Filter filter, TransactionTrace xact) throws Exception;
    
    
    /**
     * Estimate the cost of a single TransactionTrace object
     * @param catalog_db
     * @param xact
     * @return
     * @throws Exception
     */
    public final double estimateTransactionCost(Database catalog_db, TransactionTrace xact) throws Exception {
        return (this.estimateTransactionCost(catalog_db, null, null, xact));
    }
    
    /**
     * 
     * @param workload
     * @param upper_bound TODO
     * @return
     * @throws Exception
     */
    public final double estimateWorkloadCost(Database catalog_db, Workload workload, Filter filter, Double upper_bound) throws Exception {
        this.prepare(catalog_db);
        // Always make sure that we reset the filter
        if (filter != null) filter.reset();
        return (this.estimateWorkloadCostImpl(catalog_db, workload, filter, upper_bound));
    }
    
    /**
     * Base implementation to estimate cost of a Workload
     * @param catalog_db
     * @param workload
     * @param filter
     * @param upper_bound
     * @return
     * @throws Exception
     */
    protected double estimateWorkloadCostImpl(Database catalog_db, Workload workload, Filter filter, Double upper_bound) throws Exception {
        double cost = 0.0d;
        Iterator<TransactionTrace> it = workload.iterator(filter);
        while (it.hasNext()) {
            TransactionTrace xact = it.next();
            //System.out.println(xact.debug(this.catalog_db) + "\n");
            try {
                cost += this.estimateTransactionCost(catalog_db, workload, filter, xact);
            } catch (Exception ex) {
                LOG.error("Failed to estimate cost for " + xact.getCatalogItemName());
                CatalogUtil.saveCatalog(catalog_db.getCatalog(), "catalog.txt");
                throw ex;
            }
            if (upper_bound != null && cost > upper_bound.doubleValue()) {
                if (debug.get()) if (debug.get()) LOG.debug("Exceeded upper bound. Halting estimation early!");
                break;
            }
        } // WHILE
        return (cost);
    }

    /**
     * 
     * @param workload
     * @return
     * @throws Exception
     */
    public final double estimateWorkloadCost(Database catalog_db, Workload workload) throws Exception {
        return (this.estimateWorkloadCost(catalog_db, workload, null, null));
    }
    
    // ----------------------------------------------------------------------------
    // DEBUGGING METHODS
    // ----------------------------------------------------------------------------

    /**
     * Dynamic switch to enable DEBUG log level
     * If enable_debugging is false, then LOG's level will be set back to its original level
     * @param enable_debugging
     */
    public void setDebuggingEnabled(boolean enable_debugging) {
        this.enable_debugging = enable_debugging;
        if (debug.get()) LOG.debug("Setting Custom Debugging: " + this.enable_debugging);
    }
    
    protected boolean isDebugEnabled() {
        return (this.enable_debugging);
    }
    
    protected void appendDebugMessage(String msg) {
        this.appendDebugMessage(new StringBuilder(msg));

    }
    protected void appendDebugMessage(StringBuilder sb) {
        this.last_debug.add(sb);
    }
    
    public boolean hasDebugMessages() {
        return (this.last_debug.size() > 0);
    }
    
    public String getLastDebugMessage() {
        StringBuilder sb = new StringBuilder();
        for (StringBuilder inner : this.last_debug) {
            if (inner.length() == 0) continue;
            if (sb.length() > 0) sb.append(StringUtil.SINGLE_LINE);
            sb.append(inner);
        } // FOR
        return (sb.toString());
    }
    
    /**
     * Debug string of all the histograms
     * @return
     */
    @SuppressWarnings("unchecked")
    public String debugHistograms() {
        int num_histograms = 6;
        Map<String, Object> maps[] = new Map[num_histograms];
        int i = -1;

        maps[++i] = new HashMap<String, Object>();
        maps[i].put("Procedures", this.histogram_procs);

        maps[++i] = new HashMap<String, Object>();
        maps[i].put("Single Partition Txns", this.histogram_sp_procs);
        
        maps[++i] = new HashMap<String, Object>();
        maps[i].put("Multi Partition Txns", this.histogram_mp_procs);
        
        maps[++i] = new HashMap<String, Object>();
        maps[i].put("Java Execution Partitions", this.histogram_java_partitions);
        
        maps[++i] = new HashMap<String, Object>();
        maps[i].put("Txn Partition Access", this.histogram_txn_partitions);
        
        maps[++i] = new HashMap<String, Object>();
        maps[i].put("Query Partition Access", this.histogram_query_partitions);
        
        return (StringUtil.formatMaps(maps));
    }
}
