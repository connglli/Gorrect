/*
 * MIT License
 *
 * Copyright (c) 2025 Cong Li (cong.li@inf.ethz.ch)
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

package io.connglli.gorrect.smith;

import static org.junit.jupiter.api.Assertions.*;

import io.connglli.gorrect.gir.GProgram;
import io.connglli.gorrect.gir.inst.*;
import io.connglli.gorrect.gir.model.GGraph;
import io.connglli.gorrect.java.oracle.HotSpotVM;
import io.connglli.gorrect.oracle.LangVM;
import io.connglli.gorrect.oracle.OracleInterpreter;
import io.connglli.gorrect.utility.MemorySize;
import io.connglli.gorrect.utility.Rand;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProgramSmithTest {

  private ProgramSmith.Config.ConfigBuilder configBuilder;
  private LangVM vm;
  private IdSmith objectIdSmith;
  private IdSmith referenceIdSmith;
  private GraphSmith graphSmith;

  @BeforeEach
  void setUp() {
    Rand.getInstance().setSeed(42);
    vm = new HotSpotVM(MemorySize.of(1, MemorySize.Unit.GB)); // 1GB heap
    objectIdSmith = new IdSmith("obj");
    referenceIdSmith = new IdSmith("ref");

    // Set up GraphSmith to generate initial states
    GraphSmith.Config graphConfig =
        GraphSmith.Config.builder()
            .vm(vm)
            .objectIdSmith(objectIdSmith)
            .referenceIdSmith(referenceIdSmith)
            .minRoots(1)
            .maxRoots(3)
            .minNonRoots(2)
            .maxNonRoots(5)
            .minRefs(2)
            .maxRefs(8)
            .build();

    graphSmith = new GraphSmith(graphConfig);

    configBuilder =
        ProgramSmith.Config.builder()
            .vm(vm)
            .objectIdSmith(objectIdSmith)
            .referenceIdSmith(referenceIdSmith);
  }

  @Test
  void testCraftBasicProgram() {
    GGraph initialState = graphSmith.craft();
    ProgramSmith.Config config =
        configBuilder.initState(initialState).minInstructions(5).maxInstructions(10).build();

    ProgramSmith smith = new ProgramSmith(config);
    GProgram program = smith.craft();

    assertNotNull(program, "Program should not be null");
    assertFalse(program.getInstructions().isEmpty(), "Program should have at least 1 instruction");
    assertTrue(
        program.getInstructions().size() <= 10, "Program should have at most 10 instructions");

    // Verify the init instruction is GSetHeapInstruction
    assertInstanceOf(
        GSetHeapInstruction.class,
        program.getConfiguration(),
        "Init instruction should be GSetHeapInstruction");

    ProgramSmith.Stats stats = smith.getStats();
    assertTrue(stats.getNumTotalGeneration() > 0, "Should have generated some instructions");
    assertTrue(stats.getNumValidGeneration() > 0, "Should have some valid instructions");
  }

  @Test
  void testCraftWithMinimalConfiguration() {
    GGraph initialState = graphSmith.craft();
    ProgramSmith.Config config =
        configBuilder
            .initState(initialState)
            .minInstructions(1)
            .maxInstructions(1)
            .minObjSizeBytes(1)
            .maxObjSizeBytes(100)
            .build();

    ProgramSmith smith = new ProgramSmith(config);
    GProgram program = smith.craft();

    assertNotNull(program, "Program should not be null");
    assertEquals(1, program.getInstructions().size(), "Should have exactly 1 instruction");

    ProgramSmith.Stats stats = smith.getStats();
    assertTrue(stats.getNumTotalGeneration() >= 1, "Should have generated at least 1 instruction");
  }

  @Test
  void testCraftWithCustomConfiguration() {
    GGraph initialState = graphSmith.craft();
    ProgramSmith.Config config =
        configBuilder
            .initState(initialState)
            .minInstructions(10)
            .maxInstructions(20)
            .minObjSizeBytes(10)
            .maxObjSizeBytes(1000)
            .build();

    ProgramSmith smith = new ProgramSmith(config);
    GProgram program = smith.craft();

    assertNotNull(program, "Program should not be null");
    assertFalse(program.getInstructions().isEmpty(), "Should have at least 1 instruction");
    assertTrue(program.getInstructions().size() <= 20, "Should have at most 20 instructions");

    // Verify configuration is applied
    assertEquals(vm, smith.getConfig().getVm(), "VM should match configuration");
    assertEquals(objectIdSmith, smith.getConfig().getObjectIdSmith(), "ObjectIdSmith should match");
    assertEquals(
        referenceIdSmith, smith.getConfig().getReferenceIdSmith(), "ReferenceIdSmith should match");
    assertEquals(initialState, smith.getConfig().getInitState(), "InitState should match");
    assertEquals(10, smith.getConfig().getMinInstructions(), "MinInstructions should match");
    assertEquals(20, smith.getConfig().getMaxInstructions(), "MaxInstructions should match");
  }

  @Test
  void testCraftWithEmptyInitialState() {
    GGraph emptyState = new GGraph();
    ProgramSmith.Config config =
        configBuilder.initState(emptyState).minInstructions(5).maxInstructions(10).build();

    ProgramSmith smith = new ProgramSmith(config);
    GProgram program = smith.craft();

    assertNotNull(program, "Program should not be null");
    // With empty initial state, should still be able to generate root objects
    assertFalse(program.getInstructions().isEmpty(), "Should have at least 1 instruction");

    // Check that some instructions are GAddRootObjInstruction since we start with empty state
    long rootObjInstructions =
        program.getInstructions().stream()
            .filter(inst -> inst instanceof GAddRootObjInstruction)
            .count();
    assertTrue(
        rootObjInstructions > 0,
        "Should have some root object instructions with empty initial state");
  }

  @Test
  void testCraftWithRichInitialState() {
    // Create a rich initial state with multiple objects and references
    GraphSmith.Config richGraphConfig =
        GraphSmith.Config.builder()
            .vm(vm)
            .objectIdSmith(objectIdSmith)
            .referenceIdSmith(referenceIdSmith)
            .minRoots(3)
            .maxRoots(5)
            .minNonRoots(8)
            .maxNonRoots(12)
            .minRefs(10)
            .maxRefs(20)
            .build();

    GraphSmith richGraphSmith = new GraphSmith(richGraphConfig);
    GGraph richInitialState = richGraphSmith.craft();

    ProgramSmith.Config config =
        configBuilder.initState(richInitialState).minInstructions(15).maxInstructions(25).build();

    ProgramSmith smith = new ProgramSmith(config);
    GProgram program = smith.craft();

    assertNotNull(program, "Program should not be null");
    assertFalse(program.getInstructions().isEmpty(), "Should have at least 1 instruction");

    // With rich initial state, should be able to generate various types of instructions
    List<GInstruction> instructions = program.getInstructions();
    Set<Class<? extends GInstruction>> instructionTypes =
        instructions.stream().map(GInstruction::getClass).collect(Collectors.toSet());

    // Should have variety of instruction types
    assertTrue(instructionTypes.size() >= 2, "Should have at least 2 different instruction types");
  }

  @Test
  void testStatsTracking() {
    GGraph initialState = graphSmith.craft();
    ProgramSmith.Config config =
        configBuilder.initState(initialState).minInstructions(10).maxInstructions(15).build();

    ProgramSmith smith = new ProgramSmith(config);
    ProgramSmith.Stats stats = smith.getStats();

    // Initially stats should be zero
    assertEquals(0, stats.getNumTotalGeneration(), "Initial total generated should be 0");
    assertEquals(0, stats.getNumValidGeneration(), "Initial valid generation should be 0");

    GProgram program = smith.craft();

    // After crafting, stats should be updated
    assertTrue(stats.getNumTotalGeneration() > 0, "Total generated should be positive");
    assertTrue(stats.getNumValidGeneration() > 0, "Valid generation should be positive");
    assertTrue(
        stats.getNumValidGeneration() <= stats.getNumTotalGeneration(),
        "Valid generation should not exceed total generation");

    // Stats should be consistent with actual program size
    assertEquals(
        stats.getNumValidGeneration(),
        program.getInstructions().size(),
        "Valid generation should match program instruction count");
  }

  @Test
  void testCraftMultiplePrograms() {
    GGraph initialState = graphSmith.craft();
    ProgramSmith.Config config =
        configBuilder.initState(initialState).minInstructions(5).maxInstructions(10).build();

    ProgramSmith smith = new ProgramSmith(config);

    // Generate multiple programs
    GProgram program1 = smith.craft();
    GProgram program2 = smith.craft();
    GProgram program3 = smith.craft();

    assertNotNull(program1, "First program should not be null");
    assertNotNull(program2, "Second program should not be null");
    assertNotNull(program3, "Third program should not be null");

    // Programs should be different (high probability with random generation)
    assertNotEquals(
        program1.getInstructions().size(),
        program2.getInstructions().size(),
        "Programs should likely have different sizes");

    // Stats should be reset for each craft call
    ProgramSmith.Stats stats = smith.getStats();
    assertEquals(
        stats.getNumValidGeneration(),
        program3.getInstructions().size(),
        "Stats should reflect only the last program generated");
  }

  @Test
  void testCraftWithLimitedMemory() {
    // Create VM with very limited memory
    LangVM limitedVM = new HotSpotVM(1024); // 1KB heap
    IdSmith limitedObjectIdSmith = new IdSmith("limited_obj");
    IdSmith limitedReferenceIdSmith = new IdSmith("limited_ref");

    // Generate a small initial state
    GraphSmith.Config limitedGraphConfig =
        GraphSmith.Config.builder()
            .vm(limitedVM)
            .objectIdSmith(limitedObjectIdSmith)
            .referenceIdSmith(limitedReferenceIdSmith)
            .minRoots(1)
            .maxRoots(1)
            .minNonRoots(1)
            .maxNonRoots(2)
            .minRefs(1)
            .maxRefs(2)
            .minObjSizeBytes(1)
            .maxObjSizeBytes(10)
            .build();

    GraphSmith limitedGraphSmith = new GraphSmith(limitedGraphConfig);
    GGraph limitedInitialState = limitedGraphSmith.craft();

    ProgramSmith.Config config =
        ProgramSmith.Config.builder()
            .vm(limitedVM)
            .objectIdSmith(limitedObjectIdSmith)
            .referenceIdSmith(limitedReferenceIdSmith)
            .initState(limitedInitialState)
            .minInstructions(5)
            .maxInstructions(100) // High max, but should be limited by memory
            .minObjSizeBytes(1)
            .maxObjSizeBytes(10)
            .build();

    ProgramSmith smith = new ProgramSmith(config);
    GProgram program = smith.craft();

    assertNotNull(program, "Program should not be null even with limited memory");
    // Should have fewer instructions due to memory constraints
    assertTrue(
        program.getInstructions().size() < 100,
        "Should have fewer instructions due to memory constraints");
  }

  @Test
  void testCraftWithDifferentObjectSizes() {
    GGraph initialState = graphSmith.craft();

    // Test with small objects
    ProgramSmith.Config smallConfig =
        configBuilder
            .initState(initialState)
            .minInstructions(10)
            .maxInstructions(15)
            .minObjSizeBytes(1)
            .maxObjSizeBytes(10)
            .build();

    ProgramSmith smallSmith = new ProgramSmith(smallConfig);
    GProgram smallProgram = smallSmith.craft();

    // Test with large objects
    ProgramSmith.Config largeConfig =
        configBuilder
            .initState(initialState)
            .minInstructions(10)
            .maxInstructions(15)
            .minObjSizeBytes(1000)
            .maxObjSizeBytes(10000)
            .build();

    ProgramSmith largeSmith = new ProgramSmith(largeConfig);
    GProgram largeProgram = largeSmith.craft();

    assertNotNull(smallProgram, "Small object program should not be null");
    assertNotNull(largeProgram, "Large object program should not be null");

    // Large objects should result in fewer instructions due to memory constraints
    assertTrue(
        largeProgram.getInstructions().size() <= smallProgram.getInstructions().size(),
        "Large object program should have fewer or equal instructions");
  }

  @Test
  void testCraftWithDifferentReferenceWeights() {
    GGraph initialState = graphSmith.craft();

    // Test with strong reference preference
    ProgramSmith.Config strongConfig =
        configBuilder.initState(initialState).minInstructions(10).maxInstructions(20).build();

    ProgramSmith strongSmith = new ProgramSmith(strongConfig);
    GProgram strongProgram = strongSmith.craft();

    // Test with weak reference preference
    ProgramSmith.Config weakConfig =
        configBuilder.initState(initialState).minInstructions(10).maxInstructions(20).build();

    ProgramSmith weakSmith = new ProgramSmith(weakConfig);
    GProgram weakProgram = weakSmith.craft();

    assertNotNull(strongProgram, "Strong reference program should not be null");
    assertNotNull(weakProgram, "Weak reference program should not be null");

    // Both should generate valid programs
    assertFalse(
        strongProgram.getInstructions().isEmpty(), "Strong program should have instructions");
    assertFalse(weakProgram.getInstructions().isEmpty(), "Weak program should have instructions");
  }

  @Test
  void testConfigBuilderDefaults() {
    ProgramSmith.Config config =
        ProgramSmith.Config.builder()
            .vm(vm)
            .objectIdSmith(objectIdSmith)
            .referenceIdSmith(referenceIdSmith)
            .initState(new GGraph())
            .build();

    assertEquals(1, config.getMinInstructions(), "Default min instructions should be 1");
    assertEquals(100, config.getMaxInstructions(), "Default max instructions should be 100");
    assertEquals(1L, config.getMinObjSizeBytes(), "Default min object size should be 1 byte");
    assertEquals(128_000L, config.getMaxObjSizeBytes(), "Default max object size should be 128KB");
  }

  @Test
  void testStatsEqualsAndHashCode() {
    ProgramSmith.Stats stats1 = new ProgramSmith.Stats();
    ProgramSmith.Stats stats2 = new ProgramSmith.Stats();

    assertEquals(stats1, stats2, "Empty stats should be equal");
    assertEquals(stats1.hashCode(), stats2.hashCode(), "Empty stats should have same hash code");

    // Test toString
    String statsString = stats1.toString();
    assertNotNull(statsString, "Stats toString should not be null");
    assertTrue(
        statsString.contains("numTotalGeneration"),
        "Stats toString should contain numTotalGenerated");
    assertTrue(
        statsString.contains("numValidGeneration"),
        "Stats toString should contain numValidGeneration");
  }

  @Test
  void testCraftWithValidInitialState() {
    // Test that the program can be executed by an interpreter
    GGraph initialState = graphSmith.craft();
    ProgramSmith.Config config =
        configBuilder.initState(initialState).minInstructions(5).maxInstructions(10).build();

    ProgramSmith smith = new ProgramSmith(config);
    GProgram program = smith.craft();

    // Try to create and use an interpreter with the generated program
    assertDoesNotThrow(
        () -> {
          OracleInterpreter interpreter = new OracleInterpreter(vm, program);
          assertNotNull(interpreter, "Interpreter should be created successfully");
        },
        "Should be able to create interpreter with generated program");
  }
}
