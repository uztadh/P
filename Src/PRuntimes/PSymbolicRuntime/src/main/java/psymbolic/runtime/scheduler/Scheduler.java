package psymbolic.runtime.scheduler;

import lombok.Getter;
import lombok.Setter;
import psymbolic.commandline.Assert;
import psymbolic.commandline.PSymConfiguration;
import psymbolic.commandline.Program;
import psymbolic.runtime.*;
import psymbolic.runtime.logger.PSymLogger;
import psymbolic.runtime.logger.SearchLogger;
import psymbolic.runtime.logger.StatWriter;
import psymbolic.runtime.logger.TraceLogger;
import psymbolic.runtime.machine.Machine;
import psymbolic.runtime.machine.Monitor;
import psymbolic.runtime.machine.State;
import psymbolic.runtime.machine.buffer.EventBufferSemantics;
import psymbolic.runtime.statistics.CoverageStats;
import psymbolic.runtime.statistics.SearchStats;
import psymbolic.runtime.statistics.SolverStats;
import psymbolic.utils.GlobalData;
import psymbolic.utils.MemoryMonitor;
import psymbolic.utils.TimeMonitor;
import psymbolic.valuesummary.*;
import psymbolic.valuesummary.solvers.SolverEngine;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;


public class Scheduler implements SymbolicSearch {
    /** Iteration number */
    @Getter
    int iter = 0;
    /** Start iteration number */
    @Getter
    int start_iter = 0;
    /** Search statistics */
    protected SearchStats searchStats = new SearchStats();
    /** Program */
    @Getter
    private final Program program;
    /** The scheduling choices made */
    public Schedule schedule;
    @Setter
    transient PSymConfiguration configuration;
    @Getter
    /** List of all machines along any path constraints */
    final List<Machine> machines;
    /** How many instances of each Machine there are */
    protected Map<Class<? extends Machine>, PrimitiveVS<Integer>> machineCounters;
    /** The machine to start with */
    private Machine start;
    /** The map from events to listening monitors */
    private Map<Event, List<Monitor>> listeners;
    /** List of monitors instances */
    List<Monitor> monitors;
    /** Vector clock manager */
    private VectorClockManager vcManager;
    /** Result of the search */
    public String result;
    /** Current depth of exploration */
    private int depth = 0;
    /** Whether or not search is done */
    protected boolean done = false;
    /** Choice depth */
    int choiceDepth = 0;
    /** Backtrack choice depth */
    int backtrackDepth = 0;
    /** Starting choice depth from previous iteration, i.e., corresponding to srcState */
    int preChoiceDepth = Integer.MAX_VALUE;
    /** Total number of states */
    private int totalStateCount = 0;
    /** Flag whether current step is a create machine step */
    private Boolean createStep = false;
    /** Flag whether current step is a sync step */
    private Boolean syncStep = false;
    /** Flag whether current execution finished */
    private Boolean executionFinished = false;

    /** Source state at the beginning of each schedule step */
    transient Map<Machine, List<ValueSummary>> srcState = new HashMap<>();
    /** Map of distinct concrete state to number of times state is visited */
    transient private Map<String, Integer> distinctStates = new HashMap<>();
    /** List of distinct concrete states */
    transient private List<String> distinctStatesList = new ArrayList<>();
    /** Guard corresponding on distinct states at a step */
    transient private Guard distinctStateGuard = null;

    private boolean useFilters() { return configuration.isUseFilters(); }
    /** Get whether to intersect with receiver queue semantics
     * @return whether to intersect with receiver queue semantics
     */
    public boolean useReceiverSemantics() { return configuration.isUseReceiverQueueSemantics(); }

    /** Get whether to use bag semantics
     * @return whether to use bag semantics
     */
    public boolean useBagSemantics() { return configuration.isUseBagSemantics(); }

    /** Get whether to use sleep sets
     * @return whether to use sleep sets
     */
    public boolean useSleepSets() { return configuration.isUseSleepSets(); }

    public int getTotalStates() {
        return totalStateCount;
    }

    public int getTotalDistinctStates() {
        return distinctStates.size();
    }

    /** Reset scheduler state
     */
    public void reset() {
        depth = 0;
        choiceDepth = 0;
        preChoiceDepth = Integer.MAX_VALUE;
        done = false;
        machineCounters.clear();
        machines.clear();
        srcState.clear();
        schedule.setSchedulerDepth(getDepth());
        schedule.setSchedulerChoiceDepth(getChoiceDepth());
        schedule.setSchedulerState(srcState, machineCounters);
    }

    /** Reinitialize scheduler */
    public void reinitialize() {
        // set all transient data structures
        srcState = new HashMap<>();
        distinctStates = new HashMap<>();
        distinctStatesList = new ArrayList<>();
        distinctStateGuard = null;
        for (Machine machine : schedule.getMachines()) {
            machine.setScheduler(this);
        }
    }

