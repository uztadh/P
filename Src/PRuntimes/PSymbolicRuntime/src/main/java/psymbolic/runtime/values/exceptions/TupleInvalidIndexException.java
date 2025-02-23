package psymbolic.runtime.values.exceptions;

import psymbolic.runtime.values.PTuple;

public class TupleInvalidIndexException extends PRuntimeException {
    public TupleInvalidIndexException(String message) {
        super(message);
    }

    public TupleInvalidIndexException(PTuple tuple, int fieldIndex) {
        super(String.format("Invalid field access: Trying to access field at index [%d] in the tuple [%s]", fieldIndex, tuple.toString()));
    }
}
