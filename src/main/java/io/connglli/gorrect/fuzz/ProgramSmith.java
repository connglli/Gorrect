/*
 * MIT License
 *
 * Copyright (c) 2025-2025 Cong Li (cong.li@inf.ethz.ch)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.connglli.gorrect.fuzz;

import io.connglli.gorrect.gir.GProgram;
import io.connglli.gorrect.gir.GProgramVisitor;
import io.connglli.gorrect.gir.inst.*;
import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.gir.model.GID;
import io.connglli.gorrect.gir.model.GObject;
import io.connglli.gorrect.gir.model.GReference;
import io.connglli.gorrect.gir.utility.UnmodifiableGraph;
import io.connglli.gorrect.oracle.LangVM;
import io.connglli.gorrect.oracle.OracleInterpreter;
import io.connglli.gorrect.utility.*;
import java.util.*;
import java.util.function.Predicate;
import lombok.*;

/** ProgramSmith generates a random GIR program. */
@RequiredArgsConstructor
public class ProgramSmith {
  private final Logger log = Logger.getLogger(ProgramSmith.class);

  @Builder(toBuilder = true, buildMethodName = "buildUnchecked")
  @Getter
  public static class Config {
    /// The minimum number of instructions (including).
    @Builder.Default private final int minInstructions = 1;

    /// The maximum number of instructions (including).
    /// This is a soft limit, as we may stop when the heap is full.
    @Builder.Default private final int maxInstructions = 100;

    /// The minimum size (inclusive) of an object's padding in bytes.
    /// This is a soft limit as we may further decrease the object size to ensure allocation.
    @Builder.Default private final long minObjSizeBytes = 1; // 1B

    /// The maximum size (inclusive) of an object's padding in bytes.
    @Builder.Default private final long maxObjSizeBytes = 128_000; // 128KB

    /// The probability of turning an object into a humongous object.
    @Builder.Default private final double humongousObjProb = 0;

    /// The size of treating an object as a humongous object in bytes. Humongous objects are
    /// usually treated specially in a VM. Each object occupy exclusively one or more heap
    /// region for region-based garbage collectors. This should be smaller than or equal to
    /// maxObjSizeBytes and larger than or equal to minObjSizeBytes.
    @Builder.Default private final Long[] humongousObjSizeBytes = new Long[] {128_000L}; // 128KB

    /// The probability of turning a reference into a volatile reference.
    @Builder.Default private final double volatileRefProb = 0;

    public Config check() {
      if (minInstructions < 1) {
        throw new IllegalArgumentException(
            "minInstructions must be at least 1, got " + minInstructions);
      }
      if (maxInstructions < minInstructions) {
        throw new IllegalArgumentException(
            "maxInstructions must be greater than or equal to minInstructions, got minInstructions=%d, maxInstructions=%d"
                .formatted(minInstructions, maxInstructions));
      }
      if (minObjSizeBytes < 0) {
        throw new IllegalArgumentException(
            "minObjSizeBytes must be non-negative, got " + minObjSizeBytes);
      }
      if (maxObjSizeBytes < minObjSizeBytes) {
        throw new IllegalArgumentException(
            "maxObjSizeBytes must be greater than or equal to minObjSizeBytes, got minObjSizeBytes=%d, maxObjSizeBytes=%d"
                .formatted(minObjSizeBytes, maxObjSizeBytes));
      }
      if (humongousObjProb < 0 || humongousObjProb > 1) {
        throw new IllegalArgumentException(
            "humongousProb must be between 0 and 1, got %.2f".formatted(humongousObjProb));
      }
      if (humongousObjProb > 0) {
        if (humongousObjSizeBytes.length == 0) {
          throw new IllegalArgumentException("humongousObjSizeBytes cannot be an empty array");
        }
        for (int index = 0; index < humongousObjSizeBytes.length; index++) {
          long size = humongousObjSizeBytes[index];
          if (size < 0) {
            throw new IllegalArgumentException(
                "humongousObjSizeBytes[%d] must be greater than 0, got %d".formatted(index, size));
          }
          if (size < minObjSizeBytes) {
            throw new IllegalArgumentException(
                "humongousObjSizeBytes[%d] must be greater than or equal to minObjSizeBytes, got humongousObjSizeBytes[%d]=%d, minObjSizeBytes=%d"
                    .formatted(index, index, size, minObjSizeBytes));
          }
          if (size > maxObjSizeBytes) {
            throw new IllegalArgumentException(
                "humongousObjSizeBytes[%d] must be smaller than or equal to maxObjSizeBytes, got humongousObjSizeBytes[%d]=%d, maxObjSizeBytes=%d"
                    .formatted(index, index, size, maxObjSizeBytes));
          }
        }
      }
      if (volatileRefProb < 0 || volatileRefProb > 1) {
        throw new IllegalArgumentException(
            "volatileRefProb must be between 0 and 1, got %.2f".formatted(volatileRefProb));
      }
      return this;
    }
  }

