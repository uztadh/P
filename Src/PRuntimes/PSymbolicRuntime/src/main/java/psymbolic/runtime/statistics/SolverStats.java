package psymbolic.runtime.statistics;

import lombok.Getter;
import lombok.Setter;
import psymbolic.commandline.EntryPoint;
import psymbolic.commandline.MemoutException;
import psymbolic.runtime.logger.SearchLogger;
import psymbolic.utils.MemoryMonitor;
import psymbolic.utils.TimeMonitor;
import psymbolic.valuesummary.solvers.SolverEngine;
import psymbolic.valuesummary.solvers.sat.expr.Fraig;

import java.time.Duration;
import java.time.Instant;

public class SolverStats {
    public static int andOperations = 0;
    public static int orOperations = 0;
    public static int notOperations = 0;
    public static int isSatOperations = 0;
    public static int isSatResult = 0;

    @Setter
    public static double memLimit = 0;                  // memory limit in megabytes (0 means infinite)
    @Setter
    public static double timeLimit = 0;                 // time limit in seconds (0 means infinite)
    public static double timeTotalCreateGuards = 0;     // total time in milliseconds to create guards
    public static double timeMaxCreateGuards = 0;       // max time in milliseconds to create guards
    public static double timeTotalSolveGuards = 0;      // total time in milliseconds to solve guards
    public static double timeMaxSolveGuards = 0;        // max time in milliseconds to solve guards

    public static void updateCreateGuardTime(long timeSpent) {
        timeTotalCreateGuards += timeSpent;
        if (timeMaxCreateGuards < timeSpent)
            timeMaxCreateGuards = timeSpent;

        // switch engine
        SolverEngine.switchEngineAuto();
        // check if reached time or memory limit
        checkResourceLimits();
    }

    public static void updateSolveGuardTime(long timeSpent) {
        timeTotalSolveGuards += timeSpent;
        if (timeMaxSolveGuards < timeSpent)
            timeMaxSolveGuards = timeSpent;
        // check if reached time or memory limit
        checkResourceLimits();
    }

    public static void checkResourceLimits() {
        if (memLimit > 0) {
            if (MemoryMonitor.getMemSpent() > memLimit) {
                throw new MemoutException(String.format("Max memory limit reached: %.1f MB", MemoryMonitor.getMemSpent()), MemoryMonitor.getMemSpent());
            }
        }
    }



    public static double getDoublePercent(double spent, double total) {
        return (spent == 0 ? 0.0 : (spent * 100.0 / total));
    }

    public static double isSatPercent(int isSatOps, int isSatRes) {
    	return (isSatOps == 0 ? 0.0 : (isSatRes * 100.0 / isSatOps));
    }
    
    public static void logSolverStats() {
        SearchLogger.log("#-vars", String.format("%d", SolverEngine.getVarCount()));
        SearchLogger.log("#-guards", String.format("%d", SolverEngine.getGuardCount()));
        SearchLogger.log("#-expr", String.format("%d", SolverEngine.getSolver().getExprCount()));
        SearchLogger.log("#-and-ops", String.format("%d", andOperations));
        SearchLogger.log("#-or-ops", String.format("%d", orOperations));
        SearchLogger.log("#-not-ops", String.format("%d", notOperations));
        SearchLogger.log("aig-#-sat-ops", String.format("%d", Fraig.isSatOperations));
        SearchLogger.log("aig-#-sat-ops-sat", String.format("%d", Fraig.isSatResult));
        SearchLogger.log("aig-%-sat-ops-sat", String.format("%.1f", isSatPercent(Fraig.isSatOperations, Fraig.isSatResult)));
        SearchLogger.log("solver-#-nodes", String.format("%d", SolverEngine.getSolver().getNodeCount()));
        SearchLogger.log("solver-#-sat-ops", String.format("%d", isSatOperations));
        SearchLogger.log("solver-#-sat-ops-sat", String.format("%d", isSatResult));
        SearchLogger.log("solver-%-sat-ops-sat", String.format("%.1f", isSatPercent(isSatOperations, isSatResult)));
    }
}
