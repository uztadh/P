package psymbolic.valuesummary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Class for set value summaries */
public class SetVS<T extends ValueSummary<T>> implements ValueSummary<SetVS<T>> {

    /** The underlying set */
    private final ListVS<T> elements;

    /** Get all the different possible guarded values */
    public ListVS<T> getElements() {
        return elements;
    }

    public SetVS(ListVS<T> elements) {
        this.elements = elements;
    }

    public SetVS(Guard universe) {
        this.elements = new ListVS<>(universe);
    }

    /** Copy-constructor for SetVS
     * @param old The SetVS to copy
     */
    public SetVS(SetVS<T> old) {
        this.elements = new ListVS<>(old.elements);
    }

    /**
     * Copy the value summary
     *
     * @return A new cloned copy of the value summary
     */
    public SetVS<T> getCopy() {
        return new SetVS(this);
    }

    public PrimitiveVS<Integer> size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    @Override
    public boolean isEmptyVS() {
        return elements.isEmptyVS();
    }

    @Override
    public SetVS<T> restrict(Guard guard) {
        if(guard.equals(getUniverse()))
            return new SetVS<T>(new ListVS<>(elements));

        return new SetVS<>(new ListVS<>(elements.restrict(guard)));
    }

    @Override
    public SetVS<T> merge(Iterable<SetVS<T>> summaries) {
        List<ListVS<T>> listsToMerge = new ArrayList<>();

        for (SetVS<T> summary : summaries) {
            listsToMerge.add(summary.elements);
        }

        return new SetVS<>(elements.merge(listsToMerge));
    }

    @Override
    public SetVS<T> merge(SetVS<T> summary) {
        return merge(Collections.singletonList(summary));
    }

    @Override
    public SetVS<T> updateUnderGuard(Guard guard, SetVS<T> update) {
        return this.restrict(guard.not()).merge(Collections.singletonList(update.restrict(guard)));
    }

    @Override
    public PrimitiveVS<Boolean> symbolicEquals(SetVS<T> cmp, Guard pc) {
        if (cmp == null) {
            return BooleanVS.trueUnderGuard(Guard.constFalse());
        }

        // check if size is empty
        if (elements.size().isEmptyVS()) {
            if (cmp.isEmptyVS()) {
                return BooleanVS.trueUnderGuard(pc);
            } else {
                return BooleanVS.trueUnderGuard(Guard.constFalse());
            }
        }

        // check if each item in the set is symbolically equal
        Guard equalCond = Guard.constTrue();
        for (T lhs: this.elements.getItems()) {
            equalCond = equalCond.and(cmp.contains(lhs).getGuardFor(true));
        }
        for (T rhs: cmp.elements.getItems()) {
            equalCond = equalCond.and(this.contains(rhs).getGuardFor(true));
        }

        return BooleanVS.trueUnderGuard(pc.and(equalCond)).restrict(getUniverse().and(cmp.getUniverse()));
    }

    @Override
    public Guard getUniverse() {
        return elements.getUniverse();
    }

    /** Check whether the SetVS contains an element
     *
     * @param itemSummary The element to check for. Should be possible under a subset of the SetVS's conditions.
     * @return Whether or not the SetVS contains an element
     */
    public PrimitiveVS<Boolean> contains(T itemSummary) {
        return elements.contains(itemSummary);
    }

    /** Get the universe under which the data structure is nonempty
     *
     * @return The universe under which the data structure is nonempty */
    public Guard getNonEmptyUniverse() { return elements.getNonEmptyUniverse(); }

    /**
     * Add an item to the SetVS.
     *
     * @param itemSummary The element to add.
     * @return The SetVS with the element added
     */
    public SetVS<T> add(T itemSummary) {
        Guard absent = contains(itemSummary.restrict(getUniverse())).getGuardFor(false);
        ListVS<T> newElements = elements.updateUnderGuard(absent, elements.add(itemSummary));
        return new SetVS<>(newElements);
    }

    /**
     * Remove an item from the SetVS if present (otherwise no op)
     * @param itemSummary The element to remove. Should be possible under a subset of the SetVS's conditions.
     * @return The SetVS with the element removed.
     */
    public SetVS<T> remove(T itemSummary) {
        PrimitiveVS<Integer> idx = elements.indexOf(itemSummary);
        idx = idx.restrict(elements.inRange(idx).getGuardFor(true));
        if (idx.isEmptyVS()) return this;
        ListVS<T> newElements = elements.removeAt(idx);
        return new SetVS<>(newElements);
    }

    /** Get an item from the SetVS
     * @param indexSummary The index to take from the SetVS. Should be possible under a subset of the SetVS's conditions.
     */
    public T get(PrimitiveVS<Integer> indexSummary) {
        return elements.get(indexSummary);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        out.append("Set[");
        List<GuardedValue<Integer>> guardedSizeList = elements.size().getGuardedValues();
        for (int j = 0; j < guardedSizeList.size(); j++) {
            GuardedValue<Integer> guardedSize = guardedSizeList.get(j);
            out.append("  #" + guardedSize.getValue() + ": [");
            for (int i = 0; i < guardedSize.getValue(); i++) {
                out.append(this.elements.getItems().get(i).restrict(guardedSize.getGuard()));
                if (i < guardedSize.getValue() - 1) {
                    out.append(", ");
                }
            }
            if (j < guardedSizeList.size() - 1) {
                out.append(",");
            }
            out.append("]");
        }
        out.append("]");
        return out.toString();
    }

    public String toStringDetailed() {
        StringBuilder out = new StringBuilder();
        out.append("Set[");
        out.append(elements.toStringDetailed());
        out.append("]");
        return out.toString();
    }
}