  @ToString
  @EqualsAndHashCode
  public static class Stats {
    @Getter private int numTotalGeneration = 0;
    @Getter private int numValidGeneration = 0;
    @Getter private long generationTimeMillis = 0;
    private final Map<Class<? extends GInstruction>, Integer> contributions = new HashMap<>();

    {
      for (Class<? extends GInstruction> inst : instWeights.keySet()) {
        contributions.put(inst, 0);
      }
    }

    public Map<Class<? extends GInstruction>, Integer> contributions() {
      return Collections.unmodifiableMap(contributions);
    }

    public double validRatio() {
      if (numTotalGeneration == 0) {
        return 0.0; // Avoid division by zero
      }
      return numValidGeneration / (1.0 * numTotalGeneration);
    }

    public String formatToText() {
      StringBuilder sb = new StringBuilder();
      sb.append("Total instructions: ")
          .append(Formats.toCommaString(numTotalGeneration))
          .append("\n");
      sb.append("Valid instructions: ")
          .append(Formats.toCommaString(numValidGeneration))
          .append("\n");
      sb.append("Valid ratio: ").append(Formats.toPrecision(validRatio() * 100, 2)).append("%\n");
      sb.append("Generation time (ms): ").append(generationTimeMillis).append("\n");
      sb.append("Contributions:\n");
      contributions.entrySet().stream()
          .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
          .forEach(
              entry ->
                  sb.append("  ")
                      .append(entry.getKey().getSimpleName())
                      .append(": ")
                      .append(entry.getValue())
                      .append(" (")
                      .append(
                          Formats.toPrecision(
                              numValidGeneration == 0
                                  ? 0d
                                  : ((double) entry.getValue()) / numValidGeneration * 100,
                              2))
                      .append("%)\n"));
      return sb.toString();
    }

    private void contribute(Class<? extends GInstruction> inst) {
      contributions.put(inst, contributions.get(inst) + 1);
      numValidGeneration++;
    }

    private void reset() {
      numTotalGeneration = 0;
      numValidGeneration = 0;
      generationTimeMillis = 0;
    }
  }

  private final Rand random = Rand.getInstance();

  // TODO: Make the weights configurable.
  private static final SequencedMap<Class<? extends GInstruction>, Integer> instWeights =
      new LinkedHashMap<>();

  static {
    instWeights.put(GForceAnyGCInstruction.class, 10);
    instWeights.put(GRunOutOfMemoryInstruction.class, 2);
    instWeights.put(GAddRootObjInstruction.class, 50);
    instWeights.put(GAddNonRootObjInstruction.class, 50);
    instWeights.put(GMakeRootInstruction.class, 20);
    instWeights.put(GMakeNonRootInstruction.class, 20);
    // Currently, we disable cloning objects as the requirements are overly strict.
    // It requires the object, all its referers and referents to be strongly reachable.
    instWeights.put(GCloneObjInstruction.class, 0);
    instWeights.put(GRemoveObjInstruction.class, 20);
    instWeights.put(GAddStrongRefInstruction.class, 50);
    instWeights.put(GAddSoftRefInstruction.class, 10);
    instWeights.put(GAddWeakRefInstruction.class, 10);
    instWeights.put(GAddFaninRefsInstruction.class, 5);
    instWeights.put(GAddFanoutRefsInstruction.class, 5);
    // FIXME: Currently, we disable replacing referents due to bugs in Java generation.
    //  For example:
    //    initial state: a->b, c // => a.b = b
    //    1. replace_referent(a->b, c) // => a.b = c
    //    2. add_strong_ref(a, b) // => a.b = b
    //  In this case, the reference a->c (represented in Java a.b) is lost after step 2.
    instWeights.put(GReplaceReferentInstruction.class, 0);
    // TODO: Currently, we disable swapping referents due the the above issue.
    instWeights.put(GSwapReferentsInstruction.class, 0);
    instWeights.put(GSoftenRefInstruction.class, 5);
    instWeights.put(GStrengthenRefInstruction.class, 5);
    instWeights.put(GRemoveRefInstruction.class, 30);
    instWeights.put(GRemoveAllReferentsInstruction.class, 30);
  }