    /** Restore scheduler state
     */
    public void restore(int d, int cd) {
        depth = d;
        choiceDepth = cd;
        preChoiceDepth = Integer.MAX_VALUE;
        done = false;
    }

    /** Return scheduler's VC manager
        @return the scheduler's current vector clock manager
     */
    public VectorClockManager getVcManager() {
        return vcManager;
    }

    /** Find out whether symbolic execution is done
     * @return Whether or not there are more steps to run
     */
    public boolean isDone() {
        return done || depth == configuration.getMaxStepBound();
    }

    /** Find out whether current execution finished completely
     * @return Whether or not current execution finished
     */
    public boolean isFinishedExecution() {
        return executionFinished || depth == configuration.getMaxStepBound();
    }

    /** Get current depth
     * @return current depth
     */
    public int getDepth() { return depth; }

    /** Get current choice depth
     * @return current choice depth
     */
    public int getChoiceDepth() { return choiceDepth; }

    /** Make new schedule
     * @return A new Schedule instance */
    public Schedule getNewSchedule() {
        return new Schedule(this.useSleepSets());
    }

    /** Get the schedule
     * @return The schedule
     */
    public Schedule getSchedule() { return schedule; }

    /** Make a new Scheduler
     * @param machines The machines initially in the Scheduler
     */
    public Scheduler(PSymConfiguration config, Program p, Machine... machines) {
        setConfiguration(config);
        program = p;
        this.schedule = getNewSchedule();
        this.machines = new ArrayList<>();
        this.machineCounters = new HashMap<>();
        this.vcManager = new VectorClockManager(useReceiverSemantics() || configuration.isDpor() || useSleepSets());

        for (Machine machine : machines) {
            this.machines.add(machine);
            if (this.machineCounters.containsKey(machine.getClass())) {
                this.machineCounters.put(machine.getClass(),
                        IntegerVS.add(this.machineCounters.get(machine.getClass()), 1));
            } else {
                this.machineCounters.put(machine.getClass(), new PrimitiveVS<>(1));
            }
            TraceLogger.onCreateMachine(Guard.constTrue(), machine);
            machine.setScheduler(this);
            schedule.makeMachine(machine, Guard.constTrue());
            vcManager.addMachine(Guard.constTrue(), machine);
        }
    }

    public List<PrimitiveVS> getNextIntegerChoices(PrimitiveVS<Integer> bound, Guard pc) {
        List<PrimitiveVS> choices = new ArrayList<>();
        Guard zeroGuard = bound.getGuardFor(0);
        if (!zeroGuard.isFalse()) {
            bound = bound.updateUnderGuard(zeroGuard, new PrimitiveVS<Integer>(1));
        }
        for (int i = 0; i < IntegerVS.maxValue(bound); i++) {
            Guard cond = IntegerVS.lessThan(i, bound).getGuardFor(true);
            choices.add(new PrimitiveVS<>(i).restrict(cond).restrict(pc));
        }
        return choices;
    }

    public PrimitiveVS<Integer> getNextInteger(List<PrimitiveVS> candidateIntegers) {
        PrimitiveVS<Integer> choices = (PrimitiveVS<Integer>) NondetUtil.getNondetChoice(candidateIntegers);
        schedule.addRepeatInt(choices, choiceDepth);
        choiceDepth++;
        return choices;
    }

    @Override
    public PrimitiveVS<Integer> getNextInteger(PrimitiveVS<Integer> bound, Guard pc) {
        return getNextInteger(getNextIntegerChoices(bound, pc));
    }

    public List<PrimitiveVS> getNextBooleanChoices(Guard pc) {
        List<PrimitiveVS> choices = new ArrayList<>();
        choices.add(new PrimitiveVS<>(true).restrict(pc));
        choices.add(new PrimitiveVS<>(false).restrict(pc));
        return choices;
    }

    public PrimitiveVS<Boolean> getNextBoolean(List<PrimitiveVS> candidateBooleans) {
        PrimitiveVS<Boolean> choices = (PrimitiveVS<Boolean>) NondetUtil.getNondetChoice(candidateBooleans);
        schedule.addRepeatBool(choices, choiceDepth);
        choiceDepth++;
        return choices;
    }

    @Override
    public PrimitiveVS<Boolean> getNextBoolean(Guard pc) {
        return getNextBoolean(getNextBooleanChoices(pc));
    }

    public List<ValueSummary> getNextElementChoices(ListVS candidates, Guard pc) {
        PrimitiveVS<Integer> size = candidates.size();
        PrimitiveVS<Integer> index = new PrimitiveVS<>(0).restrict(size.getUniverse());
        List<ValueSummary> list = new ArrayList<>();
        while (BooleanVS.isEverTrue(IntegerVS.lessThan(index, size))) {
            Guard cond = BooleanVS.getTrueGuard(IntegerVS.lessThan(index, size));
            if (cond.isTrue()) {
                list.add(candidates.get(index).restrict(pc));
            } else {
                list.add(candidates.restrict(cond).get(index).restrict(pc));
            }
            index = IntegerVS.add(index, 1);
        }
        return list;
    }

