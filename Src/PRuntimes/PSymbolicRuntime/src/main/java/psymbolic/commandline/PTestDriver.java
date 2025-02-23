package psymbolic.commandline;

import psymbolic.runtime.Event;
import psymbolic.runtime.machine.Machine;
import psymbolic.runtime.machine.Monitor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class PTestDriver implements Serializable {
    public Machine mainMachine;
    public List<Monitor> monitorList;
    public Map<Event, List<Monitor>> observerMap;

    public Machine getStart() {
        return mainMachine;
    }

    public List<Monitor> getMonitors() {
        return monitorList;
    }

    public Map<Event, List<Monitor>> getListeners() {
        return observerMap;
    }

    public abstract void configure();

    public PTestDriver() {
        this.mainMachine = null;
        this.monitorList = new ArrayList<>();
        this.observerMap = new HashMap<>();
        this.configure();
    }
}