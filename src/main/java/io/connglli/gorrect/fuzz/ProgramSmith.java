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
    instWeights.put(GForceAnyGCInstruction.class, 2);
    instWeights.put(GRunOutOfMemoryInstruction.class, 2);
    instWeights.put(GFragmentHeapInstruction.class, 2);
    instWeights.put(GAddRootObjInstruction.class, 50);
    instWeights.put(GAddRootObjIfNotExistInstruction.class, 50);
    instWeights.put(GAddNonRootObjInstruction.class, 50);
    instWeights.put(GAddNonRootObjIfNotExistInstruction.class, 50);
    instWeights.put(GAddAnonymRootInstruction.class, 20);
    instWeights.put(GMakeRootInstruction.class, 20);
    instWeights.put(GMakeNonRootInstruction.class, 20);
    instWeights.put(GFreezeObjInstruction.class, 5);
    // Currently, we disable cloning objects as the requirements are overly strict.
    // It requires the object, all its referers and referents to be strongly reachable.
    instWeights.put(GCloneObjInstruction.class, 0);
    instWeights.put(GRemoveObjInstruction.class, 20);
    instWeights.put(GRemoveObjIfExistsInstruction.class, 20);
    instWeights.put(GAddStrongRefInstruction.class, 50);
    instWeights.put(GAddStrongRefIfNotExistInstruction.class, 50);
    instWeights.put(GAddSoftRefInstruction.class, 10);
    instWeights.put(GAddSoftRefIfNotExistInstruction.class, 10);
    instWeights.put(GAddWeakRefInstruction.class, 10);
    instWeights.put(GAddWeakRefIfNotExistInstruction.class, 10);
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
    instWeights.put(GSoftenRefIfNotWeakInstruction.class, 5);
    instWeights.put(GStrengthenRefInstruction.class, 5);
    instWeights.put(GStrengthenRefIfNotStrongInstruction.class, 5);
    instWeights.put(GRemoveRefInstruction.class, 30);
    instWeights.put(GRemoveRefIfExistsInstruction.class, 30);
    instWeights.put(GRemoveAllReferentsInstruction.class, 30);
  }

  @Getter @NonNull private final Config config;
  @NonNull private final IdSmith objectIdSmith;
  @NonNull private final IdSmith referenceIdSmith;

  @NonNull private final IdSmith nonExistentObjectIdSmith = new IdSmith("ne_obj");
  @NonNull private final IdSmith nonExistentReferenceIdSmith = new IdSmith("ne_ref");

  @Getter private final Stats stats = new Stats();
  @Getter private final int maxNumTrials = 320;

  /// Craft a GIR program for the given vm with the given initial graph state.
  public GProgram craft(@NonNull LangVM vm, @NonNull GGraph initState) {
    stats.reset();
    vm.reset(); // Reset the VM to a clean state
    log.debug("Reset the VM (heap=" + MemorySize.toString(vm.getHeapUnusedBytes()) + ")");
    stats.generationTimeMillis = System.currentTimeMillis();
    GProgram program = new GProgram(new GSetHeapInstruction(vm.getHeapCapacity(), initState));
    OracleInterpreter interpreter = new OracleInterpreter(vm);
    log.debug("Setup initial state (heap=" + MemorySize.toString(vm.getHeapUnusedBytes()) + ")");
    interpreter.interpret(program.getConfiguration()); // Initialize the interpreter state
    Map<GID, Integer> frozenObjects = new HashMap<>();
    SmithImpl smith = new SmithImpl(vm, interpreter, program, frozenObjects);
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
      GInstruction instruction;
      if (i == numInstructions - 1) {
        instruction = nextInstruction(GForceAnyGCInstruction.class, smith);
      } else {
        instruction = nextInstruction(smith);
      }
      stats.numTotalGeneration++;
      if (instruction == null) {
        i -= 1; // Retry the same instruction index
        continue;
      }
      program.addInstruction(instruction);
      stats.contribute(instruction.getClass());
    }
    stats.generationTimeMillis = System.currentTimeMillis() - stats.generationTimeMillis;
    return program;
  }

  private GInstruction nextInstruction(@NonNull SmithImpl smith) {
    return nextInstruction(random.nextItem(instWeights), smith);
  }

  private GInstruction nextInstruction(
      Predicate<Class<? extends GInstruction>> pred, @NonNull SmithImpl smith) {
    Class<? extends GInstruction> instClass;
    do {
      instClass = random.nextItem(instWeights);
    } while (!pred.test(instClass));
    return nextInstruction(instClass, smith);
  }

  private GInstruction nextInstruction(
      @NonNull Class<? extends GInstruction> instClazz, @NonNull SmithImpl smith) {
    GInstruction mock = MockInstructionSmith.craft(instClazz);
    log.debug(
        "Generating instruction (heap="
            + MemorySize.toString(smith.vm.getHeapUnusedBytes())
            + "): "
            + mock.getOpCode());
    GInstruction instruction = mock.accept(smith);
    if (instruction == null) {
      log.debug("  failed: instruction is null");
      return null;
    }
    log.debug("  instruction: " + instruction);
    try {
      // Preset the instruction index otherwise interpretation may fail
      instruction.setIndex(smith.program.size());
      // FIXME: In case the state of the interpreter is changed, we'd better unroll the state.
      smith.interpreter.interpret(instruction);
      log.debug("  succeeded");
      // Check if we need to release some frozen objects
      List<GID> toUnfreeze = new ArrayList<>();
      for (Map.Entry<GID, Integer> kv : smith.frozenObjects.entrySet()) {
        if (kv.getValue() < 0) {
          toUnfreeze.add(kv.getKey());
        } else {
          kv.setValue(kv.getValue() - 1);
        }
      }
      for (GID id : toUnfreeze) {
        smith.frozenObjects.remove(id);
        log.debug("  released frozen object " + id);
      }
      return instruction;
    } catch (Throwable e) {
      // The instruction cannot be safely executed
      log.debug("  failed: " + e.getMessage());
      return null;
    }
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
    @NonNull private final Map<GID, Integer> frozenObjects;

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
    public GInstruction visit(GForceAnyGCInstruction unused) {
      return new GForceAnyGCInstruction();
    }

    @Override
    public GInstruction visit(GRunOutOfMemoryInstruction unused) {
      return new GRunOutOfMemoryInstruction();
    }

    @Override
    public GInstruction visit(GFragmentHeapInstruction unused) {
      long takenBytes = (long) (vm.getHeapUnusedBytes() * .75);
      int minNumObjects = 10, maxNumObjects = 32;

      // We don't respect the user specified max object size, as we want to fragment the heap
      long minSize = Math.max(takenBytes / maxNumObjects, MemorySize.of(16, MemorySize.Unit.MB));
      long maxSize = Math.min(MemorySize.of(128, MemorySize.Unit.MB), takenBytes / minNumObjects);
      if (maxSize < minSize) {
        maxSize = minSize;
      }
      long aveSize = (minSize + maxSize) / 2;

      // Allocate objects until we run out of takenBytes or reach the estimated number of objects
      int estNumObjects = (int) (takenBytes / aveSize);
      long cumulatedSize = 0;
      List<Long> objectSizes = new ArrayList<>(estNumObjects);
      while (cumulatedSize < takenBytes && objectSizes.size() < estNumObjects) {
        long currentSize = random.nextLong(minSize, maxSize + 1);
        if (cumulatedSize + currentSize > takenBytes) {
          currentSize = takenBytes - cumulatedSize;
          cumulatedSize += currentSize;
        }
        objectSizes.add(currentSize);
      }

      return new GFragmentHeapInstruction(objectSizes.size(), objectSizes);
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
    public GInstruction visit(GAddRootObjIfNotExistInstruction instruction) {
      GID rootId = null;
      if (random.nextDouble() < 0.5) {
        // Use an existing root object
        rootId = currState().randomReachableObject();
      }
      if (rootId == null) {
        rootId = objectIdSmith.next();
      }
      long rootSize = randomPadding(rootId);
      if (rootSize < 0) {
        log.debug("Cannot add root object due to insufficient memory.");
        return null;
      }
      return new GAddRootObjIfNotExistInstruction(rootSize, rootId);
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
    public GInstruction visit(GAddNonRootObjIfNotExistInstruction instruction) {
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
      GID objectId = null;
      if (random.nextDouble() < 0.5) {
        // Use an existing object
        objectId = currState().randomReachableObject();
      }
      if (objectId == null) {
        objectId = objectIdSmith.next();
      }
      GID referenceId = referenceIdSmith.next(refererId, objectId);
      GReference.Type referenceType = randomReferenceType();
      boolean isVolatile = random.nextDouble() < config.volatileRefProb;
      return new GAddNonRootObjIfNotExistInstruction(
          objectSize, refererId, referenceType, isVolatile, objectId, referenceId);
    }

    @Override
    public GInstruction visit(GAddAnonymRootInstruction unused) {
      long paddingSize =
          randomPadding(OracleInterpreter.computeAnonymousId(program.getInstructions().size()));
      if (paddingSize < 0) {
        log.debug("Cannot add an anonymous object due to insufficient memory.");
        return null;
      }
      return new GAddAnonymRootInstruction(paddingSize);
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
      // Although it is allowed to make a frozen object non-root, we avoid doing so to
      // try making the frozen object survive longer.
      GID rootId =
          currState().randomObject(o -> o.isRoot() && !frozenObjects.containsKey(o.getId()));
      if (rootId == null) {
        log.debug("Cannot make a root object non-root due to no root objects.");
        return null;
      }
      return new GMakeNonRootInstruction(rootId);
    }

    @Override
    public GInstruction visit(GFreezeObjInstruction instruction) {
      GID objectId = currState().randomStronglyReachableObject();
      if (objectId == null) {
        log.debug("Cannot freeze an object due to no strongly-reachable objects.");
        return null;
      }
      int nStep = random.nextInt(16, 64);
      frozenObjects.put(objectId, nStep);
      return new GFreezeObjInstruction(objectId, nStep);
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
                  !frozenObjects.containsKey(o.getId())
                      && s.isStronglyReachable(o.getId())
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
    public GInstruction visit(GRemoveObjIfExistsInstruction instruction) {
      // We restrict the object and all its referers to be strongly reachable
      StateRandDecor s = currState();
      GID objectId;
      if (random.nextDouble() < 0.5) {
        // Use a non-existing object
        objectId = nonExistentObjectIdSmith.next();
      } else {
        objectId =
            s.randomObject(
                o ->
                    !frozenObjects.containsKey(o.getId())
                        && s.isStronglyReachable(o.getId())
                        && s.getReferers(o.getId()).stream()
                            .allMatch(or -> s.isStronglyReachable(or.getId())));
      }
      if (objectId == null) {
        log.debug(
            "Cannot remove an object due to no available candidates "
                + "(strongly reachable and strongly-reachable referers).");
        return null;
      }
      return new GRemoveObjIfExistsInstruction(objectId);
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
    public GInstruction visit(GAddStrongRefIfNotExistInstruction instruction) {
      Pair<GID, GID> data = null;
      if (random.nextDouble() < 0.5) {
        // Use an existing reference
        StateRandDecor s = currState();
        GID referenceId =
            s.randomReference(
                r ->
                    s.isStronglyReachable(r.getReferer())
                        && s.isStronglyReachable(r.getReferent()));
        if (referenceId != null) {
          GReference reference = s.getReference(referenceId);
          data = Pair.of(reference.getReferer(), reference.getReferent());
        }
      }
      if (data == null) {
        data = randomNewReference(GReference.Type.STRONG);
      }
      if (data == null) {
        return null;
      }
      GID referenceId = referenceIdSmith.next(data.a(), data.b());
      boolean isVolatile = random.nextDouble() < config.volatileRefProb;
      return new GAddStrongRefIfNotExistInstruction(data.a(), data.b(), isVolatile, referenceId);
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
    public GInstruction visit(GAddSoftRefIfNotExistInstruction instruction) {
      Pair<GID, GID> data = null;
      if (random.nextDouble() < 0.5) {
        // Use an existing reference
        StateRandDecor s = currState();
        GID referenceId =
            s.randomReference(
                r ->
                    s.isStronglyReachable(r.getReferer())
                        && s.isStronglyReachable(r.getReferent()));
        if (referenceId != null) {
          GReference reference = s.getReference(referenceId);
          data = Pair.of(reference.getReferer(), reference.getReferent());
        }
      }
      if (data == null) {
        data = randomNewReference(GReference.Type.STRONG);
      }
      if (data == null) {
        return null;
      }
      GID referenceId = referenceIdSmith.next(data.a(), data.b());
      boolean isVolatile = random.nextDouble() < config.volatileRefProb;
      return new GAddSoftRefIfNotExistInstruction(data.a(), data.b(), isVolatile, referenceId);
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
    public GInstruction visit(GAddWeakRefIfNotExistInstruction instruction) {
      Pair<GID, GID> data = null;
      if (random.nextDouble() < 0.5) {
        // Use an existing reference
        StateRandDecor s = currState();
        GID referenceId =
            s.randomReference(
                r ->
                    s.isStronglyReachable(r.getReferer())
                        && s.isStronglyReachable(r.getReferent()));
        if (referenceId != null) {
          GReference reference = s.getReference(referenceId);
          data = Pair.of(reference.getReferer(), reference.getReferent());
        }
      }
      if (data == null) {
        data = randomNewReference(GReference.Type.STRONG);
      }
      if (data == null) {
        return null;
      }
      GID referenceId = referenceIdSmith.next(data.a(), data.b());
      boolean isVolatile = random.nextDouble() < config.volatileRefProb;
      return new GAddWeakRefIfNotExistInstruction(data.a(), data.b(), isVolatile, referenceId);
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
      // Although it is allowed to replace a frozen object if it is a referent, we avoid
      // doing so to try making the frozen object survive longer.
      GID referenceId =
          s.randomReference(
              reference ->
                  s.isStronglyReachable(reference.getReferer())
                      && !frozenObjects.containsKey(reference.getReferent()));
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
    public GInstruction visit(GSoftenRefIfNotWeakInstruction instruction) {
      // We restrict the referer and referent to be strongly reachable
      StateRandDecor s = currState();
      GID referenceId = null;
      if (random.nextDouble() < 0.5) {
        // Use a weak reference directly
        referenceId =
            s.randomReference(
                reference ->
                    reference.getType() == GReference.Type.WEAK
                        && s.isStronglyReachable(reference.getReferer())
                        && s.isStronglyReachable(reference.getReferent()));
      }
      if (referenceId == null) {
        referenceId =
            s.randomReference(
                reference ->
                    reference.getType() != GReference.Type.WEAK
                        && s.isStronglyReachable(reference.getReferer())
                        && s.isStronglyReachable(reference.getReferent()));
      }
      if (referenceId == null) {
        log.debug("Cannot soften a reference due to no suitable candidates.");
        return null;
      }
      return new GSoftenRefIfNotWeakInstruction(referenceId);
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
    public GInstruction visit(GStrengthenRefIfNotStrongInstruction unused) {
      // We restrict the referer and referent to be strongly reachable
      StateRandDecor s = currState();
      GID referenceId = null;
      if (random.nextDouble() < 0.5) {
        // Use a weak reference directly
        referenceId =
            s.randomReference(
                reference ->
                    reference.getType() == GReference.Type.STRONG
                        && s.isStronglyReachable(reference.getReferer())
                        && s.isStronglyReachable(reference.getReferent()));
      }
      if (referenceId == null) {
        referenceId =
            s.randomReference(
                reference ->
                    reference.getType() != GReference.Type.STRONG
                        && s.isStronglyReachable(reference.getReferer())
                        && s.isStronglyReachable(reference.getReferent()));
      }
      if (referenceId == null) {
        log.debug("Cannot strengthen a reference due to no suitable candidates.");
        return null;
      }
      return new GStrengthenRefIfNotStrongInstruction(referenceId);
    }

    @Override
    public GInstruction visit(GRemoveRefInstruction unused) {
      // We restrict the referer to be strongly reachable
      StateRandDecor s = currState();
      // Although it is allowed to remove a reference whose referent is a frozen object,
      // we avoid doing so to try making the frozen object survive longer.
      GID referenceId =
          s.randomReference(
              reference ->
                  s.isStronglyReachable(reference.getReferer())
                      && !frozenObjects.containsKey(reference.getReferent()));
      if (referenceId == null) {
        log.debug("Cannot remove a reference due to no suitable candidates.");
        return null;
      }
      return new GRemoveRefInstruction(referenceId);
    }

    @Override
    public GInstruction visit(GRemoveRefIfExistsInstruction instruction) {
      // We restrict the referer to be strongly reachable
      StateRandDecor s = currState();
      // Although it is allowed to remove a reference whose referent is a frozen object,
      // we avoid doing so to try making the frozen object survive longer.
      GID referenceId;
      if (random.nextDouble() < 0.5) {
        // Use a non-existing reference
        referenceId = nonExistentReferenceIdSmith.next();
      } else {
        referenceId =
            s.randomReference(
                reference ->
                    s.isStronglyReachable(reference.getReferer())
                        && !frozenObjects.containsKey(reference.getReferent()));
      }
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
      // Although it is allowed to remove a frozen object if it is a referent,
      // we avoid doing so to try making the frozen object survive longer.
      GID refererId =
          s.randomObject(
              referer ->
                  s.isStronglyReachable(referer.getId())
                      && s.getReferents(referer.getId()).stream()
                          .noneMatch(referent -> frozenObjects.containsKey(referent.getId())));
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
}

class MockInstructionSmith {
  // These are fake/mock instructions that work as workarounds for branching our instruction
  // generation. They are invalid instructions and should not be used for other purpose.
  private static final Map<Class<? extends GInstruction>, GInstruction> mockInstructions =
      new HashMap<>();

  static {
    mockInstructions.put(GForceAnyGCInstruction.class, new GForceAnyGCInstruction());
    mockInstructions.put(GRunOutOfMemoryInstruction.class, new GRunOutOfMemoryInstruction());
    mockInstructions.put(
        GFragmentHeapInstruction.class, new GFragmentHeapInstruction(1, List.of(1L)));
    mockInstructions.put(
        GAddRootObjInstruction.class, new GAddRootObjInstruction(0L, new GID("_")));
    mockInstructions.put(
        GAddRootObjIfNotExistInstruction.class,
        new GAddRootObjIfNotExistInstruction(0L, new GID("_")));
    mockInstructions.put(
        GAddNonRootObjInstruction.class,
        new GAddNonRootObjInstruction(
            0L, new GID("_"), GReference.Type.STRONG, false, new GID("_"), new GID("_")));
    mockInstructions.put(
        GAddNonRootObjIfNotExistInstruction.class,
        new GAddNonRootObjIfNotExistInstruction(
            0L, new GID("_"), GReference.Type.STRONG, false, new GID("_"), new GID("_")));
    mockInstructions.put(GAddAnonymRootInstruction.class, new GAddAnonymRootInstruction(0L));
    mockInstructions.put(GMakeRootInstruction.class, new GMakeRootInstruction(new GID("_")));
    mockInstructions.put(GMakeNonRootInstruction.class, new GMakeNonRootInstruction(new GID("_")));
    mockInstructions.put(GFreezeObjInstruction.class, new GFreezeObjInstruction(new GID("_"), 1));
    mockInstructions.put(
        GCloneObjInstruction.class, new GCloneObjInstruction(new GID("_"), new GID("_"), "0"));
    mockInstructions.put(GRemoveObjInstruction.class, new GRemoveObjInstruction(new GID("_")));
    mockInstructions.put(
        GRemoveObjIfExistsInstruction.class, new GRemoveObjIfExistsInstruction(new GID("_")));
    mockInstructions.put(
        GAddStrongRefInstruction.class,
        new GAddStrongRefInstruction(new GID("_"), new GID("_"), false, new GID("_")));
    mockInstructions.put(
        GAddStrongRefIfNotExistInstruction.class,
        new GAddStrongRefIfNotExistInstruction(new GID("_"), new GID("_"), false, new GID("_")));
    mockInstructions.put(
        GAddSoftRefInstruction.class,
        new GAddSoftRefInstruction(new GID("_"), new GID("_"), false, new GID("_")));
    mockInstructions.put(
        GAddSoftRefIfNotExistInstruction.class,
        new GAddSoftRefIfNotExistInstruction(new GID("_"), new GID("_"), false, new GID("_")));
    mockInstructions.put(
        GAddWeakRefInstruction.class,
        new GAddWeakRefInstruction(new GID("_"), new GID("_"), false, new GID("_")));
    mockInstructions.put(
        GAddWeakRefIfNotExistInstruction.class,
        new GAddWeakRefIfNotExistInstruction(new GID("_"), new GID("_"), false, new GID("_")));
    mockInstructions.put(
        GAddFaninRefsInstruction.class,
        new GAddFaninRefsInstruction(
            List.of(new GID("_")),
            new GID("_"),
            List.of(GReference.Type.STRONG),
            List.of(false),
            List.of(new GID("_"))));
    mockInstructions.put(
        GAddFanoutRefsInstruction.class,
        new GAddFanoutRefsInstruction(
            new GID("_"),
            List.of(new GID("_")),
            List.of(GReference.Type.STRONG),
            List.of(false),
            List.of(new GID("_"))));
    mockInstructions.put(
        GReplaceReferentInstruction.class,
        new GReplaceReferentInstruction(new GID("_"), new GID("_")));
    mockInstructions.put(
        GSwapReferentsInstruction.class, new GSwapReferentsInstruction(new GID("_"), new GID("_")));
    mockInstructions.put(GSoftenRefInstruction.class, new GSoftenRefInstruction(new GID("_")));
    mockInstructions.put(
        GSoftenRefIfNotWeakInstruction.class, new GSoftenRefIfNotWeakInstruction(new GID("_")));
    mockInstructions.put(
        GStrengthenRefInstruction.class, new GStrengthenRefInstruction(new GID("_")));
    mockInstructions.put(
        GStrengthenRefIfNotStrongInstruction.class,
        new GStrengthenRefIfNotStrongInstruction(new GID("_")));
    mockInstructions.put(GRemoveRefInstruction.class, new GRemoveRefInstruction(new GID("_")));
    mockInstructions.put(
        GRemoveRefIfExistsInstruction.class, new GRemoveRefIfExistsInstruction(new GID("_")));
    mockInstructions.put(
        GRemoveAllReferentsInstruction.class, new GRemoveAllReferentsInstruction(new GID("_")));
  }

  static GInstruction craft(Class<? extends GInstruction> instructionClass) {
    return mockInstructions.get(instructionClass);
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

  GID randomReachableObject() {
    return randomObject(o -> isReachable(o.getId()));
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