    public PrimitiveVS<ValueSummary> getNextElementHelper(List<ValueSummary> candidates) {
        PrimitiveVS<ValueSummary> choices = NondetUtil.getNondetChoice(candidates.stream().map(x -> new PrimitiveVS(x).restrict(x.getUniverse())).collect(Collectors.toList()));
        schedule.addRepeatElement(choices, choiceDepth);
        choiceDepth++;
        return choices;
    }

    public ValueSummary getNextElementFlattener(PrimitiveVS<ValueSummary> choices) {
        ValueSummary flattened = null;
        List<ValueSummary> toMerge = new ArrayList<>();
        for (GuardedValue<ValueSummary> guardedValue : choices.getGuardedValues()) {
            if (flattened == null) {
                flattened = guardedValue.getValue().restrict(guardedValue.getGuard());
            } else {
                toMerge.add(guardedValue.getValue().restrict(guardedValue.getGuard()));
            }
        }
        if (flattened == null) {
            flattened = new PrimitiveVS<>();
        } else {
            flattened = flattened.merge(toMerge);
        }
        return flattened;
    }

    @Override
    public ValueSummary getNextElement(ListVS<? extends ValueSummary> s, Guard pc) {
        return getNextElementFlattener(getNextElementHelper(getNextElementChoices(s, pc)));
    }

    @Override
    public ValueSummary getNextElement(SetVS<? extends ValueSummary> s, Guard pc) {
        return getNextElement(s.getElements(), pc);
    }

    @Override
    public ValueSummary getNextElement(MapVS<?, ? extends ValueSummary, ? extends ValueSummary> s, Guard pc) {
        return getNextElement(s.getKeys(), pc);
    }

    /** Start execution with the specified machine
     * @param machine Machine to start execution with */
    public void startWith(Machine machine) {
        if (this.machineCounters.containsKey(machine.getClass())) {
            this.machineCounters.put(machine.getClass(),
                    IntegerVS.add(this.machineCounters.get(machine.getClass()), 1));
        } else {
            this.machineCounters.put(machine.getClass(), new PrimitiveVS<>(1));
        }

        machines.add(machine);
        start = machine;
        TraceLogger.onCreateMachine(Guard.constTrue(), machine);
        machine.setScheduler(this);
        schedule.makeMachine(machine, Guard.constTrue());
        if (vcManager.hasIdx(new PrimitiveVS<>(machine)).isFalse())
            vcManager.addMachine(Guard.constTrue(), machine);

        performEffect(
                new Message(
                        Event.createMachine,
                        new PrimitiveVS<>(machine),
                        null
                )
        );
    }

    public void replayStartWith(Machine machine) {
        PrimitiveVS<Machine> machineVS;
        if (this.machineCounters.containsKey(machine.getClass())) {
            machineVS = schedule.getMachine(machine.getClass(), this.machineCounters.get(machine.getClass()));
            this.machineCounters.put(machine.getClass(),
                    IntegerVS.add(this.machineCounters.get(machine.getClass()), 1));
        } else {
            machineVS = schedule.getMachine(machine.getClass(), new PrimitiveVS<>(0));
            this.machineCounters.put(machine.getClass(), new PrimitiveVS<>(1));
        }

        TraceLogger.onCreateMachine(machineVS.getUniverse(), machine);
        machine.setScheduler(this);

        performEffect(
                new Message(
                        Event.createMachine,
                        machineVS,
                        null
                )
        );
    }

    public void initializeSearch() {
        assert(getDepth() == 0);

        GlobalData.getChoiceLearningStats().setProgramStateHash(this);
        listeners = program.getListeners();
        monitors = new ArrayList<>(program.getMonitors());
        for (Machine m : program.getMonitors()) {
            startWith(m);
        }
        Machine target = program.getStart();
        startWith(target);
        start = target;
        depth++;
    }

    public void restoreState(Schedule.ChoiceState state) {
        assert(state != null);
        for (Map.Entry<Machine, List<ValueSummary>> entry: state.getMachineStates().entrySet()) {
            entry.getKey().setLocalState(entry.getValue());
        }
        for (Machine m: machines) {
            if (!state.getMachineStates().containsKey(m)) {
                m.reset();
            }
        }
        machineCounters = state.getMachineCounters();
    }

    public void restoreStringState(List<List<String>> state) {
        int idx = 0;
        for (Machine machine : machines) {
            List<String> machineStringState = state.get(idx++);
            List<ValueSummary> machineLocalState = new ArrayList<>();
            for (String s: machineStringState) {
                machineLocalState.add((ValueSummary) SerializeObject.objectFromString(s));
            }
            machine.setLocalState(machineLocalState);
        }
    }