  @Getter @NonNull private final Config config;
  @NonNull private IdSmith objectIdSmith;
  @NonNull private IdSmith referenceIdSmith;

  @Getter private final Stats stats = new Stats();
  @Getter private final int maxNumTrials = 320;

  /// Craft a GIR program for the given vm with the given initial graph state.
  public GProgram craft(LangVM vm, @NonNull GGraph initState) {
    stats.reset();
    vm.reset(); // Reset the VM to a clean state
    stats.generationTimeMillis = System.currentTimeMillis();
    GProgram program = new GProgram(new GSetHeapInstruction(vm.getHeapCapacity(), initState));
    OracleInterpreter interpreter = new OracleInterpreter(vm, program);
    interpreter.interpret(program.getConfiguration()); // Initialize the interpreter state
    SmithImpl smith = new SmithImpl(vm, interpreter, program);
    int numInstructions = random.nextInt(config.minInstructions, config.maxInstructions + 1);
    // TODO: Add force-GC instructions manually and intentionally
    for (int i = 0; i < numInstructions; i++) { // The last instruction is forcing GC
      if (stats.numTotalGeneration - stats.numValidGeneration > maxNumTrials) {
        log.debug("Too many invalid generations, possibly due to stopping.");
        break;
      }
      if (vm.getHeapUnusedBytes() <= 0) {
        log.debug("Cannot generate more instructions due to insufficient memory.");
        break; // Out of memory
      }
      Class<? extends GInstruction> instClazz;
      if (i == numInstructions - 1) {
        instClazz = GForceAnyGCInstruction.class;
      } else {
        instClazz = random.nextItem(instWeights);
      }
      GInstruction mock = mockInstruction(instClazz);
      log.debug("Generating instruction: " + mock.getOpCode());
      GInstruction instruction = mock.accept(smith);
      if (instruction == null) {
        stats.numTotalGeneration++;
        log.debug("  failed: instruction is null");
        i -= 1; // Retry the same instruction index
        continue;
      }
      log.debug("  instruction: " + instruction);
      try {
        // FIXME: In case the state of the interpreter is changed, we'd better unroll the state.
        interpreter.interpret(instruction);
        stats.contribute(instClazz);
        log.debug("  succeeded");
        program.addInstruction(instruction);
      } catch (Throwable e) {
        // The instruction cannot be safely executed
        log.debug("  failed: " + e.getMessage());
        i -= 1; // Retry the same instruction index
      } finally {
        stats.numTotalGeneration++;
      }
    }
    stats.generationTimeMillis = System.currentTimeMillis() - stats.generationTimeMillis;
    return program;
  }

  @RequiredArgsConstructor
  private final class SmithImpl implements GProgramVisitor<GInstruction> {
    /*
     * This implementation of crafting a random GIR program is somehow *strict* in the sense that
     * it always operates on strongly reachable objects for any generated instruction, because
     * softly and weakly reachable objects may be garbage collected at any time, which may lead to
     * NullPointerException or other unexpected behaviors in the generated program. For example:
     *
     * When adding references, always ensure that a strong/weak/soft reference is added only when a
     * strong reference exists. Otherwise, undefined behavior may occur. For example:
     *
     * 1. add_non_root_obj(u, v) // Add a non-root object v by a weak reference from u.
     * 2. add_strong_ref(z, v) // Add a strong reference to v
     *
     * It is likely that the second instruction may fail and z points to null. Such cases happen
     * when GC occurs immediately after the first instruction
     *
     * Therefore, during program generation, we should always assume that softly and weakly
     * reachable objects are indeed not reachable at all. Also, we should guarantee that the
     * referer object is not null (i.e., shouldn't be cleaned, i.e., is strongly reachable).
     *
     * TODO: Support a soft implementation and make the generated programs more interesting.
     */

