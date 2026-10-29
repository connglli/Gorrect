package io.connglli.gorrect.engine.gir;

import java.util.List;

/**
 * Immutable statements and their execution contracts for validated GIR 1 programs.
 *
 * <p>Fresh binding names differ from all declarations in active scopes, including released
 * bindings. Reference operands are live handles or {@code null}; scalar operands are live scalar
 * bindings or signed 64-bit literals. Field and array access requires a live, non-null owner handle
 * and an in-range index in the specified reference or data namespace, according to the owner's
 * shape.
 *
 * <p>Each statement changes only the state described by its contract. Invalid preconditions raise
 * {@link GirException}; exhausted checking budgets raise {@link
 * io.connglli.gorrect.engine.oracle.OracleLimitException}. {@link GirValidator} checks static
 * requirements; null dereferences and target-shape assertions are checked during execution.
 */
public sealed interface Instruction {
  /** Internal preorder index, assigned by the frontend. */
  int id();

  /**
   * A primitive consumes one step on success. Rejection for an invalid precondition or exhausted
   * budget leaves the heap, bindings, identities, and counters unchanged.
   */
  sealed interface Operation extends Instruction {}

  /**
   * Requires a fresh name and a declared shape. Creates a distinct object with that shape, all
   * references null, and all data zero, then binds the name to a strong reference to it. The
   * identity uses this allocation site and its next occurrence; named roots remain unchanged.
   */
  record Allocate(int id, String name, String shape) implements Operation {}

  /**
   * Requires a declared root, declared shape, and fresh destination name. Reads the root once and
   * binds a new strong handle to the same target, including null. A non-null target must have
   * exactly the declared shape. The root is unchanged, and later root writes do not change the
   * handle.
   */
  record ReadRoot(int id, String name, String shape, String root) implements Operation {}

  /**
   * Requires an existing named root and a reference operand. Replaces that root's target with the
   * operand's current target; the target may have any shape. Null clears the target while
   * preserving the named root slot. The source binding and other references remain unchanged.
   */
  record WriteRoot(int id, String root, Operand value) implements Operation {}

  /**
   * Requires a valid reference-slot access, declared destination shape, and fresh name. Reads the
   * slot once and binds a new strong handle to its current target, including null. A non-null
   * target must have exactly the destination shape. Later writes to the source slot do not change
   * the handle.
   */
  record ReadReference(int id, String name, String shape, String owner, int index)
      implements Operation {}

  /**
   * Requires a valid reference-slot access and a reference operand. Replaces only that slot's
   * target with the operand's current target, which may have any shape or be null. The owner
   * retains its identity and shape, and the slot retains its index and strong kind. The source
   * binding keeps its value and lifetime.
   */
  record WriteReference(int id, String owner, int index, Operand value) implements Operation {}

  /**
   * Requires a valid data-slot access and a fresh destination name. Reads the signed 64-bit value
   * once into a scalar binding. Later slot writes do not change the copied value. The scalar
   * binding does not retain the owner object.
   */
  record ReadData(int id, String name, String owner, int index) implements Operation {}

  /**
   * Requires a valid data-slot access and a scalar operand. Replaces only that slot's value with
   * the operand's current signed 64-bit value. The source binding, owner shape, and other slots are
   * unchanged.
   */
  record WriteData(int id, String owner, int index, Operand value) implements Operation {}

  /**
   * Requires a live reference or scalar binding declared in the current scope. Makes it unavailable
   * for subsequent reads and removes its handle obligation, if any. Its name remains reserved until
   * scope exit. Roots and heap slots referencing the same object retain their values.
   */
  record Release(int id, String name) implements Operation {}

  /**
   * Records immutable obligations after preceding statements: named roots, live reference handles,
   * and their strong closure with exact contents. Each execution appends a visit numbered from one
   * for this instruction. Heap and bindings are unchanged; step and snapshot budgets are charged.
   * It does not request garbage collection.
   */
  record Checkpoint(int id) implements Operation {}

  /**
   * Consumes one control step, then executes the body in a child scope with outer bindings visible.
   * Exiting the block releases its own bindings, including on failure. Root and heap writes
   * persist; the block is not a transaction that rolls back preceding successful operations.
   */
  record Scope(int id, List<Instruction> body) implements Instruction {
    public Scope {
      body = List.copyOf(body);
    }
  }

  /**
   * Requires a nonnegative literal count. Executes the body that many times, unless execution
   * fails, using a fresh child scope each iteration. Root and heap changes carry between
   * iterations; local bindings expire at each iteration's end. Zero skips execution but still
   * requires a statically valid body. Charges one control step plus one per iteration, even for an
   * empty body.
   */
  record Repeat(int id, long count, List<Instruction> body) implements Instruction {
    public Repeat {
      body = List.copyOf(body);
    }
  }
}