    @Override
    public void doSearch() throws TimeoutException, InterruptedException {
        initializeSearch();
        performSearch();
    }

    public void performSearch() throws TimeoutException {
        schedule.setNumBacktracksInSchedule();
        while (!isDone()) {
            // ScheduleLogger.log("step " + depth + ", true queries " + Guard.trueQueries + ", false queries " + Guard.falseQueries);
            Assert.prop(getDepth() < configuration.getMaxStepBound(), "Maximum allowed depth " + configuration.getMaxStepBound() + " exceeded", this, schedule.getLengthCond(schedule.size()));
            step();
        }
        schedule.setNumBacktracksInSchedule();
        if (done) {
            searchStats.setIterationCompleted();
        }
        checkLiveness();
    }

    protected void checkLiveness() {
        if (isFinishedExecution()) {
            for (Monitor m : monitors) {
                PrimitiveVS<State> monitorState = m.getCurrentState();
                for (GuardedValue<State> entry : monitorState.getGuardedValues()) {
                    State s = entry.getValue();
                    if (s.isHotState()) {
                        Guard g = entry.getGuard();
                        if (executionFinished) {
                            Assert.prop(g.isFalse(), String.format(
                                    "Monitor %s detected liveness bug in hot state %s at the end of program execution",
                                    m, s), this, g);
                        } else {
                            Assert.prop(g.isFalse(), String.format(
                                    "Monitor %s detected potential liveness bug in hot state %s",
                                    m, s), this, g);
                        }
                    }
                }
            }
        }
    }

    // print statistics
    public void print_stats() {
        SearchStats.TotalStats totalStats = searchStats.getSearchTotal();
        Instant end = Instant.now();
        double timeUsed = (Duration.between(TimeMonitor.getInstance().getStart(), end).toMillis() / 1000.0);
        double memoryUsed = MemoryMonitor.getMemSpent();

        // print basic statistics
        StatWriter.log("result", String.format("%s", result));
        StatWriter.log("time-seconds", String.format("%.1f", timeUsed));
        StatWriter.log("memory-max-MB", String.format("%.1f", MemoryMonitor.getMaxMemSpent()));
        StatWriter.log("memory-current-MB", String.format("%.1f", memoryUsed));
        StatWriter.log("max-depth-explored", String.format("%d", totalStats.getDepthStats().getDepth()));

        // print solver statistics
        StatWriter.log("time-create-guards-%", String.format("%.1f", SolverStats.getDoublePercent(SolverStats.timeTotalCreateGuards/1000.0, timeUsed)));
        StatWriter.log("time-solve-guards-%", String.format("%.1f", SolverStats.getDoublePercent(SolverStats.timeTotalSolveGuards/1000.0, timeUsed)));
        StatWriter.log("time-create-guards-seconds", String.format("%.1f", SolverStats.timeTotalCreateGuards/1000.0));
        StatWriter.log("time-solve-guards-seconds", String.format("%.1f", SolverStats.timeTotalSolveGuards/1000.0));
        StatWriter.log("time-create-guards-max-seconds", String.format("%.3f", SolverStats.timeMaxCreateGuards/1000.0));
        StatWriter.log("time-solve-guards-max-seconds", String.format("%.3f", SolverStats.timeMaxSolveGuards/1000.0));
        StatWriter.logSolverStats();

        // print search statistics
        StatWriter.log("#-states", String.format("%d", getTotalStates()));
        StatWriter.log("#-distinct-states", String.format("%d", getTotalDistinctStates()));
        StatWriter.log("#-events", String.format("%d", totalStats.getDepthStats().getNumOfTransitions()));
        StatWriter.log("#-events-merged", String.format("%d", totalStats.getDepthStats().getNumOfMergedTransitions()));
        StatWriter.log("#-events-explored", String.format("%d", totalStats.getDepthStats().getNumOfTransitionsExplored()));
    }

    public void reset_stats() {
        searchStats.reset_stats();
        distinctStates.clear();
        distinctStatesList.clear();
        totalStateCount = 0;
        GlobalData.getCoverage().resetCoverage();
        GlobalData.getChoiceLearningStats().setProgramStateHash(this);
    }