    @NonNull private final LangVM vm;
    @NonNull private final OracleInterpreter interpreter;
    @NonNull private final GProgram program;

    @Override
    public GInstruction visit(GProgram program) {
      Assert.panic("Cannot reach here.");
      return null;
    }

    @Override
    public GInstruction visit(GSetHeapInstruction unused) {
      Assert.panic("Cannot reach here.");
      return null;
    }

    @Override
    public GInstruction visit(GForceAnyGCInstruction instruction) {
      return new GForceAnyGCInstruction();
    }

    @Override
    public GInstruction visit(GRunOutOfMemoryInstruction instruction) {
      return new GRunOutOfMemoryInstruction();
    }

    @Override
    public GInstruction visit(GAddRootObjInstruction unused) {
      GID rootId = objectIdSmith.next();
      long rootSize = randomPadding(rootId);
      if (rootSize < 0) {
        log.debug("Cannot add root object due to insufficient memory.");
        return null;
      }
      return new GAddRootObjInstruction(rootSize, rootId);
    }

    @Override
    public GInstruction visit(GAddNonRootObjInstruction unused) {
      // We restrict the referer to the new non-root object to be strongly reachable
      GID refererId = currState().randomStronglyReachableObject();
      if (refererId == null) {
        log.debug("Cannot add non-root object due to no strongly reachable objects.");
        return null;
      }
      long objectSize = randomPadding(refererId);
      if (objectSize < 0) {
        log.debug("Cannot add non-root object due to insufficient memory.");
        return null;
      }
      GID objectId = objectIdSmith.next();
      GID referenceId = referenceIdSmith.next(refererId, objectId);
      GReference.Type referenceType = randomReferenceType();
      boolean isVolatile = random.nextDouble() < config.volatileRefProb;
      return new GAddNonRootObjInstruction(
          objectSize, refererId, referenceType, isVolatile, objectId, referenceId);
    }

    @Override
    public GInstruction visit(GMakeRootInstruction unused) {
      // We restrict the non-root object to be strongly reachable
      GID objectId = currState().randomStronglyReachableObject();
      // TODO: Making these objects root is not interesting, as already strongly reachable.
      if (objectId == null) {
        log.debug("Cannot make a non-root object root due to no strongly reachable objects.");
        return null;
      }
      return new GMakeRootInstruction(objectId);
    }

    @Override
    public GInstruction visit(GMakeNonRootInstruction unused) {
      GID rootId = currState().randomRootObject();
      if (rootId == null) {
        log.debug("Cannot make a root object non-root due to no root objects.");
        return null;
      }
      return new GMakeNonRootInstruction(rootId);
    }

    @Override
    public GInstruction visit(GCloneObjInstruction unused) {
      // The requirements for cloning an object are even more strict. We restrict the object to be
      // strongly reachable, as well as its referers and referents all strongly reachable.
      StateRandDecor s = currState();
      GID objectId =
          s.randomObject(
              object -> {
                // Check if all referers and referents are strongly reachable
                return s.getReferers(object.getId()).stream()
                        .allMatch(referer -> s.isStronglyReachable(referer.getId()))
                    && s.getReferents(object.getId()).stream()
                        .allMatch(referent -> s.isStronglyReachable(referent.getId()));
              });
      if (objectId == null) {
        log.debug("Cannot clone an object due to no suitable candidates.");
        return null;
      }
      // We found a candidate object to clone.
      GID cloneId = objectIdSmith.next();
      return new GCloneObjInstruction(objectId, cloneId, "__" + cloneId + "__cloned");
    }

    @Override
    public GInstruction visit(GRemoveObjInstruction unused) {
      // We restrict the object and all its referers to be strongly reachable
      StateRandDecor s = currState();
      GID objectId =
          s.randomObject(
              o ->
                  s.isStronglyReachable(o.getId())
                      && s.getReferers(o.getId()).stream()
                          .allMatch(or -> s.isStronglyReachable(or.getId())));
      if (objectId == null) {
        log.debug(
            "Cannot remove an object due to no available candidates "
                + "(strongly reachable and strongly-reachable referers).");
        return null;
      }
      return new GRemoveObjInstruction(objectId);
    }

