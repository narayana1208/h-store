package org.voltdb.sysprocs;

import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.voltdb.BackendTarget;
import org.voltdb.DependencySet;
import org.voltdb.HsqlBackend;
import org.voltdb.ParameterSet;
import org.voltdb.ProcInfo;
import org.voltdb.VoltSystemProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltType;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.Table;
import org.voltdb.jni.ExecutionEngine;

import edu.brown.hstore.PartitionExecutor;
import edu.brown.utils.PartitionEstimator;

/** 
 * 
 */
@ProcInfo(
    partitionParam = 0,
    singlePartition = true
)
public class EvictTuples extends VoltSystemProcedure {
    @SuppressWarnings("unused")
    private static final Logger LOG = Logger.getLogger(EvictTuples.class);

    public static final ColumnInfo nodeResultsColumns[] = {
        new ColumnInfo("PARTITION", VoltType.INTEGER),
        new ColumnInfo("TABLE", VoltType.STRING),
        new ColumnInfo("TUPLES_EVICTED", VoltType.INTEGER),
        new ColumnInfo("BLOCKS_EVICTED", VoltType.INTEGER),
        new ColumnInfo("BYTES_EVICTED", VoltType.BIGINT),
        new ColumnInfo("CREATED", VoltType.TIMESTAMP),
    };
    
    @Override
    public void globalInit(PartitionExecutor site, Procedure catalog_proc,
            BackendTarget eeType, HsqlBackend hsql, PartitionEstimator p_estimator) {
        super.globalInit(site, catalog_proc, eeType, hsql, p_estimator);
        site.registerPlanFragment(SysProcFragmentId.PF_antiCacheEviction, this);
    }

    @Override
    public DependencySet executePlanFragment(long txn_id,
                                             Map<Integer, List<VoltTable>> dependencies,
                                             int fragmentId,
                                             ParameterSet params,
                                             PartitionExecutor.SystemProcedureExecutionContext context) {
        assert(fragmentId == SysProcFragmentId.PF_antiCacheEviction);
        throw new IllegalAccessError("Invalid invocation of " + this.getClass() + ".executePlanFragment()");
    }
    
    public VoltTable[] run(int partition, String tableNames[], long blockSizes[]) {
        ExecutionEngine ee = executor.getExecutionEngine();
        assert(tableNames.length == blockSizes.length);

        // Check Input
        if (tableNames.length == 0) {
            throw new VoltAbortException("No tables to evict were given");
        }
        Table tables[] = new Table[tableNames.length];
        for (int i = 0; i < tableNames.length; i++) {
            tables[i] = database.getTables().getIgnoreCase(tableNames[i]);
            if (tables[i] == null) {
                String msg = String.format("Unknown table '%s'", tableNames[i]);
                throw new VoltAbortException(msg);
            }
            else if (tables[i].getEvictable() == false) {
                String msg = String.format("Trying to evict tuples from table '%s' but it is not marked as evictable", tables[i].getName());
                throw new VoltAbortException(msg);
            }
            else if (blockSizes[i] <= 0) {
                String msg = String.format("Invalid block eviction size '%d' for table '%s'", blockSizes[i], tables[i].getName());
                throw new VoltAbortException(msg);
            }
        } // FOR
        
        // TODO: Instead of sending down requests one at a time per table, it will
        //       be much faster if we just send down the entire batch
        VoltTable allResults = null;
        for (int i = 0; i < tableNames.length; i++) {
            VoltTable vt = ee.antiCacheEvictBlock(tables[i], blockSizes[i]);
            if (allResults == null) {
                allResults = new VoltTable(vt);
            }
            boolean adv = vt.advanceRow();
            assert(adv);
            allResults.add(vt);
        } // FOR
        
        return new VoltTable[]{ allResults };
    }
}
