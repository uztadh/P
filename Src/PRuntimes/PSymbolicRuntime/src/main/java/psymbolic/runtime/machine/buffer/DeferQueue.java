package psymbolic.runtime.machine.buffer;

import psymbolic.runtime.Message;
import psymbolic.valuesummary.Guard;
import psymbolic.valuesummary.ListVS;
import psymbolic.valuesummary.PrimitiveVS;
import psymbolic.valuesummary.ValueSummary;

import java.io.Serializable;
import java.util.function.Function;

/**
 * Implements the Defer Queue used to keep track of the deferred events
 */
public class DeferQueue extends SymbolicQueue<Message> implements Serializable {

    public DeferQueue() {
        super();
    }

    /**
     * Defer a particular event by adding it to the defer queue of the machine
     * @param pc Guard under which to defer the event
     * @param event Event to be deferred
     */
    public void defer(Guard pc, Message event) { enqueue(event.restrict(pc)); }

    public PrimitiveVS<Boolean> satisfiesPredUnderGuard(Function<Message, PrimitiveVS<Boolean>> pred) {
        Guard cond = isEnabledUnderGuard();
        assert(!cond.isFalse());
        Message top = peek(cond);
        return pred.apply(top).restrict(top.getUniverse());
    }

    public ValueSummary getEvents() {
        return this.elements;
    }

    public void setEvents(ValueSummary events) {
        this.elements = (ListVS<Message>) events;
        resetPeek();
    }
}