    @Override
    public GInstruction visit(GAddStrongRefInstruction unused) {
      Pair<GID, GID> data = randomNewReference(GReference.Type.STRONG);
      if (data == null) {
        return null;
      }
      GID referenceId = referenceIdSmith.next(data.a(), data.b());
      boolean isVolatile = random.nextDouble() < config.volatileRefProb;
      return new GAddStrongRefInstruction(data.a(), data.b(), isVolatile, referenceId);
    }

    @Override
    public GInstruction visit(GAddSoftRefInstruction unused) {
      Pair<GID, GID> data = randomNewReference(GReference.Type.SOFT);
      if (data == null) {
        return null;
      }
      GID referenceId = referenceIdSmith.next(data.a(), data.b());
      boolean isVolatile = random.nextDouble() < config.volatileRefProb;
      return new GAddSoftRefInstruction(data.a(), data.b(), isVolatile, referenceId);
    }

    @Override
    public GInstruction visit(GAddWeakRefInstruction unused) {
      Pair<GID, GID> data = randomNewReference(GReference.Type.WEAK);
      if (data == null) {
        return null;
      }
      GID referenceId = referenceIdSmith.next(data.a(), data.b());
      boolean isVolatile = random.nextDouble() < config.volatileRefProb;
      return new GAddWeakRefInstruction(data.a(), data.b(), isVolatile, referenceId);
    }

    @Override
    public GInstruction visit(GAddFaninRefsInstruction unused) {
      // We restrict the referent to be strongly reachable, and the new referers strongly reachable
      StateRandDecor currState = currState();
      List<GID> strongly = currState.getStronglyReachableObjects();
      if (strongly.size() < 2) {
        log.debug("Cannot add fanin references due to insufficient strongly reachable objects.");
        return null;
      }

      GID referentId = random.nextItem(strongly);
      strongly.remove(referentId);

      int maxNumReferers =
          strongly.size() == 1 ? 1 : random.nextInt(1, Math.min(3, strongly.size()));
      List<GID> refererIds = new ArrayList<>(maxNumReferers);
      for (int i = 0; i < maxNumReferers && !strongly.isEmpty(); i++) {
        GID refererId = random.nextItem(strongly);
        strongly.remove(refererId);
        if (currState.hasReference(refererId, referentId)) {
          continue;
        }
        refererIds.add(refererId);
      }
      if (refererIds.isEmpty()) {
        log.debug("Cannot add fanin references due to no suitable referers.");
        return null;
      }

      List<GReference.Type> referenceTypes = new ArrayList<>(refererIds.size());
      for (int i = 0; i < refererIds.size(); i++) {
        referenceTypes.add(randomReferenceType());
      }

      List<GID> referenceIds =
          refererIds.stream()
              .map(refererId -> referenceIdSmith.next(refererId, referentId))
              .toList();

      List<Boolean> isVolatile =
          refererIds.stream().map(r -> random.nextDouble() < config.volatileRefProb).toList();

      return new GAddFaninRefsInstruction(
          refererIds, referentId, referenceTypes, isVolatile, referenceIds);
    }

    @Override
    public GInstruction visit(GAddFanoutRefsInstruction unused) {
      // We restrict the referer to be strongly reachable, and the new referents strongly reachable
      StateRandDecor currState = currState();
      List<GID> strongly = currState.getStronglyReachableObjects();
      if (strongly.size() < 2) {
        log.debug("Cannot add fanout references due to insufficient strongly reachable objects.");
        return null;
      }

      GID refererId = random.nextItem(strongly);
      strongly.remove(refererId);

      int maxNumReferents =
          strongly.size() == 1 ? 1 : random.nextInt(1, Math.min(3, strongly.size()));
      List<GID> referentIds = new ArrayList<>(maxNumReferents);
      for (int i = 0; i < maxNumReferents && !strongly.isEmpty(); i++) {
        GID referentId = random.nextItem(strongly);
        strongly.remove(referentId);
        if (currState.hasReference(refererId, referentId)) {
          continue;
        }
        referentIds.add(referentId);
      }
      if (referentIds.isEmpty()) {
        log.debug("Cannot add fanout references due to no suitable referents.");
        return null;
      }

      List<GReference.Type> referenceTypes = new ArrayList<>(referentIds.size());
      for (int i = 0; i < referentIds.size(); i++) {
        referenceTypes.add(randomReferenceType());
      }

      List<GID> referenceIds =
          referentIds.stream()
              .map(referentId -> referenceIdSmith.next(refererId, referentId))
              .toList();

      List<Boolean> isVolatile =
          referentIds.stream().map(r -> random.nextDouble() < config.volatileRefProb).toList();

      return new GAddFanoutRefsInstruction(
          refererId, referentIds, referenceTypes, isVolatile, referenceIds);
    }