    public List<PrimitiveVS> getNextSenderChoices() {
        createStep = false;
        syncStep = false;

        // prioritize the create actions
        for (Machine machine : machines) {
            if (!machine.sendBuffer.isEmpty()) {
                Guard initCond = machine.sendBuffer.hasCreateMachineUnderGuard().getGuardFor(true);
                if (!initCond.isFalse()) {
                    PrimitiveVS<Machine> ret = new PrimitiveVS<>(machine).restrict(initCond);
                    createStep = true;
                    return new ArrayList<>(Arrays.asList(ret));
                }
            }
        }

        // prioritize the sync actions i.e. events that are marked as synchronous
        for (Machine machine : machines) {
            if (!machine.sendBuffer.isEmpty()) {
                Guard syncCond = machine.sendBuffer.hasSyncEventUnderGuard().getGuardFor(true);
                if (!syncCond.isFalse()) {
                    PrimitiveVS<Machine> ret = new PrimitiveVS<>(machine).restrict(syncCond);
                    syncStep = true;
                    return new ArrayList<>(Arrays.asList(ret));
                }
            }
        }

        // remove messages with halted target
        for (Machine machine : machines) {
            while (!machine.sendBuffer.isEmpty()) {
                Guard targetHalted = machine.sendBuffer.satisfiesPredUnderGuard(x -> x.targetHalted()).getGuardFor(true);
                if (!targetHalted.isFalse()) {
                    rmBuffer(machine, targetHalted);
                    continue;
                }
                break;
            }
        }

        // now there are no create machine and sync event actions remaining
        List<GuardedValue<Machine>> guardedMachines = new ArrayList<>();

        for (Machine machine : machines) {
            if (!machine.sendBuffer.isEmpty()) {
                Guard canRun = machine.sendBuffer.satisfiesPredUnderGuard(x -> x.canRun()).getGuardFor(true);
//                Guard canRun = machine.hasHalted().getGuardFor(true).not();
//                canRun = canRun.and(machine.sendBuffer.satisfiesPredUnderGuard(x -> x.canRun()).getGuardFor(true));
                if (!canRun.isFalse()) {
                    guardedMachines.add(new GuardedValue(machine, canRun));
 //                   candidateSenders.add(new PrimitiveVS<>(machine).restrict(canRun));
                }
            }
        }
  //      return candidateSenders;

        if (useReceiverSemantics()) {
            guardedMachines = filter(guardedMachines, ReceiverQueueOrder.getInstance());
        }

        if (useFilters()) {
            guardedMachines = filter(guardedMachines, InterleaveOrder.getInstance());
        }

        executionFinished = guardedMachines.isEmpty();

        if (configuration.isUseStateCaching()) {
            if (distinctStateGuard != null) {
                guardedMachines = filterDistinct(guardedMachines);
            }
        }

        List<PrimitiveVS> candidateSenders = new ArrayList<>();
        for (GuardedValue<Machine> guardedValue : guardedMachines) {
            candidateSenders.add(new PrimitiveVS<>(guardedValue.getValue()).restrict(guardedValue.getGuard()));
        }
        candidateSenders = getSchedule().filterSleep(candidateSenders);
        return candidateSenders;
    }

    private Message peekBuffer(Machine m, Guard g) {
        return m.sendBuffer.peek(g);
    }

    private Message rmBuffer(Machine m, Guard g) {
        return m.sendBuffer.remove(g);
    }

    private List<GuardedValue<Machine>> filter(List<GuardedValue<Machine>> choices, MessageOrder order) {
        Map<Machine, Guard> filteredMap = new HashMap<>();
        Map<Machine, Message> firstElement = new HashMap<>();
        for (GuardedValue<Machine> choice : choices) {
            Machine currentMachine = choice.getValue();
            Message current = peekBuffer(currentMachine, choice.getGuard());
            Guard add = choice.getGuard();
            List<Message> remove = new ArrayList<>();
            Map<Machine, Guard> newFilteredMap = new HashMap<>();
            for (Machine oldMachine : filteredMap.keySet()) {
                Message old = firstElement.get(oldMachine);
                add = add.and(order.lessThan(old, current).not());
            }
            for (Machine oldMachine : filteredMap.keySet()) {
                Message old = firstElement.get(oldMachine);
                Guard remCond = order.lessThan(current, old).and(add);
                newFilteredMap.put(oldMachine, filteredMap.get(oldMachine).and(remCond.not()));
                firstElement.put(oldMachine, firstElement.get(oldMachine).restrict(remCond.not()));
            }
            newFilteredMap.put(currentMachine, add);
            firstElement.put(currentMachine, current.restrict(add));
            filteredMap = newFilteredMap;
        }
        List<GuardedValue<Machine>> filtered = new ArrayList<>();
        for (Map.Entry<Machine,Guard> entry : filteredMap.entrySet()) {
            if (!entry.getValue().isFalse())
                filtered.add(new GuardedValue(entry.getKey(), entry.getValue()));
        }
        return filtered;
    }