    @Override
    public GInstruction visit(GReplaceReferentInstruction unused) {
      // We restrict the referer and the new referent to be strongly reachable
      StateRandDecor s = currState();
      GID referenceId =
          s.randomReference(reference -> s.isStronglyReachable(reference.getReferer()));
      if (referenceId == null) {
        log.debug("Cannot replace a referent due to no suitable references.");
        return null;
      }
      GReference reference = s.getReference(referenceId);
      GID oldReferentId = reference.getReferent();
      GID newReferentId =
          s.randomObject(
              o ->
                  !o.getId().equals(oldReferentId)
                      && !s.hasReference(reference.getReferer(), o.getId())
                      && s.isStronglyReachable(o.getId()));
      if (newReferentId == null) {
        log.debug("Cannot replace a referent due to no strongly reachable objects.");
        return null;
      }
      return new GReplaceReferentInstruction(referenceId, newReferentId);
    }

    @Override
    public GInstruction visit(GSwapReferentsInstruction unused) {
      // We restrict both referers and referents to be strongly reachable
      StateRandDecor s = currState();
      GID referenceId1 =
          s.randomReference(
              reference ->
                  s.isStronglyReachable(reference.getReferer())
                      && s.isStronglyReachable(reference.getReferent()));
      if (referenceId1 == null) {
        log.debug("Cannot replace a referent due to no suitable references.");
        return null;
      }
      GReference reference1 = s.getReference(referenceId1);
      GID referenceId2 =
          s.randomReference(
              reference ->
                  !reference.getId().equals(referenceId1)
                      && !reference1.getReferer().equals(reference.getReferer())
                      && !reference1.getReferent().equals(reference.getReferent())
                      && s.isStronglyReachable(reference.getReferer())
                      && s.isStronglyReachable(reference.getReferent()));
      if (referenceId2 == null) {
        log.debug("Cannot replace a referent due to no suitable references.");
        return null;
      }
      return new GSwapReferentsInstruction(referenceId1, referenceId2);
    }

    @Override
    public GInstruction visit(GSoftenRefInstruction unused) {
      // We restrict the referer and referent to be strongly reachable
      StateRandDecor s = currState();
      GID referenceId =
          s.randomReference(
              reference ->
                  reference.getType() != GReference.Type.WEAK
                      && s.isStronglyReachable(reference.getReferer())
                      && s.isStronglyReachable(reference.getReferent()));
      if (referenceId == null) {
        log.debug("Cannot soften a reference due to no suitable candidates.");
        return null;
      }
      return new GSoftenRefInstruction(referenceId);
    }

    @Override
    public GInstruction visit(GStrengthenRefInstruction unused) {
      // We restrict the referer and referent to be strongly reachable
      StateRandDecor s = currState();
      GID referenceId =
          s.randomReference(
              reference -> {
                return reference.getType() != GReference.Type.STRONG
                    && s.isStronglyReachable(reference.getReferer())
                    && s.isStronglyReachable(reference.getReferent());
              });
      if (referenceId == null) {
        log.debug("Cannot strengthen a reference due to no suitable candidates.");
        return null;
      }
      return new GStrengthenRefInstruction(referenceId);
    }

    @Override
    public GInstruction visit(GRemoveRefInstruction unused) {
      // We restrict the referer to be strongly reachable
      StateRandDecor s = currState();
      GID referenceId =
          s.randomReference(reference -> s.isStronglyReachable(reference.getReferer()));
      if (referenceId == null) {
        log.debug("Cannot remove a reference due to no suitable candidates.");
        return null;
      }
      return new GRemoveRefInstruction(referenceId);
    }

    @Override
    public GInstruction visit(GRemoveAllReferentsInstruction unused) {
      // We restrict the referer to be strongly reachable
      StateRandDecor s = currState();
      GID refererId = s.randomObject(object -> s.isStronglyReachable(object.getId()));
      if (refererId == null) {
        log.debug("Cannot remove all referents due to no suitable candidates.");
        return null;
      }
      return new GRemoveAllReferentsInstruction(refererId);
    }

    private long randomPadding(@NonNull GID id) {
      boolean humongous = random.nextDouble() < config.humongousObjProb;
      long paddingSize =
          humongous
              ? GraphSmith.randomHumongousObjSize(
                  random, random.nextItem(config.humongousObjSizeBytes), config.maxObjSizeBytes + 1)
              : random.nextLong(config.minObjSizeBytes, config.maxObjSizeBytes + 1);
      long objectSize = vm.estimateNewObjectHeapSize(id, paddingSize);
      while (paddingSize > 0 && objectSize > vm.getHeapUnusedBytes()) {
        paddingSize /= 2;
        objectSize = vm.estimateNewObjectHeapSize(id, paddingSize);
      }
      if (paddingSize < config.minObjSizeBytes) {
        log.warning(
            "Adjusting object padding (%s bytes) to be smaller than the minimum size (%s bytes)",
            Formats.toCommaString(paddingSize), Formats.toCommaString(config.minObjSizeBytes));
      }
      if (paddingSize < 0 || objectSize > vm.getHeapUnusedBytes()) {
        return -1; // We're out of memory
      }
      return paddingSize;
    }

    private Pair<GID, GID> randomNewReference(GReference.Type type) {
      // We restrict the referer and the referent to be strongly reachable
      StateRandDecor s = currState();
      List<GID> strongly = s.getStronglyReachableObjects();
      if (strongly.size() < 2) {
        log.debug(
            "Cannot add a "
                + type
                + " reference because only one strongly reachable object exists.");
        return null;
      }
      for (int i = 0; i < 10; i++) {
        GID refererId = random.nextItem(strongly);
        if (refererId == null) {
          log.debug("Cannot add a " + type + " reference due to no strongly reachable objects.");
          return null;
        }
        for (int j = 0; j < 10; j++) {
          GID referentId = random.nextItem(strongly);
          if (referentId == null) {
            log.debug("Cannot add a " + type + " reference due to no suitable referent objects.");
            return null;
          }
          if (referentId.equals(refererId)) {
            continue; // We do not allow self-references
          }
          if (s.hasReference(refererId, referentId)) {
            continue; // We do not allow duplicate references
          }

          return new Pair<>(refererId, referentId);
        }
      }
      log.debug("Cannot add a " + type + " reference after 10 attempts.");
      return null;
    }

    private GReference.Type randomReferenceType() {
      GReference.Type[] typeList =
          new GReference.Type[] {
            GReference.Type.STRONG, GReference.Type.SOFT, GReference.Type.WEAK
          };
      int[] weightList =
          new int[] {
            instWeights.get(GAddStrongRefInstruction.class),
            instWeights.get(GAddStrongRefInstruction.class),
            instWeights.get(GAddStrongRefInstruction.class)
          };
      return random.nextItem(typeList, weightList);
    }

    private StateRandDecor currState() {
      return StateRandDecor.of(interpreter);
    }
  }

  // These are fake instructions that work as workarounds for branching our instruction generation.
  // These instructions are invalid instructions and should not be used for other purpose.
  private static final Map<Class<? extends GInstruction>, GInstruction> fakeInstructions =
      new HashMap<>();