    private List<GuardedValue<Machine>> filterDistinct(List<GuardedValue<Machine>> choices) {
        assert(distinctStateGuard != null);
        List<GuardedValue<Machine>> filtered = new ArrayList<>();
        for (GuardedValue<Machine> choice: choices) {
            Machine m = choice.getValue();
            Guard g = choice.getGuard();
            Guard gNew = g.and(distinctStateGuard);
            if (!gNew.isFalse())
                filtered.add(new GuardedValue(m, gNew));
        }
        return filtered;
    }

/*
    private PrimitiveVS<Boolean> shouldInterleave(List<Message> candidateMessages, Message m) {
        if (alwaysInterleaveNonAsync) return new PrimitiveVS<>(true);
        PrimitiveVS<Event> event = m.getEvent();
        PrimitiveVS<Set<Event>> doNotInterleave = new PrimitiveVS<>();
        List<PrimitiveVS<Set<Event>>> toMerge = new ArrayList<>();
        for (GuardedValue<Event> e : event.getGuardedValues()) {
            if (interleaveMap.containsKey(e.getValue())) {
                toMerge.add(new PrimitiveVS<>(interleaveMap.get(e.getValue())));
            }
        }
        doNotInterleave = doNotInterleave.merge(toMerge);

        Guard equal = Guard.constFalse();
        for (Message other : candidateMessages) {
            for (GuardedValue<Event> e : other.getEvent().getGuardedValues()) {
                for (GuardedValue<Set<Event>> notToInterleave : doNotInterleave.getGuardedValues()) {
                    for (Event replacement : notToInterleave.getValue()) {
                        if (e.getValue().equals(replacement)) {
                            equal = equal.or(e.getGuard().and(notToInterleave.getGuard()));
                        }
                    }
                }
            }
        }
        return new PrimitiveVS<>(true).restrict(equal.not());
    }
*/

    public PrimitiveVS<Machine> getNextSender(List<PrimitiveVS> candidateSenders) {
        PrimitiveVS<Machine> choices = (PrimitiveVS<Machine>) NondetUtil.getNondetChoice(candidateSenders);
        schedule.addRepeatSender(choices, choiceDepth);
        choiceDepth++;
        return choices;
    }

    public PrimitiveVS<Machine> getNextSender() {
        return getNextSender(getNextSenderChoices());
    }

    private void storeSrcState() {
        if (!srcState.isEmpty())
            return;
        srcState.clear();
        for (Machine machine : machines) {
            List<ValueSummary> machineLocalState = machine.getLocalState();
            srcState.put(machine, machineLocalState);
        }
    }

    private String globalStateString() {
        StringBuilder out = new StringBuilder();
        out.append("Src State:").append(System.lineSeparator());
        for (Machine machine : machines) {
            List<ValueSummary> machineLocalState = machine.getLocalState();
            out.append(String.format("  Machine: %s", machine)).append(System.lineSeparator());
            for (ValueSummary vs: machineLocalState) {
                out.append(String.format("    %s", vs.toStringDetailed())).append(System.lineSeparator());
            }
        }
        return out.toString();
    }


    /**
     * Enumerate concrete states
     * @param symState symbolic state
     * @return number of concrete states represented by the symbolic state
     */
    public int[] enumerateConcreteStates(Function<ValueSummary, GuardedValue<?>> concretizer, Map<Machine, List<ValueSummary>> symState) {
        Guard iterPc = Guard.constTrue();
        Guard alreadySeen = Guard.constFalse();
        int numConcreteStates = 0;
        int numDistinctConcreteStates = 0;

        if (configuration.isUseStateCaching()) {
            distinctStateGuard = Guard.constFalse();
            if (syncStep || createStep || (choiceDepth <= backtrackDepth)) {
                distinctStateGuard = Guard.constTrue();
                return new int[]{0, 0};
            }
        }

        if (configuration.getVerbosity() > 5) {
            PSymLogger.info(globalStateString());
        }

        while (!iterPc.isFalse()) {
            Guard concreteStateGuard = Guard.constTrue();
            List<List<Object>> globalStateConcrete = new ArrayList<>();
            int i = 0;
            for(Machine m: machines) {
                if (!symState.containsKey(m))
                    continue;
                List<ValueSummary> machineStateSymbolic = symState.get(m);
                List<Object> machineStateConcrete = new ArrayList<>();
                for (int j = 0; j < machineStateSymbolic.size(); j++) {
                    GuardedValue<?> guardedValue = concretizer.apply(machineStateSymbolic.get(j).restrict(iterPc));
                    if (guardedValue == null) {
                        if (i == 0 && j == 0) {
                            return new int[]{numConcreteStates, numDistinctConcreteStates};
                        }
                        machineStateConcrete.add(null);
                    } else {
                        iterPc = iterPc.and(guardedValue.getGuard());
                        machineStateConcrete.add(guardedValue.getValue());
                        concreteStateGuard = concreteStateGuard.and(guardedValue.getGuard());
                    }
                }
                if (!machineStateConcrete.isEmpty()) {
                    globalStateConcrete.add(machineStateConcrete);
                }
                i++;
            }

            if (!globalStateConcrete.isEmpty()) {
                String concreteState = globalStateConcrete.toString();
                numConcreteStates += 1;
                if (distinctStates.containsKey(concreteState)) {
                    distinctStates.put(concreteState, distinctStates.get(concreteState) + 1);
                    totalStateCount += 1;
                    if (configuration.getVerbosity() > 5) {
                        PSymLogger.info("Repeated State: " + concreteState);
                    }
                } else {
                    numDistinctConcreteStates += 1;
                    distinctStates.put(concreteState, 1);
                    totalStateCount += 1;
                    distinctStatesList.add(concreteState);
                    if (configuration.isUseStateCaching()) {
                        distinctStateGuard = distinctStateGuard.or(concreteStateGuard);
                    }
                    if (configuration.getVerbosity() > 4) {
                        PSymLogger.info("New State:      " + concreteState);
                    }
                }
            }
            alreadySeen = alreadySeen.or(iterPc);
            iterPc = alreadySeen.not();
        }
        return new int[]{numConcreteStates, numDistinctConcreteStates};
    }