  static {
    fakeInstructions.put(GForceAnyGCInstruction.class, new GForceAnyGCInstruction());
    fakeInstructions.put(GRunOutOfMemoryInstruction.class, new GRunOutOfMemoryInstruction());
    fakeInstructions.put(
        GAddRootObjInstruction.class, new GAddRootObjInstruction(0L, new GID("_")));
    fakeInstructions.put(
        GAddNonRootObjInstruction.class,
        new GAddNonRootObjInstruction(
            0L, new GID("_"), GReference.Type.STRONG, false, new GID("_"), new GID("_")));
    fakeInstructions.put(GMakeRootInstruction.class, new GMakeRootInstruction(new GID("_")));
    fakeInstructions.put(GMakeNonRootInstruction.class, new GMakeNonRootInstruction(new GID("_")));
    fakeInstructions.put(
        GCloneObjInstruction.class, new GCloneObjInstruction(new GID("_"), new GID("_"), "0"));
    fakeInstructions.put(GRemoveObjInstruction.class, new GRemoveObjInstruction(new GID("_")));
    fakeInstructions.put(
        GAddStrongRefInstruction.class,
        new GAddStrongRefInstruction(new GID("_"), new GID("_"), false, new GID("_")));
    fakeInstructions.put(
        GAddSoftRefInstruction.class,
        new GAddSoftRefInstruction(new GID("_"), new GID("_"), false, new GID("_")));
    fakeInstructions.put(
        GAddWeakRefInstruction.class,
        new GAddWeakRefInstruction(new GID("_"), new GID("_"), false, new GID("_")));
    fakeInstructions.put(
        GAddFaninRefsInstruction.class,
        new GAddFaninRefsInstruction(
            List.of(new GID("_")),
            new GID("_"),
            List.of(GReference.Type.STRONG),
            List.of(false),
            List.of(new GID("_"))));
    fakeInstructions.put(
        GAddFanoutRefsInstruction.class,
        new GAddFanoutRefsInstruction(
            new GID("_"),
            List.of(new GID("_")),
            List.of(GReference.Type.STRONG),
            List.of(false),
            List.of(new GID("_"))));
    fakeInstructions.put(
        GReplaceReferentInstruction.class,
        new GReplaceReferentInstruction(new GID("_"), new GID("_")));
    fakeInstructions.put(
        GSwapReferentsInstruction.class, new GSwapReferentsInstruction(new GID("_"), new GID("_")));
    fakeInstructions.put(GSoftenRefInstruction.class, new GSoftenRefInstruction(new GID("_")));
    fakeInstructions.put(
        GStrengthenRefInstruction.class, new GStrengthenRefInstruction(new GID("_")));
    fakeInstructions.put(GRemoveRefInstruction.class, new GRemoveRefInstruction(new GID("_")));
    fakeInstructions.put(
        GRemoveAllReferentsInstruction.class, new GRemoveAllReferentsInstruction(new GID("_")));
  }

  private GInstruction mockInstruction(Class<? extends GInstruction> instructionClass) {
    return fakeInstructions.get(instructionClass);
  }
}

class StateRandDecor extends UnmodifiableGraph {
  private final Rand random = Rand.getInstance();

  StateRandDecor(@NonNull OracleInterpreter interpreter) {
    super(interpreter.getState());
  }

  static StateRandDecor of(@NonNull OracleInterpreter interpreter) {
    return new StateRandDecor(interpreter);
  }

  // -=======================================================
  // - Randomness utilities
  // -=======================================================

  GID randomObject(Predicate<GObject> pred) {
    List<GObject> objects = getObjects();
    List<GID> satisfied = objects.stream().filter(pred).map(GObject::getId).toList();
    return satisfied.isEmpty() ? null : random.nextItem(satisfied);
  }

  GID randomObject() {
    return randomObject(o -> true);
  }

  GID randomRootObject() {
    return randomObject(GObject::isRoot);
  }

  GID randomStronglyReachableObject() {
    return randomObject(o -> isStronglyReachable(o.getId()));
  }

  GID randomSoftlyReachableObject() {
    return randomObject(o -> isSoftlyReachable(o.getId()));
  }

  GID randomWeaklyReachableObject() {
    return randomObject(o -> isWeaklyReachable(o.getId()));
  }

  GID randomReference(Predicate<GReference> pred) {
    List<GReference> references = getReferences();
    List<GID> satisfied = references.stream().filter(pred).map(GReference::getId).toList();
    return satisfied.isEmpty() ? null : random.nextItem(satisfied);
  }

  GID randomReference() {
    return randomReference(r -> true);
  }
}