    public static void cleanup() {
        SolverEngine.cleanupEngine();
    }

    public void step() throws TimeoutException {
        srcState.clear();
        GlobalData.getChoiceLearningStats().setProgramStateHash(this);

        int numStates = 0;
        int numStatesDistinct = 0;
        int numMessages = 0;
        int numMessagesMerged = 0;
        int numMessagesExplored = 0;

        if (configuration.getCollectStats() > 3 || configuration.isUseStateCaching()) {
            storeSrcState();
            int[] numConcrete = enumerateConcreteStates(Concretizer::concretize, srcState);
            numStates = numConcrete[0];
            numStatesDistinct = numConcrete[1];
        }

        if (configuration.isUseBacktrack()) {
            storeSrcState();
            schedule.setSchedulerDepth(getDepth());
            schedule.setSchedulerChoiceDepth(getChoiceDepth());
            schedule.setSchedulerState(srcState, machineCounters);
        }

        // reward previous choices
        List<CoverageStats.CoverageChoiceDepthStats> coverageChoiceDepthStats = GlobalData.getCoverage().getPerChoiceDepthStats();
        for(int i = preChoiceDepth; i< schedule.size() && i<choiceDepth; i++) {
            GlobalData.getChoiceLearningStats().rewardStep(coverageChoiceDepthStats.get(i).getStateActions(), numStatesDistinct);
        }
        preChoiceDepth = choiceDepth;


        PrimitiveVS<Machine> choices = getNextSender();

        if (choices.isEmptyVS()) {
            done = true;
            SearchLogger.finishedExecution(depth);
        }

        if (done) {
            return;
        }

        TimeMonitor.getInstance().checkTimeout();

        Message effect = null;
        List<Message> effects = new ArrayList<>();

        for (GuardedValue<Machine> sender : choices.getGuardedValues()) {
            Machine machine = sender.getValue();
            Guard guard = sender.getGuard();
            Message removed = rmBuffer(machine, guard);
            if (configuration.getVerbosity() > 5) {
                System.out.println("  Machine " + machine.toString());
                System.out.println("    state   " + machine.getCurrentState().toStringDetailed());
                System.out.println("    message " + removed.toString());
                System.out.println("    target " + removed.getTarget().toString());
            }
            if (configuration.getCollectStats() > 3) {
                numMessages += Concretizer.getNumConcreteValues(Guard.constTrue(), removed);
            }
            if (effect == null) {
                effect = removed;
            } else {
                effects.add(removed);
            }
        }

        if (configuration.getCollectStats() > 3) {
            numMessagesMerged = Concretizer.getNumConcreteValues(Guard.constTrue(), effect);
            numMessagesExplored = Concretizer.getNumConcreteValues(Guard.constTrue(), effect.getTarget(), effect.getEvent());
        }

        assert effect != null;
        effect = effect.merge(effects);

        TraceLogger.schedule(depth, effect, choices);

        performEffect(effect);

        // simplify engine
//        SolverEngine.simplifyEngineAuto();

        // switch engine
//        SolverEngine.switchEngineAuto();

        double memoryUsed = MemoryMonitor.getMemSpent();
        if (memoryUsed > (0.8* SolverStats.memLimit)) {
            Scheduler.cleanup();
        }

        // record depth statistics
        SearchStats.DepthStats depthStats = new SearchStats.DepthStats(depth, numStates, numMessages, numMessagesMerged, numMessagesExplored);
        searchStats.addDepthStatistics(depth, depthStats);

        // log statistics
        if (configuration.getVerbosity() > 3) {
            double timeUsed = TimeMonitor.getInstance().getRuntime();
            if (configuration.getVerbosity() > 4) {
                SearchLogger.log("--------------------");
                SearchLogger.log("Resource Stats::");
                SearchLogger.log("time-seconds", String.format("%.1f", timeUsed));
                SearchLogger.log("memory-max-MB", String.format("%.1f", MemoryMonitor.getMaxMemSpent()));
                SearchLogger.log("memory-current-MB", String.format("%.1f", memoryUsed));
                SearchLogger.log("--------------------");
                SearchLogger.log("Solver Stats::");
                SearchLogger.log("time-create-guards-%", String.format("%.1f", SolverStats.getDoublePercent(SolverStats.timeTotalCreateGuards/1000.0, timeUsed)));
                SearchLogger.log("time-solve-guards-%", String.format("%.1f", SolverStats.getDoublePercent(SolverStats.timeTotalSolveGuards/1000.0, timeUsed)));
                SearchLogger.log("time-create-guards-max-seconds", String.format("%.3f", SolverStats.timeMaxCreateGuards/1000.0));
                SearchLogger.log("time-solve-guards-max-seconds", String.format("%.3f", SolverStats.timeMaxSolveGuards/1000.0));
                SolverStats.logSolverStats();
                SearchLogger.log("--------------------");
                SearchLogger.log("Detailed Solver Stats::");
                SearchLogger.log(SolverEngine.getStats());
                SearchLogger.log("--------------------");
            }
        }

        // log depth statistics
        if (configuration.getVerbosity() > 4) {
          SearchLogger.logDepthStats(depthStats);
          System.out.println("--------------------");
          System.out.println("Collect Stats::");
          System.out.println("Total States:: " + numStates + ", Running Total States::" + getTotalStates());
          System.out.println("Total Distinct States:: " + numStatesDistinct + ", Running Total Distinct States::" + getTotalDistinctStates());
          System.out.println("Total transitions:: " + depthStats.getNumOfTransitions() + ", Total Merged Transitions (merged same target):: " + depthStats.getNumOfMergedTransitions() + ", Total Transitions Explored:: " + depthStats.getNumOfTransitionsExplored());
          System.out.println("Running Total Transitions:: " + searchStats.getSearchTotal().getDepthStats().getNumOfTransitions() + ", Running Total Merged Transitions:: " + searchStats.getSearchTotal().getDepthStats().getNumOfMergedTransitions() + ", Running Total Transitions Explored:: " + searchStats.getSearchTotal().getDepthStats().getNumOfTransitionsExplored());
          System.out.println("--------------------");
        }

        depth++;
    }

    public PrimitiveVS<Machine> allocateMachine(Guard pc, Class<? extends Machine> machineType,
                                           Function<Integer, ? extends Machine> constructor) {
        if (!machineCounters.containsKey(machineType)) {
            machineCounters.put(machineType, new PrimitiveVS<>(0));
        }
        PrimitiveVS<Integer> guardedCount = machineCounters.get(machineType).restrict(pc);

        Machine newMachine;
        newMachine = constructor.apply(IntegerVS.maxValue(guardedCount));

        if (!machines.contains(newMachine)) {
            machines.add(newMachine);
            vcManager.addMachine(pc, newMachine);
        }

        TraceLogger.onCreateMachine(pc, newMachine);
        newMachine.setScheduler(this);
        if (useBagSemantics()) {
            newMachine.setSemantics(EventBufferSemantics.bag);
        }
        else if (useReceiverSemantics()) {
            newMachine.setSemantics(EventBufferSemantics.receiver);
        }
        schedule.makeMachine(newMachine, pc);

        guardedCount = IntegerVS.add(guardedCount, 1);
        PrimitiveVS<Integer> mergedCount = machineCounters.get(machineType).updateUnderGuard(pc, guardedCount);
        machineCounters.put(machineType, mergedCount);
        return new PrimitiveVS<>(newMachine).restrict(pc);
    }

    void runMonitors(Message event) {
        Map<Monitor, Guard> monitorConstraints = new HashMap<>();
        for (Monitor m : monitors) {
            monitorConstraints.put(m, Guard.constFalse());
        }
        for (GuardedValue<Event> e : event.getEvent().getGuardedValues()) {
            List<Monitor> listenersForEvent = listeners.get(e.getValue());
            if (listenersForEvent != null) {
                for (Monitor listener : listenersForEvent) {
                    monitorConstraints.computeIfPresent(listener, (k, v) -> v.or(e.getGuard()));
                }
            }
        }
        for (Monitor m: monitors) {
            Guard constraint = monitorConstraints.get(m);
            if (!constraint.isFalse()) {
                m.processEventToCompletion(constraint, event.restrict(constraint));
            }
        }
    }

    public void performEffect(Message event) {
        runMonitors(event);
        for (GuardedValue<Machine> target : event.getTarget().getGuardedValues()) {
            target.getValue().processEventToCompletion(target.getGuard(), event.restrict(target.getGuard()));
        }
    }

    public void announce(PrimitiveVS<Event> names, UnionVS payload) {
        Message event = new Message(names, new PrimitiveVS<>(), payload);
        if (event.hasNullEvent()) {
            throw new RuntimeException(String.format("Machine cannot announce a null event: %s", event));
        }
        runMonitors(event);
    }

    public int getMaxInternalSteps() {
        return configuration.getMaxInternalSteps();
    }
}
